package notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real file-backed H2 and local HTTP sockets; only the external supplier is simulated. */
public class NotificationServiceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String TOKEN = "local-test-token-with-at-least-32-characters";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int supplierStatus = 204;
    private volatile long supplierDelay;
    private volatile String retryAfter;
    private volatile String receivedBody;
    private volatile String receivedKey;
    private volatile String receivedType;
    private volatile String receivedMethod;
    private HttpServer supplier;
    private String origin;
    private TargetPolicy policy;
    private Path database;
    private NotificationStore store;
    private HttpDelivery delivery;
    private DeliveryWorker worker;
    private NotificationApi api;

    @Before public void setUp() throws Exception {
        supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        supplier.createContext("/", exchange -> {
            calls.incrementAndGet();
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            receivedType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedMethod = exchange.getRequestMethod();
            try {
                Thread.sleep(supplierDelay);
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                exchange.getResponseHeaders().set("Location", "/redirected");
                exchange.sendResponseHeaders(supplierStatus, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        supplier.start();
        origin = "http://127.0.0.1:" + supplier.getAddress().getPort();
        // This package-private override is unavailable via production environment configuration.
        policy = new TargetPolicy(origin, true);
        database = temporary.getRoot().toPath().resolve("notifications");
        store = new NotificationStore(database);
        delivery = new HttpDelivery(policy, 300);
        worker = new DeliveryWorker(store, delivery, 3, 10, 40);
        api = new NotificationApi(new InetSocketAddress("127.0.0.1", 0), TOKEN, store, policy, worker::healthy);
        api.start();
    }

    @After public void tearDown() throws Exception {
        if (api != null) api.close();
        if (worker != null) worker.close();
        if (store != null) store.close();
        if (supplier != null) supplier.stop(0);
    }

    private Notification.Request request(String id) {
        Notification.Request request = new Notification.Request();
        request.id = id;
        request.url = origin + "/notify";
        request.body = "{\"event\":\"付款成功\"}";
        request.headers = new LinkedHashMap<>();
        request.headers.put("Content-Type", "application/json");
        return request;
    }

    private HttpResponse<String> api(String method, String path, String body, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> submit(Notification.Request request) throws Exception {
        return api("POST", "/notifications", json.writeValueAsString(request), TOKEN);
    }

    @Test public void acceptsDurablyThenDeliversRawBodyAndQueriesWithoutSecrets() throws Exception {
        Notification.Request request = request("payment_1");
        request.method = "PATCH";
        request.headers.put("Authorization", "Bearer supplier-secret");
        HttpResponse<String> accepted = submit(request);
        assertEquals(202, accepted.statusCode());
        assertEquals("/notifications/payment_1", accepted.headers().firstValue("Location").orElseThrow());
        assertEquals(0, calls.get());
        assertEquals("PENDING", json.readTree(accepted.body()).get("state").asText());
        assertTrue(worker.runOnce(System.currentTimeMillis()));
        assertEquals(1, calls.get());
        assertEquals(request.body, receivedBody);
        assertEquals(request.id, receivedKey);
        assertEquals("application/json", receivedType);
        assertEquals("PATCH", receivedMethod);
        HttpResponse<String> status = api("GET", "/notifications/payment_1", "", TOKEN);
        assertEquals(200, status.statusCode());
        assertEquals("SUCCEEDED", json.readTree(status.body()).get("state").asText());
        assertFalse(status.body().contains("supplier-secret"));
        assertFalse(status.body().contains("付款"));
        assertFalse(worker.runOnce(System.currentTimeMillis()));
    }

    @Test public void duplicateIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        Notification.Request request = request("duplicate");
        request.headers.put("X-Event", "event");
        assertEquals(202, submit(request).statusCode());
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("x-event", "event");
        reordered.put("content-type", "application/json");
        request.headers = reordered;
        assertEquals(200, submit(request).statusCode());
        request.body = "changed";
        assertEquals(409, submit(request).statusCode());
        worker.runOnce(System.currentTimeMillis());
        assertEquals(1, calls.get());
    }

    @Test public void retriesServerFailureThenSucceeds() throws Exception {
        supplierStatus = 503;
        assertEquals(202, submit(request("retry")).statusCode());
        long now = System.currentTimeMillis();
        worker.runOnce(now);
        Notification.Status status = store.status("retry");
        assertEquals("RETRY", status.state);
        assertEquals(Integer.valueOf(503), status.lastHttpStatus);
        assertTrue(status.nextAttemptAt > now);
        assertFalse(worker.runOnce(status.nextAttemptAt - 1));
        supplierStatus = 200;
        worker.runOnce(status.nextAttemptAt);
        assertEquals("SUCCEEDED", store.status("retry").state);
        assertEquals(2, calls.get());
        assertEquals(2, store.status("retry").attempts);
    }

    @Test public void respectsRetryAfter() throws Exception {
        supplierStatus = 429;
        retryAfter = "120";
        submit(request("limited"));
        long before = System.currentTimeMillis();
        worker.runOnce(before);
        assertTrue(store.status("limited").nextAttemptAt >= before + 120000);
        assertFalse(worker.runOnce(before + 119000));
    }

    @Test public void exhaustsBudgetAndAllowsExplicitRedrive() throws Exception {
        supplierStatus = 503;
        submit(request("dead"));
        for (int i = 0; i < 3; i++) {
            worker.runOnce(store.status("dead").nextAttemptAt);
        }
        assertEquals("DEAD", store.status("dead").state);
        assertEquals(3, calls.get());
        assertFalse(worker.runOnce(System.currentTimeMillis() + 100000));
        assertEquals(202, api("POST", "/notifications/dead/redrive", "", TOKEN).statusCode());
        assertEquals(409, api("POST", "/notifications/dead/redrive", "", TOKEN).statusCode());
        supplierStatus = 204;
        worker.runOnce(System.currentTimeMillis());
        assertEquals("SUCCEEDED", store.status("dead").state);
        assertEquals(1, store.status("dead").attempts);
        assertEquals("dead", receivedKey);
    }

    @Test public void permanentErrorsAndRedirectsAreNotRetried() throws Exception {
        for (int code : new int[]{400, 401, 404, 302}) {
            supplierStatus = code;
            String id = "status_" + code;
            submit(request(id));
            worker.runOnce(System.currentTimeMillis());
            assertEquals("DEAD", store.status(id).state);
            assertEquals(Integer.valueOf(code), store.status(id).lastHttpStatus);
        }
        assertEquals(4, calls.get());
    }

    @Test public void timesOutAndRetriesWithoutBlockingAcceptance() throws Exception {
        supplierDelay = 700;
        submit(request("slow"));
        long start = System.nanoTime();
        worker.runOnce(System.currentTimeMillis());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
        assertEquals("RETRY", store.status("slow").state);
        assertEquals("NETWORK_ERROR_OR_TIMEOUT", store.status("slow").lastError);
        assertEquals(202, submit(request("another")).statusCode());
    }

    @Test public void restartRecoversAcceptedAndInFlightJobs() throws Exception {
        submit(request("accepted"));
        submit(request("in_flight"));
        Notification.Job claimed = store.claim(System.currentTimeMillis());
        assertNotNull(claimed);
        assertEquals("IN_FLIGHT", store.status(claimed.request.id).state);
        api.close();
        api = null;
        worker.close();
        store.close();
        store = new NotificationStore(database);
        delivery = new HttpDelivery(policy, 300);
        worker = new DeliveryWorker(store, delivery, 3, 10, 40);
        assertEquals("RETRY", store.status(claimed.request.id).state);
        worker.runOnce(System.currentTimeMillis());
        worker.runOnce(System.currentTimeMillis());
        assertEquals("SUCCEEDED", store.status("accepted").state);
        assertEquals("SUCCEEDED", store.status("in_flight").state);
        assertEquals(2, calls.get());
        assertEquals(2, store.status(claimed.request.id).attempts);
    }

    @Test public void uncertainSuccessMayRepeatWithSameSupplierIdempotencyKey() throws Exception {
        submit(request("uncertain"));
        Notification.Job claimed = store.claim(System.currentTimeMillis());
        assertTrue(delivery.send(claimed.request).success());
        // Crash boundary: supplier accepted but local finish was never committed.
        api.close();
        api = null;
        worker.close();
        store.close();
        store = new NotificationStore(database);
        delivery = new HttpDelivery(policy, 300);
        worker = new DeliveryWorker(store, delivery, 3, 10, 40);
        worker.runOnce(System.currentTimeMillis());
        assertEquals(2, calls.get());
        assertEquals("uncertain", receivedKey);
        assertEquals("SUCCEEDED", store.status("uncertain").state);
    }

    @Test public void validatesAuthRoutesAndJsonBoundaries() throws Exception {
        assertEquals(401, api("GET", "/health", "", "wrong").statusCode());
        assertEquals(200, api("GET", "/health", "", TOKEN).statusCode());
        assertEquals(404, api("GET", "/notifications/missing", "", TOKEN).statusCode());
        assertEquals(405, api("GET", "/notifications", "", TOKEN).statusCode());
        for (String body : new String[]{"null", "{}", "{", "{} {}", "{\"id\":\"a\",\"id\":\"b\"}"}) {
            assertEquals(400, api("POST", "/notifications", body, TOKEN).statusCode());
        }
        assertEquals(413, api("POST", "/notifications", " ".repeat(131073), TOKEN).statusCode());
        HttpResponse<String> wrongType = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + api.port() + "/notifications"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, wrongType.statusCode());
        Notification.Request request = request("bad");
        request.body = "x".repeat(65537);
        assertEquals(400, submit(request).statusCode());
        request.body = "valid";
        request.headers.put("Host", "evil.example");
        assertEquals(400, submit(request).statusCode());
        request.headers.remove("Host");
        request.headers.put("X-Bad", "value\r\nInjected: yes");
        assertEquals(400, submit(request).statusCode());
        assertEquals(0, calls.get());
    }

    @Test public void blocksPrivateLiteralAddressesAndDnsAndUnapprovedTargets() throws Exception {
        for (String host : new String[]{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "[::1]", "[fc00::1]"}) {
            TargetPolicy production = new TargetPolicy("http://" + host);
            Notification.Request request = request("ssrf");
            request.url = "http://" + host + "/notify";
            assertThrows(IllegalArgumentException.class, () -> production.validate(request));
        }
        assertThrows(java.net.UnknownHostException.class,
                () -> new TargetPolicy("http://localhost").resolve("localhost"));
        Notification.Request request = request("unapproved");
        request.url = "https://unapproved.example/";
        assertEquals(400, submit(request).statusCode());
        request.url = origin + "/notify#fragment";
        assertEquals(400, submit(request).statusCode());
        request.url = origin.replace("http://", "http://user:password@") + "/notify";
        assertEquals(400, submit(request).statusCode());
    }

    @Test public void concurrentDuplicateSubmissionCreatesOnlyOneJob() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> submit(request("concurrent")).statusCode()));
            }
            int created = 0;
            for (Future<Integer> result : results) {
                int status = result.get(5, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 202);
                if (status == 202) created++;
            }
            assertEquals(1, created);
            worker.runOnce(System.currentTimeMillis());
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void realSchedulerDeliversWithoutManualTick() throws Exception {
        worker.start();
        assertEquals(202, submit(request("scheduled")).statusCode());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"SUCCEEDED".equals(store.status("scheduled").state) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("SUCCEEDED", store.status("scheduled").state);
        assertTrue(worker.healthy());
    }

    @Test public void retriesFailedResultPersistenceWithoutRepeatingHttp() throws Exception {
        submit(request("db_failure"));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:file:" + database.toAbsolutePath(), "sa", "");
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_finish BEFORE UPDATE ON notifications FOR EACH ROW "
                    + "CALL 'notification.NotificationServiceTest$FailFinishOnce'");
        }
        assertThrows(java.sql.SQLException.class, () -> worker.runOnce(System.currentTimeMillis()));
        assertEquals(1, calls.get());
        assertEquals("IN_FLIGHT", store.status("db_failure").state);
        worker.runOnce(System.currentTimeMillis());
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", store.status("db_failure").state);
    }

    /** Inject a single real database write failure, not a replacement for the store. */
    public static final class FailFinishOnce implements org.h2.api.Trigger {
        private boolean fail = true;
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow)
                throws java.sql.SQLException {
            if (fail && "SUCCEEDED".equals(newRow[2])) {
                fail = false;
                throw new java.sql.SQLException("Injected result persistence failure");
            }
        }
    }

    @Test public void databaseFailureReturnsUnavailableNotAccepted() throws Exception {
        store.close();
        assertEquals(503, submit(request("no_commit")).statusCode());
        assertEquals(503, api("GET", "/health", "", TOKEN).statusCode());
        assertEquals(0, calls.get());
    }

    @Test public void backoffAndRetryAfterAreBounded() {
        for (int attempt = 1; attempt <= 100; attempt++) {
            long ceiling = attempt == 1 ? 10 : attempt == 2 ? 20 : 40;
            long delay = worker.backoff(attempt);
            assertTrue(delay >= ceiling / 2 && delay <= ceiling);
        }
        assertEquals(86400000, HttpDelivery.retryAfterMillis("999999999", 0));
        assertEquals(0, HttpDelivery.retryAfterMillis("-1", 0));
        assertEquals(0, HttpDelivery.retryAfterMillis("nonsense", 0));
        assertEquals(1000, HttpDelivery.retryAfterMillis("Thu, 1 Jan 1970 00:00:01 GMT", 0));
    }
}