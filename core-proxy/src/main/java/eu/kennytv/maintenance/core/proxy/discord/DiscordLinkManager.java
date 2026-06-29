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
 * All methods are synchronized as they are accessed from JDA's event threads.
 */
public final class DiscordLinkManager {

    private static final String KEY_PREFIX = "id_";
    private final MaintenanceProxyPlugin plugin;
    private final File file;
    private final Map<String, ProfileLookup> linksByDiscordId = new HashMap<>();
    private final Map<UUID, String> discordIdByUuid = new HashMap<>();
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
     * @return true if the given Minecraft account is already linked to a different Discord user
     */
    public synchronized boolean isLinkedToOther(final UUID uuid, final String discordId) {
        final String existing = discordIdByUuid.get(uuid);
        return existing != null && !existing.equals(discordId);
    }

    /**
     * @return true if the given Minecraft account is already linked to any Discord user
     */
    public synchronized boolean isLinked(final UUID uuid) {
        return discordIdByUuid.containsKey(uuid);
    }

    public synchronized void link(final String discordId, final UUID uuid, final String name) {
        final ProfileLookup old = linksByDiscordId.remove(discordId);
        if (old != null) {
            discordIdByUuid.remove(old.uuid());
        }

        linksByDiscordId.put(discordId, new ProfileLookup(uuid, name));
        discordIdByUuid.put(uuid, discordId);
        config.set(KEY_PREFIX + discordId, uuid + ";" + name);
        save();
    }

    @Nullable
    public synchronized ProfileLookup unlink(final String discordId) {
        final ProfileLookup removed = linksByDiscordId.remove(discordId);
        if (removed != null) {
            discordIdByUuid.remove(removed.uuid());
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
