package com.bumenfeld.announcement;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.TempAssetIdUtil;
import java.awt.Color;
import java.util.Objects;

/** Sends announcements via the world event-title system plus a chat message. */
public final class GameAnnouncementService {

    private static final String ANNOUNCEMENT_SOUND = "SFX_Memories_Unlock_Local";
    private static final Float ANNOUNCEMENT_DURATION = 20.0f;

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
            logger
                .atInfo()
                .log("Broadcasting announcement to players: %s", announcement);
            playAnnouncementSound();
            EventTitleUtil.showEventTitleToUniverse(
                primary,
                secondary,
                true,
                null,
                ANNOUNCEMENT_DURATION,
                EventTitleUtil.DEFAULT_FADE_DURATION,
                EventTitleUtil.DEFAULT_FADE_DURATION
            );
            Universe.get().sendMessage(buildStyledChatMessage(announcement));
            logger
                .atInfo()
                .log("Announcement dispatched successfully.");
        } catch (RuntimeException exception) {
            logger
                .atWarning()
                .log("Unable to dispatch announcement: %s", exception.getMessage());
            throw exception;
        }
    }

    private void playAnnouncementSound() {
        int soundIndex = resolveAnnouncementSoundIndex();
        if (soundIndex < 0) {
            logger
                .atWarning()
                .log("Could not find sound event id for %s.", ANNOUNCEMENT_SOUND);
            return;
        }

        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            try {
                SoundUtil.playSoundEvent2dToPlayer(playerRef, soundIndex, SoundCategory.SFX);
            } catch (RuntimeException exception) {
                logger
                    .atWarning()
                .log("Failed to play announcement sound for %s: %s", playerRef, exception.getMessage());
            }
        }
    }

    private Message buildStyledChatMessage(String announcement) {
        Message header =
            Message.raw("[SERVER INFO] ")
                .color(new Color(190, 153, 40))
                .bold(true);
        Message body =
            Message.raw(announcement);
        return Message
            .empty()
            .insertAll(
                header,
                body
            );
    }

    @SuppressWarnings("removal")
    private int resolveAnnouncementSoundIndex() {
        return TempAssetIdUtil.getSoundEventIndex(ANNOUNCEMENT_SOUND);
    }
}
