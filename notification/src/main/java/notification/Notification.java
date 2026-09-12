package notification;

import java.util.Map;

/** The body is raw UTF-8 text, not a JSON object: JSON/XML/form payloads are preserved. */
public final class Notification {
    private Notification() { }

    public static final class Request {
        public String id;
        public String url;
        public String method = "POST";
        public Map<String, String> headers;
        public String body = "";
    }

    public static final class Status {
        public String id;
        public String state;
        public int attempts;
        public long nextAttemptAt;
        public long createdAt;
        public long updatedAt;
        public Integer lastHttpStatus;
        public String lastError;
    }

    public static final class Job {
        public final Request request;
        public final int attempts;

        public Job(Request request, int attempts) {
            this.request = request;
            this.attempts = attempts;
        }
    }
}