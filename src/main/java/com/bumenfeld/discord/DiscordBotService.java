package com.bumenfeld.discord;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.bumenfeld.announcement.GameAnnouncementService;
import com.bumenfeld.database.WhitelistCodeService;
import com.bumenfeld.database.WhitelistCodeService.ValidateResult;
import com.bumenfeld.localization.LocalizationService;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;

public final class DiscordBotService implements AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

    private final HytaleLogger logger;
    private final WhitelistCodeService whitelistCodeService;
    private final GameAnnouncementService announcementService;
    private final LocalizationService localizationService;

    private volatile DiscordConfig configuration = DiscordConfig.defaults();
    private volatile JDA jda;
    private volatile String resolvedServerName;
    private final Path statusMessageIdFile;
    private volatile Long statusMessageId;
    private static final Color STATUS_ONLINE = new Color(67, 181, 129);
    private static final Color STATUS_OFFLINE = new Color(206, 67, 52);

    public DiscordBotService(
        HytaleLogger logger,
        WhitelistCodeService whitelistCodeService,
        GameAnnouncementService announcementService,
        LocalizationService localizationService,
        Path dataDirectory
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.whitelistCodeService = Objects.requireNonNull(
            whitelistCodeService,
            "whitelistCodeService"
        );
        this.announcementService =
            Objects.requireNonNull(announcementService, "announcementService");
        this.localizationService =
            Objects.requireNonNull(localizationService, "localizationService");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.statusMessageIdFile =
            dataDirectory.resolve("discord-status-message-id.txt");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to ensure Discord status directory exists: %s",
                    exception.getMessage()
                );
        }
    }

    public synchronized void start(DiscordConfig config) {
        configuration = config != null ? config : DiscordConfig.defaults();

        if (!configuration.hasToken()) {
            logger.atInfo().log("Discord bot token is not configured.");
            stopInternal();
            return;
        }

        stopInternal();
        logger.atInfo().log("Starting Discord bot.");

        // keep JDA's simplelogger on stdout so Hytale doesn't treat it like SEVERE
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");

        EnumSet<GatewayIntent> intents = EnumSet.of(
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.DIRECT_MESSAGES
        );

        try {
            JDABuilder builder = JDABuilder.createLight(
                configuration.token(),
                intents
            )
                .disableCache(
                    EnumSet.of(
                        CacheFlag.ACTIVITY,
                        CacheFlag.CLIENT_STATUS,
                        CacheFlag.EMOJI,
                        CacheFlag.SCHEDULED_EVENTS,
                        CacheFlag.STICKER,
                        CacheFlag.VOICE_STATE
                    )
                )
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .setStatus(OnlineStatus.ONLINE)
                .addEventListeners(
                new DiscordEventListener(
                    configuration,
                    whitelistCodeService,
                    announcementService,
                    logger
                )
                )
                .setAutoReconnect(true);

            JDA instance = builder.build();
            instance.awaitReady();

            jda = instance;
            restoreStatusMessageReference();
            logger.atInfo().log("Discord bot is online and ready.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Discord bot startup was interrupted.",
                exception
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Unable to initialize the Discord bot. Verify the configured token and configuration values.",
                exception
            );
        }
    }

    public synchronized void reload() {
        start(configuration);
    }

    public synchronized void reload(DiscordConfig config) {
        start(config);
    }

    public Optional<JDA> getJda() {
        return Optional.ofNullable(jda);
    }

    public DiscordConfig getConfiguration() {
        return configuration;
    }

    public void updatePresence(int onlinePlayers, int maxPlayers) {
        DiscordMessages messages = resolveMessages();
        JDA instance = jda;
        if (instance == null) {
            return;
        }
        if (configuration.setPresence()) {
            String presenceText = formatPresenceDescription(
                messages,
                onlinePlayers,
                maxPlayers
            );
            instance
                .getPresence()
                .setActivity(Activity.playing(presenceText));
        } else {
            instance
                .getPresence()
                .setActivity(Activity.playing("Hytale"));
        }
        int resolvedMax = maxPlayers;
        if (resolvedMax <= 0) {
            resolvedMax = configuration.maxPlayers();
        }
        if (configuration.enableStatusEmbed()) {
            refreshStatusEmbed(
                onlinePlayers,
                resolvedMax,
                true,
                messages
            );
        } else {
            removeStatusEmbedIfPresent();
        }
    }

    public void sendShutdownNotice() {
        refreshStatusEmbed(
            0,
            configuration.maxPlayers(),
            false,
            true,
            resolveMessages()
        );
    }

    private void refreshStatusEmbed(
        int onlinePlayers,
        int maxPlayers,
        boolean online,
        DiscordMessages messages
    ) {
        refreshStatusEmbed(onlinePlayers, maxPlayers, online, false, messages);
    }

    private void refreshStatusEmbed(
        int onlinePlayers,
        int maxPlayers,
        boolean online,
        boolean waitForCompletion,
        DiscordMessages messages
    ) {
        if (!configuration.hasStatusChannel() || !configuration.enableStatusEmbed()) {
            return;
        }
        JDA instance = jda;
        if (instance == null) {
            return;
        }
        TextChannel channel =
            instance.getTextChannelById(configuration.statusChannelId());
        if (channel == null) {
            logger
                .atWarning()
                .log(
                    "Discord status channel %s not found.",
                    configuration.statusChannelId()
                );
            return;
        }

        String descriptionText =
            online
                ? messages.onlineDescription()
                : messages.offlineDescription();
        MessageEmbed embed = createStatusEmbed(
            onlinePlayers,
            maxPlayers,
            online,
            descriptionText,
            embedTitle()
        );
        Long messageId = statusMessageId;
        RestAction<Message> action;

        if (messageId != null) {
            action = channel.editMessageEmbedsById(messageId, embed);
        } else {
            action = channel.sendMessageEmbeds(embed);
        }

        if (waitForCompletion) {
            try {
                Message message = action.complete();
                setStatusMessageId(message.getIdLong());
            } catch (RuntimeException failure) {
                logger
                    .atWarning()
                    .log(
                        "Unable to update Discord status embed: %s",
                        failure.getMessage()
                    );
                clearStatusMessageId();
            }
            return;
        }

        action.queue(
            message -> setStatusMessageId(message.getIdLong()),
            failure -> {
                logger
                    .atWarning()
                    .log(
                        "Unable to update Discord status embed: %s",
                        failure.getMessage()
                    );
                clearStatusMessageId();
            }
        );
    }

    private String embedTitle() {
        String serverName = resolveServerName();
        if (serverName == null || serverName.isBlank()) {
            return "Server Status";
        }
        return serverName + " Server Status";
    }

    private String resolveServerName() {
        String cached = resolvedServerName;
        if (cached != null) {
            return cached;
        }
        try {
            HytaleServerConfig config = HytaleServerConfig.load();
            String name = config.getServerName();
            if (name != null && !name.isBlank()) {
                resolvedServerName = name;
                return name;
            }
        } catch (RuntimeException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to read Hytale server name for Discord embed: %s",
                    exception.getMessage()
                );
        }
        return "Server Status";
    }

    private static MessageEmbed createStatusEmbed(
        int onlinePlayers,
        int maxPlayers,
        boolean online,
        String description,
        String title
    ) {
        EmbedBuilder builder = new EmbedBuilder();
        builder.setTitle(title);
        String status = online ? "Online" : "Offline";
        builder.addField("Status", status, true);
        int resolvedMax = maxPlayers <= 0 ? 0 : maxPlayers;
        builder.addField(
            "Players",
            onlinePlayers + " / " + (resolvedMax <= 0 ? "?" : resolvedMax),
            true
        );
        if (description != null && !description.isBlank()) {
            builder.setDescription(description);
        }
        builder.setColor(online ? STATUS_ONLINE : STATUS_OFFLINE);
        builder.setTimestamp(OffsetDateTime.now());
        builder.setFooter("Last update");
        return builder.build();
    }

    private void restoreStatusMessageReference() {
        if (statusMessageIdFile == null || !Files.exists(statusMessageIdFile)) {
            return;
        }
        try {
            String text =
                Files.readString(statusMessageIdFile, StandardCharsets.UTF_8)
                    .trim();
            if (!text.isEmpty()) {
                statusMessageId = Long.parseLong(text);
            }
        } catch (IOException | NumberFormatException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to restore Discord status message reference: %s",
                    exception.getMessage()
                );
            clearStatusMessageId();
        }
    }

    private void removeStatusEmbedIfPresent() {
        if (statusMessageId == null || !configuration.hasStatusChannel()) {
            return;
        }
        JDA instance = jda;
        if (instance == null) {
            clearStatusMessageId();
            return;
        }
        TextChannel channel =
            instance.getTextChannelById(configuration.statusChannelId());
        if (channel == null) {
            clearStatusMessageId();
            return;
        }
        channel
            .deleteMessageById(statusMessageId)
            .queue(
                ignored -> clearStatusMessageId(),
                failure -> {
                    logger
                        .atWarning()
                        .log(
                            "Unable to delete Discord status embed: %s",
                            failure.getMessage()
                        );
                    clearStatusMessageId();
                }
            );
    }

    private void setStatusMessageId(Long id) {
        if (id == null) {
            clearStatusMessageId();
            return;
        }
        statusMessageId = id;
        try {
            Files.writeString(
                statusMessageIdFile,
                id.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to persist Discord status message reference: %s",
                    exception.getMessage()
                );
        }
    }

    private void clearStatusMessageId() {
        statusMessageId = null;
        try {
            Files.deleteIfExists(statusMessageIdFile);
        } catch (IOException exception) {
            logger
                .atWarning()
                .log(
                    "Unable to clear Discord status message reference: %s",
                    exception.getMessage()
                );
        }
    }

    private DiscordMessages resolveMessages() {
        return localizationService.getMessages(configuration.effectiveLanguage());
    }

    private String formatPresenceDescription(
        DiscordMessages messages,
        int onlinePlayers,
        int maxPlayers
    ) {
        int resolvedMax = maxPlayers;
        if (resolvedMax <= 0) {
            resolvedMax = configuration.maxPlayers();
        }
        String template = messages.presenceFormat();
        return template
            .replace("{online}", String.valueOf(onlinePlayers))
            .replace("{max}", String.valueOf(resolvedMax));
    }

    @Override
    public synchronized void close() {
        stopInternal();
    }

    private void stopInternal() {
        JDA instance = jda;
        if (instance == null) {
            return;
        }

        jda = null;
        logger.atInfo().log("Shutting down Discord bot.");

        instance.shutdown();

        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        boolean shutdownComplete = false;

        while (System.nanoTime() < deadline) {
            if (instance.getStatus() == JDA.Status.SHUTDOWN) {
                shutdownComplete = true;
                break;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                logger
                    .atWarning()
                    .log("Interrupted while waiting for Discord bot shutdown.");
                return;
            }
        }

        if (shutdownComplete) {
            logger.atInfo().log("Discord bot stopped cleanly.");
        } else {
            logger
                .atWarning()
                .log(
                    "Discord bot did not shut down before timeout (%s seconds).",
                    SHUTDOWN_TIMEOUT.toSeconds()
                );
        }
    }

    private static final class DiscordEventListener extends ListenerAdapter {

        private static final String NEW_LINE = "\n";

        private final DiscordConfig config;
        private final WhitelistCodeService whitelistCodeService;
        private final GameAnnouncementService announcementService;
        private final HytaleLogger logger;

        private DiscordEventListener(
            DiscordConfig config,
            WhitelistCodeService whitelistCodeService,
            GameAnnouncementService announcementService,
            HytaleLogger logger
        ) {
            this.config = Objects.requireNonNull(config, "config");
            this.whitelistCodeService = Objects.requireNonNull(
                whitelistCodeService,
                "whitelistCodeService"
            );
            this.announcementService = Objects.requireNonNull(
                announcementService,
                "announcementService"
            );
            this.logger = Objects.requireNonNull(logger, "logger");
        }

        @Override
        public void onReady(@NotNull ReadyEvent event) {
            CommandData whitelistCommand = Commands.slash(
                "whitelist",
                "Validate a whitelist code issued in-game"
            ).addOption(
                OptionType.STRING,
                "code",
                "The whitelist code you received in-game",
                true
            );

            CommandData announcementCommand = Commands.slash(
                "announcement",
                "Broadcast a message to connected players"
            ).addOption(
                OptionType.STRING,
                "message",
                "The announcement text to deliver",
                true
            );

            List<CommandData> commands = List.of(whitelistCommand, announcementCommand);

            if (config.hasGuildId()) {
                String guildId = config.guildId();
                net.dv8tion.jda.api.entities.Guild guild = event
                    .getJDA()
                    .getGuildById(guildId);
                if (guild == null) {
                    logger
                        .atWarning()
                        .log(
                            "Configured Discord guild %s not found, unable to register commands.",
                            guildId
                        );

                    return;
                }

                guild
                    .updateCommands()
                    .addCommands(commands.toArray(new CommandData[0]))
                    .queue(
                        success ->
                            logger
                                .atInfo()
                                .log(
                                    "Discord slash commands registered for guild %s.",
                                    guildId
                                ),
                        failure ->
                            logger
                                .atWarning()
                                .log(
                                    "Unable to register Discord slash commands for guild %s: %s",
                                    guildId,
                                    failure.getMessage()
                                )
                    );
            } else {
                registerCommandsGlobally(event, commands);
            }
        }

        @Override
        public void onSlashCommandInteraction(
            @NotNull SlashCommandInteractionEvent event
        ) {
            switch (event.getName()) {
                case "whitelist" -> {
                    String code = Objects.requireNonNull(
                        event.getOption("code"),
                        "code option"
                    ).getAsString();
                    handleValidate(event, code);
                }
                case "announcement" -> handleAnnouncement(event);
                default -> {
                    // Ignore other commands
                }
            }
        }

        private void handleAnnouncement(SlashCommandInteractionEvent event) {
            if (!config.enableAnnouncements()) {
                event
                    .reply("Announcements are disabled on this server.")
                    .setEphemeral(true)
                    .queue();
                return;
            }
            if (!isAuthorized(event.getMember())) {
                event
                    .reply(
                        "You are not permitted to send announcements."
                    )
                    .setEphemeral(true)
                    .queue();
                return;
            }

            String message = Objects.requireNonNull(
                event.getOption("message"),
                "message option"
            ).getAsString().trim();

            if (message.isEmpty()) {
                event
                    .reply("Announcement message must not be empty.")
                    .setEphemeral(true)
                    .queue();
                return;
            }

            try {
                announcementService.broadcast(message);
                event
                    .reply("Announcement sent to the Hytale server.")
                    .setEphemeral(true)
                    .queue();
            } catch (RuntimeException ex) {
                logger
                    .atWarning()
                    .log("Failed to deliver announcement: %s", ex.getMessage());
                event
                    .reply("Unable to send announcement, see server logs.")
                    .setEphemeral(true)
                    .queue();
            }
        }

        private boolean isAuthorized(Member member) {
            String requiredRole = config.announcementRoleId();
            if (requiredRole == null || requiredRole.isBlank()) {
                return true;
            }
            if (member == null) {
                return false;
            }
            return member
                .getRoles()
                .stream()
                .anyMatch(role -> requiredRole.equals(role.getId()));
        }

        private void handleValidate(
            SlashCommandInteractionEvent event,
            String code
        ) {
            ValidateResult result = attemptValidate(
                code,
                event.getUser().getId()
            );

            switch (result.getStatus()) {
                case SUCCESS -> {
                    StringBuilder builder = new StringBuilder("✅ Success! ");
                    builder
                        .append(event.getUser().getAsMention())
                        .append(", you are now whitelisted.");
                    event.reply(builder.toString()).setEphemeral(true).queue();
                }
                case NOT_FOUND -> event
                    .reply(
                        "That whitelist code could not be found or has expired."
                    )
                    .setEphemeral(true)
                    .queue();
                case ALREADY_VALIDATED -> event
                    .reply("That whitelist code has already been used.")
                    .setEphemeral(true)
                    .queue();
                case ERROR -> {
                    StringBuilder builder = new StringBuilder(
                        "⚠️ We weren't able to whitelist you, there was an error."
                    );
                    result
                        .getMessage()
                        .ifPresent(msg -> builder.append(NEW_LINE).append(msg));
                    event.reply(builder.toString()).setEphemeral(true).queue();
                }
            }
        }

        private void registerCommandsGlobally(
            ReadyEvent event,
            List<CommandData> commands
        ) {
            event
                .getJDA()
                .updateCommands()
                .addCommands(commands.toArray(new CommandData[0]))
                .queue(
                    success ->
                        logger
                            .atInfo()
                            .log("Discord slash commands registered globally."),
                    failure ->
                        logger
                            .atWarning()
                            .log(
                                "Unable to register global Discord slash commands: %s",
                                failure.getMessage()
                            )
                );
        }

        private ValidateResult attemptValidate(String code, String actorId) {
            if (code == null || code.isBlank()) {
                return ValidateResult.notFound();
            }

            try {
                return whitelistCodeService.validateCode(code.trim());
            } catch (RuntimeException exception) {
                logger
                    .atWarning()
                    .log(
                        "Error while validating code %s for Discord user %s: %s",
                        code,
                        actorId,
                        exception.toString()
                    );
                return ValidateResult.error(
                    null,
                    "An unexpected error occurred while validating the code."
                );
            }
        }
    }
}
