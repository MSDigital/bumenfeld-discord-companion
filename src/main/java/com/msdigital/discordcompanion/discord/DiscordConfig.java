package com.msdigital.discordcompanion.discord;

import java.util.Objects;

public record DiscordConfig(
    String token,
    String guildId,
    boolean setPresence,
    String presenceFormat,
    int maxPlayers,
    String shutdownMessage,
    String statusChannelId,
    String language,
    String announcementRoleId
) {

    private static final String DEFAULT_PRESENCE_FORMAT = "Players {online}/{max}";
    private static final String DEFAULT_SHUTDOWN_MESSAGE =
        "Server is shutting down, see you soon!";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final DiscordConfig DEFAULT =
        new DiscordConfig(
            null,
            null,
            true,
            DEFAULT_PRESENCE_FORMAT,
            0,
            DEFAULT_SHUTDOWN_MESSAGE,
            null,
            DEFAULT_LANGUAGE
            ,
            null
        );

    public DiscordConfig {
        token = normalize(token);
        guildId = normalize(guildId);
        presenceFormat = normalizePresenceFormat(presenceFormat);
        statusChannelId = normalize(statusChannelId);
        shutdownMessage = normalize(shutdownMessage);
        language = normalize(language);
        announcementRoleId = normalize(announcementRoleId);
        if (maxPlayers < 0) {
            throw new IllegalArgumentException("maxPlayers must be >= 0");
        }
    }

    public boolean hasToken() {
        return token != null;
    }

    public boolean hasGuildId() {
        return guildId != null;
    }

    public boolean hasStatusChannel() {
        return statusChannelId != null;
    }

    public String effectiveLanguage() {
        return language == null || language.isBlank()
            ? DEFAULT_LANGUAGE
            : language;
    }

    public String announcementRoleId() {
        return announcementRoleId;
    }

    public static DiscordConfig defaults() {
        return DEFAULT;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizePresenceFormat(String value) {
        String normalized = normalize(value);
        return normalized == null ? DEFAULT_PRESENCE_FORMAT : normalized;
    }
}
