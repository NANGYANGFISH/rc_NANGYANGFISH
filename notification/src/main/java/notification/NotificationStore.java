package notification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** One embedded DB connection and one service process. All mutations are durable auto-commit statements. */
public final class NotificationStore implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper json = new ObjectMapper();

    public NotificationStore(Path file) throws SQLException, IOException {
        Path absolute = file.toAbsolutePath();
        if (absolute.toString().contains(";")) {
            throw new IllegalArgumentException("Invalid database path");
        }
        Files.createDirectories(absolute.getParent());
        connection = DriverManager.getConnection("jdbc:h2:file:" + absolute + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS notifications (id VARCHAR(128) PRIMARY KEY, "
                    + "payload CLOB NOT NULL, state VARCHAR(16) NOT NULL, attempts INT NOT NULL, "
                    + "next_attempt BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                    + "last_status INT, last_error VARCHAR(128))");
            statement.execute("CREATE INDEX IF NOT EXISTS due_jobs ON notifications(state, next_attempt)");
            // Only one process may open this embedded database. Its predecessor is no longer delivering.
            statement.executeUpdate("UPDATE notifications SET state='RETRY' WHERE state='IN_FLIGHT'");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    public synchronized boolean submit(Notification.Request request, long now) throws SQLException, IOException {
        String payload = json.writeValueAsString(request);
        try (PreparedStatement statement = connection.prepareStatement("SELECT payload FROM notifications WHERE id=?")) {
            statement.setString(1, request.id);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (!payload.equals(rows.getString(1))) {
                        throw new ConflictException("Notification id already has a different payload");
                    }
                    return false;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notifications(id,payload,state,attempts,next_attempt,created_at,updated_at) "
                        + "VALUES(?,?,'PENDING',0,?,?,?)")) {
            statement.setString(1, request.id);
            statement.setString(2, payload);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
        return true;
    }

    public synchronized Notification.Job claim(long now) throws SQLException, IOException {
        String id;
        String payload;
        int attempts;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,payload,attempts FROM notifications WHERE state IN ('PENDING','RETRY') "
                        + "AND next_attempt<=? ORDER BY next_attempt,created_at,id LIMIT 1")) {
            statement.setLong(1, now);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                id = rows.getString(1);
                payload = rows.getString(2);
                attempts = rows.getInt(3) + 1;
            }
        }
        Notification.Request request = json.readValue(payload, Notification.Request.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE notifications SET state='IN_FLIGHT',attempts=?,updated_at=? WHERE id=?")) {
            statement.setInt(1, attempts);
            statement.setLong(2, now);
            statement.setString(3, id);
            statement.executeUpdate();
        }
        return new Notification.Job(request, attempts);
    }

    public synchronized void finish(String id, String state, long next, Integer status, String error, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE notifications SET state=?,next_attempt=?,last_status=?,last_error=?,updated_at=? "
                        + "WHERE id=? AND state='IN_FLIGHT'")) {
            statement.setString(1, state);
            statement.setLong(2, next);
            statement.setObject(3, status);
            statement.setString(4, error);
            statement.setLong(5, now);
            statement.setString(6, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Job was not in flight");
            }
        }
    }

    public synchronized Notification.Status status(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM notifications WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Notification.Status result = new Notification.Status();
                result.id = id;
                result.state = rows.getString("state");
                result.attempts = rows.getInt("attempts");
                result.nextAttemptAt = rows.getLong("next_attempt");
                result.createdAt = rows.getLong("created_at");
                result.updatedAt = rows.getLong("updated_at");
                result.lastHttpStatus = (Integer) rows.getObject("last_status");
                result.lastError = rows.getString("last_error");
                return result;
            }
        }
    }

    public synchronized boolean redrive(String id, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE notifications SET state='PENDING',attempts=0,next_attempt=?,updated_at=?,"
                        + "last_status=NULL,last_error=NULL WHERE id=? AND state='DEAD'")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, id);
            return statement.executeUpdate() == 1;
        }
    }

    public synchronized boolean healthy() {
        try {
            return connection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    public static final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}