package notification;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.logging.Logger;

public final class NotificationApplication {
    private static final Logger LOG = Logger.getLogger(NotificationApplication.class.getName());

    private NotificationApplication() { }

    public static void main(String[] args) throws Exception {
        String token = required("NOTIFY_API_TOKEN");
        TargetPolicy policy = new TargetPolicy(required("NOTIFY_ALLOWED_ORIGINS"));
        int port = number("NOTIFY_PORT", 8080, 1, 65535);
        int timeout = number("NOTIFY_TIMEOUT_MS", 5000, 100, 10000);
        int attempts = number("NOTIFY_MAX_ATTEMPTS", 8, 1, 100);
        int base = number("NOTIFY_RETRY_BASE_MS", 1000, 1, 86400000);
        int cap = number("NOTIFY_RETRY_CAP_MS", 300000, base, 86400000);
        // JDK HttpServer limits: put a TLS/rate-limiting reverse proxy in front for deployment.
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "10");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
        NotificationStore store = new NotificationStore(Paths.get(env("NOTIFY_DB_PATH", "./data/notifications")));
        HttpDelivery delivery = new HttpDelivery(policy, timeout);
        DeliveryWorker worker = new DeliveryWorker(store, delivery, attempts, base, cap);
        NotificationApi api;
        try {
            api = new NotificationApi(new InetSocketAddress(env("NOTIFY_BIND_HOST", "127.0.0.1"), port),
                    token, store, policy, worker::healthy);
        } catch (Exception e) {
            worker.close();
            store.close();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            try {
                worker.close();
                store.close();
            } catch (Exception e) {
                LOG.warning("Shutdown incomplete: " + e.getClass().getSimpleName());
            }
        }, "notification-shutdown"));
        worker.start();
        api.start();
        LOG.info("Notification service listening on port " + api.port());
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + key);
        }
        return value;
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    private static int number(String key, int fallback, int min, int max) {
        int value = Integer.parseInt(env(key, Integer.toString(fallback)));
        if (value < min || value > max) {
            throw new IllegalArgumentException("Out of range: " + key);
        }
        return value;
    }
}