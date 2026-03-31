package com.bumenfeld;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExtractedAssetPackManifestEnsurer {
    private static final Pattern GROUP_PATTERN = Pattern.compile("\"Group\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"Name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern INCLUDES_ASSET_PACK_PATTERN = Pattern.compile("\"IncludesAssetPack\"\\s*:\\s*(true|false)");
    private static final Pattern SERVER_VERSION_PATTERN = Pattern.compile("\"ServerVersion\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BUILD_ID_PATTERN = Pattern.compile("\"Id\"\\s*:\\s*\"([^\"]*)\"");

    private ExtractedAssetPackManifestEnsurer() {
    }

    static void ensure(JavaPlugin plugin, HytaleLogger logger) {
        try {
            var pluginManifest = plugin.getManifest();
            Path dataDirectory = plugin.getDataDirectory();
            if (dataDirectory == null) {
                return;
            }

            Files.createDirectories(dataDirectory);
            Path extractedManifest = dataDirectory.resolve("manifest.json");
            String pluginGroup = pluginManifest != null ? pluginManifest.getGroup() : "";
            String pluginName = pluginManifest != null ? pluginManifest.getName() : plugin.getName();
            String extractedPackName = extractedPackName(pluginName);

            try (InputStream stream = plugin.getClass().getClassLoader().getResourceAsStream("manifest.json")) {
                if (stream == null) {
                    logger.atWarning().log("Missing bundled resource: manifest.json");
                    return;
                }
                String bundledJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (!shouldWriteExtractedManifest(extractedManifest, pluginGroup, pluginName, extractedPackName, bundledJson)) {
                    return;
                }

                String json = bundledJson;
                json = replaceField(NAME_PATTERN, json, extractedPackName);
                json = replaceField(INCLUDES_ASSET_PACK_PATTERN, json, "false");
                Files.writeString(extractedManifest, json, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.atWarning().log("Unable to ensure extracted manifest.json: %s", e.getMessage());
        } catch (RuntimeException e) {
            logger.atWarning().log("Unable to ensure extracted manifest.json: %s", e.getMessage());
        }
    }

    private static boolean shouldWriteExtractedManifest(
        Path path,
        String pluginGroup,
        String pluginName,
        String extractedPackName,
        String bundledManifestText
    ) {
        if (!Files.exists(path)) {
            return true;
        }

        try {
            String extractedManifestText = Files.readString(path, StandardCharsets.UTF_8);
            String group = capture(GROUP_PATTERN, extractedManifestText);
            String name = capture(NAME_PATTERN, extractedManifestText);
            if (pluginGroup.equals(group) && extractedPackName.equals(name)) {
                String includesAssetPack = capture(INCLUDES_ASSET_PACK_PATTERN, extractedManifestText);
                if (!"false".equals(includesAssetPack)) {
                    return true;
                }

                String extractedBuildId = capture(BUILD_ID_PATTERN, extractedManifestText);
                String bundledBuildId = capture(BUILD_ID_PATTERN, bundledManifestText);
                String extractedServerVersion = capture(SERVER_VERSION_PATTERN, extractedManifestText);
                String bundledServerVersion = capture(SERVER_VERSION_PATTERN, bundledManifestText);
                boolean buildIdChanged = !bundledBuildId.isBlank() && !bundledBuildId.equals(extractedBuildId);
                boolean serverVersionChanged = !bundledServerVersion.isBlank() && !bundledServerVersion.equals(extractedServerVersion);

                return buildIdChanged || serverVersionChanged;
            }
            if (pluginGroup.equals(group) && pluginName.equals(name)) {
                return true;
            }
            return false;
        } catch (IOException ignored) {
            return true;
        }
    }

    private static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String replaceField(Pattern pattern, String json, String replacementValueLiteral) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return json;
        }
        String replacement = matcher.group().replace(matcher.group(1), replacementValueLiteral);
        return json.substring(0, matcher.start()) + replacement + json.substring(matcher.end());
    }

    private static String extractedPackName(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return "Config_Plugin";
        }
        return "Config_" + pluginName;
    }
}
