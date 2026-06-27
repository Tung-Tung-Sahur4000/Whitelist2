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
package eu.kennytv.maintenance.core.proxy.util;

import eu.kennytv.maintenance.core.config.Config;
import eu.kennytv.maintenance.core.proxy.MaintenanceProxyPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Caches the {@code name <-> uuid} of every player that connects to the proxy, persisted to
 * {@code usernamecache.yml}.
 *
 * <p>This is what makes whitelisting <b>cracked/offline</b> players (e.g. LimboAuth players with a {@code .}
 * prefix) and <b>Bedrock</b> players reliable: those accounts do not exist in the Mojang or Geyser databases,
 * so the only dependable way to resolve their UUID by name is to remember it from when they joined. The
 * resolver checks this cache before falling back to the Mojang/Geyser lookups.
 */
public final class PlayerNameCache {

    private final MaintenanceProxyPlugin plugin;
    private final File file;
    private final Map<String, UUID> uuidByName = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameByUuid = new ConcurrentHashMap<>();
    private Config config;

    public PlayerNameCache(final MaintenanceProxyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "usernamecache.yml");
        load();
    }

    private synchronized void load() {
        try {
            if (!file.exists()) {
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                Files.writeString(file.toPath(),
                        "# Cached name <-> uuid of players that have joined, used to whitelist cracked/Bedrock players.\n"
                                + "# Format: <uuid>: <name>\n", StandardCharsets.UTF_8);
            }
            config = new Config(file);
            config.load();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load usernamecache.yml", e);
            return;
        }

        for (final Map.Entry<String, Object> entry : config.getValues().entrySet()) {
            try {
                final UUID uuid = UUID.fromString(entry.getKey());
                final String name = String.valueOf(entry.getValue());
                nameByUuid.put(uuid, name);
                uuidByName.put(name.toLowerCase(Locale.ROOT), uuid);
            } catch (final IllegalArgumentException ignored) {
                // Skip malformed entries
            }
        }
    }

    /**
     * Records a player's current name. Saves asynchronously only when something actually changed.
     */
    public void cache(final UUID uuid, final String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        final String previous = nameByUuid.get(uuid);
        if (name.equals(previous)) {
            return;
        }

        if (previous != null) {
            uuidByName.remove(previous.toLowerCase(Locale.ROOT));
        }
        nameByUuid.put(uuid, name);
        uuidByName.put(name.toLowerCase(Locale.ROOT), uuid);

        if (config != null) {
            config.set(uuid.toString(), name);
            plugin.async(this::save);
        }
    }

    @Nullable
    public ProfileLookup getProfile(final String name) {
        final UUID uuid = uuidByName.get(name.toLowerCase(Locale.ROOT));
        if (uuid == null) {
            return null;
        }
        return new ProfileLookup(uuid, nameByUuid.getOrDefault(uuid, name));
    }

    @Nullable
    public String getName(final UUID uuid) {
        return nameByUuid.get(uuid);
    }

    private synchronized void save() {
        if (config == null) {
            return;
        }
        try {
            config.save();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save usernamecache.yml", e);
        }
    }
}
