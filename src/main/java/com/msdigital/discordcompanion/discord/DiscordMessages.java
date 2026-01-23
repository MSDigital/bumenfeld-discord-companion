package com.msdigital.discordcompanion.discord;

import java.util.Objects;

public final class DiscordMessages {

    private final String onlineDescription;
    private final String offlineDescription;

    public DiscordMessages(String onlineDescription, String offlineDescription) {
        this.onlineDescription = normalize(onlineDescription);
        this.offlineDescription = normalize(offlineDescription);
    }

    public String onlineDescription() {
        return onlineDescription;
    }

    public String offlineDescription() {
        return offlineDescription;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    public static DiscordMessages defaults() {
        return new DiscordMessages("Server is online", "Server has shut down");
    }
}
