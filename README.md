# Janky Paper

**Created and owned by [Pepe-tf](https://github.com/Pepe-tf) · code and contributions by
[CamerG1011](https://github.com/CamerG1011)** · built on [Paper](https://papermc.io) by the PaperMC team and
contributors ([Credits](#credits))

Janky Paper is a fork of [PaperMC/Paper](https://github.com/PaperMC/Paper) for **recording and
content-creation servers**. It is tuned for two jobs that stock Paper handles poorly:

1. **Hosting huge crowds of fake-player bots** - 10,000+ bots from
   [FPP (Fake Player Plugin Origin)](#fpp-fake-player-plugin-origin-setup) or any plugin that uses a fake
   connection, including thousands packed into the same few chunks.
2. **Focusing the server on what is being filmed** - the recorder's area gets more view distance, faster
   chunk loading and more simulation, and everyone else only gives resources up when the server is
   actually struggling.

Everything else behaves like Paper: same plugins, same configs, same `latest.log` format. It is built
with [paperweight](https://github.com/PaperMC/paperweight) (the tooling Paper and Folia use) - nothing
from Paper is vendored; Paper is pulled in at the commit pinned in `gradle.properties` and Janky
Paper's changes are patches on top of it.

| | |
|---|---|
| Minecraft | **1.21.11** (`mcVersion` in `gradle.properties`) - this is the `ver/1.21.11` branch; `main` has the newest Minecraft version and the list of every supported one |
| Based on Paper | commit `c5eb0790f199da6c38d0a650e1e5cd5415b28185` (`paperRef`, Paper 1.21.11 build 132) |
| Channel | ALPHA |
| Java | 21 (Gradle auto-provisions a JetBrains JDK 21 for the dev server; the jar also runs on newer Java) |
| License | [GPL-3.0](LICENSE) with additional terms - you may fork and change it; see [NOTICE.md](NOTICE.md) |

---

## Contents

- [Feature overview](#feature-overview)
- [Running a Janky Paper server](#running-a-janky-paper-server)
- [Configuration reference (`config/janky-paper.yml`)](#configuration-reference-configjanky-paperyml)
- [Commands and permissions](#commands-and-permissions)
- [Lightweight bots](#lightweight-bots)
- [Player grid (mob nearest-player searches)](#player-grid-mob-nearest-player-searches)
- [Bot worker threads (off-main-thread path searches)](#bot-worker-threads-off-main-thread-path-searches)
- [Parallel bot movement (experimental)](#parallel-bot-movement-experimental)
- [Focus mode](#focus-mode)
- [CPU and threading](#cpu-and-threading)
- [FPP (Fake Player Plugin Origin) setup](#fpp-fake-player-plugin-origin-setup)
- [Measured results](#measured-results)
- [Troubleshooting](#troubleshooting)
- [Console and log style](#console-and-log-style)
- [Developing Janky Paper](#developing-janky-paper)

---

## Feature overview

| Feature | What it does | Where |
|---|---|---|
| **Lightweight bots** | Players on a fake connection skip all the per-player work that only exists to feed a real game client (chunk packets, entity tracking, advancements, tab-list refreshes, command tree, ...). Turns several bots x bots costs into per-bot costs. | `dev.jankypaper.server.bot.BotPlayers` |
| **Player grid** | Mob "nearest player" searches (despawn checks, targeting, tempting, spawner activation) use a per-tick spatial index instead of scanning every player in the world. Exact same results; handles thousands of bots in one chunk. | `dev.jankypaper.server.bot.PlayerGrid` |
| **Bot worker threads** | Plugins can run bot path searches on spare CPU cores; block reads from those threads never load chunks. FPP uses them automatically. | `dev.jankypaper.server.bot.BotWorkerThreads` |
| **Parallel bot movement** (experimental, off by default) | With a plugin that uses it (FPP), the movement physics of all bots run together on worker threads after the plugin's tick, instead of one by one on the main thread. | `dev.jankypaper.server.bot.BotMovement` |
| **Typed nearby-entity lookups** | `World#getNearbyEntitiesByType(Class, ...)` uses the per-type entity index when the class covers only a few entity types (arrows, TNT minecarts, items, ...), instead of fetching every entity in the box and filtering. Helps every plugin; in a crowd of bots it no longer walks every bot to find one arrow. | `dev.jankypaper.server.world.TypedEntityLookup` |
| **Focus mode** | `/focus` pours view distance, simulation distance, chunk-loading speed and entity activation into the players/areas being recorded, and throttles everyone else only under load. | `dev.jankypaper.server.focus` |
| **Whole-CPU chunk system** | Chunk loading/generation defaults to every core but two (Paper: about a quarter). | `MoonriseCommon` patch |
| **Console theme** | A styled, colour-coded console (boot header, startup/shutdown banners, `[ᴊᴀɴᴋʏ]` tag) that keeps Paper's standard `latest.log` format. | `dev.jankypaper.server.log` |

---

## Running a Janky Paper server

### Getting the jar

There are no published downloads yet - build it (see [Developing Janky Paper](#developing-janky-paper)):

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is `janky-paper-server/build/libs/jankypaper-paperclip-<version>.jar`. It is a Paperclip
jar like Paper's: on first start it downloads the vanilla server, patches it and caches it under
`cache/` and `libraries/`.

### Starting it

Use it exactly like a Paper jar:

```bash
java -Xms6G -Xmx6G -jar jankypaper-paperclip-26.3.build.12-alpha.jar --nogui
```

Accept the EULA in `eula.txt` on first start. All of Paper's, Spigot's and Bukkit's files
(`config/paper-global.yml`, `config/paper-world-defaults.yml`, `spigot.yml`, `bukkit.yml`,
`server.properties`) work unchanged. Janky Paper adds **one** file: `config/janky-paper.yml`,
written with defaults on first start.

### What the startup log shows

During start-up Janky Paper prints a few extra rows you can use to confirm the features are active:

```
[ᴊᴀɴᴋʏ]   Chunk workers ..... 10 worker threads, 3 I/O threads
[ᴊᴀɴᴋʏ]   Focus mode ........ auto (0 player(s), 0 area(s))
[ᴊᴀɴᴋʏ]   Lightweight bots .. on (view 3, sim world)
```

The final line still contains `Done (…)! For help, type "help"`, so hosting panels (Pterodactyl,
Folium, etc.) detect the start normally.

### Memory sizing for big bot counts

Measured on the dev server with everything here enabled, live heap after GC was about **1.2 GB at
5,000 bots** (bots all at spawn, view distance 3). Plan roughly 0.15-0.25 MB per bot on top of what
your worlds and plugins need, and give the JVM headroom for G1 (`-Xmx` well above the live heap). The
biggest per-bot memory costs that Paper normally has (advancement progress, locator-bar connections,
nearby-player arrays) are removed for bots - see [Lightweight bots](#lightweight-bots).

---

## Configuration reference (`config/janky-paper.yml`)

The file is created on first start (in Paper's config folder, `config/` by default, or wherever
`--paper-settings-directory` points). It is re-read with `/focus reload`; bot settings apply to bots
that join after the reload. Full file with defaults:

```yaml
focus:
  mode: auto                 # auto | always | off
  pressure:
    engage-mspt: 40.0        # auto mode starts throttling unfocused players at this average MSPT ...
    release-mspt: 30.0       # ... and stops once it falls back to this (hysteresis)
  focused:                   # tier for focused players
    view-distance: 16        # 2-32, or -1 = the world's own value
    simulation-distance: 10  # 2-32, or -1; capped by view distance
    chunk-rate-multiplier: 4.0
    activation-range-multiplier: 2.0
  others:                    # tier for everyone else, only while throttling
    view-distance: -1
    simulation-distance: 4
    chunk-rate-multiplier: 0.5
    activation-range-multiplier: 0.5
  targets:                   # managed by /focus - you rarely edit these by hand
    players: []              # UUIDs
    areas: []                # "world;chunkX;chunkZ;radius"
bots:
  lightweight: true          # false = treat bots exactly like real players
  view-distance: 3           # view distance a bot joins with (2-32, -1 = world value)
  simulation-distance: -1    # simulation distance a bot joins with (2-32, -1 = world value)
  vanilla-push: false        # true = restore vanilla bot-on-bot pushing (costs bots x bots per tick)
  affect-mob-spawning: true  # false = no natural mob spawning around bots (crops/random ticks keep running)
  activation-range-multiplier: 0.5  # entity activation range around bots, as a multiple of spigot.yml's
  mob-spawn-representative-every: 1000  # above this many bots, only 1 in (online / this) counts for mob caps
  parallel-movement: false   # EXPERIMENTAL: bots' movement physics on worker threads (needs a plugin that uses it, e.g. FPP)
  distant-update-interval: 4 # ticks between movement updates of a bot far from every viewer (2 = vanilla)
  distant-update-range: 64.0 # blocks - a viewer further than this counts as far
logging:
  mask-player-ips: true      # 85.226.123.45 is logged as 85.226.***.** (join line, login/kick messages, packet errors)
```

### `focus` keys

| Key | Default | Meaning |
|---|---|---|
| `mode` | `auto` | `auto`: focused players always get the focused tier; everyone else gets the `others` tier only while the server is under pressure. `always`: everyone not focused is always on the `others` tier. `off`: focus mode does nothing. |
| `pressure.engage-mspt` | `40.0` | Average milliseconds per tick (100-tick average) at which `auto` mode starts throttling. 50 ms = exactly 20 TPS. |
| `pressure.release-mspt` | `30.0` | Average MSPT at which throttling stops again. Kept below `engage-mspt` so it doesn't flap. |
| `focused.*` / `others.*` `view-distance` | 16 / -1 | Per-player view distance (chunks loaded and sent). `-1` keeps the world's value. |
| `focused.*` / `others.*` `simulation-distance` | 10 / 4 | Per-player simulation (ticking) distance. Can't exceed the view distance. |
| `focused.*` / `others.*` `chunk-rate-multiplier` | 4.0 / 0.5 | Multiplies Paper's per-player chunk load / generate / send rates and concurrent load / generate limits (`chunk-loading-basic` / `chunk-loading-advanced` in `paper-global.yml`). |
| `focused.*` / `others.*` `activation-range-multiplier` | 2.0 / 0.5 | Multiplies the entity activation ranges from `spigot.yml` around that player (how far away mobs keep full AI). |
| `targets.players` | `[]` | Focused player UUIDs (`/focus add`). |
| `targets.areas` | `[]` | Focus areas (`/focus here`), each `world;chunkX;chunkZ;radius`. |

### `bots` keys

| Key | Default | Meaning |
|---|---|---|
| `lightweight` | `true` | Detect bots and apply every [lightweight-bot](#lightweight-bots) optimization. `false` makes bots cost exactly what real players cost. |
| `view-distance` | `3` | View distance set on a bot when it joins. Small on purpose: every chunk in view is kept loaded per bot. A plugin may change it afterwards (FPP's `performance.bot-view-distance` does). |
| `simulation-distance` | `-1` | Simulation distance set on a bot when it joins. `-1` = the world's value, capped by the bot's view distance (so 3 with the default view distance). This is how far around each bot chunks tick and mobs spawn. |
| `vanilla-push` | `false` | Vanilla makes every player check every nearby entity for pushing each tick - with a crowd of bots that is bots x bots. Off by default; mobs still push bots, and FPP runs its own crowd separation. |
| `affect-mob-spawning` | `true` | Whether bots make mobs spawn around them like real players. With thousands of bots spread out, natural spawning around all of them is one of the largest remaining costs (~10% of the tick at 10,000 roaming bots). `false` leaves bots out of natural spawning, despawn distance checks and spawners; chunks around bots still tick (crops grow, furnaces run), and real players spawn mobs normally. |
| `activation-range-multiplier` | `0.5` | Multiplies the `spigot.yml` entity activation ranges around each bot. Mobs that are only near bots - not near any real player - keep full AI within half the usual range and run Spigot's cheap inactive tick beyond it. Real players keep their full ranges, so anything near you behaves normally. `1.0` = treat bots like players. |
| `mob-spawn-representative-every` | `1000` | Paper's per-player mob caps cost mobs x players every tick (each mob adds itself to every nearby player's count; each spawn attempt checks every nearby player). Up to this many bots online every bot counts as a mob-spawning player, exactly like vanilla - an AFK bot at a farm behaves normally. Above it, only one bot in `online / this` counts (chosen by UUID, re-checked as the count changes), so a 10,000-bot crowd costs and spawns mobs like a ~1,000-bot crowd. `0` = every bot always counts. |
| `distant-update-interval` / `distant-update-range` | `4` / `64.0` | A bot further than `distant-update-range` blocks from **every** player watching it sends its movement every `distant-update-interval` ticks instead of every 2 (staggered by entity id, so the sends spread evenly). Halves the movement packets built for the far part of a crowd a recording player is looking at; bots that close in on a viewer go back to every 2 ticks at once, and gliding bots always update every 2 ticks. `2` = vanilla. |
| `parallel-movement` | `false` | **Experimental.** Lets a plugin hand the movement physics of all its bots to Janky Paper, which runs them together on worker threads after the plugin's tick - see [Parallel bot movement](#parallel-bot-movement-experimental). Does nothing without a plugin that uses it (FPP does). Not benchmarked yet; turn it on, watch the console for `Parallel bot movement:` lines, and turn it off again if bots misbehave. |

### `logging` keys

| Key | Default | Meaning |
|---|---|---|
| `mask-player-ips` | `true` | Masks player IP addresses everywhere Paper logs them - the join line (`Name[/85.226.***.**:54321] logged in ...`), login failures and disconnects, packet-handling errors - in the console and in `logs/latest.log`. The last two IPv4 octets (everything after the first two IPv6 groups) become one `*` per digit, so you can still tell players and networks apart in a support thread without publishing anyone's address. `false` logs full addresses like Paper does. Paper's own `logging.log-player-ip-addresses: false` (`paper-global.yml`) still hides them completely. Plugins that print addresses themselves are not affected. |

---

## Commands and permissions

| Command | What it does |
|---|---|
| `/focus` | Status: mode, current MSPT, whether others are being throttled, both tiers, focused players/areas, lightweight-bot status and how many bots are online. |
| `/focus add <players>` | Always give these players the focused tier (accepts selectors, e.g. `@p`). Saved to the config. |
| `/focus remove <players>` | Remove players from the focus list. |
| `/focus here [radius]` | Focus a square of chunks centred on you (radius 0-64 chunks, default 8). Anyone standing inside gets the focused tier. |
| `/focus clear` | Remove all focused players and areas. |
| `/focus mode auto\|always\|off` | Change the mode (saved). |
| `/focus reload` | Re-read `config/janky-paper.yml`. |

Permission: **`jankypaper.command.focus`** (ops by default). The command is registered under the
`jankypaper` namespace, so `/jankypaper:focus` also works.

---

## Lightweight bots

### How bots are detected

A player counts as a bot when its network connection is **not one the server accepted**. Every real client
connection (TCP or unix domain socket) is a Netty `DuplexChannel`; fake-player plugins attach a
fake channel instead (FPP's `FakeChannel`, `EmbeddedChannel`, ...). Some plugins swap a real player's channel
for a wrapper of their own (ProtocolLib does), so a channel that isn't a socket still counts as real when it
reports the server's listening channel as its parent, or when the connection is in the server's connection
list - fake connections have neither. Detection happens once, when the player is placed into the world, and
is stored on the player (`ServerPlayer.jankyBot`). No plugin changes or API calls are needed, so it works for
any fake-player plugin that uses a fake connection. The console prints one line per kind of bot connection
(`Lightweight bots: players on <channel class> connections are treated as bots`), so a misdetected real player
is easy to spot.

### What a bot no longer costs

| Removed for bots | Why it mattered | Scales with |
|---|---|---|
| **Entity tracking as a viewer** | Every entity near a bot checked that bot as a viewer every tick and built spawn/movement packets for it. Bots are left out of the tracker's nearby-player map. Real players still see bots normally. | entities x bots |
| **Chunk packets** | Every chunk around every bot was serialised (with light data), watch events fired and block changes fanned out to it. Chunks are still loaded and ticked around bots; nothing is built or sent. | bots x chunks |
| **Advancement progress** | ~1,866 progress objects plus criterion listeners per bot (about 0.5 MB each) and a full advancement packet on join. Released at join; the bot's advancement file is never overwritten. | bots |
| **Tab-list latency refresh** | Every 30 s each player got a latency packet listing every visible player. Bots are skipped as receivers. | bots x bots |
| **Join player list** | Each joining bot was sent the whole player list. | bots x bots |
| **Positional sounds / particles / `broadcastAll`** | Every broadcast looped over every bot. Bots are skipped. | broadcasts x bots |
| **Command tree packet** | The full Brigadier tree was built for each joining bot (tab completion on a client). | bots |
| **Join-time work** | Each joining player is offered every tracked entity as a viewer, is added to `usercache.json` (which re-sorts and re-saves the whole cache), is counted for sleeping, and receives the join message - all skipped for bots. Bots also no longer block real players from skipping the night by sleeping. | bots x bots |
| **System broadcasts** | Join / leave / death messages and other system broadcasts go to real players only. Messages addressed to a bot directly still arrive. | broadcasts x bots |
| **Locator bar** | Every player tracked every other player as a waypoint (`bots x bots` connection objects). Bots don't receive a locator bar; they still appear on real players' bars. | bots x bots |
| **Unused nearby-player maps** | Moonrise keeps three "general" nearby-player maps the server never reads; each bot took a slot in every chunk within 33 chunks. | bots x chunks |
| **Vanilla bot-on-bot pushing** | Each bot checked every entity in its hitbox (plus a climbable-block lookup per candidate). Configurable: `bots.vanilla-push`. | bots x bots |
| **Item-pickup scan** | Each bot scanned every nearby entity (including every other bot) each tick. Bots now only look up entity classes that react to a touch: items, XP orbs, arrows/tridents, slimes/sulfur cubes, pufferfish. | bots x bots |
| **Entity activation** | Spigot's activation range fetched every entity in a big box around each player - per bot, that box held the whole crowd. The chunks holding bots are now collected first and every chunk within reach of one is visited **once**: an entity is active when its chunk is within its activation range (in whole chunks, rounded up, scaled by `bots.activation-range-multiplier`) of a chunk with a bot. Real players and focused bots keep Spigot's exact boxes. | bots x entities |
| **Container sync, stat counter, debug-subscription check, map-item sync** | Per-tick work that only feeds a client. Stat-tracking scoreboards still update for bots. | bots |
| **Chunk tickets** | Moonrise gives every player its own ticket on every chunk in its load radius - 10,000 bots in one area put ~10,000 tickets on each chunk under them, and the ticket set of a chunk is a sorted array (every bot crossing a chunk border inserted into / removed from it, and the expiry pass walked all of them every tick once a bot left a delayed unload ticket there). All bots in a world now share **one reference-counted ticket per chunk and level**; chunks stay loaded and ticking exactly as before. | bots x chunks |
| **Chunk-loader ticking** | Each bot's chunk-loading queues are processed every other tick (half of the bots each tick), and not at all while the bot has nothing queued (the common case once its chunks are loaded). Chunks a bot "sends" are marked sent at once - no rate limit, no waiting for neighbours, no post-processing for a client that doesn't exist. | bots |
| **Per-player mob caps** | Above `mob-spawn-representative-every` bots, only a share of the bots count for Paper's per-player mob caps (every mob tick and spawn attempt loops over the players in range). The per-tick mob-count reset skips bots that aren't in the mob-cap map, and a spawn attempt stops walking the players in range as soon as one of them is at its cap. | mobs x bots |
| **Spawning chunks** | Deciding which chunks can spawn mobs walked every player in range of each chunk - every bot, for every chunk at the edge of a crowd where none qualified. It now asks the [player grid](#player-grid-mob-nearest-player-searches). | chunks x bots |
| **Per-tick per-player busywork** | Bots skip the network flush and update their attribute modifiers once a second. The per-tick `PlayerNaturallySpawnCreaturesEvent` is not fired for bots (their spawn radius is kept and re-checked once a second). Endermen never treat a bot as staring at them. | bots |
| **Mob despawn checks** | With more than 64 players in a world, each mob checks for despawning every 4th tick (staggered) instead of every tick; the random despawn chance is scaled so despawn rates stay the same. | mobs x players |
| **Crowded entity sections** | Moonrise's per-section entity lists checked for duplicates and removed entries by scanning the whole list - in a crowd, thousands of bots per section, several times per bot crossing a section border. Lists above 32 entries now keep a hash index (O(1) add/remove). Helps any crowded area, not just bots. | bots x bots |
| **Block-effect walk of plain ground** | Every tick, every moving entity walks each block its box swept through and asks it to react (cobweb, fire, portal, pressure plate, berry bush, powder snow, fluids - vanilla's `entityInside`), step by step with a lambda per block and an effect-collector flush - 3.5% of a 10,000-bot roam tick, almost all of it over blocks where nothing can happen. The blocks of the swept box are now read once; when none has a fluid and none of their block classes overrides `entityInside` (air, grass, flowers, slabs, carpet... - decided per class), the walk and the flush are skipped. Water also counts as inert for anything that isn't burning (its only reaction is putting out fire, and the fire ticks are reset at the end either way) - a crowd partly standing in a lake used to take the full walk for every bot in the water. Exact: such a visit has no effect. Real players (the "enter block" advancement trigger) and ender pearls always take the full walk. Applies to mobs too. | entities |
| **Nearest-player searches in 3D** | The [player grid](#player-grid-mob-nearest-player-searches) bounded its searches by horizontal distance only, so a mob in a cave under a crowd (or a bird above it) could not rule out any part of the crowd and checked every bot in it - 43% of the server thread with 10,000 bots standing over a cave system. The grid now keeps each square's height range (and a player moving more than a block vertically goes on the overflow list like a horizontal move), so 3D searches skip whole squares above or below. Exact. | mobs x bots |
| **Pose checks** | Every player tick checks whether the body fits its pose: vanilla tests the swimming box first, then the desired pose. Every pose except sleeping contains the swimming box, so when the desired pose fits, the swimming test is skipped (one collision query instead of two). Exact. Applies to real players too. | bots |
| **Locator-bar updates nobody receives** | Every entity move (bots and mobs) read the transmit-range attribute and built several set views and copies for the waypoint manager, even in a world with no player receiving waypoints. Skipped there. | entities |
| **Sprint re-applies** | Vanilla's `setSprinting` removes and re-adds the sprint speed modifier on every call, even when nothing changes - and the re-dirtied movement-speed attribute is then sent to every player tracking that entity. Plugin bots (and mob AI) set the same sprint state every tick, so every sprinting bot cost an attribute packet per viewer per tick. A call that changes nothing now does nothing. Applies to all entities. | bots x viewers |
| **Movement updates of far bots** | Bots further than `bots.distant-update-range` from every viewer send movement every `bots.distant-update-interval` ticks (4) instead of every 2. | bots x viewers |
| **Tracker updates nobody receives** | Every entity in a ticking chunk built a movement packet every tick even with no player watching it. With no viewers it now only keeps the state a later viewer needs (and Paper's forced resync sends the full position when one arrives). Applies to all entities. | entities |

### What bots keep

- **Packets addressed to the bot itself**: chat, system messages, titles, action bars, and the
  knockback motion packet. Fake-player plugins often read these from their fake connection (FPP does),
  so they still arrive.
- **Gameplay**: mobs still see, target and attack bots; bots still tick chunks, spawn mobs and count
  for per-player mob caps (removing them from those caps would remove the spawn limit around bots).
- **Visibility**: real players still see bots, in the world and in the tab list.

### Turning it off

Set `bots.lightweight: false` and restart (or `/focus reload`, which affects bots that join
afterwards). Bots then cost exactly what real players cost.

---

## Player grid (mob nearest-player searches)

Vanilla answers "who is the nearest player?" by walking every player in the world. Mobs ask that
constantly - every despawn check, every target search (`NearestAttackableTargetGoal`), tempt goals,
spawners, natural spawning - so with thousands of bots it becomes **mobs x players** per tick (about half
the whole tick at 10,000 bots before this change).

Janky Paper keeps a per-world spatial index of players (`ServerLevel.jankyPlayerGrid`):

- Players are bucketed by chunk **once per tick**, the first time a search needs it (a counting sort into
  flat arrays - no per-cell lists or sorting).
- A chunk holding more than 24 players is split into 4x4-block cells, and those again into 1x1-block
  cells, so a search inside a dense crowd only looks at the few players actually around it.
- A search walks rings of chunks outwards from the mob, visits cells nearest-first, skips any cell that is
  already further than the best match, and stops as soon as no unvisited ring can hold anything closer.
  The expensive targeting test (line of sight, visibility) only runs on players that would beat the
  current best.
- Target searches stop at the mob's target range (scaled by the largest `mob_visibility` equipment
  boost anyone is wearing, since datapacks can raise visibility above 1).
- If the rings to visit would cost more than a plain scan (players spread across the world, or nothing in
  range), it falls back to the plain scan.
- Deciding which chunks can spawn mobs uses it too (horizontal distance, each player's own spawn radius).

It returns the same player as the vanilla scan. Positions are snapshotted when the grid is built and
every bound keeps 1 block of slack; a player that moves further than that from its snapshot during the
tick (a teleport, a long fall, a launch) goes on an overflow list that every search also checks, and the
distance that decides the winner is always the live one. The grid is only used when a world has **more than
64 players**; below that the vanilla scan is cheaper and runs unchanged.

---

## Bot worker threads (off-main-thread path searches)

All game logic in a world - bot movement, mobs, block updates - runs on the server's **main thread**.
Planning bot routes doesn't have to: it only reads the world. Janky Paper provides a worker-thread type
that plugins can use for that, so route planning runs on cores that would otherwise sit idle.

**What makes it safe:** block and fluid reads made *from these worker threads* only read chunks that are
already loaded (through Moonrise's lock-free chunk map). A block in an unloaded chunk reads as a
**barrier** (solid), and its fluid as empty, so a search routes around unknown terrain instead of loading
or generating chunks from a worker thread. Reads from every other thread - the main thread and any other
plugin's async threads - keep vanilla behaviour.

Worker threads run one step below normal priority, so they only use spare CPU.

### For plugin developers

The API is reached by reflection, so the same plugin jar runs on Paper, Folia and Janky Paper:

```java
ExecutorService pool = null;
try {
    Class<?> workers = Class.forName("dev.jankypaper.server.bot.BotWorkerThreads");
    if (workers.getField("API_VERSION").getInt(null) == 1) {
        ThreadFactory factory = (ThreadFactory) workers.getMethod("threadFactory", String.class)
                .invoke(null, "MyPlugin Path Worker");
        pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), factory);
    }
} catch (ReflectiveOperationException e) {
    // not Janky Paper - keep searching on the main thread
}
```

A second optional hook makes spawning bots cheaper: call `BotPlayers.expectBot(UUID)` (same reflection
pattern, `dev.jankypaper.server.bot.BotPlayers`, `static void expectBot(UUID)`) right before constructing a
bot's `ServerPlayer`, and the server skips loading advancement progress the bot would never use. Bots are
still detected by their connection either way.

Rules for code running on those threads:

- **Read-only.** Only read blocks/fluids (`World#getBlockAt`, `Block#getType`, `getBlockData`,
  collision shapes, ...). Never change the world, entities or inventories, and never call events.
- **Snapshot your inputs on the main thread** (locations, entity state, sets you own) and pass copies in.
- **Apply results on the main thread**, and re-check that the bot still exists and still wants the
  result.
- `API_VERSION` is bumped on incompatible changes - check it.

---

## Parallel bot movement (experimental)

**Status: written, compiles, not yet run.** Off by default (`bots.parallel-movement: false`).

Every bot's movement physics - friction, gravity, the collision sweep against blocks, fluids, climbing, step sounds -
runs inside its `doTick()`, which the fake-player plugin calls for one bot after another on the main thread. In a
10,000-bot fight that is the single largest piece of the tick (about a quarter of it). The movement itself only reads
the world and only writes the bot's own fields; the only things it touches that other code also touches are the entity
lookup's section bookkeeping, the locator-bar and player-grid updates when the position changes, and the sounds and
game events a step emits.

With this on, a plugin that uses the hooks (FPP does) runs its tick in two halves:

1. For every bot: its checks, web escape and `doTick()` - but the bot's `travel()` only **records** its movement input
   and returns. The rest of the bot's tick (fire, air, effects, food, item pickup, inventory) runs as always, at the
   position the tick started with - exactly like a real player's server-side entity, which is moved by its client's
   packets, not by its own `travel()`.
2. The plugin calls `flush()`: all recorded bots travel **in parallel** on movement worker threads (`cores - 1` of them,
   plus the server thread taking a slice). Nothing else runs on the server thread meanwhile, so the world is read-only
   for the duration; block reads from the workers never load chunks (an unloaded chunk reads as a barrier). The four
   shared-structure effects above are deferred, and then applied on the main thread, bot by bot, followed by the
   block effects of the move (step-on / inside-block, e.g. magma or cactus damage) and the queued sounds and game events.
3. The plugin runs its own fall-damage check and the bots' AI with the bots moved.

Bots that are riding, gliding (an elytra wall hit damages the bot - an event), sleeping, creative-flying, in spectator
mode or dead are never captured and move inline as before. A plugin that arms bots and never flushes them is caught by
a per-tick safety net. On a server without a plugin that uses the hooks, the setting changes nothing.

Known trade-offs: a bot's body rotation follows its head instead of its walking direction (the body-turn code runs
before the move now), item pickup and pose updates use the position from the start of the tick (one tick late), and
the first tick after a spawn is always inline.

Measured (server-side benchmark below, 10,000 bots packed in a 24-block radius): a **walking** crowd drops from
120 ms to 103 ms per tick (bot physics on the main thread 73 ms -> 46 ms, plus a 16 ms flush), but a **fighting** crowd
- bots standing toe to toe, barely moving - gets slightly slower (127 ms -> 140 ms): there the hand-off costs more than
the movement it moves off the main thread. While moving, each worker also works out whether the bot's block effects
(cobwebs, fire, pressure plates, water...) can do anything this tick, so the main thread skips that pass when they
cannot. Keep it off for fight-heavy recordings.

Plugin hooks (`dev.jankypaper.server.bot.BotMovement`, reflection, `API_VERSION` = 1): `boolean isAvailable()`,
`void defer(org.bukkit.entity.Player)` right before the bot's `doTick()`, `boolean isDeferred(Player)` after it, and
`void flush()` after the loop.

---

## Focus mode

Focus mode is for the player(s) actually recording.

**Who is focused:**
- players added with `/focus add <players>`, and
- anyone standing inside a focus area (`/focus here [radius]`).

Bots are never focused through an area, only if you `/focus add` them explicitly.

**What focused players get** (the `focused` tier, all the time):
- view distance 16 and simulation distance 10 (by default),
- 4x faster and 4x more concurrent chunk loading/generation, and their chunk loads go first in the queue,
- 2x entity activation range, so mobs further away keep full AI on camera.

**What everyone else gets** (the `others` tier, only while throttling):
- simulation distance 4, half-speed chunk loading and half activation range. Their view distance is kept
  (`-1`), so they can still fly around and see terrain.

**When throttling happens:** in `auto` mode, when the server's average MSPT reaches `engage-mspt` (40 ms
by default) and until it drops to `release-mspt` (30 ms). The console logs both moments:

```
[ᴊᴀɴᴋʏ] Focus: server under pressure (52.3 mspt) - throttling 14 unfocused player(s)
[ᴊᴀɴᴋʏ] Focus: pressure gone (24.7 mspt) - everyone back to full settings
```

When nothing is throttled, unfocused players keep the world's normal settings. Focus mode never touches a
player it hasn't put on a tier, so distances other plugins set are left alone; while a player *is* on a
tier, the tier's distances replace them, and when the player leaves the tier they reset to the world's
values.

**Notes:**
- A focus area only boosts players standing inside it. It doesn't keep the area loaded when nobody is
  there.
- Focus mode updates every 10 ticks.

---

## CPU and threading

### What uses which cores

| Work | Thread(s) | Janky Paper default |
|---|---|---|
| All game logic in the world: players, bots, mobs, redstone, block updates, plugins' sync tasks | **1 main thread** | unchanged (as in Paper) |
| Chunk loading, generation, lighting | Moonrise worker pool | **cores - 2** (min 1). Paper: about a quarter of the cores |
| Chunk/region file I/O | Moonrise I/O pool | **cores / 4, between 1 and 4** |
| Networking | Netty threads | unchanged (`spigot.yml` `settings.netty-threads`) |
| Bot route planning (FPP) | bot worker threads | cores / 2 (FPP's choice), below normal priority |
| Bot movement physics (FPP, with `bots.parallel-movement`) | movement workers + the server thread | cores - 1 workers, normal priority, only while the server thread waits for them |

`chunk-system.worker-threads` and `chunk-system.io-threads` in `config/paper-global.yml` still override
the chunk pool sizes when set above 0.

### Why TPS can drop while the CPU looks idle

If your panel shows roughly **one core's worth of CPU** (e.g. ~100% of 400%) while TPS drops, the main
thread is full. Minecraft runs everything in a region on that one thread, and no server can split one
dense region across cores without changing how the game behaves (Folia splits *separate* regions, but
thousands of bots in one spot are one region). Janky Paper attacks this two ways:

1. **Less main-thread work per bot** - everything in [Lightweight bots](#lightweight-bots) and the
   [Player grid](#player-grid-mob-nearest-player-searches).
2. **Moving safe work off the main thread** - chunk work on the big worker pool, route planning on
   [bot worker threads](#bot-worker-threads-off-main-thread-path-searches).

What still costs main-thread time per bot is the bot's own physics tick (movement and collisions), the
fake-player plugin's AI, and the mobs and chunk ticking that bots cause around them.

---

## FPP (Fake Player Plugin Origin) setup

Janky Paper works with any FPP build, but these FPP features use it:

| FPP setting / behaviour | Notes |
|---|---|
| `pathfinding.async-search: true` (default in current builds) | On Janky Paper, roam, follow and combat-chase route searches run on [bot worker threads](#bot-worker-threads-off-main-thread-path-searches). The console prints `Path searches run on N worker thread(s)` on the first search. Commands, breath surfacing, web/water escapes, waypoint routes and combat wander stay on the main thread. Set `false` to search only on the main thread. |
| `/fpp roam @a` | Starts every selected bot on the same tick (the old `roam-start-batch-size` setting was removed). |
| `performance.bot-view-distance` | `0` leaves Janky Paper's `bots.view-distance` (3) in place. Set a value only if your bots need to keep more chunks loaded (e.g. farms). |
| CPU-load sampling | FPP's performance monitor reads CPU load on an async thread (it was a slow native call on the main thread). |
| Spawn pacing | Bots are placed into the world under a 25 ms per-tick budget. A big `/fpp spawn` used to place every bot whose async steps finished in the same tick - over a minute in one tick at 10,000 bots, enough to trip the watchdog. |
| Stuck recovery | When a walking bot gets stuck, its replan runs on a worker thread too (a crowd of thousands starting to walk at once used to stall the main thread for seconds). |
| Head glances | A walking bot only checks real players for something to glance at, instead of scanning every entity around it (that scan was nearly the whole tick with 10,000 bots roaming in a crowd). |
| `expectBot` hook | FPP announces each bot to Janky Paper before creating it, so its advancement progress is never loaded. |
| **Bot detail levels** (`performance.bot-detail`) | Bots within `full-detail-range` (48 blocks) of a real player run every tick. Bots further away run every Nth tick - `mid-interval` (2) when busy within `mid-range` (128), `far-interval` (4) further out, `combat-interval` (2) when fighting or hurt, `idle-interval` (20) when standing with nothing to do - and the intervals grow with the bot count (x1 per `crowd-scale` = 2500 bots online, up to `max-crowd-multiplier` = 4). Falling, water, lava, fire, surfacing for air and elytra flight always run at full detail; a knocked-back bot was hurt, so it uses the combat interval. On a skipped tick a bot simply stands still, so far-away bots walk and fight slower while nobody is around to see them. Ticks are staggered per bot so the load spreads evenly. Paper only; `enabled: false` turns it off. |
| Crowd separation | Bots are bucketed into a spatial hash (cells one collision radius wide) every 5 ticks; each bot only checks bots in the 3x3 cells around it, at most 12 neighbours, and its pushes are summed and applied once. The old Paper pass compared bots in list order (so crowds barely separated at scale); the Folia pass called `getNearbyPlayers` per bot (bots x bots). |
| Combat targeting | Target pick, rebalance, nearest-threat and retreat-direction searches use a per-tick spatial index of players and consider the 32 nearest candidates in range, instead of every player in aggro range for every fighting bot (a 10,000-bot free-for-all froze the server for minutes). Folia keeps its region-local lookups. |
| Player movement push | A real player's move event only checks the bots within reach of it (it used to copy and walk the whole bot list on every move event). |
| Sitting-out bots | A bot sitting out a tick under the detail levels isn't looked at again until its next turn (re-checked at least every 4 ticks), so an unwatched crowd costs one field read per bot per tick instead of the full set of checks. |
| Real-player list | The list of real players (for head glances, detail levels and crowd separation) is kept across ticks and only rebuilt when someone joins or leaves, instead of walking every online player each tick. |
| Route re-checks | When a walking bot's route ahead turns out blocked, the re-plan runs on a worker thread like stuck recovery does (it ran on the main thread before). |
| Knockback / separation pushes | Pushes set the bot's motion directly, without the "sync to the client" flag a bot has no use for (it made the entity tracker fire a `PlayerVelocityEvent` and build a motion packet per push). FPP builds against older server internals, and the field it used to set was renamed in 26.3 (`hurtMarked` -> `syncVelocity`), which threw and caught an error on every push. |
| Crowd separation pass | Each bot's game mode, collidable flag and scoreboard team are read once per pass instead of once per pair (up to 12 pairs per bot), and the edge check reads block types without creating `Block` objects. |
| Combat tick | The combat AI asked "do I carry a shield / totem / golden apple / arrows / the cart kit / a bow" about a dozen times per fighting bot per tick, each a 36-slot inventory scan; they now read one half-second snapshot per bot. The target entity behind the target UUID is cached (it was looked up in every world's entity map each tick), the shield cooldown is cached, gear upkeep (best weapon / armor) runs every half second instead of every tick, a ranged bot's line-of-sight re-check runs every few ticks instead of every tick, and the chase destination (target prediction) is only computed on the ticks the chase actually plans a route. |
| **Bot quality levels** (`performance.quality`) | `auto` (default), `ultra`, `high`, `balanced`, `low`, `potato`. Each bot gets a tier from its distance to the nearest real player (full within the bot-detail full range, reduced within the mid range, minimal beyond); the level decides what each tier gives up - path search budget and route smoothing, head turns toward watchers, leading moving targets and reacting to drawn bows, the width of the elytra obstacle scan - and lower levels stretch the bot-detail intervals and shrink the full-detail range. `auto` starts from the bot count and steps down while the average tick stays above 45 ms, back up below 30 ms (logged; `/fpp perf` shows it). Paper only. |
| **Shared crowd routes** (`pathfinding.flow-fields`) | Once 6 bots ask for a route to about the same spot within a second (a crowd chasing or following one player), one Dijkstra search outward from that spot builds a route table over every walkable block within 48 blocks, and each of those bots reads its route from the table instead of searching. Built on the bot worker threads on Janky Paper; rebuilt where the crowd is headed once that moves 3 blocks. Uses the same walking model and step costs as FPP's A*, minus parkour, block placing/mining and drops that would hurt - bots that need those, or got stuck, search on their own. |
| Path search block reads | FPP's A* reads each block from the world once per search (a per-search table of material, water/climbable/door flags and collision top/bottom) instead of up to dozens of times; full blocks and air skip the collision-shape query. The per-tick walk checks (corner cuts, obstacle steering) share a 250 ms table. |
| Elytra | Obstacle probes above the column's highest non-air block (the `WORLD_SURFACE` height map) are answered without reading blocks, and the ground distance under a high glider comes from the `OCEAN_FLOOR` height map instead of a ray cast. The flight planner memoizes blocked lattice points. |
| Sprinting | FPP only changes a bot's sprint state when it differs (on any server - Janky Paper also fixes it server-side, see [What a bot no longer costs](#what-a-bot-no-longer-costs)). |
| Parallel movement | With `bots.parallel-movement: true`, FPP ticks in two halves and lets Janky Paper move all bots at once between them - see [Parallel bot movement](#parallel-bot-movement-experimental). The console prints `Bot movement runs on the server's parallel movement pass (Janky Paper)` once. |

**Recommended:**
- Keep `bots.view-distance` small (2-4) unless bots must keep farms loaded: every chunk in view is kept
  loaded per bot.
- Leave `bots.vanilla-push: false` and keep FPP's own collision/separation enabled.
- If you don't need mobs to spawn around bots, set `bots.affect-mob-spawning: false`.
- Turn FPP's self-profiler (`performance.self-profiler`) off in production; it adds overhead per bot.

---

## Measured results

Measured on the dev server (Windows, 12 CPU threads, 5-12 GB heap) with the bundled FPP build, all bots spawned at
the same spot - the worst case for crowd costs. MSPT = milliseconds per tick; 50 ms = full 20 TPS.

**Steady state, idle bots** (all bots standing around):

| Bots | Stock behaviour | Round 1 (lightweight bots) | Round 2 (pickup scan) | Current |
|---|---|---|---|---|
| 500 | ~71 ms | ~19 ms | ~14 ms | ~10 ms |
| 2,000 | ~2,500 ms | ~111 ms | ~61 ms | ~40 ms |
| 5,000 | out of memory (4 GB) | ~480 ms | ~150 ms | ~100 ms |
| 10,000 | - | - | ~200 ms | ~230-300 ms right after spawning |

**10,000 bots, current build** (no real player online, so every bot is "far from the camera" and uses FPP's
bot detail levels; with you online, bots within 48 blocks of you run at full detail):

| Scenario | Before bot detail levels | With bot detail levels | Now (mob-side round) |
|---|---|---|---|
| `fpp spawn 10000` (one command) | single ticks of 25-84 s before spawn pacing | ~4 min to place all 10,000 (part of it is Mojang's skin-lookup rate limit), worst tick ~1-3 s | same |
| Idle | ~230-300 ms | ~140 ms (~7 TPS) | **~78 ms (~13 TPS)** |
| `fpp roam @a` | ~380-490 ms | ~190 ms (~5 TPS) | ~200-230 ms (~4.5-5 TPS) |
| `fpp attack @a` (free-for-all) | ~550-650 ms (minutes-long freezes before the combat index) | ~380 ms (~2.6 TPS) | ~390-480 ms (~2-2.5 TPS) |

Run-to-run noise is about +-15%.

**Server-side benchmark, 2026-11-08** (a test plugin spawns 10,000 bare bot players - fake connections, no FPP AI - in
a 24-block radius and drives them with vanilla physics: *nothing* = bots not ticked, *idle* = standing, *walk* = sprinting
in random directions, *fight* = keeping a target and swinging at it). Same machine (6 cores / 12 threads, 12 GB heap;
another server and a game client were running on it too). This isolates what Janky Paper itself costs per bot; FPP's
AI comes on top.

| 10,000 bots | 2026-11-06 | 2026-11-08, start of day | 2026-11-08, end of day |
|---|---|---|---|
| nothing | 137 ms | 60 ms | **37 ms** |
| idle | 414 ms | 134 ms | **95 ms** |
| walk | 613 ms | 202 ms | **120 ms** (103 ms with `parallel-movement`) |
| fight | 1,100-2,900 ms | - | **127 ms** (140 ms with `parallel-movement`) |

The 11-08 changes behind the right-hand column: the inert-sweep block-effect skip (and water counting as inert for
anything not burning), one collision check instead of two for a player's pose, no locator-bar work in a world without
receivers, nearest-player searches bounded in 3D (a mob in a cave under a crowd checked every bot of it), and - for
parallel movement - the block-effect check moved onto the movement workers. What is left at idle is mostly vanilla
physics (standing bots still run gravity and collision every tick - FPP's detail levels skip most of those ticks for
bots away from you) and the mobs around the crowd.

**Round 4 (not measured yet):** the shared bot chunk tickets, idle chunk-loader skip, single-pass bot activation,
finer player grid, grid-based spawning-chunk check, crowded-section index, no-viewer tracker path and cached world
border (server), plus FPP's sitting-out skip, cached real-player list, worker-thread route re-checks and the knockback
fix. They target costs the last round's profiles attributed roughly 35-45% of the 10,000-bot idle tick and 25-35% of
the roam tick to; the table above will be updated once they are benchmarked.

**Round 5 (not measured yet):** FPP's combat tick trimmed (inventory snapshot, cached target entity, cheaper shield /
line-of-sight / gear-upkeep checks, lazy chase prediction - the combat AI was ~26% of the 10,000-bot fight tick) and the
crowd separation pass cheapened, plus the opt-in [parallel bot movement](#parallel-bot-movement-experimental) (bot
physics, ~17-26% of the roam / fight tick, across all cores).

For scale: at 10,000 bots a 20 TPS tick leaves **5 µs per bot** for everything - physics, AI, mobs, chunks. A fighting
bot's physics alone is ~10-20 µs on one core today, and a 4-thread host can parallelise at most ~3.5x. Full-action 10,000-bot
fights at 20 TPS therefore need both the parallel movement and a much cheaper per-bot tick, and realistically more than
4 CPU threads; the settings here (detail levels, focus mode, `bots.*`) are what trades fidelity far from the camera for
that budget.

What is left at 10,000 idle bots is mostly the mobs around them (natural spawning, mob AI and despawn checks -
see `bots.affect-mob-spawning` and `bots.activation-range-multiplier`), per-bot chunk loading, and the server's own
per-player bookkeeping. 20 TPS holds up to roughly 2,000-2,500 bots at full detail on this machine. Results on your host depend mostly on its single-core speed (the main thread), how
spread out the bots are, and what else runs.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Bots behave like real players / no improvement | The startup log should show `Lightweight bots .. on`. `/focus` shows how many bots are online - if it says 0 while bots are online, the plugin's connection is a real socket (not a fake one) and can't be detected. |
| A real player is stuck on "Loading terrain" / sees nothing | That player is being treated as a bot (bots get no chunk or entity packets). Look for `Lightweight bots: players on <class> connections are treated as bots (first: <name>)` in the console naming a real player, and report the class. Builds before this fix did this to every real player when ProtocolLib was installed. `bots.lightweight: false` is the emergency switch. |
| Plugins log `Invalid Bukkit version format: 26.3.local-SNAPSHOT`, `Incompatible version 26.3.local-SNAPSHOT` or `Found Minecraft: 26.3.local` | The jar was built by a Janky Paper that reported `<mc>.local-SNAPSHOT`. Rebuild it: builds now report `26.3.build.<n>-alpha` like Paper's own, which plugin version parsers understand. |
| Nobody can join; the console repeats `Failed to initialize a channel. Closing` with `zip file closed` and a plugin's name | A plugin that failed to enable (usually built for another Minecraft version) left a channel initialization listener behind, and every new connection died in it. Janky Paper now removes those listeners when the plugin is disabled and keeps the connection alive if a listener throws - but update or remove the plugin named in the trace. |
| `Exception stopping the server ... "connection.channel" is null` on shutdown | Those dead connections had no channel to close, which aborted the shutdown **before the worlds were saved**. Fixed; it was a symptom of the row above. |
| A plugin says Minecraft/Paper 26.3 is not supported, or throws on enable with a `1.21.x`/`26.1`/`26.2` version in its name | Not a Janky Paper problem - it does not support 26.3 on Paper either (seen: AxiomPaper built for 1.21.11, InvSee++ 0.31.x, FastAsyncWorldEdit 2.15.x, CoreProtect 24, ViaVersion 5.12.0). Update it. BungeeCord/Velocity plugins (`raknetify-bungee-*.jar`) do not belong in `plugins/`. |
| Bots stall before walking, or walk oddly, after a roam/follow | Set FPP's `pathfinding.async-search: false` and reload FPP. If that fixes it, the worker-thread searches are the cause - report it. |
| Bots don't keep farms / chunks around them loaded | Raise `bots.view-distance` and `bots.simulation-distance` (or FPP's `performance.bot-view-distance`). |
| Bots overlap in a crowd | Expected with `bots.vanilla-push: false` unless the plugin separates them (FPP does, when its collision setting is on). |
| A plugin needs bots to receive chunk/entity packets | Set `bots.lightweight: false`. |
| `/focus` throttles too early or too late | Tune `focus.pressure.engage-mspt` / `release-mspt`, then `/focus reload`. |
| Box-drawing characters show as `?` | Start with `-Dstdout.encoding=UTF-8` or set the terminal to UTF-8. |

---

## Console and log style

Server lifecycle output (boot header, startup banner, shutdown banner, the "Starting/Loading/Saving"
lines) goes through `dev.jankypaper.server.log.JankyLog` and `ServerBanners` in
`janky-paper-server/src/main/java`: a bold `[ᴊᴀɴᴋʏ]` tag, `═`/`─` rules, `── Section ──` headers,
dotted `Key ...... value` rows and `[+] [!] [-]` status badges.

Janky Paper has its own **"amethyst dusk"** palette, chosen not to collide with any sibling project
(FPP is blue/orange, SSMP is red/mint, Haven is green/pink, the Janky web front-end is gold):

| Role | Colour |
|---|---|
| Brand / tag / titles | `#B388FF` amethyst |
| Values | `#D7B8FF` soft lilac |
| Text | `#F3ECFF` lilac white |
| Secondary text, rules, dotted leaders | `#9187A8` dusty mauve, `#5E5675` deep plum |
| OK / warning / error | `#3DF5A0` teal-green, `#FFB454` amber, `#FF5C8A` rose |

Every console line - vanilla, Paper and plugins alike - is prefixed by the `%jankyPrefix` log4j
converter (`janky-paper-server/src/log4jPlugins/java`), which renders `HH:mm:ss LEVEL │` in that
palette; `log4j2.xml` is patched to use it (`janky-paper-server/paper-patches/files/`). If you change
the palette, update both `JankyLog` and `JankyPrefixConverter`. `logs/latest.log` keeps Paper's
standard file format so log parsers and hosting panels keep working, and the startup line still
contains `Done (…)! For help, type "help"` for the same reason.

Player IP addresses are masked (`85.226.***.**`) in every line Paper logs them in - see
`logging.mask-player-ips` in the [configuration reference](#configuration-reference-configjanky-paperyml).

---

## Developing Janky Paper

### Requirements

* Git (the project **must** be cloned, not downloaded as a zip)
* A JDK to run Gradle with (JDK 25 for compiling is auto-provisioned by Gradle if missing)
* **Windows only:** enable long paths in Git before the first `applyAllPatches`, otherwise it fails
  with `Filename too long` while checking out Minecraft's datapack resources:

  ```bash
  git config --global core.longpaths true
  ```

### Building

```bash
./gradlew applyAllPatches      # clone Paper, decompile Minecraft, apply all patches (slow the first time)
./gradlew build                # compile + run the tests (what CI runs after applyAllPatches)
./gradlew createPaperclipJar   # -> janky-paper-server/build/libs/jankypaper-paperclip-<ver>.jar
```

Shortcuts: `patch.sh` / `patch.bat` apply patches, `rb.sh` / `rb.bat` rebuild them.

The version is `<mcVersion>.build.<n>-<channel>` (`channel` from `gradle.properties`; `n` is `BUILD_NUMBER`
when set, otherwise the git commit count) - the same shape as Paper's own builds (`26.3.build.244-stable`),
because plugins parse `Bukkit.getBukkitVersion()` and break on anything else (ItemEdit threw
`Invalid Bukkit version format`, voicechat and NBT-API fell back to degraded modes on `26.3.local-SNAPSHOT`).
`<mcVersion>.local-SNAPSHOT` is only used when there is no git history. The manifest build time falls back to
the commit time when `BUILD_STARTED_AT` is unset, so `/version` no longer shows 1970-01-01.

### Dev server

```bash
./gradlew :janky-paper-server:runDevServer
```

Runs in `run/` with `--nogui`. Knobs: `-Ppaper.runWorkDir=...`, `-Ppaper.runMemoryGb=4`,
`-Ppaper.runDisableWatchdog=true` (useful when stress-testing with thousands of bots, where single ticks
can exceed the watchdog's limit).

### Project layout

| Directory | Tracked in git? | Purpose |
|---|---|---|
| `janky-paper-api/` | yes | Janky Paper API sources (`src/`), patches to Paper API (`paper-patches/`), patch for Paper's API build script |
| `janky-paper-server/` | yes | Janky Paper server sources (`src/main`), patches to Paper server (`paper-patches/`), patches to Minecraft (`minecraft-patches/`) |
| `build-data/` | yes | `dev-imports.txt` (extra vanilla classes to import), `fork.at` (access transformers) |
| `paper-api/`, `paper-server/` | **no** | Paper's sources with our patches applied (generated by `applyAllPatches`) |
| `janky-paper-server/src/minecraft/` | **no** | Decompiled Minecraft sources with our patches applied (generated) |

Fork-only server code (no patches needed):

| Package | Contents |
|---|---|
| `dev.jankypaper.server.bot` | `BotPlayers` (detection, settings, lightweight-bot hooks), `PlayerGrid` (nearest-player index), `BotChunkTickets` (shared chunk tickets), `BotActivation` (entity activation around bots), `BotMovement` (parallel movement phases + plugin hooks), `BotWorkerThreads` (worker threads + loaded-only reads) |
| `dev.jankypaper.server.focus` | `FocusManager` (state, tick, config file), `FocusSettings`, `FocusArea`, `FocusCommand` (`/focus`) |
| `dev.jankypaper.server.log` | `JankyLog`, `ServerBanners` |
| `src/log4jPlugins/java/dev/jankypaper/server/log` | `JankyPrefixConverter` (`%jankyPrefix`) |

Where the patches hook in (each change is marked `// Janky Paper - <why>` in the patched file):

| Patched file | Hook |
|---|---|
| `PlayerList` | bot detection on join, skipped broadcasts, latency refresh, join list |
| `ServerPlayer` | `jankyBot` flag, no vanilla push, no container sync / stats / debug check |
| `ChunkMap`, `NearbyPlayers`, `RegionizedPlayerChunkLoader` | bots out of entity tracking and unused maps, no chunk packets, shared bot chunk tickets (`BotChunkTickets`), idle loader skip; focus chunk-rate scaling and priority; grid-based spawning-chunk check |
| `ActivationRange` | focus activation scaling, single-pass bot activation (`BotActivation`) |
| `EntityGetter`, `ServerEntityGetter`, `TargetingConditions`, `ServerLevel`, `Entity` | player grid (`Entity#setPosRaw` feeds its overflow list); `ServerLevel` also caches the world border |
| `ServerChunkCache`, `NaturalSpawner` | per-player mob cap bookkeeping for bots |
| `ServerEntity` | no-viewer tracker path |
| `Player`, `LivingEntity`, `Entity`, `ServerLevel`, `MinecraftServer` | parallel bot movement: `travel()` capture, deferred block effects, off-main `move()`, deferred position callbacks, deferred sounds / game events, per-tick safety-net flush |
| `ChunkEntitySlices` (Moonrise) | hash index for crowded section lists |
| `Player` | class-indexed pickup scan for bots |
| `PlayerAdvancements` | released progress for bots |
| `ServerWaypointManager` | no locator bar for bots |
| `Commands` | no command tree for bots |
| `Level` | loaded-only reads on bot worker threads |
| `MinecraftServer`, `DedicatedServer` | focus mode tick and start-up |
| `MoonriseCommon` (paper-server) | chunk worker / I/O thread defaults |
| `PaperCommands` (paper-server) | `/focus` registration |

### Making changes

1. Run `./gradlew applyAllPatches`.
2. Edit code in the generated directories (`paper-api/`, `paper-server/`, `janky-paper-server/src/minecraft/`).
   Each of those is a git repository - commit your work there.
   * `git commit` normally -> a **feature patch** (`features/NNNN-name.patch`)
   * amend the base commit (or run `./gradlew fixupPaperServerFilePatches` /
     `fixupMinecraftSourcePatches` etc.) -> **file patches** (`files/...java.patch`)
3. Run the matching rebuild task to regenerate the patch files that are tracked by git:
   * `rebuildPaperPatches` - paper-api (this line has no paper-checkstyle module: Paper added it in 26.2)
   * `rebuildPaperServerPatches` - paper-server
   * `rebuildMinecraftPatches` - Minecraft sources
   * `rebuildAllServerPatches` - paper-server + Minecraft (`rb.sh` / `rb.bat` run everything)
4. Commit the regenerated patches in this repository.

Code that doesn't need to touch Paper/Minecraft internals can go straight into
`janky-paper-api/src/main/java` or `janky-paper-server/src/main/java` - no patches needed.

Patch conventions (inherited from Paper): mark every change with `// Janky Paper - <why>` (or
`// Janky Paper start/end - <why>` around blocks), use fully qualified names in patched files instead
of new imports, and keep hunks as small as possible - every changed line is merge pain when `paperRef`
moves. `AGENTS.md` has the full set of repository notes.

### Updating upstream Paper

Set `paperRef` in `gradle.properties` to the new Paper commit, run `./gradlew applyAllPatches`,
fix any patch conflicts (see `*.rej` files), rebuild patches, commit.

---

## Credits

- **[Pepe-tf](https://github.com/Pepe-tf)** - original creator and owner of Janky Paper
- **[CamerG1011](https://github.com/CamerG1011)** - coder and contributor
- **[Paper](https://papermc.io)** - the PaperMC team and
  [Paper's contributors](https://github.com/PaperMC/Paper/graphs/contributors). Janky Paper is a fork of Paper,
  and almost all of the server is their work; Janky Paper only adds its changes on top. Please consider
  [supporting Paper](https://papermc.io).
- **Spigot, CraftBukkit and Bukkit** - the projects Paper builds on, and their contributors
- **Minecraft** by Mojang Studios

More in [CREDITS.md](CREDITS.md).

**Every fork, clone, copy and modified version of Janky Paper must keep this credit:** Pepe-tf as the
original creator and owner, CamerG1011 as a coder and contributor, and Paper as the project it is built on.
That covers this section, [CREDITS.md](CREDITS.md), the credit headers in the source files and the credit
lines the server prints at startup. It's a condition of the license (GPL-3.0 with the additional terms in
[NOTICE.md](NOTICE.md)).
