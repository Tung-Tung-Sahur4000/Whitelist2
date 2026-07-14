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
package eu.kennytv.maintenance.core;

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eu.kennytv.maintenance.api.Maintenance;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import eu.kennytv.maintenance.api.event.MaintenanceChangedEvent;
import eu.kennytv.maintenance.api.event.manager.EventManager;
import eu.kennytv.maintenance.core.command.MaintenanceCommand;
import eu.kennytv.maintenance.core.discord.DiscordBot;
import eu.kennytv.maintenance.core.dump.MaintenanceDump;
import eu.kennytv.maintenance.core.dump.PluginDump;
import eu.kennytv.maintenance.core.hook.PremiumResolver;
import eu.kennytv.maintenance.core.hook.ServerListPlusHook;
import eu.kennytv.maintenance.core.runnable.MaintenanceRunnable;
import eu.kennytv.maintenance.core.runnable.MaintenanceScheduleRunnable;
import eu.kennytv.maintenance.core.util.DiscordWebhook;
import eu.kennytv.maintenance.core.util.DummySenderInfo;
import eu.kennytv.maintenance.core.util.GeyserApiUtil;
import eu.kennytv.maintenance.core.util.PlayerNameCache;
import eu.kennytv.maintenance.core.util.ProfileLookup;
import eu.kennytv.maintenance.core.util.RateLimitedException;
import eu.kennytv.maintenance.core.util.SenderInfo;
import eu.kennytv.maintenance.core.util.ServerType;
import eu.kennytv.maintenance.core.util.Task;
import eu.kennytv.maintenance.core.util.Version;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public abstract class MaintenancePlugin implements Maintenance {
    public static final Gson GSON = new GsonBuilder().create();
    public static final String HANGAR_URL = "https://hangar.papermc.io/kennytv/Maintenance";
    private static final Pattern INT_PATTERN = Pattern.compile("[0-9]+");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    protected final EventManager eventManager;
    protected final Version version;
    protected Settings settings;
    protected ServerListPlusHook serverListPlusHook;
    @Nullable
    protected PremiumResolver premiumResolver;
    protected MaintenanceRunnable runnable;
    protected MaintenanceCommand commandManager;
    protected DiscordBot discordBot;
    protected PlayerNameCache playerNameCache;
    private final Component prefix;
    private final ServerType serverType;
    private Version newestVersion;
    private boolean debug;

    protected MaintenancePlugin(final String version, final ServerType serverType) {
        this.version = new Version(version);
        this.serverType = serverType;
        this.prefix = Component.text()
                .append(Component.text().content("[").color(NamedTextColor.DARK_GRAY))
                .append(Component.text().content("Whitelist").color(NamedTextColor.YELLOW))
                .append(Component.text().content("]").color(NamedTextColor.DARK_GRAY))
                .append(Component.text(" "))
                .build();
        this.eventManager = new eu.kennytv.maintenance.core.event.EventManager();
        MaintenanceProvider.setMaintenance(this);
    }

    public void disable() {
        if (playerNameCache != null) {
            playerNameCache.flush();
        }
        if (discordBot != null) {
            discordBot.shutdown();
        }
    }

    @Override
    public void setMaintenance(final boolean maintenance, final String mode) {
        settings.setMaintenance(maintenance, mode);
        serverActions(maintenance);
    }

    public void serverActions(final boolean maintenance) {
        if (isTaskRunning()) {
            cancelTask();
        }
        if (serverListPlusHook != null && settings.isEnablePingMessages()) {
            serverListPlusHook.setEnabled(!maintenance);
        }

        broadcast(maintenance ? settings.getMessage("maintenanceActivated") : settings.getMessage("maintenanceDeactivated"));
        if (maintenance && settings.isKickOnlinePlayers()) {
            kickPlayers();
        }

        eventManager.callEvent(new MaintenanceChangedEvent(maintenance));
        if (maintenance) {
            sendWebhookMessage("webhookMaintenanceActivated", DiscordWebhook.EventType.MAINTENANCE_ENABLED);
        } else {
            sendWebhookMessage("webhookMaintenanceDeactivated", DiscordWebhook.EventType.MAINTENANCE_DISABLED);
        }

        for (final String command : (maintenance ? settings.getCommandsOnMaintenanceEnable() : settings.getCommandsOnMaintenanceDisable())) {
            try {
                executeConsoleCommand(command);
            } catch (final Exception e) {
                getLogger().log(Level.SEVERE, "Error while executing extra maintenance " + (maintenance ? "enable" : "disable") + " command: " + command, e);
            }
        }
    }

    public void sendWebhookMessage(final String messageKey, final DiscordWebhook.EventType eventType, final String... replacements) {
        if (!settings.isWebhookEnabled() || !settings.isWebhookEventEnabled(eventType.configKey())) {
            return;
        }

        async(() -> {
            try {
                DiscordWebhook.sendMessage(settings.getWebhookMessage(messageKey, replacements), eventType, settings);
            } catch (final Exception e) {
                getLogger().log(Level.WARNING, "Could not send Discord webhook message", e);
            }
        });
    }

    public String getTargetTimestamp(final Duration duration) {
        return Long.toString((System.currentTimeMillis() + duration.toMillis()) / TimeUnit.SECONDS.toMillis(1));
    }

    public String replacePingVariables(final String component) {
        return replacePingVariables(component, settings.activeMode());
    }

    public String replacePingVariables(String component, @Nullable final String mode) {
        if (component.contains("%TIMER%")) {
            component = component.replace("%TIMER%", getTimerMessage());
        }
        final String reason = settings.activeReason() == null ? "" : settings.activeReason();
        component = component.replace("%REASON%", reason);
        component = component.replace("%MODE%", mode == null ? "default" : mode);
        component = component.replace("%ONLINE%", String.valueOf(getOnlinePlayers()));
        component = component.replace("%MAX%", String.valueOf(getMaxPlayers()));
        component = component.replace("%DISCORD%", settings.getDiscordInvite());
        return component;
    }

    public String getTimerMessage() {
        if (!isTaskRunning()) {
            return settings.getLanguageString("motdTimerNotRunning");
        }

        final int preHours = runnable.getSecondsLeft() / 60;
        final int minutes = preHours % 60;
        final int seconds = runnable.getSecondsLeft() % 60;
        return settings.getLanguageString("motdTimer",
                "%HOURS%", String.format("%02d", preHours / 60),
                "%MINUTES%", String.format("%02d", minutes),
                "%SECONDS%", String.format("%02d", seconds));
    }

    public String getFormattedTime(final int timeSeconds) {
        final int preHours = timeSeconds / 60;
        final int minutes = preHours % 60;
        final int seconds = timeSeconds % 60;

        final StringBuilder buider = new StringBuilder();
        append(buider, "hour", preHours / 60);
        append(buider, "minute", minutes);
        append(buider, "second", seconds);
        return buider.toString();
    }

    private void append(final StringBuilder builder, final String timeUnit, final int time) {
        if (time == 0) return;
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(time).append(' ').append(settings.language.getString(time == 1 ? timeUnit : timeUnit + "s"));
    }

    public void startMaintenanceRunnable(final Duration duration, final boolean enable) {
        runnable = new MaintenanceRunnable(this, settings, (int) duration.getSeconds(), enable);
        // Save the endtimer to be able to continue it after a server stop
        if (settings.isSaveEndtimerOnStop() && !runnable.shouldEnable()) {
            settings.setSavedEndtimer(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(runnable.getSecondsLeft()));
        }
    }

    public void scheduleMaintenanceRunnable(final Duration enableIn, final Duration maintenanceDuration) {
        runnable = new MaintenanceScheduleRunnable(this, settings, (int) enableIn.getSeconds(), (int) maintenanceDuration.getSeconds());
    }

    public boolean updateAvailable() {
        try {
            checkNewestVersion();
            return version.compareTo(newestVersion) < 0;
        } catch (final Exception e) {
            return false;
        }
    }

    protected void continueLastEndtimer() {
        if (!settings.isSaveEndtimerOnStop()) return;
        if (settings.getSavedEndtimer() == 0) return;

        final long current = System.currentTimeMillis();
        getLogger().info("Found interrupted endtimer from last uptime...");
        if (!isMaintenance()) {
            getLogger().info("Maintenance has already been disabled, thus the timer has been cancelled.");
            settings.setSavedEndtimer(0);
        } else if (settings.getSavedEndtimer() < current) {
            getLogger().info("The endtimer has already expired, maintenance has been disabled.");
            setMaintenance(false);
            settings.setSavedEndtimer(0);
        } else {
            startMaintenanceRunnable(Duration.ofMillis(settings.getSavedEndtimer() - current), false);
            getLogger().info("The timer has been continued - maintenance will be disabled in: " + getTimerMessage());
        }
    }

    protected void sendEnableMessage() {
        if (!settings.hasUpdateChecks()) return;
        async(() -> {
            try {
                checkNewestVersion();
            } catch (final Exception e) {
                return;
            }

            final int compare = version.compareTo(newestVersion);
            if (compare < 0) {
                getLogger().warning("Newest version available: Version " + newestVersion + ", you're on " + version);
            } else if (compare > 0) {
                if (version.getTag() != null && !version.getTag().isBlank()) {
                    getLogger().info("You're running a development version, please report bugs on the Discord server (https://discord.gg/vGCUzHq) or the GitHub issue tracker (https://github.com/kennytv/Maintenance/issues)");
                } else {
                    getLogger().info("You're running a version, that doesn't exist!");
                }
            }
        });
    }

    public boolean installUpdate() throws Exception {
        final String fileName = "Maintenance-" + serverType.name() + "-" + newestVersion + ".jar";
        final Path tempFilePath = Paths.get(getPluginFolder() + "Maintenance.tmp");
        final URLConnection connection = URI.create("https://github.com/kennytv/Maintenance/releases/download/" + newestVersion + "/" + fileName).toURL().openConnection();
        try (final BufferedInputStream is = new BufferedInputStream(connection.getInputStream());
             final BufferedOutputStream os = new BufferedOutputStream(Files.newOutputStream(tempFilePath))) {
            writeFile(is, os);
        }

        final File file = tempFilePath.toFile();
        final long newlength = file.length();
        if (newlength < 10_000) {
            // Sanity check the file length
            file.delete();
            return false;
        }

        try (final InputStream is = Files.newInputStream(file.toPath());
             final BufferedOutputStream os = new BufferedOutputStream(Files.newOutputStream(getPluginFile().toPath()))) {
            writeFile(is, os);
        }

        file.delete();
        return true;
    }

    private void writeFile(final InputStream is, final OutputStream os) throws IOException {
        final byte[] chunk = new byte[1024];
        int chunkSize;
        while ((chunkSize = is.read(chunk)) != -1) {
            os.write(chunk, 0, chunkSize);
        }
    }

    private void checkNewestVersion() throws Exception {
        final URLConnection connection = URI.create("https://hangar.papermc.io/api/v1/projects/Maintenance/latestrelease").toURL().openConnection();
        connection.setRequestProperty("User-Agent", "Maintenance/" + getVersion());
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            final String newVersionString = reader.readLine();
            final Version newVersion = new Version(newVersionString);
            if (!newVersion.equals(version)) {
                newestVersion = newVersion;
            }
        }
    }

    public String pasteDump() {
        final MaintenanceDump dump = new MaintenanceDump(this, settings);
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create("https://api.pastes.dev/post").toURL().openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", "Maintenance/" + getVersion());
            connection.setRequestProperty("Content-Type", "text/plain");

            try (final OutputStream out = connection.getOutputStream()) {
                out.write(new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(dump).getBytes(StandardCharsets.UTF_8));
            }

            if (connection.getResponseCode() == 503) {
                getLogger().warning("Could not paste dump, pastes.dev down?");
                return null;
            }

            try (final InputStream in = connection.getInputStream()) {
                final String output = CharStreams.toString(new InputStreamReader(in));
                final JsonObject jsonOutput = GSON.fromJson(output, JsonObject.class);
                if (!jsonOutput.has("key")) {
                    getLogger().log(Level.WARNING, "Could not paste dump, there was no key returned :(");
                    return null;
                }

                return jsonOutput.get("key").getAsString();
            }
        } catch (final IOException e) {
            getLogger().log(Level.WARNING, "Could not paste dump :(", e);
            return null;
        }
    }

    public void loadMaintenanceIcon() {
        final File file = new File(getDataFolder(), "maintenance-icon.png");
        if (!file.exists()) {
            getLogger().warning("Could not find a 'maintenance-icon.png' file - did you create one in the plugin's folder?");
            return;
        }

        try {
            loadIcon(file);
        } catch (final Exception e) {
            getLogger().log(Level.WARNING, "Could not load the 'maintenance-icon.png' file!", e);
        }
    }

    public void cancelTask() {
        if (settings.isSaveEndtimerOnStop() && !runnable.shouldEnable()) {
            settings.setSavedEndtimer(0);
        }

        runnable.getTask().cancel();
        runnable = null;
    }

    @Nullable
    public UUID checkUUID(final SenderInfo sender, final String s) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(s);
        } catch (final Exception e) {
            sender.send(settings.getMessage("invalidUuid"));
            return null;
        }
        return uuid;
    }

    public String[] removeArrayIndex(final String[] args, final int index) {
        final List<String> argsList = Lists.newArrayList(args);
        argsList.remove(index);
        return argsList.toArray(new String[0]);
    }

    public boolean isNumeric(final String string) {
        return INT_PATTERN.matcher(string).matches();
    }

    @Override
    public boolean isMaintenance() {
        return settings.isMaintenance();
    }

    @Override
    public boolean isTaskRunning() {
        return runnable != null;
    }

    public @Nullable MaintenanceRunnable getCurrentTask() {
        return runnable;
    }

    @Override
    public Settings getSettings() {
        return settings;
    }

    @Override
    public EventManager getEventManager() {
        return eventManager;
    }

    @Override
    public String getVersion() {
        return version.toString();
    }

    @Nullable
    public List<String> getMaintenanceServersDump() {
        return isMaintenance() ? Arrays.asList("global") : null;
    }

    @Override
    public boolean isDebug() {
        return debug;
    }

    @Override
    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    public int getSaltLevel() {
        return Integer.MAX_VALUE;
    }

    public Version getNewestVersion() {
        return newestVersion;
    }

    public Component prefix() {
        return prefix;
    }

    /**
     * @see #isTaskRunning()
     */
    @Nullable
    public MaintenanceRunnable getRunnable() {
        return runnable;
    }

    public MaintenanceCommand getCommandManager() {
        return commandManager;
    }

    public ServerType getServerType() {
        return serverType;
    }

    protected String getPluginFolder() {
        return "plugins/";
    }

    public void sync(final Runnable runnable) {
        async(runnable);
    }

    public abstract void async(Runnable runnable);

    protected abstract void executeConsoleCommand(String command);

    public abstract void broadcast(Component component);

    public abstract Task startMaintenanceRunnable(Runnable runnable);

    /**
     * Gets the offline sender info of a player.
     * This method may do a web lookup.
     *
     * @param name name of the player
     */
    public abstract CompletableFuture<@Nullable SenderInfo> getOfflinePlayer(String name);

    public abstract CompletableFuture<@Nullable SenderInfo> getOfflinePlayer(UUID uuid);

    /**
     * Resolves both the premium (online) and cracked (offline) account a name could connect as, so a
     * single {@code /whitelist add <name>} whitelists the player no matter which way they log in.
     *
     * <p>This matters on offline-mode and mixed servers (e.g. LimboAuth with premium auto-login): the
     * exact same username can arrive with its Mojang UUID (premium) or with an offline UUID derived from
     * the name (cracked). Whitelisting only one of them leaves the player blocked when they connect as
     * the other. Bedrock gamertags have no such duality and resolve to a single Floodgate UUID.
     */
    public CompletableFuture<List<SenderInfo>> getOfflinePlayers(final String name) {
        return CompletableFuture.supplyAsync(() -> {
            final List<SenderInfo> profiles = new ArrayList<>();
            final Set<UUID> seen = new HashSet<>();
            try {
                // Primary resolution: cache (real joined UUID) -> Bedrock -> mode-appropriate UUID.
                final ProfileLookup primary = doUUIDLookup(name);
                if (primary != null) {
                    addProfile(profiles, seen, primary);
                }

                // Bedrock gamertags map to exactly one Floodgate UUID - no premium/offline variants.
                final String bedrockPrefix = settings.getBedrockPrefix();
                final boolean bedrock = settings.isBedrockSupport()
                        && !bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix);
                if (!bedrock && USERNAME_PATTERN.matcher(name).matches()) {
                    // Premium variant: relevant on online servers and on mixed servers allowing premium logins.
                    ProfileLookup premium = null;
                    try {
                        premium = doUUIDLookupMojangAPI(name);
                    } catch (final RateLimitedException e) {
                        premium = doUUIDLookupAshconAPI(name);
                    }
                    if (premium != null) {
                        addProfile(profiles, seen, premium);
                    }
                    // Cracked variant: only possible when the server itself runs in offline mode.
                    if (!isOnlineMode()) {
                        addProfile(profiles, seen, new ProfileLookup(offlineUUID(name), name));
                    }

                    // Narrow the premium/offline pair down to a single variant when we can: a FastLogin-known
                    // account type, or strict mode (whitelist-both-variants: false) on an offline server, where
                    // we refuse to speculatively whitelist the premium account of an unconfirmed name.
                    return narrowProfiles(name, profiles);
                }
            } catch (final IOException e) {
                getLogger().log(Level.WARNING, "Could not fully resolve profiles for " + name, e);
            }
            return profiles;
        });
    }

    /**
     * Narrows a resolved premium/offline pair down to a single account variant when we can be confident:
     * <ul>
     *   <li>{@link #premiumResolver} (e.g. FastLogin) reports a definite account type - keep that variant;</li>
     *   <li>strict mode ({@code whitelist-both-variants: false}) on an offline server with an unconfirmed
     *       name - keep only the offline (cracked) UUID, never the speculative premium one.</li>
     * </ul>
     * Otherwise the full list is returned. Also falls back to the full list if narrowing would leave nothing,
     * so this can never make a name un-whitelistable.
     */
    private List<SenderInfo> narrowProfiles(final String name, final List<SenderInfo> profiles) {
        if (profiles.size() < 2) {
            return profiles;
        }

        // keepOffline: TRUE = keep only the offline UUID, FALSE = keep only the premium UUID, null = keep both.
        final Boolean keepOffline;
        final PremiumResolver.AccountType type = accountType(name);
        if (type == PremiumResolver.AccountType.CRACKED) {
            keepOffline = Boolean.TRUE;
        } else if (type == PremiumResolver.AccountType.PREMIUM) {
            keepOffline = Boolean.FALSE;
        } else if (!settings.whitelistBothVariants() && !isOnlineMode()) {
            // Strict, unknown account type: don't speculatively whitelist the premium account of the name.
            keepOffline = Boolean.TRUE;
        } else {
            keepOffline = null;
        }
        if (keepOffline == null) {
            return profiles;
        }

        final UUID offline = offlineUUID(name);
        final List<SenderInfo> narrowed = new ArrayList<>();
        for (final SenderInfo profile : profiles) {
            if (profile.uuid().equals(offline) == keepOffline) {
                narrowed.add(profile);
            }
        }
        return narrowed.isEmpty() ? profiles : narrowed;
    }

    /**
     * Fail-safe account-type lookup: {@link PremiumResolver.AccountType#UNKNOWN} when no resolver is
     * registered or it throws, so a misbehaving hook never blocks whitelisting.
     */
    private PremiumResolver.AccountType accountType(final String name) {
        final PremiumResolver resolver = premiumResolver;
        if (resolver == null) {
            return PremiumResolver.AccountType.UNKNOWN;
        }
        try {
            final PremiumResolver.AccountType type = resolver.accountType(name);
            return type != null ? type : PremiumResolver.AccountType.UNKNOWN;
        } catch (final Exception e) {
            getLogger().log(Level.WARNING, "Premium resolver hook failed for " + name, e);
            return PremiumResolver.AccountType.UNKNOWN;
        }
    }

    public void setPremiumResolver(@Nullable final PremiumResolver premiumResolver) {
        this.premiumResolver = premiumResolver;
    }

    private static void addProfile(final List<SenderInfo> profiles, final Set<UUID> seen, final ProfileLookup profile) {
        if (seen.add(profile.uuid())) {
            profiles.add(new DummySenderInfo(profile.uuid(), profile.name()));
        }
    }

    /**
     * Resolves a single name to a profile: username cache first, then Bedrock (Geyser), then the
     * mode-appropriate Mojang/offline UUID. Blocking - call it off the main thread.
     */
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
        final String bedrockPrefix = settings.getBedrockPrefix();
        if (settings.isBedrockSupport() && !bedrockPrefix.isEmpty() && name.startsWith(bedrockPrefix)) {
            final String gamertag = name.substring(bedrockPrefix.length());
            if (gamertag.isEmpty()) {
                return null;
            }
            return GeyserApiUtil.lookupBedrockProfile(gamertag, name);
        }

        // On an offline-mode (cracked) server, players are assigned offline UUIDs derived from their name,
        // not their Mojang UUID. Looking the name up against Mojang would return the PREMIUM UUID, which
        // never matches how the player actually joins, so they would stay blocked despite being "added".
        if (!isOnlineMode()) {
            return new ProfileLookup(offlineUUID(name), name);
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

        if (profileLookup == null && settings.isFallbackToOfflineUUID()) {
            // Use offline uuid
            return new ProfileLookup(offlineUUID(name), name);
        }

        return profileLookup;
    }

    protected static UUID offlineUUID(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
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

    /**
     * Starts the built-in Discord bot if it is enabled and a token is configured.
     * The login happens off the main thread.
     */
    public void restartDiscordBot() {
        if (discordBot != null) {
            discordBot.shutdown();
        }
        startDiscordBot();
    }

    public void startDiscordBot() {
        if (!settings.isDiscordBotEnabled()) {
            if (settings.isLinkingEnforced()) {
                getLogger().warning("Code-based linking is enabled, but the Discord bot is disabled - players will not be able to get a code! Enable the bot in the config.");
            }
            return;
        }
        final String token = settings.getDiscordBotToken();
        if (token == null || token.isBlank()) {
            getLogger().warning("The Discord bot is enabled, but no token is set in the config!");
            return;
        }

        discordBot = new DiscordBot(this, settings);
        async(() -> discordBot.start(token));

        if (settings.isLinkingEnforced()) {
            getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            getLogger().warning("Code-based linking is active. CRACKED/OFFLINE SERVER NOTICE:");
            getLogger().warning("  On a cracked server, usernames are not authenticated.");
            getLogger().warning("  Any player can join with ANY username, so:");
            getLogger().warning("  - Two players with the same name share the same UUID and");
            getLogger().warning("    whitelist entry (username-collision / whitelist bypass).");
            getLogger().warning("  - The linking code shown at join is the same for all players");
            getLogger().warning("    sharing that username (code-theft race condition).");
            getLogger().warning("  Mitigation: use an auth plugin (e.g. LimboAuth) and set");
            getLogger().warning("  'code-length' to 8+ in config.yml to reduce risk.");
            getLogger().warning("  See the 'linking:' block in config.yml for full details.");
            getLogger().warning("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }

    /**
     * Initializes the username cache. Call this once on enable (the data folder must exist).
     */
    public void initPlayerCache() {
        if (settings.isUsernameCacheEnabled()) {
            playerNameCache = new PlayerNameCache(this, settings.getUsernameCacheMaxEntries());
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
        if (settings.isLinkingEnforced() && discordBot != null) {
            // Already linked (primary UUID check, or name-based fallback for cracked/offline servers).
            if (discordBot.isLinked(sender.uuid()) || discordBot.isLinkedByName(sender.name())) {
                // Live role check: ask Discord for their current roles now, so if they already have the
                // whitelist role they are added immediately and let in on their next join - no waiting for
                // the (possibly delayed or missed) gateway role-update event.
                discordBot.checkRoleLive(sender.uuid(), sender.name());
                return settings.getMessage("linkingPendingApproval");
            }
            // null means the active-code pool is at capacity - bot flood in progress.
            final String code = discordBot.generateLinkCode(sender.uuid(), sender.name());
            if (code == null) {
                return settings.getMessage("linkingServerBusy");
            }
            return settings.getMessage("linkingKickMessage", "%CODE%", code, "%BOT%", discordBot.getBotName());
        }
        return settings.getKickMessage();
    }

    /**
     * @return {@code true} if the server itself is running in online mode (authenticated Mojang UUIDs),
     *         {@code false} if it is in offline/cracked mode (offline UUIDs derived from the username)
     */
    public abstract boolean isOnlineMode();

    public abstract File getDataFolder();

    @Nullable
    public InputStream getResource(final String name) {
        return this.getClass().getClassLoader().getResourceAsStream(name);
    }

    public abstract Logger getLogger();

    public abstract String getServerVersion();

    public abstract List<PluginDump> getPlugins();

    protected abstract void loadIcon(File file) throws Exception;

    protected abstract void kickPlayers();

    protected abstract File getPluginFile();

    protected abstract int getOnlinePlayers();

    protected abstract int getMaxPlayers();
}
