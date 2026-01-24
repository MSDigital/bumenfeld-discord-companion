package com.bumenfeld;

import com.bumenfeld.database.DatabaseManager;
import com.bumenfeld.database.WhitelistCodeService;
import com.bumenfeld.announcement.GameAnnouncementService;
import com.bumenfeld.discord.DiscordBotService;
import com.bumenfeld.discord.DiscordConfig;
import com.bumenfeld.discord.DiscordConfigLoader;
import com.bumenfeld.localization.LocalizationService;
import com.bumenfeld.util.ReflectionUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.provider.HytaleWhitelistProvider;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordCompanion extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final HytaleWhitelistProvider whitelistProvider;
    private final DatabaseManager databaseManager;
    private final WhitelistCodeService whitelistCodeService;
    private final GameAnnouncementService announcementService;
    private final LocalizationService localizationService;
    private final DiscordBotService discordBotService;
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final Thread shutdownHook;
    private final PlayerLifecycleListener playerLifecycleListener = new PlayerLifecycleListener();
    private final ShutdownListener shutdownListener = new ShutdownListener();
    private final AtomicBoolean shutdownNotified = new AtomicBoolean(false);
        private final ServerLifecycleListener serverLifecycleListener =
            new ServerLifecycleListener();

    private volatile DiscordConfig discordConfig = DiscordConfig.defaults();

    public DiscordCompanion(JavaPluginInit init) {
        super(init);
        LOGGER
            .atInfo()
            .log(
                "Loaded %s (version %s)",
                this.getName(),
                this.getManifest().getVersion().toString()
            );

        HytaleWhitelistProvider provider = ReflectionUtil.getPublic(
            HytaleWhitelistProvider.class,
            AccessControlModule.get(),
            "whitelistProvider"
        );
        if (provider == null) {
            throw new IllegalStateException("Could not find Hytale Access Control Module");
        }
        this.whitelistProvider = provider;

        this.databaseManager = new DatabaseManager(resolveDatabaseDirectory(), LOGGER);
        this.whitelistCodeService = new WhitelistCodeService(
            databaseManager,
            whitelistProvider,
            LOGGER
        );
        this.announcementService = new GameAnnouncementService(LOGGER);
        this.localizationService = new LocalizationService(
            this.getDataDirectory(),
            LOGGER
        );
        this.discordBotService = new DiscordBotService(
            LOGGER,
            whitelistCodeService,
            announcementService,
            localizationService,
            this.getDataDirectory()
        );
        this.shutdownHook = new Thread(
            () -> {
                notifyShutdown();
                try {
                    discordBotService.close();
                } catch (RuntimeException ex) {
                    LOGGER
                        .atWarning()
                        .log(
                            "Failed to close Discord bot cleanly: %s",
                            ex.getMessage()
                        );
                }

                try {
                    whitelistCodeService.close();
                } catch (RuntimeException ex) {
                    LOGGER
                        .atWarning()
                        .log(
                            "Failed to close whitelist database cleanly: %s",
                            ex.getMessage()
                        );
                }
            },
            getName() + "-shutdown-hook"
        );
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    protected void setup() {
        whitelistCodeService.initialize();

        this.discordConfig = DiscordConfigLoader.load(getDataDirectory(), LOGGER);
        discordBotService.start(discordConfig);

        playerLifecycleListener.register();
        shutdownListener.register();
        serverLifecycleListener.register();
    }

    private void refreshPresence() {
        int online = onlinePlayers.size();
        int max = discordConfig.maxPlayers();
        discordBotService.updatePresence(online, max);
    }

    private void notifyShutdown() {
        if (!shutdownNotified.compareAndSet(false, true)) {
            return;
        }

        try {
            discordBotService.sendShutdownNotice();
        } catch (RuntimeException ex) {
            LOGGER
                .atWarning()
                .log("Failed to send shutdown notice: %s", ex.getMessage());
        }
    }

    private Path resolveDatabaseDirectory() {
        return this.getDataDirectory();
    }

    private final class PlayerLifecycleListener {

        void register() {
            getEventRegistry()
                .register(
                    EventPriority.FIRST,
                    PlayerSetupConnectEvent.class,
                    this::onPlayerSetupConnect
                );
            getEventRegistry()
                .register(
                    EventPriority.LAST,
                    PlayerDisconnectEvent.class,
                    this::onPlayerDisconnect
                );
        }

        void onPlayerSetupConnect(PlayerSetupConnectEvent event) {
            UUID playerUuid = event.getUuid();
            if (!discordConfig.enableWhitelist()) {
                if (onlinePlayers.add(playerUuid)) {
                    refreshPresence();
                }
                return;
            }
            if (!whitelistProvider.getList().contains(playerUuid)) {
                String disconnectMessage = buildWhitelistInstructions(playerUuid);
                PacketHandler packetHandler = event.getPacketHandler();
                packetHandler.disconnect(disconnectMessage);
                return;
            }

            if (onlinePlayers.add(playerUuid)) {
                refreshPresence();
            }
        }

        void onPlayerDisconnect(PlayerDisconnectEvent event) {
            UUID playerUuid = event.getPlayerRef().getUuid();
            if (onlinePlayers.remove(playerUuid)) {
                refreshPresence();
            }
        }

        private String buildWhitelistInstructions(UUID playerUuid) {
            String guildInstruction = discordConfig.hasGuildId()
                ? " in the configured Discord server"
                : " in our Discord server";
            try {
                String code = whitelistCodeService.ensureCode(playerUuid);
                return "Use Discord to run /whitelist " +
                    code +
                    " to whitelist yourself" +
                    guildInstruction +
                    ".";
            } catch (RuntimeException ex) {
                LOGGER
                    .atWarning()
                    .log(
                        "Failed to generate whitelist code for %s: %s",
                        playerUuid,
                        ex.getMessage()
                    );
            return "We could not generate a whitelist code at this time. Please contact staff so they can assist you with the /whitelist command" +
                    guildInstruction +
                    ".";
            }
        }
    }

    private final class ShutdownListener {

        void register() {
            getEventRegistry()
                .register(
                    ShutdownEvent.DISCONNECT_PLAYERS,
                    ShutdownEvent.class,
                    this::onShutdown
                );
        }

        void onShutdown(ShutdownEvent event) {
            notifyShutdown();
        }
    }

    private final class ServerLifecycleListener {

        void register() {
            getEventRegistry()
                .register(
                    EventPriority.LAST,
                    BootEvent.class,
                    this::onBoot
                );
        }
        void onBoot(BootEvent event) {
            refreshPresence();
        }
    }
}
