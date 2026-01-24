package com.bumenfeld.database;

import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class DatabaseManager implements AutoCloseable {

    private static final String DEFAULT_DATABASE_NAME = "whitelist_codes.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS whitelist_codes (
            player_uuid TEXT PRIMARY KEY,
            whitelist_code TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL,
            validated_at INTEGER
        )
        """;

    private static final String UPSERT_SQL = """
        INSERT INTO whitelist_codes (player_uuid, whitelist_code, created_at, validated_at)
        VALUES (?, ?, ?, NULL)
        ON CONFLICT(player_uuid) DO UPDATE SET
            whitelist_code = excluded.whitelist_code,
            created_at = excluded.created_at,
            validated_at = NULL
        """;

    private static final String SELECT_BY_PLAYER_SQL = """
        SELECT player_uuid, whitelist_code, created_at, validated_at
        FROM whitelist_codes
        WHERE player_uuid = ?
        """;

    private static final String SELECT_BY_CODE_SQL = """
        SELECT player_uuid, whitelist_code, created_at, validated_at
        FROM whitelist_codes
        WHERE whitelist_code = ?
        """;

    private static final String SELECT_ACTIVE_SQL = """
        SELECT player_uuid, whitelist_code, created_at, validated_at
        FROM whitelist_codes
        WHERE validated_at IS NULL
        ORDER BY created_at ASC
        """;

    private static final String MARK_VALIDATED_SQL = """
        UPDATE whitelist_codes
        SET validated_at = ?
        WHERE player_uuid = ? AND validated_at IS NULL
        """;

    private static final String DELETE_SQL = """
        DELETE FROM whitelist_codes
        WHERE player_uuid = ?
        """;

    private final Path databasePath;
    private final HytaleLogger logger;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private Connection connection;

    public DatabaseManager(Path dataDirectory, HytaleLogger logger) {
        this(dataDirectory, DEFAULT_DATABASE_NAME, logger);
    }

    public DatabaseManager(
        Path dataDirectory,
        String databaseFileName,
        HytaleLogger logger
    ) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
        String resolvedFileName = sanitizeFileName(databaseFileName);
        this.databasePath = dataDirectory.resolve(resolvedFileName);
    }

    public void initialize() {
        lock.lock();
        try {
            if (initialized.get()) {
                return;
            }

            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Class.forName("org.sqlite.JDBC");

            connection = DriverManager.getConnection(
                JDBC_URL_PREFIX + databasePath.toAbsolutePath()
            );
            connection.setAutoCommit(true);

            executeInitializationStatements(connection);

            initialized.set(true);
            logger
                .atInfo()
                .log(
                    "Whitelist database ready at %s",
                    databasePath.toAbsolutePath()
                );
        } catch (
            SQLException
            | IOException
            | ClassNotFoundException exception
        ) {
            throw new DatabaseException(
                "Unable to initialize whitelist database",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public void upsertCode(UUID playerUuid, String whitelistCode) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(whitelistCode, "whitelistCode");

        lock.lock();
        try {
            ensureInitialized();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    UPSERT_SQL
                )
            ) {
                long now = Instant.now().toEpochMilli();
                statement.setString(1, playerUuid.toString());
                statement.setString(2, whitelistCode);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to upsert whitelist code",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public Optional<WhitelistCode> findByPlayer(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");

        lock.lock();
        try {
            ensureInitialized();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    SELECT_BY_PLAYER_SQL
                )
            ) {
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(mapRow(resultSet));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to fetch whitelist code by player",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public Optional<WhitelistCode> findByCode(String whitelistCode) {
        Objects.requireNonNull(whitelistCode, "whitelistCode");

        lock.lock();
        try {
            ensureInitialized();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    SELECT_BY_CODE_SQL
                )
            ) {
                statement.setString(1, whitelistCode);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return Optional.of(mapRow(resultSet));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to fetch whitelist code by value",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public List<WhitelistCode> listActiveCodes() {
        lock.lock();
        try {
            ensureInitialized();
            List<WhitelistCode> results = new ArrayList<>();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    SELECT_ACTIVE_SQL
                )
            ) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        results.add(mapRow(resultSet));
                    }
                }
            }
            return List.copyOf(results);
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to list active whitelist codes",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public boolean markValidated(UUID playerUuid, Instant validatedAt) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Instant timestamp = validatedAt != null ? validatedAt : Instant.now();

        lock.lock();
        try {
            ensureInitialized();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    MARK_VALIDATED_SQL
                )
            ) {
                statement.setLong(1, timestamp.toEpochMilli());
                statement.setString(2, playerUuid.toString());
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to mark whitelist code as validated",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    public boolean delete(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");

        lock.lock();
        try {
            ensureInitialized();
            try (
                PreparedStatement statement = connection.prepareStatement(
                    DELETE_SQL
                )
            ) {
                statement.setString(1, playerUuid.toString());
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(
                "Unable to delete whitelist code",
                exception
            );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (connection != null) {
                try {
                    connection.close();
                    logger.atInfo().log("Whitelist database connection closed");
                } catch (SQLException exception) {
                    throw new DatabaseException(
                        "Unable to close whitelist database connection",
                        exception
                    );
                } finally {
                    connection = null;
                    initialized.set(false);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void executeInitializationStatements(Connection connection)
        throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute(CREATE_TABLE_SQL);
        }
    }

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException(
                "DatabaseManager has not been initialized"
            );
        }
    }

    private static WhitelistCode mapRow(ResultSet resultSet)
        throws SQLException {
        UUID playerUuid = UUID.fromString(resultSet.getString("player_uuid"));
        String whitelistCode = resultSet.getString("whitelist_code");
        Instant createdAt = Instant.ofEpochMilli(
            resultSet.getLong("created_at")
        );
        long validatedAtRaw = resultSet.getLong("validated_at");
        Instant validatedAt = resultSet.wasNull()
            ? null
            : Instant.ofEpochMilli(validatedAtRaw);
        return new WhitelistCode(
            playerUuid,
            whitelistCode,
            createdAt,
            validatedAt
        );
    }

    private static String sanitizeFileName(String databaseFileName) {
        if (databaseFileName == null || databaseFileName.isBlank()) {
            return DEFAULT_DATABASE_NAME;
        }
        return databaseFileName;
    }

    public record WhitelistCode(
        UUID playerUuid,
        String whitelistCode,
        Instant createdAt,
        Instant validatedAt
    ) {
        public boolean isValidated() {
            return validatedAt != null;
        }
    }

    public static final class DatabaseException extends RuntimeException {

        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
