/*
 * This file is part of Maintenance - https://github.com/kennytv/Maintenance
 * Copyright (C) 2018-2024 kennytv (https://github.com/kennytv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package eu.kennytv.maintenance.core.proxy.discord;

import eu.kennytv.maintenance.core.proxy.SettingsProxy;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Generates and verifies one-time linking codes used by the Discord bot.
 *
 * <p>Security properties:
 * <ul>
 *     <li>Codes are generated with {@link SecureRandom} and are unique while active.</li>
 *     <li>Each code is bound to the exact {@link UUID} (and name) of the connecting player, so a code can only
 *         ever link <i>that</i> account — a cracked/offline player cannot use it to claim someone else's account.</li>
 *     <li>Codes are single-use (removed on successful verification) and expire after a configurable TTL.</li>
 *     <li>A player only ever has one active code at a time (re-requests return the same code), keeping the pool
 *         of valid codes tiny.</li>
 *     <li>Verification attempts are rate-limited per Discord user to make brute-forcing infeasible.</li>
 * </ul>
 */
public final class LinkCodeManager {

    private static final long ATTEMPT_WINDOW_MILLIS = 60_000L;
    private final SettingsProxy settings;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingLink> codes = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeCodeByUuid = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attemptsByUser = new ConcurrentHashMap<>();

    public LinkCodeManager(final SettingsProxy settings) {
        this.settings = settings;
    }

    /**
     * Returns the active code for the player, generating a new one if none is active.
     * The code is bound to the given uuid and name.
     */
    public String generateCode(final UUID uuid, final String name) {
        purgeExpired();

        final String existing = activeCodeByUuid.get(uuid);
        if (existing != null) {
            final PendingLink pending = codes.get(existing);
            if (pending != null && !pending.isExpired()) {
                return existing;
            }
        }

        final int length = Math.max(4, settings.getLinkCodeLength());
        final long expiresAt = System.currentTimeMillis() + settings.getLinkCodeExpirySeconds() * 1000L;
        // Find a code value not already in use. putIfAbsent returns null on success (slot was empty).
        // The collision guard is a hard upper bound — with 6+ digits and a tiny active-code pool this
        // will never realistically be reached, but we must not store an un-inserted code if it somehow
        // is (that would give the player a code the bot cannot verify).
        String code = null;
        for (int guard = 0; guard < 10_000; guard++) {
            final String candidate = randomDigits(length);
            if (codes.putIfAbsent(candidate, new PendingLink(uuid, name, expiresAt)) == null) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            // Should be unreachable in practice; generate an oversized emergency code and insert directly.
            code = randomDigits(length + 4);
            codes.put(code, new PendingLink(uuid, name, expiresAt));
        }

        activeCodeByUuid.put(uuid, code);
        return code;
    }

    /**
     * Returns the pending link bound to {@code code} without consuming it, or {@code null} if the code
     * is unknown or expired. Use this to perform pre-checks (e.g. guild membership) before committing
     * to a link via {@link #verifyAndConsume}; this avoids burning the code when the caller decides to
     * reject the request for reasons unrelated to the code itself.
     */
    @Nullable
    public PendingLink lookupCode(final String code) {
        final PendingLink pending = codes.get(code);
        return (pending == null || pending.isExpired()) ? null : pending;
    }

    /**
     * Verifies and consumes a code. Returns the bound player, or null if the code is unknown/expired.
     */
    @Nullable
    public PendingLink verifyAndConsume(final String code) {
        purgeExpired();

        final PendingLink pending = codes.remove(code);
        if (pending == null || pending.isExpired()) {
            return null;
        }
        activeCodeByUuid.remove(pending.uuid(), code);
        return pending;
    }

    public boolean isRateLimited(final String discordUserId) {
        final Attempts attempts = attemptsByUser.get(discordUserId);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            if (System.currentTimeMillis() - attempts.windowStart > ATTEMPT_WINDOW_MILLIS) {
                return false;
            }
            return attempts.count >= Math.max(1, settings.getLinkMaxAttemptsPerMinute());
        }
    }

    public void recordFailedAttempt(final String discordUserId) {
        final Attempts attempts = attemptsByUser.computeIfAbsent(discordUserId, k -> new Attempts());
        synchronized (attempts) {
            final long now = System.currentTimeMillis();
            if (now - attempts.windowStart > ATTEMPT_WINDOW_MILLIS) {
                attempts.windowStart = now;
                attempts.count = 0;
            }
            attempts.count++;
        }
    }

    public void clearAttempts(final String discordUserId) {
        attemptsByUser.remove(discordUserId);
    }

    private void purgeExpired() {
        final long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() < now) {
                activeCodeByUuid.remove(entry.getValue().uuid(), entry.getKey());
                return true;
            }
            return false;
        });
        // Remove attempt-window entries whose window has fully elapsed so the map cannot
        // grow without bound. An attacker with many throwaway Discord accounts could
        // otherwise fill memory indefinitely by sending one wrong code per account.
        attemptsByUser.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return now - entry.getValue().windowStart > ATTEMPT_WINDOW_MILLIS;
            }
        });
    }

    private String randomDigits(final int length) {
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }

    public record PendingLink(UUID uuid, String name, long expiresAt) {
        public boolean isExpired() {
            return expiresAt < System.currentTimeMillis();
        }
    }

    private static final class Attempts {
        private int count;
        private long windowStart = System.currentTimeMillis();
    }
}
