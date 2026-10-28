package de.fhaachen.voicehome.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import de.fhaachen.voicehome.domain.HomeModel.*;

/** Embedded relational storage. Each configuration replacement is one transaction. */
public final class JdbcHomeStore implements ConfigurationRepository, EventRepository {
    public static final class StorageException extends RuntimeException {
        public StorageException(Throwable cause) { super("Local storage is unavailable.", cause); }
    }
    private final String url;

    public JdbcHomeStore(Path databaseFile, Path legacyConfiguration) {
        Path database = databaseFile.toAbsolutePath().normalize();
        try { Files.createDirectories(database.getParent()); }
        catch (IOException exception) { throw new StorageException(exception); }
        url = "jdbc:h2:file:" + database;
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS app_meta (meta_key VARCHAR(60) PRIMARY KEY, meta_value VARCHAR(60) NOT NULL)");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS controller_requests (
                    request_key VARCHAR(40) PRIMARY KEY, request_frame VARCHAR(128) NOT NULL,
                    reply_frame VARCHAR(128))
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS devices (
                    id VARCHAR(48) PRIMARY KEY, device_name VARCHAR(80) NOT NULL,
                    room VARCHAR(60) NOT NULL, simulation_mode VARCHAR(20) NOT NULL,
                    device_type VARCHAR(20) NOT NULL)
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS command_mappings (
                    command_id VARCHAR(48) PRIMARY KEY, device_id VARCHAR(48) NOT NULL,
                    device_action VARCHAR(8) NOT NULL,
                    FOREIGN KEY (device_id) REFERENCES devices(id))
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS command_events (
                    sequence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    id VARCHAR(36) UNIQUE NOT NULL, occurred_at VARCHAR(40) NOT NULL,
                    command_id CLOB, confidence DOUBLE PRECISION,
                    device_id VARCHAR(48), device_action VARCHAR(8), outcome VARCHAR(20) NOT NULL,
                    requested_state VARCHAR(10) NOT NULL, reported_state VARCHAR(10) NOT NULL,
                    result_message VARCHAR(1000) NOT NULL, duration_ms DOUBLE PRECISION NOT NULL,
                    is_read BOOLEAN DEFAULT FALSE NOT NULL)
                """);
        } catch (SQLException exception) { throw new StorageException(exception); }
        // Import only into an uninitialized database. Preserve the original JSON file.
        if (load().isEmpty() && Files.exists(legacyConfiguration)) {
            new ConfigurationStore(legacyConfiguration).load().ifPresent(this::save);
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }

    public record ControllerReceipt(boolean fresh, String request, String reply) { }

    /** Reserve before actuation. An interrupted request stays uncertain and is never replayed. */
    public ControllerReceipt claimControllerRequest(String key, String frame) {
        try (Connection connection = connect()) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO controller_requests (request_key, request_frame) VALUES (?, ?)")) {
                insert.setString(1, key); insert.setString(2, frame); insert.executeUpdate();
                return new ControllerReceipt(true, frame, null);
            } catch (SQLException duplicate) {
                if (!"23505".equals(duplicate.getSQLState())) { throw duplicate; }
            }
            try (PreparedStatement select = connection.prepareStatement("SELECT request_frame, reply_frame FROM controller_requests WHERE request_key = ?")) {
                select.setString(1, key);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) { throw new SQLException("Missing controller receipt"); }
                    return new ControllerReceipt(false, row.getString(1), row.getString(2));
                }
            }
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    public void completeControllerRequest(String key, String reply) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE controller_requests SET reply_frame = ? WHERE request_key = ? AND reply_frame IS NULL")) {
            update.setString(1, reply); update.setString(2, key);
            if (update.executeUpdate() != 1) { throw new SQLException("Controller receipt was not pending"); }
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    @Override public Optional<Configuration> load() {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            int version;
            try (ResultSet meta = statement.executeQuery("SELECT meta_value FROM app_meta WHERE meta_key = 'schema_version'")) {
                if (!meta.next()) { return Optional.empty(); }
                version = Integer.parseInt(meta.getString(1));
            }
            List<Device> devices = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT * FROM devices ORDER BY id")) {
                while (rows.next()) devices.add(new Device(rows.getString("id"), rows.getString("device_name"),
                        rows.getString("room"), SimulationMode.valueOf(rows.getString("simulation_mode")),
                        DeviceType.valueOf(rows.getString("device_type"))));
            }
            List<Mapping> mappings = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT * FROM command_mappings ORDER BY command_id")) {
                while (rows.next()) mappings.add(new Mapping(rows.getString("command_id"), rows.getString("device_id"), Action.valueOf(rows.getString("device_action"))));
            }
            return Optional.of(new Configuration(version, devices, mappings));
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    @Override public void save(Configuration configuration) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM command_mappings");
                    statement.executeUpdate("DELETE FROM devices");
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO devices VALUES (?, ?, ?, ?, ?)")) {
                    for (Device device : configuration.devices()) {
                        statement.setString(1, device.id()); statement.setString(2, device.name());
                        statement.setString(3, device.room()); statement.setString(4, device.simulationMode().name());
                        statement.setString(5, device.type().name()); statement.addBatch();
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO command_mappings VALUES (?, ?, ?)")) {
                    for (Mapping mapping : configuration.mappings()) {
                        statement.setString(1, mapping.commandId()); statement.setString(2, mapping.deviceId());
                        statement.setString(3, mapping.action().name()); statement.addBatch();
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement("MERGE INTO app_meta (meta_key, meta_value) KEY (meta_key) VALUES ('schema_version', ?)")) {
                    statement.setString(1, Integer.toString(configuration.schemaVersion())); statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback(); throw exception;
            }
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    @Override public void append(CommandResult event) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO command_events (id, occurred_at, command_id, confidence, device_id, device_action,
                    outcome, requested_state, reported_state, result_message, duration_ms, is_read)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.id()); statement.setString(2, event.timestamp().toString());
            statement.setString(3, event.commandId()); statement.setObject(4, event.confidence());
            statement.setString(5, event.deviceId()); statement.setString(6, event.action() == null ? null : event.action().name());
            statement.setString(7, event.outcome().name()); statement.setString(8, event.requestedState().name());
            statement.setString(9, event.reportedState().name()); statement.setString(10, event.message());
            statement.setDouble(11, event.durationMs()); statement.setBoolean(12, event.read());
            statement.executeUpdate();
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    @Override public List<CommandResult> recent() {
        try (Connection connection = connect(); var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM command_events ORDER BY sequence_id DESC LIMIT 100")) {
            List<CommandResult> events = new ArrayList<>();
            while (rows.next()) {
                String action = rows.getString("device_action");
                events.add(new CommandResult(rows.getString("id"), Instant.parse(rows.getString("occurred_at")),
                        rows.getString("command_id"), rows.getObject("confidence", Double.class), rows.getString("device_id"),
                        action == null ? null : Action.valueOf(action), Outcome.valueOf(rows.getString("outcome")),
                        State.valueOf(rows.getString("requested_state")), State.valueOf(rows.getString("reported_state")),
                        rows.getString("result_message"), rows.getDouble("duration_ms"), rows.getBoolean("is_read")));
            }
            return List.copyOf(events);
        } catch (SQLException exception) { throw new StorageException(exception); }
    }

    @Override public void markRead(List<String> ids) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("UPDATE command_events SET is_read = TRUE WHERE id = ?")) {
            connection.setAutoCommit(false);
            try {
                for (String id : ids) { statement.setString(1, id); statement.addBatch(); }
                statement.executeBatch(); connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new StorageException(exception); }
    }
}
