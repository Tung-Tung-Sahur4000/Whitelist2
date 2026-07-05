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
package eu.kennytv.maintenance.core.discord;

import eu.kennytv.maintenance.core.Settings;
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
 *
 * <p>Bot-flood / DoS hardening:
 * <ul>
 *     <li>{@link #purgeExpired()} is throttled to run at most once every {@value #PURGE_INTERVAL_MILLIS} ms.
 *         Without throttling, every connection event triggers a full map scan, creating O(N²) CPU work during
 *         a connection flood (N connections each scanning N active codes = N² iterations per minute).</li>
 *     <li>The active-code pool is hard-capped at {@value #MAX_ACTIVE_CODES} entries. A bot flood that exhausts
 *         this cap causes {@link #generateCode} to return {@code null} for new players; callers should display
 *         a "server busy" message. Players who already have an active code are never affected by the cap.</li>
 * </ul>
 */
public final class LinkCodeManager {

    private static final long ATTEMPT_WINDOW_MILLIS = 60_000L;
    /**
     * Minimum interval between full map scans in {@link #purgeExpired()}.
     * During a bot flood, connections arrive far faster than codes expire, so scanning on every
     * connection wastes CPU proportionally to pool size. Throttling to once per 5 s caps the
     * total scan work to O(N / 5) regardless of connection rate.
     */
    private static final long PURGE_INTERVAL_MILLIS = 5_000L;
    /**
     * Hard upper bound on the number of simultaneously active linking codes.
     * With a 10-minute expiry and this cap: the plugin tolerates a sustained bot-join rate of up to
     * MAX_ACTIVE_CODES / (expiry_seconds) connections per second before rejecting new code requests.
     * At default settings (600 s expiry, 2000 cap): ~3.3 new unique bots/second before cap is hit.
     * Players who already have an active code are always served from the existing entry and are
     * unaffected by the cap.
     */
    private static final int MAX_ACTIVE_CODES = 2_000;

    private final Settings settings;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingLink> codes = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeCodeByUuid = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attemptsByUser = new ConcurrentHashMap<>();
    /**
     * Timestamp of the last {@link #purgeExpired()} scan. Volatile so concurrent calls from
     * different threads all see the latest value without locking.
     */
    private volatile long lastPurgeMs = 0L;

    public LinkCodeManager(final Settings settings) {
        this.settings = settings;
    }

    /**
     * Returns the active code for the player, generating a new one if none is active.
     * The code is bound to the given uuid and name.
     *
     * @return the code string, or {@code null} if the active-code pool is at capacity (bot flood);
     *         callers should display a "server is busy" message in that case
     */
    @Nullable
    public String generateCode(final UUID uuid, final String name) {
        maybePurgeExpired();

        // Idempotent: return the same code if the player already has a valid one.
        // This covers the "rejoin before expiry" case and is unaffected by the pool cap.
        final String existing = activeCodeByUuid.get(uuid);
        if (existing != null) {
            final PendingLink pending = codes.get(existing);
            if (pending != null && !pending.isExpired()) {
                return existing;
            }
        }

        // Enforce the pool cap for new entries only. Existing players (handled above) are exempt.
        // This bounds memory usage and prevents the code pool from growing without limit during a
        // bot flood. The cap is checked after maybePurgeExpired() so freshly-expired entries are
        // already gone and the count is as accurate as possible.
        if (codes.size() >= MAX_ACTIVE_CODES) {
            return null;
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
            // Should be unreachable in practice (would require 10 000 consecutive collisions). Insert
            // directly with a code of the configured length so it still matches the DM handler's
            // exactly-'length'-digits check — an oversized emergency code could never be submitted.
            code = randomDigits(length);
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
        maybePurgeExpired();

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

    /**
     * Runs {@link #purgeExpired()} only if at least {@value #PURGE_INTERVAL_MILLIS} ms have elapsed
     * since the last scan. This prevents O(N²) CPU usage during connection floods: without throttling,
     * every new connection would scan the entire codes map, so N simultaneous bot connections would
     * trigger N × N = N² map iterations. With throttling, the scan runs at most once per interval
     * regardless of how many connections arrive.
     */
    private void maybePurgeExpired() {
        final long now = System.currentTimeMillis();
        if (now - lastPurgeMs < PURGE_INTERVAL_MILLIS) {
            return;
        }
        lastPurgeMs = now;
        purgeExpired(now);
    }

    private void purgeExpired(final long now) {
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
