package notification;

import org.apache.http.Header;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class HttpDelivery implements AutoCloseable {
    private final TargetPolicy policy;
    private final CloseableHttpClient client;
    private final int timeoutMillis;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "notification-http-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public HttpDelivery(TargetPolicy policy, int timeoutMillis) {
        this.policy = policy;
        this.timeoutMillis = timeoutMillis;
        client = HttpClients.custom().setDnsResolver(policy)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .disableContentCompression().setConnectionReuseStrategy((response, context) -> false)
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(timeoutMillis)
                        .setSocketTimeout(timeoutMillis).setConnectionRequestTimeout(timeoutMillis).build())
                .build();
    }

    public Result send(Notification.Request request) {
        try {
            policy.validate(request);
        } catch (IllegalArgumentException e) {
            return new Result(null, false, "TARGET_POLICY_REJECTED", 0);
        }
        RequestBuilder builder = RequestBuilder.create(request.method).setUri(request.url);
        request.headers.forEach(builder::setHeader);
        builder.setHeader("Idempotency-Key", request.id);
        if (!"GET".equals(request.method)) {
            builder.setEntity(new ByteArrayEntity(request.body.getBytes(StandardCharsets.UTF_8)));
        }
        HttpUriRequest outgoing = builder.build();
        ScheduledFuture<?> deadline = deadlines.schedule(outgoing::abort, timeoutMillis, TimeUnit.MILLISECONDS);
        try (CloseableHttpResponse response = client.execute(outgoing)) {
            int code = response.getStatusLine().getStatusCode();
            boolean retry = code == 408 || code == 429 || code >= 500;
            Header retryAfter = response.getFirstHeader("Retry-After");
            long delay = retryAfter == null ? 0 : retryAfterMillis(retryAfter.getValue(), System.currentTimeMillis());
            // Do not read/store supplier response bodies (which may be unbounded or contain secrets).
            return new Result(code, retry, code >= 200 && code < 300 ? null : "HTTP_" + code, delay);
        } catch (IOException e) {
            // Exception messages often contain URLs/tokens: expose only a stable category.
            return new Result(null, true, "NETWORK_ERROR_OR_TIMEOUT", 0);
        } finally {
            deadline.cancel(false);
        }
    }

    static long retryAfterMillis(String value, long now) {
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.max(0, Math.min(seconds, 86400)) * 1000;
        } catch (NumberFormatException e) {
            try {
                long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(0, Math.min(date - now, 86400000));
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }

    public static final class Result {
        final Integer status;
        final boolean retryable;
        final String error;
        final long retryAfter;

        Result(Integer status, boolean retryable, String error, long retryAfter) {
            this.status = status;
            this.retryable = retryable;
            this.error = error;
            this.retryAfter = retryAfter;
        }

        boolean success() {
            return status != null && status >= 200 && status < 300;
        }
    }
}