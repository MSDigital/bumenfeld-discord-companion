package com.msdigital.discordcompanion.discord;

import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Loads the Discord bridge configuration from the plugin data directory.
 */
public final class DiscordConfigLoader {

    private static final String CONFIG_FILE_NAME = "discord.yml";
    private static final Yaml YAML = new Yaml();

    private static final String DEFAULT_FILE_CONTENT = """
        # Discord bridge settings
        token: ""
        guild-id: ""
        status-channel-id: ""
        shutdown-message: "Server is shutting down, see you soon!"
        set-presence: true
        presence-format: "Players {online}/{max}"
        max-players: 0
        language: "en"
        announcement-role-id: ""
        """;

    private DiscordConfigLoader() {
        // utility class
    }

    public static DiscordConfig load(
        Path dataDirectory,
        HytaleLogger logger
    ) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(logger, "logger");

        ensureDirectoryExists(dataDirectory, logger);

        Path configPath = dataDirectory.resolve(CONFIG_FILE_NAME);
        if (Files.notExists(configPath)) {
            writeDefaultConfig(configPath, logger);
            logger
                .atWarning()
                .log(
                    "Discord configuration created at %s. Populate the token to enable the bot.",
                    configPath.toAbsolutePath()
                );
            return DiscordConfig.defaults();
        }

        try (InputStream stream = Files.newInputStream(configPath)) {
            Object root = YAML.load(stream);
            if (root == null) {
                logger
                    .atWarning()
                    .log(
                        "Discord configuration file %s is empty; using defaults.",
                        configPath.toAbsolutePath()
                    );
                return DiscordConfig.defaults();
            }

            if (!(root instanceof Map<?, ?> mapRoot)) {
                throw new IllegalStateException(
                    "Discord configuration must contain a YAML mapping at the root."
                );
            }

            Map<String, Object> normalized = normalizeMap(mapRoot);
            DiscordConfig config = fromMap(normalized);

            if (!config.hasToken()) {
                logger
                    .atWarning()
                    .log(
                        "Discord bot token is not configured; Discord integration remains disabled."
                    );
            } else {
                logger
                    .atInfo()
                    .log("Discord configuration loaded successfully.");
            }

            return config;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to read Discord configuration file.",
                exception
            );
        } catch (YAMLException exception) {
            throw new IllegalStateException(
                "Discord configuration file contains invalid YAML.",
                exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Discord configuration file contains invalid values.",
                exception
            );
        }
    }

    private static void ensureDirectoryExists(
        Path dataDirectory,
        HytaleLogger logger
    ) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            logger
                .atSevere()
                .log(
                    "Unable to prepare data directory for Discord configuration: %s",
                    exception.getMessage()
                );
            throw new IllegalStateException(
                "Unable to prepare data directory for Discord configuration.",
                exception
            );
        }
    }

    private static void writeDefaultConfig(
        Path configPath,
        HytaleLogger logger
    ) {
        try {
            Files.writeString(
                configPath,
                DEFAULT_FILE_CONTENT,
                StandardCharsets.UTF_8
            );
            logger
                .atInfo()
                .log(
                    "Created default Discord configuration file at %s",
                    configPath.toAbsolutePath()
                );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to create default Discord configuration file.",
                exception
            );
        }
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            normalized.put(entry.getKey().toString(), entry.getValue());
        }
        return normalized;
    }

    private static String readOptionalString(
        Map<String, Object> source,
        String key
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");

        Object raw = source.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException(
                "Discord configuration property '" +
                    key +
                    "' must be a string when provided."
            );
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Boolean readOptionalBoolean(
        Map<String, Object> source,
        String key
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");

        Object raw = source.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return Boolean.parseBoolean(trimmed);
        }
        throw new IllegalArgumentException(
            "Discord configuration property '" +
                key +
                "' must be a boolean when provided."
        );
    }

    private static Integer readOptionalInteger(
        Map<String, Object> source,
        String key
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");

        Object raw = source.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (raw instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return Integer.parseInt(trimmed);
        }
        throw new IllegalArgumentException(
            "Discord configuration property '" +
                key +
                "' must be an integer when provided."
        );
    }

    private static DiscordConfig fromMap(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return DiscordConfig.defaults();
        }

        String token = readOptionalString(rawConfig, "token");
        String guildId = readOptionalString(rawConfig, "guild-id");
        Boolean setPresenceValue =
            readOptionalBoolean(rawConfig, "set-presence");
        boolean setPresence =
            setPresenceValue == null ? true : setPresenceValue;
        String presenceFormat =
            readOptionalString(rawConfig, "presence-format");
        Integer maxPlayersValue =
            readOptionalInteger(rawConfig, "max-players");
        int maxPlayers = maxPlayersValue == null ? 0 : maxPlayersValue;
        String statusChannelId =
            readOptionalString(rawConfig, "status-channel-id");
        String shutdownMessage =
            readOptionalString(rawConfig, "shutdown-message");
        String language = readOptionalString(rawConfig, "language");
        String announcementRoleId =
            readOptionalString(rawConfig, "announcement-role-id");

        return new DiscordConfig(
            token,
            guildId,
            setPresence,
            presenceFormat,
            maxPlayers,
            shutdownMessage,
            statusChannelId,
            language,
            announcementRoleId
        );
    }
}
