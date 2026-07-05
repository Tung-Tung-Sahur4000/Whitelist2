# ProxyWhitelist

ProxyWhitelist is a network-wide whitelist plugin for **BungeeCord** and **Velocity** proxies, and it also
runs directly on a single **Paper** (Spigot/Bukkit) server. When the whitelist is enabled, only whitelisted
players may join the network (or specific backend servers), everyone else is shown a configurable kick
message / MOTD.

It is a proxy-only fork of [kennytv's Maintenance plugin](https://github.com/kennytv/Maintenance), refocused
around whitelisting and extended with Bedrock support and a built-in Discord bot.

## Features
* Toggle the whitelist globally or per backend server (`/whitelist <on/off> [server]`)
* Manage whitelisted players with `/whitelist <add/remove/list>`
* Custom MOTD, server icon, and player-count message shown while the whitelist is active
* Start-/end-/schedule-timers to automatically enable/disable the whitelist
* Link multiple proxy instances through Redis, so the whitelist status and whitelisted players stay in sync
* **Bedrock / Geyser support** – add Bedrock players by their gamertag (resolved via the Geyser global API
  into the Floodgate UUID). Just prefix the gamertag with the configured Bedrock prefix, e.g. `/whitelist add .Notch`.
* **Built-in Discord bot** – run a Discord bot straight from the plugin (just provide a bot token) so staff can
  run `/whitelist add|remove|list` from Discord and have it applied to the server whitelist instantly
  (and synced across proxies if Redis is enabled).

## Commands & permissions
The main command is `/whitelist` (aliases: `/pwhitelist`, `/pwl`, and the legacy `/maintenance`, `/mt`).
Permission nodes are unchanged from the original plugin (`maintenance.admin`, `maintenance.bypass`, ...),
so existing permission setups keep working. Run `/whitelist help` in-game for the full list.

## Bedrock / Geyser
Set `bedrock.enabled: true` in `config.yml`. Bedrock players are recognised by their Floodgate UUID
(`mostSignificantBits == 0`), so no Floodgate dependency is required at build time. To whitelist an offline
Bedrock player, add them with their gamertag prefixed by `bedrock.prefix` (default `.`):
```
/whitelist add .SomeGamertag
```

## Discord bot
Configure the `discord-bot` section in `config.yml`:
```yaml
discord-bot:
  enabled: true
  token: "YOUR_BOT_TOKEN"
  # Optional: restrict slash commands to a single guild (registers instantly instead of up to 1h globally).
  guild-id: ""
  # Optional: role id required to use the whitelist slash commands. Empty = require the Administrator permission.
  whitelist-role-id: ""
```
The bot registers slash commands:
* `/whitelist add|remove|list` – staff management (gated by `whitelist-role-id`, or the Administrator permission if empty).
* `/link <player>` – a member links their own Discord account to a Minecraft account (one Minecraft account per Discord user).
* `/unlink` – removes the link (and the whitelist entry gained through it).

Bedrock gamertags work in all of these too (prefix them with the configured Bedrock prefix).

### Role-based auto-whitelisting
Set `auto-whitelist-role-id` to a Discord role id to enable role syncing. Once a member has linked their account,
gaining that role automatically adds them to the whitelist; losing it removes them (`remove-on-role-loss`). Role
holders are also reconciled on startup. Receiving role changes requires the **Server Members Intent** to be enabled
for the bot in the Discord developer portal.

### Cracked / offline players (LimboAuth etc.)
Cracked (offline-mode) accounts don't exist in Mojang's database, so they can't be looked up by name the
normal way — and auth plugins like **LimboAuth** prefix cracked names with a `.`. To handle this, the plugin
keeps a **username cache** (`usernamecache.yml`): every player that connects is recorded `name → uuid`, and
`/whitelist add` checks the cache first. So a cracked player just needs to **attempt to join once** (they'll be
cached even though they're kicked), then staff can add them by the exact name shown in the kick log, e.g.
`/whitelist add .randomnoob`. This also covers Bedrock/Floodgate players. (Note: if your Bedrock `prefix`
and your auth plugin's cracked prefix are both `.`, the cache is what disambiguates them.)

The cache is hardened against join floods / bot attacks: it's **bounded** (`username-cache.max-entries`, default
10000 — oldest entries evicted past the limit) and **throttled** (saved at most once every 30s, plus on shutdown),
so a burst of random names can't bloat the file or hammer the disk. Set `username-cache.enabled: false` to turn it
off entirely.

### Manual whitelist (no linking)
With `linking.mode: off`, it's a plain whitelist: non-whitelisted players are kicked with the `kickmessage`,
which by default points them at your Discord to apply. Set `discord-invite: "discord.gg/yourserver"` in the
config and it fills the `%DISCORD%` placeholder. Staff then add approved players manually with
`/whitelist add <name>` or `<uuid>` (Bedrock gamertags work with the `.` prefix).

### Code-based linking (require-link-to-play)
Players can prove they own a Minecraft account by linking it with a one-time code (the same idea as
DiscordSRV's "require linking to play"). Enable the whitelist (`/whitelist on`) and set `discord-bot.linking.mode`:

```yaml
discord-bot:
  linking:
    # off   = no code requirement (manual whitelist / role sync only)
    # kick  = unlinked players are kicked at join and shown their code (DiscordSRV-style)
    # limbo = unlinked players are sent to the 'waiting-server' (use a Limbo server) and shown their code there
    mode: "off"
    require-role: true          # a linked player must also have auto-whitelist-role to be whitelisted
    code-length: 6              # 6+ digits recommended
    code-expiry-seconds: 600
    max-attempts-per-minute: 5  # anti-brute-force, per Discord user
```

Flow: a non-whitelisted player joins → they get a one-time code → they DM that code to the bot → they're
linked and (if `require-role`) checked for the role → added to the whitelist → they can play. For `limbo` mode,
point `waiting-server` at a lightweight Limbo (e.g. NanoLimbo/LimboAPI) so they can read their code there.

**Security:** codes are generated, single-use, expire, and are **bound to the connecting
player's UUID** — so a cracked/offline player can never use a code to claim someone else's account. DM input is
strictly validated verification attempts are rate-limited per Discord user, one Minecraft
account maps to one Discord user,  the
underlying account still requires online-mode auth / Floodgate for Bedrock.)

### Working with DiscordSRV or other linkers (the permission bridge)
If you'd rather let **DiscordSRV** (or any role-sync plugin) own the Discord side, grant the
`maintenance.whitelisted` permission to your "Whitelisted" group via LuckPerms and have DiscordSRV sync the
Discord role to that group. ProxyWhitelist treats anyone with `maintenance.whitelisted` (or `maintenance.bypass`)
as allowed — so no code coupling is needed. Any linker that can "run a command on link" can instead run
`/whitelist add <player>`.

### Pairing with a chat bridge
This bot is intentionally scoped to whitelisting/linking. If you also want a Minecraft⇄Discord chat bridge, run a
dedicated bridge plugin such as [VelocityDiscord](https://modrinth.com/plugin/velocitydiscord) alongside it — they
complement each other. Use a **separate Discord bot application/token** for each, since a single token cannot run two
gateway sessions at once.

## Running on a single Paper server
Besides the two proxy platforms, the plugin runs on a single **Paper** (Spigot/Bukkit) server. Drop
`ProxyWhitelist-Paper-<version>.jar` into `plugins/`. The Paper build has the same feature set as the proxy
build, minus the parts that only make sense on a proxy:

* ✅ whitelist toggle, `/whitelist add/remove/list`, custom MOTD / player-count / hover messages, timers,
  kick message, join notifications, Discord webhook and ServerListPlus integration
* ✅ **Bedrock / Geyser** – whitelist Bedrock players by gamertag (`/whitelist add .Notch`), recognised on
  join by their Floodgate UUID
* ✅ **username cache** – cracked/offline (e.g. LimboAuth `.name`) and Bedrock players can be whitelisted by
  name after they connect once
* ✅ **built-in Discord bot** – `/whitelist add|remove|list`, `/link`, `/unlink`, `/lookup`, role sync and
  code-based linking (`kick` mode)
* ❌ **not** included (inherently proxy-wide): cross-proxy Redis sync, per-backend-server whitelisting, the
  fallback/waiting-server routing and the `limbo` linking mode

Unlike the proxy build, the Paper build does **not** keep its own whitelist file. A single Minecraft server
already has a whitelist, so entries are written straight to the server's native **`whitelist.json`** (via the
Bukkit whitelist API) — the Discord bot and Bedrock resolution just decide *who* gets added. This means the
plugin's whitelist and the vanilla `/whitelist` command share one list. The only files the plugin keeps of
its own are the Discord links (`DiscordLinks.yml`) and the username cache (`usernamecache.yml`) — the cache
is still needed because `whitelist.json` / `usercache.json` don't record whether a name is a premium, cracked
or Floodgate (Bedrock) account, which is exactly what the resolver needs to store the correct UUID.

Because vanilla Minecraft already owns `/whitelist` on a Paper server, the plugin's command is registered
as `/pwhitelist` (aliases `/pwl`, `/maintenance`, `/mt`, and `/proxywhitelist:whitelist`). All permission
nodes (`maintenance.admin`, `maintenance.bypass`, `maintenance.whitelisted`, ...) are unchanged.

## Compiling
Clone the project and build with Gradle (`./gradlew build`). You need a JDK 21+.
The jars are written to `build/libs/ProxyWhitelist-Bungee-<version>.jar`,
`build/libs/ProxyWhitelist-Velocity-<version>.jar` and `build/libs/ProxyWhitelist-Paper-<version>.jar`.

## License
This project is licensed under the [GNU General Public License v3](LICENSE.txt), like the upstream project.
