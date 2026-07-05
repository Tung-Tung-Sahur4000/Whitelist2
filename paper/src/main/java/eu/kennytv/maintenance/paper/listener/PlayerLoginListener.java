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
package eu.kennytv.maintenance.paper.listener;

import eu.kennytv.maintenance.core.Settings;
import eu.kennytv.maintenance.core.listener.JoinListenerBase;
import eu.kennytv.maintenance.core.util.SenderInfo;
import eu.kennytv.maintenance.paper.MaintenancePaperPlugin;
import eu.kennytv.maintenance.paper.util.PaperSenderInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public final class PlayerLoginListener extends JoinListenerBase implements Listener {
    private final MaintenancePaperPlugin plugin;

    public PlayerLoginListener(final MaintenancePaperPlugin plugin, final Settings settings) {
        super(plugin, settings);
        this.plugin = plugin;
    }

    @EventHandler
    public void postLogin(final PlayerLoginEvent event) { // the validation event does not allow permission checking
        final Player player = event.getPlayer();
        // Record name -> uuid so cracked/offline and Bedrock players (who Mojang/Geyser cannot resolve) can be
        // whitelisted by name later - even players who are about to be kicked are cached.
        plugin.cachePlayer(player.getUniqueId(), player.getName());

        final SenderInfo sender = new PaperSenderInfo(player);
        if (shouldKick(sender)) {
            event.setResult(PlayerLoginEvent.Result.KICK_OTHER);
            // With code-based linking enabled this shows the player their one-time link code; otherwise the
            // normal kick message.
            event.kickMessage(plugin.getJoinDenyMessage(sender));
            if (settings.isJoinNotifications()) {
                broadcastJoinNotification(sender.name());
            }
        }
    }

    @Override
    protected void broadcastJoinNotification(final String name) {
        for (final Player p : plugin.getServer().getOnlinePlayers()) {
            if (plugin.hasPermission(p, "joinnotification")) {
                p.sendMessage(settings.getMessage("joinNotification", "%PLAYER%", name));
            }
        }
    }
}
