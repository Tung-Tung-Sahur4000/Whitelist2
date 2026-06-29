# ProxyWhitelist — Discord bot (how it works, for auditing)

This document explains **everything the Discord bot does**, with flow diagrams and an explicit list of
what it **can** and **cannot** do. It is meant to be shared so anyone can verify the bot is safe.

The entire bot is **three small files** (all in `core-proxy/src/main/java/eu/kennytv/maintenance/core/proxy/discord/`):

| File | Responsibility |
|------|----------------|
| `DiscordBot.java` | The bot itself: logs in, slash commands, DM code handling, role-sync. |
| `DiscordLinkManager.java` | Stores Discord ↔ Minecraft links in `DiscordLinks.yml`. |
| `LinkCodeManager.java` | Generates/verifies one-time linking codes (in memory only). |

The bot only ever calls `SettingsProxy.addWhitelistedPlayer(...)` / `removeWhitelistedPlayer(...)` to do its
job — the same methods the in-game `/whitelist add` command uses.

---

## 1. What it CAN and CANNOT do (the trust part)

### ✅ It CAN
- Log into Discord using the bot token **you** put in `config.yml`.
- Receive the slash commands it registers: `/whitelist add|remove|list`, `/link`, `/unlink`.
- Receive **direct messages** sent to it — but it **only acts on messages that are exactly the linking-code
  digits** (e.g. `6` digits). Every other DM is ignored.
- Read a member's **roles** (only to check if they have the configured "whitelist" role).
- Add/remove a player **in the whitelist file** (`WhitelistedPlayers.yml`).
- Store account links in `DiscordLinks.yml`.

### ❌ It CANNOT
- **Read your Discord server's chat.** It does **not** request the `MESSAGE_CONTENT` privileged intent, so by
  Discord's own rules it cannot see the content of normal channel messages. It only sees: slash-command
  inputs, DMs sent *directly to it*, and role changes.
- Kick/ban Discord members, change roles, delete messages, or manage the server in any way.
- Run arbitrary Minecraft commands or touch anything other than the whitelist.
- Leak the token — the token is only read from your config and used to log in; it is never sent anywhere
  else and never written to any other file or message.
- Ping `@everyone`/`@here` or mention users — every reply sets *allowed mentions = none*.

### Gateway intents it requests (verifiable in Discord)
| Intent | Requested? | Why |
|--------|-----------|-----|
| `DIRECT_MESSAGES` | always | to receive the linking-code DMs (DM content needs **no** privileged intent) |
| `GUILD_MEMBERS` | only if role-sync is configured | to see who gains/loses the whitelist role |
| `MESSAGE_CONTENT` | **never** | (this is why it cannot read server chat) |

It is built with `JDABuilder.createLight(...)`, the most minimal JDA setup — see `DiscordBot.start()`.

---

## 2. Architecture

```
                         your config.yml (token, role ids, settings)
                                      │
                                      ▼
   Discord  ◄───gateway (JDA)───►  DiscordBot ──► SettingsProxy.addWhitelistedPlayer()
                                      │                     │
                          ┌───────────┴───────────┐         ▼
                          ▼                       ▼   WhitelistedPlayers.yml ──(optional)──► Redis ──► other proxies
                 DiscordLinkManager        LinkCodeManager
                 (DiscordLinks.yml)        (codes: memory only,
                  discordId → uuid;name     expire ~10 min)
```

Everything the bot writes to disk: `DiscordLinks.yml` (links) and `WhitelistedPlayers.yml` (the whitelist).
Linking **codes are never written to disk** — they live in memory and expire.

---

## 3. Flows

### 3a. Staff command — `/whitelist add|remove|list`
```
Staff: /whitelist add Notch
   │
   ├─ is the caller allowed?  (has the configured role, OR Administrator)   ──no──► "no permission" (ephemeral)
   │
   ├─ resolve "Notch" → uuid   (online player → username cache → Mojang → Geyser for Bedrock)
   │
   ├─ SettingsProxy.addWhitelistedPlayer(uuid, name)  →  WhitelistedPlayers.yml  (+ Redis if enabled)
   │
   ├─ ephemeral "✅ Added Notch"   (only the staff member sees the command + this reply)
   └─ public embed "✅ Notch has been whitelisted!"   (looks automatic - no visible command)
```
`/whitelist remove` and `/whitelist list` reply **ephemerally** (private to the staff member) and are gated by
the same permission. Only `add` posts the public "whitelisted" embed.

### Security of the linking system
| Concern | Protection |
|---------|-----------|
| Guessing a code (brute force) | Codes are `SecureRandom`, **per-user rate-limited**, expire (~10 min), single-use, and only exist briefly while a player is mid-link. A guess is only useful for the few seconds a specific code is active. |
| Claiming **someone else's** account | The code is **bound to the joining player's UUID**, so it can only ever link *that* account. While code-linking is on, `/link <name>` (which would trust a typed name) is **disabled** — everyone must prove ownership via the in-game code. |
| Hijacking an already-linked account | One Minecraft account maps to one Discord user; a second linker is refused. Already-linked players are **not** re-issued a code on rejoin. |
| Markdown / mention injection via names | Names are sanitized and all bot replies set *allowed mentions = none*. |
| Reading server chat / abusing the bot | No `MESSAGE_CONTENT` intent; DMs are ignored unless they are exactly the code digits. |

> If you run with `linking.mode: off` and `allow-linking: true`, the `/link <name>` convenience is available but
> trusts the typed name (someone could "squat" a name they don't own). With `require-role: true` the impact is
> limited to squatting. Set `allow-linking: false` to force the secure code flow.

### 3b. Self-service link — `/link <name>` (only when code-linking is OFF)
```
Member: /link Notch
   │
   ├─ linking allowed in config?  ──no──► "linking disabled"
   ├─ resolve "Notch" → uuid
   ├─ is that MC account already linked to someone else?  ──yes──► refuse (1 MC account = 1 Discord user)
   ├─ DiscordLinkManager.link(discordId, uuid, name)  →  DiscordLinks.yml
   └─ if the member already has the whitelist role → also whitelist them now
```

### 3c. Code linking — "prove you own the account" (DiscordSRV-style)
This is for requiring players to link before they can play. The code is **bound to the joining player's UUID**,
so a cracked/offline player can never use a code to claim someone else's account.
```
 ┌── In Minecraft ───────────────────────────────┐        ┌── In Discord (DM to the bot) ───────────────┐
 │ Player joins, not whitelisted                 │        │ Player DMs the bot:  482913                 │
 │   → kicked (or sent to Limbo) WITH a 6-digit  │        │   │                                         │
 │     code, e.g. "482913"                       │        │   ├─ exactly N digits? ──no──► ignored      │
 │   (code is generated for THEIR uuid+name)     │        │   ├─ too many tries this minute? ──► wait   │
 └───────────────────────────────────────────────┘        │   ├─ code valid & not expired? ──no──► error │
                                                           │   ├─ link Discord ↔ that MC account          │
                                                           │   ├─ require role? → check member's roles    │
                                                           │   └─ addWhitelistedPlayer → "you can join!"  │
                                                           └─────────────────────────────────────────────┘
```
Security on the code path: `SecureRandom` codes, single-use, expire (~10 min), one active code per player,
per-user rate-limiting on guesses, DM input strictly validated (digits only), replies never ping anyone.

### 3d. Role sync — Discord role drives the whitelist
```
Staff gives a member the "Whitelisted" role in Discord
   │                                    Staff removes the role
   ├─ bot receives GuildMemberRoleAdd   ├─ bot receives GuildMemberRoleRemove
   ├─ is that member linked?            ├─ is that member linked?
   └─ yes → addWhitelistedPlayer        └─ yes → removeWhitelistedPlayer (if remove-on-role-loss)

On startup the bot also reconciles: it scans role holders once and syncs the whitelist to match.
(Requires the GUILD_MEMBERS intent.)
```

---

## 4. Configuration (in `config.yml`)
```yaml
discord-bot:
  enabled: false
  token: ""                 # your bot token - only used to log in
  guild-id: ""              # optional: register commands to one server instantly
  whitelist-role-id: ""     # who may use /whitelist add|remove|list (empty = Administrator)
  auto-whitelist-role-id: "" # the "Whitelisted" role for role-sync (empty = role-sync off)
  remove-on-role-loss: true
  allow-linking: true
  linking:
    mode: "off"             # off | kick | limbo
    require-role: true
    code-length: 6
    code-expiry-seconds: 600
    max-attempts-per-minute: 5
```

## 5. Files the bot reads/writes
| File | Contents | Written by |
|------|----------|-----------|
| `config.yml` | token + settings (you provide) | you |
| `DiscordLinks.yml` | `id_<discordUserId>: <uuid>;<name>` | the bot, on link/unlink |
| `WhitelistedPlayers.yml` | `<uuid>: <name>` | the whitelist (bot + in-game command) |
| linking codes | **in memory only**, never on disk | `LinkCodeManager` |

## 6. Read the source
The three files are in this repository at
`core-proxy/src/main/java/eu/kennytv/maintenance/core/proxy/discord/`. They are short and commented; the bot's
behavior is entirely contained there plus the `startDiscordBot()` / `getJoinDenyMessage()` methods in
`MaintenanceProxyPlugin.java`.
