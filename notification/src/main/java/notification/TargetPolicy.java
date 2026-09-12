package notification;

import org.apache.http.conn.DnsResolver;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/** Exact origins plus connect-time IP checks prevent an arbitrary HTTP proxy/SSRF. */
public final class TargetPolicy implements DnsResolver {
    private final Set<String> origins = new HashSet<>();
    private final boolean allowPrivateForTests;
    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "host", "content-length", "transfer-encoding", "connection", "expect", "upgrade",
            "proxy-authorization", "proxy-connection", "te", "trailer", "idempotency-key"));

    public TargetPolicy(String configuredOrigins) {
        this(configuredOrigins, false);
    }

    TargetPolicy(String configuredOrigins, boolean allowPrivateForTests) {
        this.allowPrivateForTests = allowPrivateForTests;
        for (String value : configuredOrigins.split(",")) {
            URI uri = parse(value.trim());
            if (uri.getRawQuery() != null || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException("Allowed origins must not contain a path or query");
            }
            origins.add(origin(uri));
        }
    }

    public void validate(Notification.Request request) {
        if (request == null || request.id == null || !request.id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("id must contain 1-128 letters, digits, underscores or hyphens");
        }
        URI uri = parse(request.url);
        if (!origins.contains(origin(uri))) {
            throw new IllegalArgumentException("Target origin is not allowed");
        }
        // HttpClient may bypass its DNS resolver for IP literals; validate those here too.
        String host = uri.getHost();
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try {
                resolve(host);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Target IP is prohibited");
            }
        }
        if (!Arrays.asList("POST", "PUT", "PATCH", "DELETE", "GET").contains(request.method)) {
            throw new IllegalArgumentException("Unsupported HTTP method");
        }
        if (request.body == null) {
            request.body = "";
        }
        if (request.body.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Body exceeds 64 KiB");
        }
        if ("GET".equals(request.method) && !request.body.isEmpty()) {
            throw new IllegalArgumentException("GET body is not supported");
        }
        normalizeHeaders(request);
    }

    private void normalizeHeaders(Notification.Request request) {
        TreeMap<String, String> normalized = new TreeMap<>();
        int size = 0;
        if (request.headers != null) {
            for (java.util.Map.Entry<String, String> entry : request.headers.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value == null
                        || value.chars().anyMatch(c -> c < 32 || c > 126)) {
                    throw new IllegalArgumentException("Invalid header name or value");
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (FORBIDDEN.contains(lower) || normalized.put(lower, value) != null) {
                    throw new IllegalArgumentException("Reserved or duplicate header");
                }
                size += name.length() + value.length();
            }
        }
        if (normalized.size() > 32 || size > 8192) {
            throw new IllegalArgumentException("Headers exceed limit");
        }
        request.headers = normalized;
    }

    private static URI parse(String value) {
        try {
            URI uri = new URI(value);
            if (value.length() > 4096 || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Invalid HTTP(S) URL");
            }
            return uri;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid HTTP(S) URL");
        }
    }

    private static String origin(URI uri) {
        int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (!allowPrivateForTests && (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)
                    || (address.getAddress().length == 4 && (address.getAddress()[0] & 255) == 100
                    && (address.getAddress()[1] & 255) >= 64 && (address.getAddress()[1] & 255) <= 127))) {
                throw new UnknownHostException("Target resolved to a prohibited network");
            }
        }
        return addresses;
    }
}