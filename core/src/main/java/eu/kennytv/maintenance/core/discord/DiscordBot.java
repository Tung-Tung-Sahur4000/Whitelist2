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
package eu.kennytv.maintenance.core.discord;

import eu.kennytv.maintenance.core.MaintenancePlugin;
import eu.kennytv.maintenance.core.Settings;
import eu.kennytv.maintenance.core.util.ProfileLookup;
import eu.kennytv.maintenance.core.util.SenderInfo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.Nullable;

/**
 * A self-contained Discord bot that manages the proxy whitelist from Discord.
 *
 * <p>It supports three flows:
 * <ul>
 *     <li><b>Manual</b> – staff run {@code /whitelist add|remove|list} (gated by a role or the Administrator
 *     permission).</li>
 *     <li><b>Account linking</b> – members run {@code /link <player>} to link their Discord account to a
 *     Minecraft account (one Minecraft account per Discord user), and {@code /unlink} to undo it.</li>
 *     <li><b>Role sync</b> – when a member who has linked an account gains the configured auto-whitelist role,
 *     their Minecraft account is automatically added to the whitelist; losing the role removes it again
 *     (configurable). Role holders are also reconciled on startup.</li>
 * </ul>
 *
 * <p>All whitelist changes go through {@link Settings}, so they are persisted and synced across proxies
 * via Redis (if enabled).
 */
public final class DiscordBot extends ListenerAdapter {

    private final MaintenancePlugin plugin;
    private final Settings settings;
    private final DiscordLinkManager linkManager;
    private final LinkCodeManager linkCodeManager;
    /**
     * Per-Discord-account timestamp (ms) of the last live role check, used to rate-limit on-demand
     * role lookups so a rejoin flood cannot hammer the Discord REST API.
     */
    private final Map<String, Long> lastLiveRoleCheck = new ConcurrentHashMap<>();
    private JDA jda;

    public DiscordBot(final MaintenancePlugin plugin, final Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.linkManager = new DiscordLinkManager(plugin);
        this.linkCodeManager = new LinkCodeManager(settings);
    }

    public LinkCodeManager linkCodeManager() {
        return linkCodeManager;
    }

    /**
     * Generates (or reuses) the active one-time linking code for a player.
     *
     * @return the code, or {@code null} if the active-code pool is at capacity (bot flood in progress);
     *         callers should display a "server busy" message rather than a code in that case
     */
    @Nullable
    public String generateLinkCode(final UUID uuid, final String name) {
        return linkCodeManager.generateCode(uuid, name);
    }

    /**
     * @return true if the Minecraft account is already linked to a Discord user
     */
    public boolean isLinked(final UUID uuid) {
        return linkManager.isLinked(uuid);
    }

    /**
     * Secondary link check by player name (case-insensitive). Used as a fallback on cracked/offline
     * servers where the same player may have a different UUID each session (UUID drift).
     *
     * @return true if any link exists for the given Minecraft username
     * @see DiscordLinkManager#isLinkedByName(String)
     */
    public boolean isLinkedByName(final String name) {
        return linkManager.isLinkedByName(name);
    }

    /**
     * Live, on-demand role check for an already-linked player who joined but is not whitelisted yet.
     *
     * <p>Instead of waiting for Discord to push a {@code GuildMemberRoleAdd} gateway event (which requires
     * the privileged Server Members Intent and can be delayed or missed entirely if the role was granted
     * while the bot was offline), this fetches the member's <b>current</b> roles directly via a REST lookup
     * and, if they now satisfy the whitelist requirement, adds them to the whitelist immediately. The player
     * is then let in on their <b>next</b> join — no manual reconcile, no waiting for the background sync.
     *
     * <p>The lookup runs fully asynchronously on JDA's callback thread, so it never blocks the join. Calls
     * are rate-limited per Discord account (see {@code live-role-check-cooldown-seconds}) so a rejoin flood
     * cannot spam the Discord API.
     *
     * @param uuid the joining player's UUID
     * @param name the joining player's name (used as a cracked/offline UUID-drift fallback)
     */
    public void checkRoleLive(final UUID uuid, final String name) {
        if (jda == null || !settings.isLinkingLiveRoleCheck()) {
            return;
        }

        // Resolve the linked Discord account (primary UUID index, then the name fallback for offline/cracked
        // servers where the session UUID may have drifted since link time).
        String discordId = linkManager.getDiscordId(uuid);
        if (discordId == null) {
            discordId = linkManager.getDiscordIdByName(name);
        }
        if (discordId == null) {
            return; // not linked - nothing to check (the caller shows the link code instead)
        }

        final ProfileLookup link = linkManager.getLink(discordId);
        if (link == null) {
            return;
        }

        // Nothing a live check could change if the linked account is already whitelisted.
        if (settings.isWhitelisted(link.uuid())) {
            return;
        }

        // Per-account cooldown so repeated rejoins don't hammer the REST API.
        final long now = System.currentTimeMillis();
        final long cooldownMs = Math.max(0, settings.getLinkingLiveRoleCheckCooldownSeconds()) * 1000L;
        final Long last = lastLiveRoleCheck.get(discordId);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastLiveRoleCheck.put(discordId, now);

        final Guild guild = resolveGuild();
        if (guild == null) {
            // Without a resolvable guild we cannot look up the member. Role add / reconcile paths carry their
            // own guild context; nudge the owner to set guild-id so the live check can work.
            return;
        }

        final String resolvedDiscordId = discordId;
        guild.retrieveMemberById(resolvedDiscordId).queue(member -> {
            // Mirror the whitelist decision used by the code-link flow: whitelist when no role is required,
            // when role sync is off, or when the member currently holds the auto-whitelist role.
            if (!settings.isLinkingRequireRole() || !roleSyncEnabled() || hasAutoRole(member)) {
                if (settings.addWhitelistedPlayer(link.uuid(), link.name())) {
                    plugin.getLogger().info("Live role check: whitelisted " + link.name()
                            + " (they already hold the whitelist role) - they can join on their next attempt.");
                }
            }
        }, error -> {
            // UNKNOWN_MEMBER simply means they are not in the guild (yet); anything else is a transient API
            // error. Either way there is nothing to do - allow a retry sooner by clearing the cooldown stamp.
            lastLiveRoleCheck.remove(resolvedDiscordId);
        });
    }

    /**
     * @return the bot's display name, or a fallback if it is not connected yet
     */
    public String getBotName() {
        try {
            if (jda != null && jda.getSelfUser() != null) {
                return jda.getSelfUser().getName();
            }
        } catch (final Exception ignored) {
            // Not ready yet
        }
        return "the Discord bot";
    }

    /**
     * Logs the bot in and registers its slash commands. Should be called off the main thread, as the
     * initial connection blocks for a short moment.
     *
     * @param token the Discord bot token
     */
    public void start(final String token) {
        try {
            // DIRECT_MESSAGES lets the bot receive linking codes DMed to it (DM content needs no privileged intent).
            final EnumSet<GatewayIntent> intents = EnumSet.of(GatewayIntent.DIRECT_MESSAGES);
            if (roleSyncEnabled()) {
                // Receiving role changes and loading members requires the (privileged) Server Members Intent.
                intents.add(GatewayIntent.GUILD_MEMBERS);
            }

            jda = JDABuilder.createLight(token, intents)
                    .setActivity(Activity.watching("the whitelist"))
                    .addEventListeners(this)
                    .build();
            plugin.getLogger().info("Starting Discord bot integration...");
        } catch (final Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start the Discord bot - is the token correct?", e);
            jda = null;
        }
    }

    public void shutdown() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    // --- Lifecycle / command registration ---

    @Override
    public void onReady(final ReadyEvent event) {
        final SlashCommandData whitelistCommand = Commands.slash("whitelist", "Manage the server whitelist")
                .addSubcommands(
                        new SubcommandData("add", "Add a player to the whitelist")
                                .addOption(OptionType.STRING, "player", "Player name, UUID or .Gamertag for Bedrock", true),
                        new SubcommandData("remove", "Remove a player from the whitelist")
                                .addOption(OptionType.STRING, "player", "Player name or UUID", true),
                        new SubcommandData("list", "List all whitelisted players")
                );
        // /link (name-based) is intentionally absent. Linking by name allows anyone to claim any account
        // without proving ownership. The only supported link path is the in-game code flow: join the
        // server, receive a code, DM it here.
        // /unlink is ADMIN-ONLY by design. If players could unlink themselves, anyone who controls a
        // Discord account could move the whitelist grant to a different Minecraft account (impersonation /
        // account takeover). Unlinking is the staff recovery path for a player who lost access to their
        // Minecraft OR Discord account: unlink by 'discord' (lost Minecraft account) or by 'minecraft'
        // (lost Discord account), then the player links the new account through the normal code flow.
        final SlashCommandData unlinkCommand = Commands.slash("unlink", "Admin: unlink an account so a new one can be linked")
                .addSubcommands(
                        new SubcommandData("discord", "Unlink the Minecraft account linked to a Discord user")
                                .addOption(OptionType.USER, "user", "Discord user to unlink", true),
                        new SubcommandData("minecraft", "Unlink the Discord account linked to a Minecraft player")
                                .addOption(OptionType.STRING, "player", "Minecraft name or UUID to unlink", true)
                );
        final SlashCommandData lookupCommand = Commands.slash("lookup", "Admin: look up the account linked to a player or Discord user")
                .addSubcommands(
                        new SubcommandData("minecraft", "Find which Discord account is linked to a Minecraft player")
                                .addOption(OptionType.STRING, "player", "Minecraft name or UUID", true),
                        new SubcommandData("discord", "Find which Minecraft account is linked to a Discord user")
                                .addOption(OptionType.USER, "user", "Discord user to look up", true)
                );

        final String guildId = settings.getDiscordGuildId();
        final boolean guildIdConfigured = guildId != null && !guildId.isEmpty();
        if (guildIdConfigured) {
            final Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(whitelistCommand, unlinkCommand, lookupCommand).queue();
                plugin.getLogger().info("Registered Discord slash commands for guild " + guild.getName() + ".");
                reconcileRoleMembers(guild);
                return;
            }
        }

        jda.updateCommands().addCommands(whitelistCommand, unlinkCommand, lookupCommand).queue();
        plugin.getLogger().info("Registered global Discord slash commands (may take up to an hour to appear).");

        // Emit EXACTLY ONE diagnostic below, so the log never contradicts itself (previously a set-but-unknown
        // guild-id logged both "could not be found" AND "no guild-id is set").
        if (guildIdConfigured) {
            // A guild-id WAS set, but the bot is not a member of that guild - the id is wrong, or the bot was
            // never invited to that specific server. Say that precisely (not "no guild-id is set") and print the
            // servers the bot actually IS in, so the owner can copy the correct id instead of guessing.
            plugin.getLogger().warning("Configured Discord guild-id '" + guildId + "' was not found among the "
                    + jda.getGuilds().size() + " server(s) this bot is in, so commands were registered globally "
                    + "(up to ~1h to appear) and membership can't be verified. Check that the bot was invited to "
                    + "that server and that 'guild-id' is the SERVER (guild) id - not a channel, role, user or "
                    + "application id. In Discord: enable Developer Mode, right-click the server icon, 'Copy Server ID'.");
            logCurrentGuilds();
        } else if ((settings.isLinkingEnforced() || roleSyncEnabled()) && jda.getGuilds().size() != 1) {
            // With no guild-id set, guild membership cannot be reliably resolved when the bot is in more than
            // one server (resolveGuild() returns null), so the code-link flow falls back to "an admin still
            // needs to give you the role" instead of verifying membership/role at link time. Strongly recommend
            // setting guild-id when linking or role sync is in use.
            plugin.getLogger().warning("No 'guild-id' is set in config.yml and the bot is in "
                    + jda.getGuilds().size() + " servers. Set 'discord-bot.guild-id' to your server's ID so "
                    + "linking and role sync can verify membership reliably and slash commands appear instantly.");
            logCurrentGuilds();
        }

        if (roleSyncEnabled()) {
            for (final Guild guild : jda.getGuilds()) {
                reconcileRoleMembers(guild);
            }
        }
    }

    // --- Slash command handling ---

    @Override
    public void onSlashCommandInteraction(final SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "whitelist" -> handleWhitelistCommand(event);
            case "link" -> handleLink(event);
            case "unlink" -> handleUnlink(event);
            case "lookup" -> handleLookup(event);
            default -> {
            }
        }
    }

    private void handleWhitelistCommand(final SlashCommandInteractionEvent event) {
        final Member member = event.getMember();
        if (member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        if (!isAdmin(member)) {
            event.reply("You do not have permission to manage the whitelist.").setEphemeral(true).queue();
            return;
        }

        final String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }

        switch (subcommand) {
            case "add" -> handleAdd(event);
            case "remove" -> handleRemove(event);
            case "list" -> handleList(event);
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleAdd(final SlashCommandInteractionEvent event) {
        final OptionMapping option = event.getOption("player");
        if (option == null) {
            event.reply("Missing player argument.").setEphemeral(true).queue();
            return;
        }

        final String input = option.getAsString();
        // Ephemeral: the staff member's command and typed input stay private to them.
        event.deferReply(true).queue();
        // A UUID resolves to exactly one account; a name resolves to every account it could connect as
        // (premium and/or cracked) so the player is whitelisted however they log in.
        final UUID uuid = parseUuid(input);
        if (uuid != null) {
            plugin.getOfflinePlayer(uuid).whenComplete((selected, ex) ->
                    finishAdd(event, input, ex, selected == null ? List.of() : List.of(selected)));
        } else {
            plugin.getOfflinePlayers(input).whenComplete((profiles, ex) -> finishAdd(event, input, ex, profiles));
        }
    }

    private void finishAdd(final SlashCommandInteractionEvent event, final String input,
                           @Nullable final Throwable ex, @Nullable final List<SenderInfo> profiles) {
        if (ex != null || profiles == null || profiles.isEmpty()) {
            event.getHook().sendMessage("❌ Could not find a player named `" + input + "`.").queue();
            return;
        }

        boolean added = false;
        final String displayName = profiles.get(0).name();
        for (final SenderInfo selected : profiles) {
            if (settings.addWhitelistedPlayer(selected.uuid(), selected.name())) {
                added = true;
            }
        }

        if (added) {
            // Quiet confirmation only the staff member sees...
            event.getHook().sendMessage("✅ Added `" + displayName + "` to the whitelist.").queue();
            // ...and a clean public embed so it looks automatic - no visible command, just the result.
            event.getChannel().sendMessageEmbeds(whitelistedEmbed(displayName)).queue();
        } else {
            event.getHook().sendMessage("ℹ️ `" + displayName + "` is already whitelisted.").queue();
        }
    }

    private void handleRemove(final SlashCommandInteractionEvent event) {
        final OptionMapping option = event.getOption("player");
        if (option == null) {
            event.reply("Missing player argument.").setEphemeral(true).queue();
            return;
        }

        final String input = option.getAsString();
        final UUID uuid = parseUuid(input);
        final boolean removed = uuid != null
                ? settings.removeWhitelistedPlayer(uuid)
                : settings.removeWhitelistedPlayer(input);

        if (removed) {
            event.reply("✅ Removed `" + input + "` from the whitelist.").setEphemeral(true).queue();
        } else {
            event.reply("❌ `" + input + "` is not on the whitelist.").setEphemeral(true).queue();
        }
    }

    private void handleList(final SlashCommandInteractionEvent event) {
        final Map<UUID, String> players = settings.getWhitelistedPlayers();
        if (players.isEmpty()) {
            event.reply("The whitelist is empty.").setEphemeral(true).queue();
            return;
        }

        final StringBuilder builder = new StringBuilder("**Whitelisted players (").append(players.size()).append("):**\n");
        for (final Map.Entry<UUID, String> entry : players.entrySet()) {
            final String line = "• " + entry.getValue() + " (" + entry.getKey() + ")\n";
            // Discord messages are capped at 2000 characters
            if (builder.length() + line.length() > 1900) {
                builder.append("… and more");
                break;
            }
            builder.append(line);
        }
        event.reply(builder.toString()).setEphemeral(true).queue();
    }

    private void handleLink(final SlashCommandInteractionEvent event) {
        if (!settings.isDiscordAllowLinking()) {
            event.reply("Account linking is disabled on this server.").setEphemeral(true).queue();
            return;
        }

        // Security: when code-based linking is active, do NOT allow linking by typing a name (anyone could claim
        // someone else's account). The in-game code proves ownership, so direct the user there instead.
        if (settings.isLinkingEnforced()) {
            event.reply("To link, just join the server - you'll be shown a code to DM me here. "
                    + "(Linking by name is disabled while code linking is on, so nobody can claim an account that isn't theirs.)")
                    .setEphemeral(true).queue();
            return;
        }

        final Member member = event.getMember();
        if (member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        final OptionMapping option = event.getOption("player");
        if (option == null) {
            event.reply("Missing player argument.").setEphemeral(true).queue();
            return;
        }

        final String input = option.getAsString();
        event.deferReply().setEphemeral(true).queue();
        lookup(input).whenComplete((selected, ex) -> {
            if (ex != null || selected == null) {
                event.getHook().sendMessage("❌ Could not find a player named `" + input + "`.").queue();
                return;
            }

            if (linkManager.isLinkedToOther(selected.uuid(), member.getId())) {
                event.getHook().sendMessage("❌ `" + selected.name() + "` is already linked to another Discord account.").queue();
                return;
            }

            // If this Discord user was previously linked to a DIFFERENT Minecraft account, remove that
            // old account from the whitelist. Without this, a user could link account A (whitelisted),
            // then re-link to account B (also whitelisted), leaving account A permanently whitelisted.
            final ProfileLookup previousLink = linkManager.getLink(member.getId());
            if (previousLink != null && !previousLink.uuid().equals(selected.uuid())) {
                settings.removeWhitelistedPlayer(previousLink.uuid());
            }

            linkManager.link(member.getId(), selected.uuid(), selected.name());

            final StringBuilder reply = new StringBuilder("✅ Linked your Discord account to `").append(selected.name()).append("`.");
            if (hasAutoRole(member)) {
                settings.addWhitelistedPlayer(selected.uuid(), selected.name());
                reply.append(" You have the whitelist role, so you have been added to the whitelist!");
            } else if (roleSyncEnabled()) {
                reply.append(" You will be whitelisted automatically once you receive the whitelist role.");
            }
            event.getHook().sendMessage(reply.toString()).queue();
        });
    }

    private void handleUnlink(final SlashCommandInteractionEvent event) {
        // Admin-only: self-service unlinking would let anyone who controls a Discord account move the
        // whitelist to a different Minecraft account. This is the staff recovery path only.
        final Member member = event.getMember();
        if (member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        if (!isAdmin(member)) {
            event.reply("You do not have permission to unlink accounts. Ask a staff member if you lost access to your account.").setEphemeral(true).queue();
            return;
        }

        final String subcommand = event.getSubcommandName();
        final String discordId;
        if ("discord".equals(subcommand)) {
            final OptionMapping option = event.getOption("user");
            if (option == null) {
                event.reply("Missing user argument.").setEphemeral(true).queue();
                return;
            }
            discordId = option.getAsUser().getId();
        } else if ("minecraft".equals(subcommand)) {
            final OptionMapping option = event.getOption("player");
            if (option == null) {
                event.reply("Missing player argument.").setEphemeral(true).queue();
                return;
            }
            final String input = option.getAsString();
            final UUID uuid = parseUuid(input);
            discordId = uuid != null ? linkManager.getDiscordId(uuid) : linkManager.getDiscordIdByName(input);
            if (discordId == null) {
                event.reply("❌ `" + sanitizeName(input) + "` is not linked to any Discord account.").setEphemeral(true).queue();
                return;
            }
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }

        final ProfileLookup removed = linkManager.unlink(discordId);
        if (removed == null) {
            event.reply("❌ <@" + discordId + "> does not have a linked Minecraft account.").setEphemeral(true).queue();
            return;
        }

        // A link that is removed should also lose any whitelist access it granted.
        settings.removeWhitelistedPlayer(removed.uuid());
        event.reply("✅ Unlinked `" + sanitizeName(removed.name()) + "` (was linked to <@" + discordId
                + ">) and removed it from the whitelist. A new account can now be linked.").setEphemeral(true).queue();
    }

    private void handleLookup(final SlashCommandInteractionEvent event) {
        final Member member = event.getMember();
        if (member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }
        if (!isAdmin(member)) {
            event.reply("You do not have permission to look up links.").setEphemeral(true).queue();
            return;
        }

        final String subcommand = event.getSubcommandName();
        if ("minecraft".equals(subcommand)) {
            final OptionMapping option = event.getOption("player");
            if (option == null) {
                event.reply("Missing player argument.").setEphemeral(true).queue();
                return;
            }
            final String input = option.getAsString();
            final UUID uuid = parseUuid(input);
            final String discordId = uuid != null ? linkManager.getDiscordId(uuid) : linkManager.getDiscordIdByName(input);
            if (discordId == null) {
                event.reply("❌ `" + sanitizeName(input) + "` is not linked to any Discord account.").setEphemeral(true).queue();
                return;
            }
            event.reply("🔗 `" + sanitizeName(input) + "` is linked to <@" + discordId + "> (`" + discordId + "`).")
                    .setEphemeral(true).queue();
        } else if ("discord".equals(subcommand)) {
            final OptionMapping option = event.getOption("user");
            if (option == null) {
                event.reply("Missing user argument.").setEphemeral(true).queue();
                return;
            }
            final User user = option.getAsUser();
            final ProfileLookup link = linkManager.getLink(user.getId());
            if (link == null) {
                event.reply("❌ <@" + user.getId() + "> is not linked to any Minecraft account.").setEphemeral(true).queue();
                return;
            }
            event.reply("🔗 <@" + user.getId() + "> is linked to `" + sanitizeName(link.name()) + "` (`" + link.uuid() + "`).")
                    .setEphemeral(true).queue();
        } else {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    // --- Role sync ---

    @Override
    public void onGuildMemberRoleAdd(final GuildMemberRoleAddEvent event) {
        if (!roleSyncEnabled() || !containsAutoRole(event.getRoles())) {
            return;
        }

        final ProfileLookup link = linkManager.getLink(event.getMember().getId());
        if (link == null) {
            // Common admin confusion: the member was given the role but never linked a Minecraft account,
            // so there is nothing to whitelist. Log it so this is diagnosable instead of silently doing nothing.
            plugin.getLogger().info("Discord user " + event.getMember().getId()
                    + " received the whitelist role but has not linked a Minecraft account yet; nothing to whitelist.");
            return;
        }
        if (settings.addWhitelistedPlayer(link.uuid(), link.name())) {
            plugin.getLogger().info("Auto-whitelisted " + link.name() + " after they received the whitelist role.");
        }
    }

    @Override
    public void onGuildMemberRoleRemove(final GuildMemberRoleRemoveEvent event) {
        if (!roleSyncEnabled() || !settings.isDiscordRemoveOnRoleLoss() || !containsAutoRole(event.getRoles())) {
            return;
        }

        final ProfileLookup link = linkManager.getLink(event.getMember().getId());
        if (link != null && settings.removeWhitelistedPlayer(link.uuid())) {
            plugin.getLogger().info("Removed " + link.name() + " from the whitelist after they lost the whitelist role.");
        }
    }

    @Override
    public void onGuildMemberRemove(final GuildMemberRemoveEvent event) {
        // A member leaving the server should lose whitelist access the same way losing the role does.
        // Discord does NOT fire a role-remove event when a member leaves, so without this handler a
        // whitelisted player could simply leave the Discord and keep their whitelist forever.
        // The link record is kept so that if they rejoin and regain the role they are re-whitelisted.
        if (!roleSyncEnabled() || !settings.isDiscordRemoveOnRoleLoss()) {
            return;
        }

        final ProfileLookup link = linkManager.getLink(event.getUser().getId());
        if (link != null && settings.removeWhitelistedPlayer(link.uuid())) {
            plugin.getLogger().info("Removed " + link.name() + " from the whitelist after they left the Discord server.");
        }
    }

    /**
     * Logs the servers the bot is currently a member of (name -> id). Printed when the configured
     * {@code guild-id} is missing/unknown or unset, so the owner can copy the correct id from the log
     * instead of guessing why linking, role sync or instant slash commands are not working.
     */
    private void logCurrentGuilds() {
        final List<Guild> guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            plugin.getLogger().warning("The bot is not a member of any Discord server yet. Invite it with BOTH the "
                    + "'bot' and 'applications.commands' scopes, then restart the server.");
            return;
        }
        plugin.getLogger().warning("This bot is currently in the following server(s) - use the id of the one you want as 'guild-id':");
        for (final Guild guild : guilds) {
            plugin.getLogger().warning("  - " + guild.getName() + "  ->  guild-id: " + guild.getId());
        }
    }

    private void reconcileRoleMembers(final Guild guild) {
        if (!roleSyncEnabled()) {
            return;
        }
        final Role role = guild.getRoleById(settings.getDiscordAutoWhitelistRoleId());
        if (role == null) {
            return;
        }

        guild.loadMembers().onSuccess(members -> {
            for (final Member member : members) {
                final ProfileLookup link = linkManager.getLink(member.getId());
                if (link == null) {
                    continue;
                }

                if (member.getRoles().contains(role)) {
                    settings.addWhitelistedPlayer(link.uuid(), link.name());
                } else if (settings.isDiscordRemoveOnRoleLoss()) {
                    settings.removeWhitelistedPlayer(link.uuid());
                }
            }
            plugin.getLogger().info("Reconciled whitelist with the '" + role.getName() + "' role in " + guild.getName() + ".");
        }).onError(error ->
                plugin.getLogger().warning("Could not load members of " + guild.getName()
                        + " for role sync. Make sure the 'Server Members Intent' is enabled for the bot."));
    }

    // --- Code-based linking (DM the bot a code) ---

    @Override
    public void onMessageReceived(final MessageReceivedEvent event) {
        // Only react to direct messages from real users.
        if (!event.isFromType(ChannelType.PRIVATE) || event.getAuthor().isBot()) {
            return;
        }

        final String discordId = event.getAuthor().getId();
        final String content = event.getMessage().getContentRaw().trim();
        // Sanitize: only treat messages that are exactly the expected number of digits as a code attempt.
        // Anything else is ignored, so the bot can't be spammed into doing work.
        if (!content.matches("\\d{" + Math.max(4, settings.getLinkCodeLength()) + "}")) {
            return;
        }

        if (linkCodeManager.isRateLimited(discordId)) {
            reply(event, "⏳ Too many attempts. Please wait a minute and try again.");
            return;
        }

        // Peek first (without consuming) so that a rejection for reasons unrelated to the code
        // (e.g. the Minecraft account is already claimed) doesn't burn the code and force the
        // legitimate player to rejoin just to get a fresh one.
        final LinkCodeManager.PendingLink peeked = linkCodeManager.lookupCode(content);
        if (peeked == null) {
            linkCodeManager.recordFailedAttempt(discordId);
            reply(event, "❌ That code is invalid or has expired. Get a new one in-game and try again.");
            return;
        }

        // One Minecraft account can only be linked to one Discord user. Check before consuming.
        if (linkManager.isLinkedToOther(peeked.uuid(), discordId)) {
            reply(event, "❌ That Minecraft account is already linked to a different Discord account.");
            return;
        }

        // ...and one Discord user can only be linked to one Minecraft account. Without this, a single
        // Discord user could DM a code for account A, then later DM a code for account B: the link record
        // would be silently overwritten while account A stayed on the whitelist, letting one Discord
        // account whitelist multiple Minecraft accounts. Peek-check here (no code burned) so re-DMing the
        // SAME account's code is still a harmless no-op; the authoritative, atomic check is linkExclusive.
        final ProfileLookup existingLink = linkManager.getLink(discordId);
        if (existingLink != null && !existingLink.uuid().equals(peeked.uuid())) {
            reply(event, "❌ Your Discord account is already linked to `" + sanitizeName(existingLink.name())
                    + "`. If you lost access to that account, ask a staff member to unlink it for you - "
                    + "you cannot switch accounts yourself.");
            return;
        }

        // All pre-checks passed — now atomically consume the code.
        final LinkCodeManager.PendingLink pending = linkCodeManager.verifyAndConsume(content);
        if (pending == null) {
            // Code expired or was consumed by a concurrent request in the instant between peek and consume.
            linkCodeManager.recordFailedAttempt(discordId);
            reply(event, "❌ That code just expired. Get a new one in-game and try again.");
            return;
        }
        linkCodeManager.clearAttempts(discordId);

        // Link atomically — linkExclusive re-checks BOTH directions (this Minecraft account not taken by
        // another Discord user, AND this Discord user not already linked to a different account) under the
        // same lock as the write. This closes the TOCTOU windows where two requests racing between the
        // peeks above and the write could otherwise silently overwrite an existing link.
        final DiscordLinkManager.LinkResult result = linkManager.linkExclusive(discordId, pending.uuid(), pending.name());
        switch (result) {
            case ACCOUNT_TAKEN -> {
                reply(event, "❌ That Minecraft account was just linked to another Discord account. Contact a staff member if this was not you.");
                return;
            }
            case ALREADY_LINKED_TO_OTHER_ACCOUNT -> {
                reply(event, "❌ Your Discord account is already linked to a different Minecraft account. Ask a staff member to unlink it if you lost access - you cannot switch accounts yourself.");
                return;
            }
            case LINKED -> {
                // proceed
            }
        }
        finishCodeLink(event, discordId, pending);
    }

    private void finishCodeLink(final MessageReceivedEvent event, final String discordId, final LinkCodeManager.PendingLink pending) {
        final String safeName = sanitizeName(pending.name());
        final Guild guild = resolveGuild();

        // When a guild is configured, always verify the sender is actually a member of it before
        // granting any access. This closes two gaps:
        //   1. Non-guild members whitelisted immediately when require-role=false.
        //   2. UNKNOWN_MEMBER (not in guild) was previously swallowed as "could not verify roles",
        //      leaving the account linked with no clear rejection for the server owner.
        if (guild != null) {
            guild.retrieveMemberById(discordId).queue(member -> {
                // Member confirmed — whitelist immediately (no role required) or check the role.
                if (!settings.isLinkingRequireRole() || !roleSyncEnabled() || hasAutoRole(member)) {
                    settings.addWhitelistedPlayer(pending.uuid(), pending.name());
                    reply(event, "✅ Linked and whitelisted `" + safeName + "` - you can join now!");
                } else {
                    reply(event, "✅ Linked `" + safeName + "`. You'll be whitelisted once you receive the whitelist role.");
                }
            }, error -> {
                if (error instanceof ErrorResponseException err
                        && err.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                    // The sender is not a member of the configured guild: reject the whitelist.
                    // The link record is intentionally kept so that subsequent attempts show
                    // "already linked" rather than re-issuing a code. The player needs a staff
                    // member to grant them the whitelist role once they join the guild, or an
                    // admin can /unlink and have them start fresh.
                    // NOTE: do NOT direct them to "get a fresh code" — the link is already stored
                    // and they will see the linkingPendingApproval message on the next join, not a code.
                    reply(event, "❌ You must join the Discord server before you can be whitelisted. Join the Discord server, then ask a staff member to give you the whitelist role.");
                } else {
                    // Transient API error: linked but not whitelisted yet — an admin can /whitelist add manually.
                    reply(event, "✅ Linked `" + safeName + "`. (Could not verify your server membership right now — contact an admin to be whitelisted.)");
                }
            });
            return;
        }

        // No guild could be resolved (no guild-id set, and the bot is in zero or several guilds), so we
        // CANNOT verify that this Discord user is actually a member of your server.
        if (!settings.isLinkingRequireRole() || !roleSyncEnabled()) {
            // Without a membership check and without a required role, whitelisting here would let ANY
            // Discord user who shares a server with the bot whitelist an account just by DMing a valid
            // code. Refuse and tell the owner to configure guild-id instead of granting access blindly.
            // (Role-based whitelisting does not need this branch: role add / reconcile events carry
            // their own guild context, so they still verify membership implicitly.)
            reply(event, "✅ Linked `" + safeName + "`, but you could not be whitelisted automatically because "
                    + "the Discord server is not fully configured. Please contact an admin.");
            plugin.getLogger().warning("Linked " + pending.name() + " but refused to auto-whitelist: no guild could "
                    + "be resolved, so server membership cannot be verified. Set 'discord-bot.guild-id' in config.yml "
                    + "(required for linking to whitelist safely when 'require-role' is off).");
        } else {
            reply(event, "✅ Linked `" + safeName + "`. An admin still needs to give you the whitelist role.");
        }
    }

    private void reply(final MessageReceivedEvent event, final String message) {
        // Never ping anyone, even if a (sanitized) name somehow contained mention-like text.
        event.getChannel().sendMessage(message).setAllowedMentions(Collections.emptyList()).queue();
    }

    @Nullable
    private Guild resolveGuild() {
        final String guildId = settings.getDiscordGuildId();
        if (guildId != null && !guildId.isEmpty()) {
            return jda.getGuildById(guildId);
        }
        final List<Guild> guilds = jda.getGuilds();
        return guilds.size() == 1 ? guilds.get(0) : null;
    }

    private String sanitizeName(final String name) {
        // Strip anything that could break out of Discord markdown or inject mentions/backticks.
        return name.replaceAll("[^A-Za-z0-9_ .]", "");
    }

    /**
     * The public "X has been whitelisted" announcement embed. Kept clean so the action looks automatic.
     */
    private MessageEmbed whitelistedEmbed(final String name) {
        final String safe = sanitizeName(name);
        return new EmbedBuilder()
                .setColor(0x57F287)
                .setDescription("✅ **" + safe + "** has been whitelisted!")
                .setThumbnail("https://mc-heads.net/avatar/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/100")
                .build();
    }

    // --- Helpers ---

    private boolean roleSyncEnabled() {
        final String id = settings.getDiscordAutoWhitelistRoleId();
        return id != null && !id.isEmpty();
    }

    private boolean containsAutoRole(final java.util.List<Role> roles) {
        final String id = settings.getDiscordAutoWhitelistRoleId();
        return roles.stream().anyMatch(role -> role.getId().equals(id));
    }

    private boolean hasAutoRole(final Member member) {
        if (!roleSyncEnabled()) {
            return false;
        }
        final String id = settings.getDiscordAutoWhitelistRoleId();
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(id));
    }

    private boolean isAdmin(final Member member) {
        final String roleId = settings.getDiscordWhitelistRoleId();
        if (roleId != null && !roleId.isEmpty()) {
            return member.getRoles().stream().anyMatch(role -> role.getId().equals(roleId));
        }
        return member.hasPermission(Permission.ADMINISTRATOR);
    }

    private CompletableFuture<@Nullable SenderInfo> lookup(final String input) {
        final UUID uuid = parseUuid(input);
        return uuid != null ? plugin.getOfflinePlayer(uuid) : plugin.getOfflinePlayer(input);
    }

    @Nullable
    private UUID parseUuid(final String input) {
        if (input.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(input);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
