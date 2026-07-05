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
package eu.kennytv.maintenance.core.util;

import eu.kennytv.maintenance.core.config.Config;
import eu.kennytv.maintenance.core.MaintenancePlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Caches the {@code name <-> uuid} of every player that connects to the proxy, persisted to
 * {@code usernamecache.yml}.
 *
 * <p>This is what makes whitelisting <b>cracked/offline</b> players (e.g. LimboAuth players with a {@code .}
 * prefix) and <b>Bedrock</b> players reliable: those accounts do not exist in the Mojang or Geyser databases.
 *
 * <p>Hardened against join floods / bot attacks:
 * <ul>
 *     <li><b>Bounded (LRU):</b> at most {@code maxEntries} are kept; the oldest are evicted, so a flood of
 *     random names cannot grow the cache unboundedly.</li>
 *     <li><b>Throttled writes:</b> the file is saved at most once every {@link #SAVE_THROTTLE_MILLIS}ms (plus a
 *     final flush on shutdown), so a burst of new names cannot trigger an I/O storm.</li>
 * </ul>
 * Detecting "random" names heuristically is unreliable (a real cracked name looks random too), so bounding and
 * throttling is used instead of trying to guess which names to skip.
 */
public final class PlayerNameCache {

    private static final long SAVE_THROTTLE_MILLIS = 30_000L;
    private final MaintenancePlugin plugin;
    private final File file;
    private final int maxEntries;
    // Insertion-ordered, so the first entry is the eldest (used for eviction).
    private final LinkedHashMap<UUID, String> nameByUuid = new LinkedHashMap<>();
    private final Map<String, UUID> uuidByName = new HashMap<>();
    private Config config;
    private boolean dirty;
    private long lastSave;

    public PlayerNameCache(final MaintenancePlugin plugin, final int maxEntries) {
        this.plugin = plugin;
        this.maxEntries = maxEntries > 0 ? maxEntries : Integer.MAX_VALUE;
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
                                + "# Bounded and auto-managed; format: <uuid>: <name>\n", StandardCharsets.UTF_8);
            }
            config = new Config(file);
            config.load();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load usernamecache.yml", e);
            return;
        }

        // Iterate a copy so eviction (which mutates config) can't cause a ConcurrentModificationException.
        for (final Map.Entry<String, Object> entry : new ArrayList<>(config.getValues().entrySet())) {
            try {
                final UUID uuid = UUID.fromString(entry.getKey());
                put(uuid, String.valueOf(entry.getValue()));
            } catch (final IllegalArgumentException ignored) {
                // Skip malformed entries
            }
        }
        if (config.getValues().size() != nameByUuid.size()) {
            // The file had more than maxEntries; persist the trimmed view.
            dirty = true;
        }
    }

    /**
     * Records a player's current name. Saves are throttled, so a burst of joins won't hammer the disk.
     */
    public synchronized void cache(final UUID uuid, final String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (name.equals(nameByUuid.get(uuid))) {
            return;
        }

        put(uuid, name);
        if (config != null) {
            config.set(uuid.toString(), name);
            dirty = true;

            final long now = System.currentTimeMillis();
            if (now - lastSave >= SAVE_THROTTLE_MILLIS) {
                lastSave = now;
                plugin.async(this::save);
            }
        }
    }

    private void put(final UUID uuid, final String name) {
        final String previous = nameByUuid.remove(uuid); // remove first so re-insert updates ordering
        if (previous != null) {
            uuidByName.remove(previous.toLowerCase(Locale.ROOT));
        }
        nameByUuid.put(uuid, name);
        uuidByName.put(name.toLowerCase(Locale.ROOT), uuid);
        evictExcess();
    }

    private void evictExcess() {
        while (nameByUuid.size() > maxEntries) {
            final Iterator<Map.Entry<UUID, String>> it = nameByUuid.entrySet().iterator();
            final Map.Entry<UUID, String> eldest = it.next();
            it.remove();
            uuidByName.remove(eldest.getValue().toLowerCase(Locale.ROOT));
            if (config != null) {
                config.remove(eldest.getKey().toString());
            }
        }
    }

    @Nullable
    public synchronized ProfileLookup getProfile(final String name) {
        final UUID uuid = uuidByName.get(name.toLowerCase(Locale.ROOT));
        if (uuid == null) {
            return null;
        }
        return new ProfileLookup(uuid, nameByUuid.getOrDefault(uuid, name));
    }

    @Nullable
    public synchronized String getName(final UUID uuid) {
        return nameByUuid.get(uuid);
    }

    /**
     * Forces a save if there are unsaved changes. Call on shutdown.
     */
    public synchronized void flush() {
        if (dirty) {
            save();
        }
    }

    private synchronized void save() {
        if (config == null || !dirty) {
            return;
        }
        dirty = false;
        try {
            config.save();
        } catch (final IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save usernamecache.yml", e);
        }
    }
}
