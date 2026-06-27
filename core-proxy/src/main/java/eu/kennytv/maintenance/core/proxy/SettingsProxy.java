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

import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import eu.kennytv.maintenance.core.Settings;
import eu.kennytv.maintenance.core.config.ConfigSection;
import eu.kennytv.maintenance.core.proxy.redis.RedisHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public final class SettingsProxy extends Settings {

    private Map<String, String> maintenanceServers;
    private List<String> fallbackServers;
    private String waitingServer;
    private boolean fallbackToOfflineUUID;
    private boolean bedrockSupport;
    private String bedrockPrefix;
    private String discordInvite;
    private boolean discordBotEnabled;
    private String discordBotToken;
    private String discordGuildId;
    private String discordWhitelistRoleId;
    private String discordAutoWhitelistRoleId;
    private boolean discordRemoveOnRoleLoss;
    private boolean discordAllowLinking;
    private String linkingMode;
    private boolean linkingRequireRole;
    private int linkCodeLength;
    private int linkCodeExpirySeconds;
    private int linkMaxAttemptsPerMinute;

    private Map<String, List<String>> commandsOnMaintenanceEnable;
    private Map<String, List<String>> commandsOnMaintenanceDisable;

    private RedisHandler redisHandler;

    public SettingsProxy(final MaintenanceProxyPlugin plugin) {
        super(plugin);
    }

    @Override
    protected void loadExtraSettings() {
        // Open Redis connection if enabled and not already done
        final ConfigSection redisSection = config.getSection("redis");
        if (!hasRedis() && redisSection.getBoolean("enabled")) {
            connectToRedis(redisSection.getString("uri"));
        }

        final Object fallback = config.getObject("fallback");
        fallbackServers = fallback instanceof String s ? Collections.singletonList(s) : config.getStringList("fallback", Collections.emptyList());

        waitingServer = config.getString("waiting-server", "");
        if (waitingServer.isEmpty() || waitingServer.equalsIgnoreCase("none")) {
            waitingServer = null;
        }
        fallbackToOfflineUUID = config.getBoolean("fallback-to-offline-uuid", false);

        final ConfigSection bedrockSection = config.getSection("bedrock");
        bedrockSupport = bedrockSection.getBoolean("enabled", false);
        bedrockPrefix = bedrockSection.getString("prefix", ".");

        discordInvite = config.getString("discord-invite", "");

        final ConfigSection discordSection = config.getSection("discord-bot");
        discordBotEnabled = discordSection.getBoolean("enabled", false);
        discordBotToken = discordSection.getString("token", "");
        discordGuildId = discordSection.getString("guild-id", "");
        discordWhitelistRoleId = discordSection.getString("whitelist-role-id", "");
        discordAutoWhitelistRoleId = discordSection.getString("auto-whitelist-role-id", "");
        discordRemoveOnRoleLoss = discordSection.getBoolean("remove-on-role-loss", true);
        discordAllowLinking = discordSection.getBoolean("allow-linking", true);

        final ConfigSection linkingSection = discordSection.getSection("linking");
        if (linkingSection != null) {
            linkingMode = normalizeLinkingMode(linkingSection.getString("mode", "off"));
            linkingRequireRole = linkingSection.getBoolean("require-role", true);
            linkCodeLength = linkingSection.getInt("code-length", 6);
            linkCodeExpirySeconds = linkingSection.getInt("code-expiry-seconds", 600);
            linkMaxAttemptsPerMinute = linkingSection.getInt("max-attempts-per-minute", 5);
        } else {
            linkingMode = "off";
            linkingRequireRole = true;
            linkCodeLength = 6;
            linkCodeExpirySeconds = 600;
            linkMaxAttemptsPerMinute = 5;
        }

        commandsOnMaintenanceEnable = new HashMap<>();
        final ConfigSection enableCommandsSection = config.getSection("commands-on-single-maintenance-enable");
        for (final String key : enableCommandsSection.getKeys()) {
            commandsOnMaintenanceEnable.put(key.toLowerCase(Locale.ROOT), enableCommandsSection.getStringList(key));
        }

        commandsOnMaintenanceDisable = new HashMap<>();
        final ConfigSection disableCommandsSection = config.getSection("commands-on-single-maintenance-disable");
        for (final String key : disableCommandsSection.getKeys()) {
            commandsOnMaintenanceDisable.put(key.toLowerCase(Locale.ROOT), disableCommandsSection.getStringList(key));
        }

        if (!hasRedis()) {
            final ConfigSection maintenanceServersSection = config.getSection("proxied-maintenance-servers");
            maintenanceServers = new LinkedHashMap<>();
            for (final Map.Entry<String, ?> entry : maintenanceServersSection.getValues().entrySet()) {
                if (entry.getKey() != null) {
                    maintenanceServers.put(entry.getKey(), normalizeServerMode(entry.getValue()));
                }
            }
        }
    }

    private void connectToRedis(final String redisUri) {
        redisHandler = new RedisHandler((MaintenanceProxyPlugin) plugin, this);
        try {
            redisHandler.setup(redisUri);
        } catch (final Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error while trying to open Redis connection!", e);
            e.printStackTrace();
            redisHandler.close();
            redisHandler = null;
            return;
        }

        maintenance = redisHandler.loadMaintenanceStatus();
        activeMode = normalizeMode(redisHandler.loadGlobalMode());
        maintenanceServers = redisHandler.loadMaintenanceServers();
    }

    public boolean isMaintenance(final String serverName) {
        return maintenanceServers.containsKey(serverName);
    }

    public @Nullable String activeMode(final String serverName) {
        final String mode = maintenanceServers.get(serverName);
        return mode == null || mode.equals("default") ? null : mode;
    }

    public Component getServerKickMessage(final String server) {
        String message = getLanguageStringOrNull("singleMaintenanceKicks." + server, "%SERVER%", server);
        if (message == null) {
            message = getLanguageString("singleMaintenanceKick", "%SERVER%", server);
        }
        return parse(plugin.replacePingVariables(message, activeMode(server)));
    }

    public Component getFullServerKickMessage(final String server) {
        String message = getLanguageStringOrNull("singleMaintenanceKicksComplete." + server, "%SERVER%", server);
        if (message == null) {
            message = getLanguageString("singleMaintenanceKickComplete", "%SERVER%", server);
        }
        return parse(plugin.replacePingVariables(message, activeMode(server)));
    }

    public boolean hasRedis() {
        return redisHandler != null;
    }

    @Override
    protected void loadWhitelistedPlayers() {
        if (hasRedis()) {
            redisHandler.loadPlayers(getWhitelistedPlayers());
        } else {
            super.loadWhitelistedPlayers();
        }
    }

    @Override
    public void setMaintenance(final boolean maintenance, @Nullable final String mode) {
        super.setMaintenance(maintenance, mode);
        if (hasRedis()) {
            redisHandler.set(maintenance, mode != null ? mode : "default");
        }
    }

    @Override
    public void setActiveMode(@Nullable final String activeMode) {
        super.setActiveMode(activeMode);
        if (hasRedis() && isMaintenance()) {
            redisHandler.set(true, activeMode != null ? activeMode : "default");
        }
    }

    public void setActiveModeDirect(@Nullable final String activeMode) {
        super.setActiveMode(activeMode);
    }

    public void setMaintenanceDirect(final boolean maintenance, @Nullable final String mode) {
        super.setMaintenance(maintenance, mode);
    }

    @Override
    public boolean addWhitelistedPlayer(final UUID uuid, final String name) {
        if (super.addWhitelistedPlayer(uuid, name)) {
            if (hasRedis()) {
                redisHandler.addPlayer(uuid, name);
            }
            return true;
        }
        return false;
    }

    public boolean addWhitelistedPlayerDirect(final UUID uuid, final String name) {
        return super.addWhitelistedPlayer(uuid, name);
    }

    @Override
    public boolean removeWhitelistedPlayer(final UUID uuid) {
        if (super.removeWhitelistedPlayer(uuid)) {
            if (hasRedis()) {
                redisHandler.removePlayer(uuid);
            }
            return true;
        }
        return false;
    }

    public boolean removeWhitelistedPlayerDirect(final UUID uuid) {
        return super.removeWhitelistedPlayer(uuid);
    }

    boolean addMaintenanceServer(final String server, @Nullable final String mode) {
        final String normalizedMode = normalizeServerMode(mode);
        if (hasRedis()) {
            if (redisHandler.addServer(server, normalizedMode)) {
                maintenanceServers.put(server, normalizedMode);
                return true;
            }
        } else if (maintenanceServers.putIfAbsent(server, normalizedMode) == null) {
            saveServersToConfig();
            return true;
        }
        return false;
    }

    boolean setMaintenanceServerMode(final String server, @Nullable final String mode) {
        final String normalizedMode = normalizeServerMode(mode);
        final String oldMode = maintenanceServers.get(server);
        if (oldMode == null || oldMode.equals(normalizedMode)) {
            return false;
        }

        maintenanceServers.put(server, normalizedMode);
        if (hasRedis()) {
            redisHandler.setServerMode(server, normalizedMode);
        } else {
            saveServersToConfig();
        }
        return true;
    }

    boolean removeMaintenanceServer(final String server) {
        if (hasRedis()) {
            if (redisHandler.removeServer(server)) {
                maintenanceServers.remove(server);
                return true;
            }
        } else if (maintenanceServers.remove(server) != null) {
            saveServersToConfig();
            return true;
        }
        return false;
    }

    public boolean addMaintenanceServerDirect(final String server, @Nullable final String mode) {
        return maintenanceServers.put(server, normalizeServerMode(mode)) == null;
    }

    public boolean removeMaintenanceServerDirect(final String server) {
        return maintenanceServers.remove(server) != null;
    }

    public void setMaintenanceServerModeDirect(final String server, @Nullable final String mode) {
        if (maintenanceServers.containsKey(server)) {
            maintenanceServers.put(server, normalizeServerMode(mode));
        }
    }

    private void saveServersToConfig() {
        config.set("proxied-maintenance-servers", new LinkedHashMap<>(maintenanceServers));
        saveConfig();
    }

    public Set<String> getMaintenanceServers() {
        return maintenanceServers.keySet();
    }

    @Nullable
    public Server getFallbackServer() {
        for (final String fallbackServer : fallbackServers) {
            final Server server = ((MaintenanceProxy) plugin).getServer(fallbackServer);
            if (server != null && !isMaintenance(server.getName())) {
                return server;
            }
        }
        return null;
    }

    @Nullable
    public String getWaitingServer() {
        return waitingServer;
    }

    public boolean isFallbackToOfflineUUID() {
        return fallbackToOfflineUUID;
    }

    public boolean isBedrockSupport() {
        return bedrockSupport;
    }

    @Override
    public String getDiscordInvite() {
        return discordInvite;
    }

    public String getBedrockPrefix() {
        return bedrockPrefix;
    }

    public boolean isDiscordBotEnabled() {
        return discordBotEnabled;
    }

    public String getDiscordBotToken() {
        return discordBotToken;
    }

    public String getDiscordGuildId() {
        return discordGuildId;
    }

    public String getDiscordWhitelistRoleId() {
        return discordWhitelistRoleId;
    }

    public String getDiscordAutoWhitelistRoleId() {
        return discordAutoWhitelistRoleId;
    }

    public boolean isDiscordRemoveOnRoleLoss() {
        return discordRemoveOnRoleLoss;
    }

    public boolean isDiscordAllowLinking() {
        return discordAllowLinking;
    }

    public boolean isLinkingEnforced() {
        return !linkingMode.equals("off");
    }

    public boolean isLinkingKickMode() {
        return linkingMode.equals("kick");
    }

    public boolean isLinkingLimboMode() {
        return linkingMode.equals("limbo");
    }

    public boolean isLinkingRequireRole() {
        return linkingRequireRole;
    }

    public int getLinkCodeLength() {
        return linkCodeLength;
    }

    public int getLinkCodeExpirySeconds() {
        return linkCodeExpirySeconds;
    }

    public int getLinkMaxAttemptsPerMinute() {
        return linkMaxAttemptsPerMinute;
    }

    private static String normalizeLinkingMode(@Nullable final String mode) {
        if (mode == null) {
            return "off";
        }
        final String value = mode.toLowerCase(Locale.ROOT).trim();
        return switch (value) {
            case "kick", "limbo" -> value;
            default -> "off";
        };
    }

    public List<String> getCommandsOnMaintenanceEnable(final Server server) {
        final List<String> enableCommands = commandsOnMaintenanceEnable.getOrDefault("all", new ArrayList<>());
        final List<String> serverEnableCommands = commandsOnMaintenanceEnable.get(server.getName().toLowerCase(Locale.ROOT));
        if (serverEnableCommands != null) {
            enableCommands.addAll(serverEnableCommands);
        }
        return enableCommands;
    }

    public List<String> getCommandsOnMaintenanceDisable(final Server server) {
        final List<String> disableCommands = commandsOnMaintenanceDisable.getOrDefault("all", new ArrayList<>());
        final List<String> serverDisableCommands = commandsOnMaintenanceDisable.get(server.getName().toLowerCase(Locale.ROOT));
        if (serverDisableCommands != null) {
            disableCommands.addAll(serverDisableCommands);
        }
        return disableCommands;
    }

    public @Nullable RedisHandler redisHandler() {
        return redisHandler;
    }

    private static String normalizeServerMode(@Nullable final Object mode) {
        if (mode == null) {
            return "default";
        }
        final String value = String.valueOf(mode).toLowerCase(Locale.ROOT);
        return value.isBlank() ? "default" : value;
    }
}
