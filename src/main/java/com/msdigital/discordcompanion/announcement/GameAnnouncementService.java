package com.msdigital.discordcompanion.announcement;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import java.util.Objects;

/** Sends announcements via the world event-title system plus a chat message. */
public final class GameAnnouncementService {

    private final HytaleLogger logger;

    public GameAnnouncementService(HytaleLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void broadcast(String announcement) {
        if (announcement == null || announcement.isBlank()) {
            logger
                .atWarning()
                .log("Skipping empty announcement.");
            return;
        }

        Message primary = Message.raw(announcement);
        Message secondary = Message.raw("SERVER INFO");

        try {
            EventTitleUtil.showEventTitleToUniverse(
                primary,
                secondary,
                true,
                EventTitleUtil.DEFAULT_ZONE,
                EventTitleUtil.DEFAULT_DURATION,
                EventTitleUtil.DEFAULT_FADE_DURATION,
                EventTitleUtil.DEFAULT_FADE_DURATION
            );
            Universe.get().sendMessage(Message.raw(announcement));
        } catch (RuntimeException exception) {
            logger
                .atWarning()
                .log("Unable to dispatch announcement: %s", exception.getMessage());
            throw exception;
        }
    }
}
