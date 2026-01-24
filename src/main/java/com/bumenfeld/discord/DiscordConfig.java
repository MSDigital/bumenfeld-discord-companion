package com.bumenfeld.discord;

import java.util.Objects;

public record DiscordConfig(
    String token,
    String guildId,
    boolean setPresence,
    int maxPlayers,
    String statusChannelId,
    String language,
    String announcementRoleId,
    boolean enableStatusEmbed,
    boolean enableWhitelist,
    boolean enableAnnouncements
) {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final DiscordConfig DEFAULT =
        new DiscordConfig(
            null,
            null,
            true,
            0,
            null,
            DEFAULT_LANGUAGE,
            null,
            true,
            true,
            true
        );

    public DiscordConfig {
        token = normalize(token);
        guildId = normalize(guildId);
        statusChannelId = normalize(statusChannelId);
        language = normalize(language);
        announcementRoleId = normalize(announcementRoleId);
        if (maxPlayers < 0) {
            throw new IllegalArgumentException("maxPlayers must be >= 0");
        }
    }

    public boolean enableStatusEmbed() {
        return enableStatusEmbed;
    }

    public boolean enableWhitelist() {
        return enableWhitelist;
    }

    public boolean enableAnnouncements() {
        return enableAnnouncements;
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
}
