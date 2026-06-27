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

import eu.kennytv.maintenance.core.proxy.MaintenanceProxyPlugin;
import eu.kennytv.maintenance.core.proxy.SettingsProxy;
import eu.kennytv.maintenance.core.proxy.util.ProfileLookup;
import eu.kennytv.maintenance.core.util.SenderInfo;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
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
 * <p>All whitelist changes go through {@link SettingsProxy}, so they are persisted and synced across proxies
 * via Redis (if enabled).
 */
public final class DiscordBot extends ListenerAdapter {

    private final MaintenanceProxyPlugin plugin;
    private final SettingsProxy settings;
    private final DiscordLinkManager linkManager;
    private final LinkCodeManager linkCodeManager;
    private JDA jda;

    public DiscordBot(final MaintenanceProxyPlugin plugin, final SettingsProxy settings) {
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
     */
    public String generateLinkCode(final UUID uuid, final String name) {
        return linkCodeManager.generateCode(uuid, name);
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
        final SlashCommandData linkCommand = Commands.slash("link", "Link your Discord account to a Minecraft account")
                .addOption(OptionType.STRING, "player", "Your Minecraft name, UUID or .Gamertag for Bedrock", true);
        final SlashCommandData unlinkCommand = Commands.slash("unlink", "Unlink your Discord account from Minecraft");

        final String guildId = settings.getDiscordGuildId();
        if (guildId != null && !guildId.isEmpty()) {
            final Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(whitelistCommand, linkCommand, unlinkCommand).queue();
                plugin.getLogger().info("Registered Discord slash commands for guild " + guild.getName() + ".");
                reconcileRoleMembers(guild);
                return;
            }
            plugin.getLogger().warning("Configured Discord guild-id '" + guildId + "' could not be found; registering commands globally instead.");
        }

        jda.updateCommands().addCommands(whitelistCommand, linkCommand, unlinkCommand).queue();
        plugin.getLogger().info("Registered global Discord slash commands (may take up to an hour to appear).");

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
        event.deferReply().queue();
        lookup(input).whenComplete((selected, ex) -> {
            if (ex != null || selected == null) {
                event.getHook().sendMessage("❌ Could not find a player named `" + input + "`.").queue();
                return;
            }

            final boolean added = settings.addWhitelistedPlayer(selected.uuid(), selected.name());
            event.getHook().sendMessage(added
                    ? "✅ Added `" + selected.name() + "` to the whitelist."
                    : "ℹ️ `" + selected.name() + "` is already whitelisted.").queue();
        });
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
            event.reply("✅ Removed `" + input + "` from the whitelist.").queue();
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
        final Member member = event.getMember();
        if (member == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        final ProfileLookup removed = linkManager.unlink(member.getId());
        if (removed == null) {
            event.reply("You do not have a linked Minecraft account.").setEphemeral(true).queue();
            return;
        }

        // A linked account that loses the link should also lose whitelist access gained through it.
        settings.removeWhitelistedPlayer(removed.uuid());
        event.reply("✅ Unlinked your Discord account from `" + removed.name() + "` and removed it from the whitelist.").setEphemeral(true).queue();
    }

    // --- Role sync ---

    @Override
    public void onGuildMemberRoleAdd(final GuildMemberRoleAddEvent event) {
        if (!roleSyncEnabled() || !containsAutoRole(event.getRoles())) {
            return;
        }

        final ProfileLookup link = linkManager.getLink(event.getMember().getId());
        if (link != null && settings.addWhitelistedPlayer(link.uuid(), link.name())) {
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

        final LinkCodeManager.PendingLink pending = linkCodeManager.verifyAndConsume(content);
        if (pending == null) {
            linkCodeManager.recordFailedAttempt(discordId);
            reply(event, "❌ That code is invalid or has expired. Get a new one in-game and try again.");
            return;
        }
        linkCodeManager.clearAttempts(discordId);

        // One Minecraft account can only be linked to one Discord user.
        if (linkManager.isLinkedToOther(pending.uuid(), discordId)) {
            reply(event, "❌ That Minecraft account is already linked to a different Discord account.");
            return;
        }

        linkManager.link(discordId, pending.uuid(), pending.name());
        finishCodeLink(event, discordId, pending);
    }

    private void finishCodeLink(final MessageReceivedEvent event, final String discordId, final LinkCodeManager.PendingLink pending) {
        final String safeName = sanitizeName(pending.name());
        if (!settings.isLinkingRequireRole() || !roleSyncEnabled()) {
            settings.addWhitelistedPlayer(pending.uuid(), pending.name());
            reply(event, "✅ Linked and whitelisted `" + safeName + "` - you can join now!");
            return;
        }

        final Guild guild = resolveGuild();
        if (guild == null) {
            reply(event, "✅ Linked `" + safeName + "`. An admin still needs to give you the whitelist role.");
            return;
        }

        guild.retrieveMemberById(discordId).queue(member -> {
            if (hasAutoRole(member)) {
                settings.addWhitelistedPlayer(pending.uuid(), pending.name());
                reply(event, "✅ Linked and whitelisted `" + safeName + "` - you can join now!");
            } else {
                reply(event, "✅ Linked `" + safeName + "`. You'll be whitelisted once you receive the whitelist role.");
            }
        }, error -> reply(event, "✅ Linked `" + safeName + "`. (Could not verify your roles right now.)"));
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
