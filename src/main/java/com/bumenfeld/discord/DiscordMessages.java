package com.bumenfeld.discord;

import java.util.Objects;

public final class DiscordMessages {

    private final String onlineDescription;
    private final String offlineDescription;
    private final String presenceFormat;

    public DiscordMessages(
        String onlineDescription,
        String offlineDescription,
        String presenceFormat
    ) {
        this.onlineDescription = normalize(onlineDescription);
        this.offlineDescription = normalize(offlineDescription);
        this.presenceFormat = normalize(presenceFormat);
    }

    public String onlineDescription() {
        return onlineDescription;
    }

    public String offlineDescription() {
        return offlineDescription;
    }

    public String presenceFormat() {
        return presenceFormat;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    public static DiscordMessages defaults() {
        return new DiscordMessages(
            "Server is online",
            "Server has shut down",
            "Players {online}/{max}"
        );
    }
}
