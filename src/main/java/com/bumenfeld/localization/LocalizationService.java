package com.bumenfeld.localization;

import com.hypixel.hytale.logger.HytaleLogger;
import com.bumenfeld.discord.DiscordMessages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
          "language": "en",
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
        ensureBundledLocales();
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

    private void ensureBundledLocales() {
        for (Map.Entry<String, String> entry : discoverBundledLocales().entrySet()) {
            String language = entry.getKey();
            Path localeFile = localizationDir.resolve(language + ".json");
            if (Files.exists(localeFile)) {
                continue;
            }
            createBundledLocale(localeFile, language, entry.getValue());
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

    private void createBundledLocale(Path file, String language, String resourcePath) {
        Map<String, String> bundledEntries = readBundledLocale(resourcePath);
        if (bundledEntries.isEmpty()) {
            return;
        }
        bundledEntries.putIfAbsent("language", language);
        try {
            writeLocaleFile(file, bundledEntries);
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to write localization for %s: %s",
                    language,
                    exception.getMessage()
                );
        }
    }

    private Map<String, String> discoverBundledLocales() {
        Map<String, String> detected = new LinkedHashMap<>();
        for (String resourcePath : locateLocaleResources()) {
            String filename = resourcePath.replaceFirst(".*/", "");
            if (!filename.endsWith(".properties")) {
                continue;
            }
            String language =
                filename.substring(0, filename.length() - ".properties".length());
            if (language.isBlank()) {
                continue;
            }
            String normalizedResource =
                resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
            detected.put(language, normalizedResource);
        }
        return detected;
    }

    private List<String> locateLocaleResources() {
        List<String> resources = new ArrayList<>();
        URL localesUrl = LocalizationService.class.getResource("/locales/");
        if (localesUrl == null) {
            return resources;
        }
        try {
            String protocol = localesUrl.getProtocol();
            if ("file".equals(protocol)) {
                Path dir = Paths.get(localesUrl.toURI());
                try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(dir, "*.properties")) {
                    for (Path entry : stream) {
                        resources.add("locales/" + entry.getFileName().toString());
                    }
                }
            } else if ("jar".equals(protocol)) {
                JarURLConnection connection =
                    (JarURLConnection) localesUrl.openConnection();
                try (JarFile jarFile = connection.getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry jarEntry = entries.nextElement();
                        String name = jarEntry.getName();
                        if (!jarEntry.isDirectory()
                            && name.startsWith("locales/")
                            && name.endsWith(".properties")) {
                            resources.add(name);
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to discover bundled localizations: %s",
                    exception.getMessage()
                );
        }
        return resources;
    }

    private Map<String, String> readBundledLocale(String resourcePath) {
        try (InputStream stream =
            LocalizationService.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return Collections.emptyMap();
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> entries = new LinkedHashMap<>();
            for (String key : properties.stringPropertyNames()) {
                entries.put(key, properties.getProperty(key, ""));
            }
            return entries;
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to load bundled localization %s: %s",
                    resourcePath,
                    exception.getMessage()
                );
            return Collections.emptyMap();
        }
    }

    private void writeLocaleFile(Path file, Map<String, String> entries)
        throws IOException
    {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, String> entry = iterator.next();
            builder
                .append("  \"")
                .append(entry.getKey())
                .append("\": \"")
                .append(escape(entry.getValue()))
                .append("\"");
            if (iterator.hasNext()) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("}\n");
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        return language.toLowerCase(java.util.Locale.ROOT);
    }
}
