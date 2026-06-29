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

import com.google.common.io.CharStreams;
import com.google.gson.JsonObject;
import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import eu.kennytv.maintenance.core.MaintenancePlugin;
import eu.kennytv.maintenance.core.proxy.command.MaintenanceProxyCommand;
import eu.kennytv.maintenance.core.proxy.discord.DiscordBot;
import eu.kennytv.maintenance.core.proxy.runnable.SingleMaintenanceRunnable;
import eu.kennytv.maintenance.core.proxy.runnable.SingleMaintenanceScheduleRunnable;
import eu.kennytv.maintenance.core.proxy.util.GeyserApiUtil;
import eu.kennytv.maintenance.core.proxy.util.PlayerNameCache;
import eu.kennytv.maintenance.core.proxy.util.ProfileLookup;
import eu.kennytv.maintenance.core.runnable.MaintenanceRunnableBase;
import eu.kennytv.maintenance.core.util.DiscordWebhook;
import eu.kennytv.maintenance.core.util.RateLimitedException;
import eu.kennytv.maintenance.core.util.SenderInfo;
import eu.kennytv.maintenance.core.util.ServerType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * @author kennytv
 * @since 3.0
 */
public abstract class MaintenanceProxyPlugin extends MaintenancePlugin implements MaintenanceProxy {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private final Map<String, MaintenanceRunnableBase> serverTasks = new HashMap<>();
    protected SettingsProxy settingsProxy;
    protected DiscordBot discordBot;
    protected PlayerNameCache playerNameCache;

    protected MaintenanceProxyPlugin(final String version, final ServerType serverType) {
        super(version, serverType);
    }

    @Override
    public void disable() {
        super.disable();
        if (playerNameCache != null) {
            playerNameCache.flush();
        }
        if (discordBot != null) {
            discordBot.shutdown();
        }
        if (settingsProxy.redisHandler() != null) {
            settingsProxy.redisHandler().close();
        }
    }

    /**
     * Starts the built-in Discord bot if it is enabled and a token is configured.
     * The login happens off the main thread.
     */
    public void startDiscordBot() {
        if (!settingsProxy.isDiscordBotEnabled()) {
            if (settingsProxy.isLinkingEnforced()) {
                getLogger().warning("Code-based linking is enabled, but the Discord bot is disabled - players will not be able to get a code! Enable the bot in the config.");
            }
            return;
        }
        final String token = settingsProxy.getDiscordBotToken();
        if (token == null || token.isBlank()) {
            getLogger().warning("The Discord bot is enabled, but no token is set in the config!");
            return;
        }

        discordBot = new DiscordBot(this, settingsProxy);
        async(() -> discordBot.start(token));
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

    @Blocking
    @Nullable
    protected ProfileLookup doUUIDLookup(final String name) throws IOException {
        // Username cache first. This is the only reliable way to resolve cracked/offline players (e.g. LimboAuth
        // names with a '.' prefix) and Bedrock players, since they don't exist in the Mojang/Geyser databases.
        if (playerNameCache != null) {
            final ProfileLookup cached = playerNameCache.getProfile(name);
            if (cached != null) {
                return cached;
            }
        }

        // Bedrock (Geyser/Floodgate) lookup: gamertags prefixed with the configured Bedrock prefix
        // are resolved against the Geyser global API instead of the Mojang API.
        final String bedrockPrefix = settingsProxy.getBedrockPrefix();
        if (settingsProxy.isBedrockSupport() && !bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix)) {
            final String gamertag = name.substring(bedrockPrefix.length());
            if (gamertag.isEmpty()) {
                return null;
            }
            return GeyserApiUtil.lookupBedrockProfile(gamertag, name);
        }

        ProfileLookup profileLookup = null;
        if (USERNAME_PATTERN.matcher(name).matches()) {
            try {
                profileLookup = doUUIDLookupMojangAPI(name);
            } catch (RateLimitedException e) {
                // Use fallback API if rate limit is reached
                profileLookup = doUUIDLookupAshconAPI(name);
            }
        }

        if (profileLookup == null && settingsProxy.isFallbackToOfflineUUID()) {
            // Use offline uuid
            return new ProfileLookup(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        }

        return profileLookup;
    }

    /**
     * Official Mojang API
     */
    @Nullable
    private ProfileLookup doUUIDLookupMojangAPI(final String name) throws IOException {
        final URL url = URI.create("https://api.mojang.com/users/profiles/minecraft/" + name).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        int status = connection.getResponseCode();
        if (status == 429) {
            throw new RateLimitedException();
        }
        if (status == 404) {
            // Return null if profile not found
            return null;
        }

        try (final InputStream in = connection.getInputStream()) {
            final String output = CharStreams.toString(new InputStreamReader(in));
            final JsonObject json = GSON.fromJson(output, JsonObject.class);

            final UUID uuid = fromStringUUIDWithoutDashes(json.getAsJsonPrimitive("id").getAsString());
            final String username = json.getAsJsonPrimitive("name").getAsString();
            return new ProfileLookup(uuid, username);
        }
    }

    /**
     * Fallback API (Ashcon API)
     */
    @Nullable
    private ProfileLookup doUUIDLookupAshconAPI(final String name) throws IOException {
        final URL url = URI.create("https://api.ashcon.app/mojang/v2/user/" + name).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        if (connection.getResponseCode() == 403) {
            // Return null if profile not found
            return null;
        }

        try (final InputStream in = connection.getInputStream()) {
            final String output = CharStreams.toString(new InputStreamReader(in));
            final JsonObject json = GSON.fromJson(output, JsonObject.class);

            final UUID uuid = UUID.fromString(json.getAsJsonPrimitive("uuid").getAsString());
            final String username = json.getAsJsonPrimitive("username").getAsString();
            return new ProfileLookup(uuid, username);
        }
    }

    private UUID fromStringUUIDWithoutDashes(String undashedUUID) {
        return UUID.fromString(
                undashedUUID.substring(0, 8) + "-" + undashedUUID.substring(8, 12) + "-" +
                        undashedUUID.substring(12, 16) + "-" + undashedUUID.substring(16, 20) + "-" +
                        undashedUUID.substring(20, 32)
        );
    }

    public SettingsProxy getSettingsProxy() {
        return settingsProxy;
    }

    /**
     * Initializes the username cache. Call this once on enable (the data folder must exist).
     */
    public void initPlayerCache() {
        if (settingsProxy.isUsernameCacheEnabled()) {
            playerNameCache = new PlayerNameCache(this, settingsProxy.getUsernameCacheMaxEntries());
        }
    }

    /**
     * Records a player's current name -> uuid in the cache so they can later be whitelisted by name,
     * even if they are a cracked/offline or Bedrock player that Mojang/Geyser cannot resolve.
     */
    public void cachePlayer(final UUID uuid, final String name) {
        if (playerNameCache != null) {
            playerNameCache.cache(uuid, name);
        }
    }

    @Nullable
    public String getCachedName(final UUID uuid) {
        return playerNameCache != null ? playerNameCache.getName(uuid) : null;
    }

    /**
     * Message shown when a non-whitelisted player is denied at join. If code-based linking is enabled, this
     * generates the player's one-time code and tells them to DM it to the bot (DiscordSRV-style); otherwise
     * the normal kick message is used.
     */
    public Component getJoinDenyMessage(final SenderInfo sender) {
        if (settingsProxy.isLinkingEnforced() && discordBot != null) {
            // Already linked but not whitelisted yet (waiting for the role): don't issue a new code.
            if (discordBot.isLinked(sender.uuid())) {
                return settingsProxy.getMessage("linkingPendingApproval");
            }
            final String code = discordBot.generateLinkCode(sender.uuid(), sender.name());
            return settingsProxy.getMessage("linkingKickMessage", "%CODE%", code, "%BOT%", discordBot.getBotName());
        }
        return settingsProxy.getKickMessage();
    }

    /**
     * Message shown when a non-whitelisted player is sent to the waiting/limbo server. In 'limbo' linking mode
     * this contains their one-time code so they can link from there.
     */
    public Component getWaitingJoinMessage(final SenderInfo sender) {
        if (settingsProxy.isLinkingLimboMode() && discordBot != null) {
            // Already linked but not whitelisted yet (waiting for the role): don't issue a new code.
            if (discordBot.isLinked(sender.uuid())) {
                return settingsProxy.getMessage("linkingPendingApproval");
            }
            final String code = discordBot.generateLinkCode(sender.uuid(), sender.name());
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
