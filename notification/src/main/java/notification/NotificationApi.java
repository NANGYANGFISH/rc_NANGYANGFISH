package notification;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

public final class NotificationApi implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(NotificationApi.class.getName());
    private final NotificationStore store;
    private final TargetPolicy policy;
    private final byte[] authorization;
    private final BooleanSupplier workerHealthy;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());

    public NotificationApi(InetSocketAddress address, String token, NotificationStore store,
                           TargetPolicy policy, BooleanSupplier workerHealthy) throws IOException {
        if (token == null || token.length() < 32 || !token.matches("[!-~]+")) {
            throw new IllegalArgumentException("NOTIFY_API_TOKEN requires at least 32 printable non-space characters");
        }
        this.store = store;
        this.policy = policy;
        this.workerHealthy = workerHealthy;
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(address, 64);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respond(exchange, 401, error("Unauthorized"));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if ("/health".equals(path) && "GET".equals(method)) {
                boolean healthy = store.healthy() && workerHealthy.getAsBoolean();
                respond(exchange, healthy ? 200 : 503, Collections.singletonMap("healthy", healthy));
            } else if ("/notifications".equals(path)) {
                if (!"POST".equals(method)) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !"application/json".equals(type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))) {
                    respond(exchange, 415, error("Content-Type must be application/json"));
                    return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(131073);
                if (bytes.length > 131072) {
                    respond(exchange, 413, error("Request exceeds 128 KiB"));
                    return;
                }
                Notification.Request request = json.readValue(bytes, Notification.Request.class);
                policy.validate(request);
                boolean created = store.submit(request, System.currentTimeMillis());
                exchange.getResponseHeaders().set("Location", "/notifications/" + request.id);
                respond(exchange, created ? 202 : 200, store.status(request.id));
            } else if (path.matches("/notifications/[A-Za-z0-9_-]{1,128}(/redrive)?")) {
                String[] parts = path.split("/");
                String id = parts[2];
                boolean redrive = parts.length == 4;
                if (!(redrive ? "POST" : "GET").equals(method)) {
                    methodNotAllowed(exchange, redrive ? "POST" : "GET");
                    return;
                }
                Notification.Status status = store.status(id);
                if (status == null) {
                    respond(exchange, 404, error("Notification not found"));
                } else if (redrive) {
                    if (store.redrive(id, System.currentTimeMillis())) {
                        respond(exchange, 202, store.status(id));
                    } else {
                        respond(exchange, 409, error("Only DEAD notifications can be redriven"));
                    }
                } else {
                    respond(exchange, 200, status);
                }
            } else {
                respond(exchange, 404, error("Not found"));
            }
        } catch (NotificationStore.ConflictException e) {
            respond(exchange, 409, error(e.getMessage()));
        } catch (JsonProcessingException e) {
            respond(exchange, 400, error("Invalid JSON request"));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (SQLException e) {
            LOG.warning("Notification storage unavailable");
            respond(exchange, 503, error("Storage unavailable; retry with the same notification id"));
        } catch (Exception e) {
            LOG.warning("Notification API failure: " + e.getClass().getSimpleName());
            respond(exchange, 500, error("Internal error; retry with the same notification id"));
        } finally {
            exchange.close();
        }
    }

    private static Object error(String message) {
        return Collections.singletonMap("error", message);
    }

    private void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Allow", method);
        respond(exchange, 405, error("Method not allowed"));
    }

    private void respond(HttpExchange exchange, int status, Object result) throws IOException {
        byte[] bytes = json.writeValueAsBytes(result);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdownNow();
    }
}