package com.bumenfeld.localization;

import com.hypixel.hytale.logger.HytaleLogger;
import com.bumenfeld.discord.DiscordMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads localization entries from JSON files stored next to the plugin configuration. */
public final class LocalizationService {

    private static final String LOCALIZATION_DIR = "localization";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_PRESENCE_FORMAT =
        "Players {online}/{max}";
    private static final String DEFAULT_JSON = """
        {
          "online-description": "Server is online",
          "offline-description": "Server has shut down",
          "presence-format": "Players {online}/{max}"
        }
        """;
    private static final Pattern ENTRY_PATTERN =
        Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");

    private final HytaleLogger logger;
    private final Path localizationDir;
    private final Map<String, DiscordMessages> cache = new ConcurrentHashMap<>();

    public LocalizationService(Path dataDirectory, HytaleLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dataDirectory, "dataDirectory");

        this.localizationDir = dataDirectory.resolve(LOCALIZATION_DIR);
        try {
            Files.createDirectories(localizationDir);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Unable to prepare localization directory.",
                exception
            );
        }

        ensureDefaultLocale();
    }

    public DiscordMessages getMessages(String language) {
        String normalized = normalizeLanguage(language);
        return cache.computeIfAbsent(normalized, this::loadLocale);
    }

    private DiscordMessages loadLocale(String language) {
        Path localeFile = localizationDir.resolve(language + ".json");
        if (Files.notExists(localeFile)) {
            if (DEFAULT_LANGUAGE.equals(language)) {
                createDefaultLocale(localeFile);
            } else {
                return getMessages(DEFAULT_LANGUAGE);
            }
        }

        try {
            String content = Files.readString(localeFile, StandardCharsets.UTF_8);
            Map<String, String> entries = parseJson(content);
            return new DiscordMessages(
                entries.getOrDefault("online-description", ""),
                entries.getOrDefault("offline-description", ""),
                entries.getOrDefault("presence-format", DEFAULT_PRESENCE_FORMAT)
            );
        } catch (IOException | IllegalArgumentException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to read localization for %s: %s",
                    language,
                    exception.getMessage()
                );
            return DiscordMessages.defaults();
        }
    }

    private Map<String, String> parseJson(String content) {
        Matcher matcher = ENTRY_PATTERN.matcher(content);
        Map<String, String> entries = new ConcurrentHashMap<>();
        while (matcher.find()) {
            entries.put(matcher.group(1), unescape(matcher.group(2)));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Localization JSON contains no entries.");
        }
        return entries;
    }

    private String unescape(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case '\\' -> builder.append('\\');
                    case '"' -> builder.append('"');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    default -> builder.append(next);
                }
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private void ensureDefaultLocale() {
        Path defaultFile = localizationDir.resolve(DEFAULT_LANGUAGE + ".json");
        if (Files.notExists(defaultFile)) {
            createDefaultLocale(defaultFile);
        }
    }

    private void createDefaultLocale(Path file) {
        try {
            Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to write default localization file: %s",
                    exception.getMessage()
                );
        }
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return language.toLowerCase(java.util.Locale.ROOT);
    }
}
