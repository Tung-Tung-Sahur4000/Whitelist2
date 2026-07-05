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
package eu.kennytv.maintenance.paper;

import eu.kennytv.maintenance.core.Settings;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;

/**
 * Paper stores whitelisted players in the server's native whitelist ({@code whitelist.json}) instead of the
 * plugin's own {@code WhitelistedPlayers.yml}. This way the plugin only adds the value a proxy can't get from
 * the server (the Discord bot, Bedrock/Geyser resolution and the username cache), while the actual whitelist
 * stays the one Minecraft already manages - fully interoperable with the vanilla {@code /whitelist} command.
 *
 * <p>The plugin still keeps its own linking info ({@code DiscordLinks.yml}) and the username cache
 * ({@code usernamecache.yml}); the cache is what lets a name be resolved to the right premium / cracked /
 * Floodgate UUID before it is written to {@code whitelist.json}.
 *
 * <p>Whitelist writes are marshalled onto the main thread, since they are triggered from async contexts
 * (command lookups, JDA event threads) and Bukkit's whitelist is not safe to mutate off-thread.
 */
public final class SettingsPaper extends Settings {
    private final MaintenancePaperPlugin paper;

    public SettingsPaper(final MaintenancePaperPlugin plugin, final String... unsupportedFields) {
        super(plugin, unsupportedFields);
        this.paper = plugin;
    }

    @Override
    protected boolean useInternalWhitelist() {
        return false;
    }

    @Override
    protected void loadWhitelistedPlayers() {
        // The server owns whitelist.json - nothing to load into the plugin.
    }

    @Override
    public boolean isWhitelisted(final UUID uuid) {
        return paper.getServer().getOfflinePlayer(uuid).isWhitelisted();
    }

    @Override
    public boolean addWhitelistedPlayer(final UUID uuid, final String name) {
        final OfflinePlayer player = paper.getServer().getOfflinePlayer(uuid);
        if (player.isWhitelisted()) {
            return false;
        }
        paper.sync(() -> player.setWhitelisted(true));
        return true;
    }

    @Override
    public boolean removeWhitelistedPlayer(final UUID uuid) {
        final OfflinePlayer player = paper.getServer().getOfflinePlayer(uuid);
        if (!player.isWhitelisted()) {
            return false;
        }
        paper.sync(() -> player.setWhitelisted(false));
        return true;
    }

    @Override
    public boolean removeWhitelistedPlayer(final String name) {
        boolean removed = false;
        for (final OfflinePlayer player : paper.getServer().getWhitelistedPlayers()) {
            if (name.equalsIgnoreCase(player.getName())) {
                paper.sync(() -> player.setWhitelisted(false));
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public Map<UUID, String> getWhitelistedPlayers() {
        final Map<UUID, String> players = new HashMap<>();
        for (final OfflinePlayer player : paper.getServer().getWhitelistedPlayers()) {
            final String name = player.getName();
            players.put(player.getUniqueId(), name != null ? name : player.getUniqueId().toString());
        }
        return players;
    }
}
