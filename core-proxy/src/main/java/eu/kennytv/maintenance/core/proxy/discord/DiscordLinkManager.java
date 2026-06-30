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

import eu.kennytv.maintenance.core.config.Config;
import eu.kennytv.maintenance.core.proxy.MaintenanceProxyPlugin;
import eu.kennytv.maintenance.core.proxy.util.ProfileLookup;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Persists Discord &lt;-&gt; Minecraft account links in {@code DiscordLinks.yml}.
 * <p>
 * Keys are stored with an {@code id_} prefix so the (numeric) Discord snowflake ids are not parsed as
 * numbers by the YAML loader. Values are stored as {@code <uuid>;<name>}.
 * <p>
 * Three indices are maintained for O(1) lookup in all directions:
 * <ul>
 *   <li>{@code linksByDiscordId} — Discord ID → profile (primary store)</li>
 *   <li>{@code discordIdByUuid}  — Minecraft UUID → Discord ID</li>
 *   <li>{@code discordIdByLowercaseName} — lowercase Minecraft name → Discord ID (cracked-server fallback)</li>
 * </ul>
 * The name index is a secondary safeguard for offline/cracked servers where the same player can appear
 * with a different UUID across sessions (e.g. different proxy software, case change in username).
 * <p>
 * All methods are synchronized as they are accessed from JDA's event threads.
 */
public final class DiscordLinkManager {

    private static final String KEY_PREFIX = "id_";
    private final MaintenanceProxyPlugin plugin;
    private final File file;
    private final Map<String, ProfileLookup> linksByDiscordId = new HashMap<>();
    private final Map<UUID, String> discordIdByUuid = new HashMap<>();
    /**
     * Secondary index: lowercase Minecraft name → Discord ID.
     * Used on cracked/offline servers where the same player can join with a different UUID each
     * session (e.g. UUID drift, different proxy offline UUID algorithm, case mismatch).
     * Checking by name in addition to UUID prevents the "new code on every rejoin" symptom when
     * the link IS present but the UUID stored at link-time no longer matches the current session UUID.
     */
    private final Map<String, String> discordIdByLowercaseName = new HashMap<>();
    private Config config;

    public DiscordLinkManager(final MaintenanceProxyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "DiscordLinks.yml");
        reload();
    }

    public synchronized void reload() {
        try {
            if (!file.exists()) {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                Files.writeString(file.toPath(),
                        "# Discord <-> Minecraft account links managed by ProxyWhitelist.\n"
                                + "# Format: id_<discordUserId>: <uuid>;<name>\n", StandardCharsets.UTF_8);
            }
            config = new Config(file);
            config.load();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load DiscordLinks.yml", e);
            return;
        }
        load();
    }

    private void load() {
        linksByDiscordId.clear();
        discordIdByUuid.clear();
        discordIdByLowercaseName.clear();
        for (final Map.Entry<String, Object> entry : config.getValues().entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }

            final String discordId = key.substring(KEY_PREFIX.length());
            final String value = String.valueOf(entry.getValue());
            final int idx = value.indexOf(';');
            if (idx <= 0) {
                continue;
            }

            try {
                final UUID uuid = UUID.fromString(value.substring(0, idx));
                final String name = value.substring(idx + 1);
                linksByDiscordId.put(discordId, new ProfileLookup(uuid, name));
                discordIdByUuid.put(uuid, discordId);
                discordIdByLowercaseName.put(name.toLowerCase(Locale.ROOT), discordId);
            } catch (final IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid DiscordLinks entry: " + key);
            }
        }
    }

    @Nullable
    public synchronized ProfileLookup getLink(final String discordId) {
        return linksByDiscordId.get(discordId);
    }

    /**
     * Looks up the Discord user ID linked to a Minecraft UUID.
     * Used by the {@code /lookup minecraft} admin command.
     *
     * @return the Discord user ID, or {@code null} if that UUID is not linked
     */
    @Nullable
    public synchronized String getDiscordId(final UUID uuid) {
        return discordIdByUuid.get(uuid);
    }

    /**
     * @return true if the given Minecraft UUID is already linked to any Discord user
     */
    public synchronized boolean isLinked(final UUID uuid) {
        return discordIdByUuid.containsKey(uuid);
    }

    /**
     * Secondary link check by player name (case-insensitive).
     *
     * <p>This is a fallback for offline/cracked servers where the same player can have a different
     * UUID each session. If {@link #isLinked(UUID)} returns false but this returns true, the player's
     * account IS linked — their UUID just drifted between sessions. In that case the join-deny path
     * should show the "pending approval" message rather than issuing a fresh code.
     *
     * <p><b>Security note:</b> on a cracked server, usernames are not authenticated, so this check
     * alone is not a reliable identity proof. It is intentionally used only to avoid re-issuing codes
     * to an already-linked name, not to grant additional access.
     *
     * @return true if a link exists for that player name
     */
    public synchronized boolean isLinkedByName(final String name) {
        return discordIdByLowercaseName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Looks up the Discord user ID linked to a Minecraft player name (case-insensitive).
     * Used by the {@code /lookup minecraft} admin command when the input is a name rather than a UUID.
     *
     * @return the Discord user ID, or {@code null} if that name is not linked
     */
    @Nullable
    public synchronized String getDiscordIdByName(final String name) {
        return discordIdByLowercaseName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * @return true if the given Minecraft UUID is already linked to a different Discord user
     */
    public synchronized boolean isLinkedToOther(final UUID uuid, final String discordId) {
        final String existing = discordIdByUuid.get(uuid);
        return existing != null && !existing.equals(discordId);
    }

    /**
     * Outcome of an exclusive link attempt via {@link #linkExclusive(String, UUID, String)}.
     */
    public enum LinkResult {
        /** The link was created (or re-affirmed for the same account). */
        LINKED,
        /** This Discord user is already linked to a <i>different</i> Minecraft account. */
        ALREADY_LINKED_TO_OTHER_ACCOUNT,
        /** This Minecraft account is already linked to a <i>different</i> Discord user. */
        ACCOUNT_TAKEN
    }

    /**
     * Atomically enforces the <b>one Discord user ⇄ one Minecraft account</b> invariant and, if it holds,
     * creates the link.
     *
     * <p>Unlike {@link #link(String, UUID, String)} (which silently overwrites whatever the Discord user
     * was previously linked to), this method <b>refuses</b> to re-link a Discord user that is already bound
     * to a different Minecraft account. That overwrite was the root cause of a single Discord user being
     * able to link — and, through role sync, whitelist — multiple Minecraft accounts: the link record was
     * replaced while the previously linked account stayed on the whitelist.
     *
     * <p>Both directions are checked and the write performed under a single lock, so there is no TOCTOU
     * window between the checks and the link. Re-sending a code for the <i>same</i> account already linked
     * to this user is treated as a harmless no-op and returns {@link LinkResult#LINKED}.
     *
     * @return the {@link LinkResult}; callers must only proceed to whitelist on {@link LinkResult#LINKED}
     */
    public synchronized LinkResult linkExclusive(final String discordId, final UUID uuid, final String name) {
        final ProfileLookup existing = linksByDiscordId.get(discordId);
        if (existing != null && !existing.uuid().equals(uuid)) {
            return LinkResult.ALREADY_LINKED_TO_OTHER_ACCOUNT;
        }
        if (isLinkedToOther(uuid, discordId)) {
            return LinkResult.ACCOUNT_TAKEN;
        }
        link(discordId, uuid, name);
        return LinkResult.LINKED;
    }

    public synchronized void link(final String discordId, final UUID uuid, final String name) {
        // Remove stale indices for the old link this Discord ID might have had.
        final ProfileLookup old = linksByDiscordId.remove(discordId);
        if (old != null) {
            discordIdByUuid.remove(old.uuid());
            discordIdByLowercaseName.remove(old.name().toLowerCase(Locale.ROOT));
        }

        linksByDiscordId.put(discordId, new ProfileLookup(uuid, name));
        discordIdByUuid.put(uuid, discordId);
        discordIdByLowercaseName.put(name.toLowerCase(Locale.ROOT), discordId);
        config.set(KEY_PREFIX + discordId, uuid + ";" + name);
        save();
    }

    @Nullable
    public synchronized ProfileLookup unlink(final String discordId) {
        final ProfileLookup removed = linksByDiscordId.remove(discordId);
        if (removed != null) {
            discordIdByUuid.remove(removed.uuid());
            discordIdByLowercaseName.remove(removed.name().toLowerCase(Locale.ROOT));
            config.remove(KEY_PREFIX + discordId);
            save();
        }
        return removed;
    }

    private void save() {
        try {
            config.save();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save DiscordLinks.yml", e);
        }
    }
}
