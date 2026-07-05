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
package eu.kennytv.maintenance.core.proxy;

import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import eu.kennytv.maintenance.core.MaintenancePlugin;
import eu.kennytv.maintenance.core.proxy.command.MaintenanceProxyCommand;
import eu.kennytv.maintenance.core.proxy.runnable.SingleMaintenanceRunnable;
import eu.kennytv.maintenance.core.proxy.runnable.SingleMaintenanceScheduleRunnable;
import eu.kennytv.maintenance.core.runnable.MaintenanceRunnableBase;
import eu.kennytv.maintenance.core.util.DiscordWebhook;
import eu.kennytv.maintenance.core.util.SenderInfo;
import eu.kennytv.maintenance.core.util.ServerType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * @author kennytv
 * @since 3.0
 */
public abstract class MaintenanceProxyPlugin extends MaintenancePlugin implements MaintenanceProxy {
    private final Map<String, MaintenanceRunnableBase> serverTasks = new HashMap<>();
    protected SettingsProxy settingsProxy;

    protected MaintenanceProxyPlugin(final String version, final ServerType serverType) {
        super(version, serverType);
    }

    @Override
    public void disable() {
        // Base flushes the username cache and shuts down the Discord bot.
        super.disable();
        if (settingsProxy.redisHandler() != null) {
            settingsProxy.redisHandler().close();
        }
    }

    @Override
    public boolean isMaintenance(final Server server) {
        return settingsProxy.isMaintenance(server.getName());
    }

    @Override
    public boolean setMaintenanceToServer(final Server server, final boolean maintenance, @Nullable final String mode) {
        if (maintenance) {
            if (settingsProxy.addMaintenanceServer(server.getName(), mode)) {
                serverActions(server, true);
                return true;
            }
            return mode != null && settingsProxy.setMaintenanceServerMode(server.getName(), mode);
        }

        if (!settingsProxy.removeMaintenanceServer(server.getName())) {
            return false;
        }

        serverActions(server, false);
        return true;
    }

    @Override
    public boolean setMaintenanceToServer(final Server server, final boolean maintenance) {
        return setMaintenanceToServer(server, maintenance, null);
    }

    public boolean setMaintenanceModeToServer(final Server server, @Nullable final String mode) {
        if (!settingsProxy.isMaintenance(server.getName())) {
            return false;
        }
        return settingsProxy.setMaintenanceServerMode(server.getName(), mode);
    }

    public @Nullable String activeMode(final Server server) {
        return settingsProxy.activeMode(server.getName());
    }

    public void serverActions(final Server server, final boolean maintenance) {
        if (server == null) {
            return;
        }

        // Skip to the even fire for dummy servers
        if (server.isRegisteredServer()) {
            if (maintenance) {
                final Server fallback = settingsProxy.getFallbackServer();
                if (fallback == null) {
                    if (server.hasPlayers()) {
                        getLogger().warning("The set fallback could not be found! Instead kicking players from that server off the network!");
                    }
                }
                kickPlayers(server, fallback);
            } else {
                server.broadcast(settingsProxy.getMessage("singleMaintenanceDeactivated", "%SERVER%", server.getName()));
            }

            cancelSingleTask(server);
        }

        eventManager.callEvent(new ServerMaintenanceChangedEvent(server, maintenance));
        if (maintenance) {
            sendWebhookMessage("webhookSingleMaintenanceActivated", DiscordWebhook.EventType.MAINTENANCE_ENABLED, "%SERVER%", server.getName());
        } else {
            sendWebhookMessage("webhookSingleMaintenanceDeactivated", DiscordWebhook.EventType.MAINTENANCE_DISABLED, "%SERVER%", server.getName());
        }

        for (final String command : (maintenance ? settingsProxy.getCommandsOnMaintenanceEnable(server) : settingsProxy.getCommandsOnMaintenanceDisable(server))) {
            try {
                executeConsoleCommand(command.replace("%SERVER%", server.getName()));
            } catch (final Exception e) {
                getLogger().log(Level.SEVERE, "Error while executing extra maintenance " + (maintenance ? "enable" : "disable") + " command: " + command, e);
            }
        }
    }

    @Override
    public boolean isServerTaskRunning(final Server server) {
        return serverTasks.containsKey(server.getName());
    }

    public boolean hasServerTasks() {
        return !serverTasks.isEmpty();
    }

    public Map<String, MaintenanceRunnableBase> getServerTasks() {
        return serverTasks;
    }

    public MaintenanceRunnableBase getServerTask(final String server) {
        return serverTasks.get(server);
    }

    @Override
    public Set<String> getMaintenanceServers() {
        return Collections.unmodifiableSet(settingsProxy.getMaintenanceServers());
    }

    public void cancelSingleTask(final Server server) {
        final MaintenanceRunnableBase task = serverTasks.remove(server.getName());
        if (task != null) {
            task.getTask().cancel();
        }
    }

    public MaintenanceRunnableBase startSingleMaintenanceRunnable(final Server server, final Duration duration, final boolean enable, @Nullable final String mode) {
        final MaintenanceRunnableBase runnable = new SingleMaintenanceRunnable(this, settingsProxy, (int) duration.getSeconds(), enable, server, mode);
        serverTasks.put(server.getName(), runnable);
        return runnable;
    }

    public MaintenanceRunnableBase startSingleMaintenanceRunnable(final Server server, final Duration duration, final boolean enable) {
        return startSingleMaintenanceRunnable(server, duration, enable, null);
    }

    public MaintenanceRunnableBase scheduleSingleMaintenanceRunnable(final Server server, final Duration enableIn,
                                                                     final Duration maintenanceDuration, @Nullable final String mode) {
        final MaintenanceRunnableBase runnable = new SingleMaintenanceScheduleRunnable(this, settingsProxy,
                (int) enableIn.getSeconds(), (int) maintenanceDuration.getSeconds(), server, mode);
        serverTasks.put(server.getName(), runnable);
        return runnable;
    }

    @Override
    @Nullable
    public List<String> getMaintenanceServersDump() {
        final List<String> list = new ArrayList<>();
        if (isMaintenance()) {
            list.add("global");
        }
        list.addAll(settingsProxy.getMaintenanceServers());
        return list.isEmpty() ? null : list;
    }

    @Override
    public MaintenanceProxyCommand getCommandManager() {
        return (MaintenanceProxyCommand) commandManager;
    }

    @Override
    protected void kickPlayers() {
        // Send players to waiting server is set
        if (settingsProxy.getWaitingServer() != null) {
            final Server waitingServer = getServer(settingsProxy.getWaitingServer());
            if (waitingServer != null) {
                kickPlayersTo(waitingServer);
                return;
            }
        }

        // If not set, kick players from proxy
        kickPlayersFromProxy();
    }

    public SettingsProxy getSettingsProxy() {
        return settingsProxy;
    }

    /**
     * Message shown when a non-whitelisted player is sent to the waiting/limbo server. In 'limbo' linking mode
     * this contains their one-time code so they can link from there.
     *
     * <p>Uses the same two-tier linked check as {@link #getJoinDenyMessage(SenderInfo)}.
     */
    public Component getWaitingJoinMessage(final SenderInfo sender) {
        if (settingsProxy.isLinkingLimboMode() && discordBot != null) {
            // Already linked (primary UUID check, or name-based fallback for cracked/offline servers).
            if (discordBot.isLinked(sender.uuid()) || discordBot.isLinkedByName(sender.name())) {
                return settingsProxy.getMessage("linkingPendingApproval");
            }
            // null means the active-code pool is at capacity — bot flood in progress.
            final String code = discordBot.generateLinkCode(sender.uuid(), sender.name());
            if (code == null) {
                return settingsProxy.getMessage("linkingServerBusy");
            }
            return settingsProxy.getMessage("linkingLimboMessage", "%CODE%", code, "%BOT%", discordBot.getBotName());
        }
        return settingsProxy.getMessage("sentToWaitingServer");
    }

    @Nullable
    public abstract String getServerNameOf(SenderInfo sender);

    protected abstract void kickPlayers(Server server, Server fallback);

    protected abstract void kickPlayersTo(Server server);

    protected abstract void kickPlayersFromProxy();
}
