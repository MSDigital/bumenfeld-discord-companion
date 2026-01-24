package com.bumenfeld.database;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.accesscontrol.provider.HytaleWhitelistProvider;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.bumenfeld.database.DatabaseManager.DatabaseException;
import com.bumenfeld.database.DatabaseManager.WhitelistCode;
import com.bumenfeld.util.ReflectionUtil;

public final class WhitelistCodeService implements AutoCloseable {

    private static final char[] CODE_POSSIBLE_CHARS =
        "0123456789".toCharArray();
    private static final int DEFAULT_CODE_LENGTH = 6;
    private static final int MAX_GENERATION_ATTEMPTS = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseManager databaseManager;
    private final HytaleWhitelistProvider whitelistProvider;
    private final HytaleLogger logger;

    public WhitelistCodeService(
        DatabaseManager databaseManager,
        HytaleWhitelistProvider whitelistProvider,
        HytaleLogger logger
    ) {
        this.databaseManager = Objects.requireNonNull(
            databaseManager,
            "databaseManager"
        );
        this.whitelistProvider = Objects.requireNonNull(
            whitelistProvider,
            "whitelistProvider"
        );
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void initialize() {
        databaseManager.initialize();
    }

    public String ensureCode(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Optional<WhitelistCode> existing = databaseManager.findByPlayer(
            playerUuid
        );
        if (existing.isPresent() && !existing.get().isValidated()) {
            return existing.get().whitelistCode();
        }

        String code = generateUniqueCode();
        databaseManager.upsertCode(playerUuid, code);
        logger
            .atInfo()
            .log("Issued whitelist code %s for player %s", code, playerUuid);
        return code;
    }

    public Optional<WhitelistCode> findByPlayer(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return databaseManager.findByPlayer(playerUuid);
    }

    public Optional<WhitelistCode> findByCode(String whitelistCode) {
        Objects.requireNonNull(whitelistCode, "whitelistCode");
        return databaseManager.findByCode(normalizeCode(whitelistCode));
    }

    public List<WhitelistCode> listActiveCodes() {
        return databaseManager.listActiveCodes();
    }

    public ValidateResult validateCode(String whitelistCode) {
        Objects.requireNonNull(whitelistCode, "whitelistCode");
        String normalized = normalizeCode(whitelistCode);

        Optional<WhitelistCode> lookup = databaseManager.findByCode(normalized);
        if (lookup.isEmpty()) {
            return ValidateResult.notFound();
        }

        WhitelistCode record = lookup.get();
        UUID playerUuid = record.playerUuid();

        if (record.isValidated()) {
            return ValidateResult.alreadyValidated(playerUuid);
        }

        if (!databaseManager.markValidated(playerUuid, Instant.now())) {
            return ValidateResult.error(
                playerUuid,
                "Unable to mark whitelist code as validated"
            );
        }

        boolean added;
        try {
            Set<UUID> whitelist = whitelistProvider.getList();
            added = whitelist.add(playerUuid);
        } catch (UnsupportedOperationException exception) {
            added = addToWhitelistBackingSet(playerUuid, exception);
        }

        logger
            .atInfo()
            .log(
                "Whitelist code %s validated for %s (already whitelisted=%s)",
                normalized,
                playerUuid,
                !added
            );

        return ValidateResult.success(playerUuid, added);
    }

    public boolean revoke(UUID playerUuid, boolean removeFromWhitelist) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        boolean deleted = databaseManager.delete(playerUuid);
        if (deleted && removeFromWhitelist) {
            removeFromWhitelist(playerUuid);
            logger
                .atInfo()
                .log("Removed %s from whitelist and cleared code", playerUuid);
        } else if (deleted) {
            logger
                .atInfo()
                .log(
                    "Cleared whitelist code for %s (whitelist entry retained)",
                    playerUuid
                );
        }
        return deleted;
    }

    public boolean isWhitelisted(UUID playerUuid) {
        return whitelistProvider.getList().contains(playerUuid);
    }

    private boolean addToWhitelistBackingSet(
        UUID playerUuid,
        UnsupportedOperationException cause
    ) {
        Set<UUID> mutableSet = resolveMutableWhitelistSet();
        if (mutableSet == null) {
            throw new IllegalStateException(
                "Whitelist provider returned an unmodifiable set and no mutable backing field could be resolved",
                cause
            );
        }

        logger
            .atWarning()
            .log(
                "Whitelist provider returned an unmodifiable whitelist set; mutating backing set directly."
            );
        return mutableSet.add(playerUuid);
    }

    private void removeFromWhitelist(UUID playerUuid) {
        try {
            whitelistProvider.getList().remove(playerUuid);
        } catch (UnsupportedOperationException exception) {
            Set<UUID> mutableSet = resolveMutableWhitelistSet();
            if (mutableSet == null) {
                throw new IllegalStateException(
                    "Whitelist provider returned an unmodifiable set and no mutable backing field could be resolved",
                    exception
                );
            }

            logger
                .atWarning()
                .log(
                    "Whitelist provider returned an unmodifiable whitelist set; mutating backing set directly."
                );
            mutableSet.remove(playerUuid);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> resolveMutableWhitelistSet() {
        String[] candidates = {
            "whitelist",
            "mutableWhitelist",
            "mutableList",
            "list",
            "backingWhitelist",
        };

        for (String field : candidates) {
            Set<?> candidate = ReflectionUtil.getPublic(
                Set.class,
                whitelistProvider,
                field
            );
            if (candidate != null) {
                try {
                    return (Set<UUID>) candidate;
                } catch (ClassCastException ignored) {
                    // continue searching
                }
            }
        }
        return null;
    }

    @Override
    public void close() throws DatabaseException {
        databaseManager.close();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = generateRandomCode(DEFAULT_CODE_LENGTH);
            if (databaseManager.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new DatabaseException(
            "Unable to generate unique whitelist code",
            null
        );
    }

    private static String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String generateRandomCode(int length) {
        char[] buffer = new char[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = CODE_POSSIBLE_CHARS[RANDOM.nextInt(
                CODE_POSSIBLE_CHARS.length
            )];
        }
        return new String(buffer);
    }

    public static final class ValidateResult {

        public enum Status {
            SUCCESS,
            NOT_FOUND,
            ALREADY_VALIDATED,
            ERROR,
        }

        private final Status status;
        private final UUID playerUuid;
        private final String message;
        private final boolean newlyWhitelisted;

        private ValidateResult(
            Status status,
            UUID playerUuid,
            String message,
            boolean newlyWhitelisted
        ) {
            this.status = status;
            this.playerUuid = playerUuid;
            this.message = message;
            this.newlyWhitelisted = newlyWhitelisted;
        }

        public static ValidateResult success(
            UUID playerUuid,
            boolean newlyWhitelisted
        ) {
            return new ValidateResult(
                Status.SUCCESS,
                playerUuid,
                null,
                newlyWhitelisted
            );
        }

        public static ValidateResult notFound() {
            return new ValidateResult(
                Status.NOT_FOUND,
                null,
                "Whitelist code not found",
                false
            );
        }

        public static ValidateResult alreadyValidated(UUID playerUuid) {
            return new ValidateResult(
                Status.ALREADY_VALIDATED,
                playerUuid,
                "Whitelist code already validated",
                false
            );
        }

        public static ValidateResult error(UUID playerUuid, String message) {
            return new ValidateResult(Status.ERROR, playerUuid, message, false);
        }

        public Status getStatus() {
            return status;
        }

        public Optional<UUID> getPlayerUuid() {
            return Optional.ofNullable(playerUuid);
        }

        public Optional<String> getMessage() {
            return Optional.ofNullable(message);
        }

        public boolean wasNewlyWhitelisted() {
            return newlyWhitelisted;
        }
    }
}
