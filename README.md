# Janky Paper

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
| Minecraft | **26.3** (`mcVersion` in `gradle.properties`) |
| Based on Paper | commit `7e88a0da385c201f00011ce5eef1834d391e4023` (`paperRef`) |
| Channel | ALPHA |
| Java | 25 (Gradle auto-provisions a JetBrains JDK 25 for the dev server) |

---

## Contents

- [Feature overview](#feature-overview)
- [Running a Janky Paper server](#running-a-janky-paper-server)
- [Configuration reference (`config/janky-paper.yml`)](#configuration-reference-configjanky-paperyml)
- [Commands and permissions](#commands-and-permissions)
- [Lightweight bots](#lightweight-bots)
- [Player grid (mob nearest-player searches)](#player-grid-mob-nearest-player-searches)
- [Bot worker threads (off-main-thread path searches)](#bot-worker-threads-off-main-thread-path-searches)
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
java -Xms6G -Xmx6G -jar jankypaper-paperclip-26.3.local-SNAPSHOT.jar --nogui
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

A player counts as a bot when its network connection is **not a real socket**. Every real client
connection (TCP or unix domain socket) is a Netty `DuplexChannel`; fake-player plugins attach a
fake channel instead (FPP's `FakeChannel`, `EmbeddedChannel`, ...). Detection happens once, when the
player is placed into the world, and is stored on the player (`ServerPlayer.jankyBot`). No plugin
changes or API calls are needed, so it works for any fake-player plugin that uses a fake connection.

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
| **Locator bar** | Every player tracked every other player as a waypoint (`bots x bots` connection objects). Bots don't receive a locator bar; they still appear on real players' bars. | bots x bots |
| **Unused nearby-player maps** | Moonrise keeps three "general" nearby-player maps the server never reads; each bot took a slot in every chunk within 33 chunks. | bots x chunks |
| **Vanilla bot-on-bot pushing** | Each bot checked every entity in its hitbox (plus a climbable-block lookup per candidate). Configurable: `bots.vanilla-push`. | bots x bots |
| **Item-pickup scan** | Each bot scanned every nearby entity (including every other bot) each tick. Bots now only look up entity classes that react to a touch: items, XP orbs, arrows/tridents, slimes/sulfur cubes, pufferfish. | bots x bots |
| **Entity activation** | Activation ranges were computed once per bot. Now once per chunk that holds bots (box padded to cover the chunk). | bots x entities |
| **Container sync, stat counter, debug-subscription check** | Per-tick work that only feeds a client. Stat-tracking scoreboards still update for bots. | bots |

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

- Players are bucketed by chunk **once per tick**, the first time a search needs it.
- A search walks rings of chunks outwards from the mob and stops as soon as no unvisited ring can hold
  anything closer than the best match.
- Inside a crowded chunk, players are sorted by X; a search binary-searches its own X and sweeps
  outwards, so in a dense crowd it only checks a thin slice of it. The expensive targeting test (line of
  sight, visibility) only runs on players that would beat the current best.
- Target searches stop at the mob's target range (scaled by the largest `mob_visibility` equipment
  boost anyone is wearing, since datapacks can raise visibility above 1).
- If the rings to visit would cost more than a plain scan (players spread across the world, or nothing in
  range), it falls back to the plain scan.

It returns the same player as the vanilla scan. The only difference: positions are snapshotted once
per tick with 4 blocks of slack, so a player **teleported** more than 4 blocks within the same tick can
be missed until the next tick. The grid is only used when a world has **more than 64 players**; below
that the vanilla scan is cheaper and runs unchanged.

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

Rules for code running on those threads:

- **Read-only.** Only read blocks/fluids (`World#getBlockAt`, `Block#getType`, `getBlockData`,
  collision shapes, ...). Never change the world, entities or inventories, and never call events.
- **Snapshot your inputs on the main thread** (locations, entity state, sets you own) and pass copies in.
- **Apply results on the main thread**, and re-check that the bot still exists and still wants the
  result.
- `API_VERSION` is bumped on incompatible changes - check it.

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

**Recommended:**
- Keep `bots.view-distance` small (2-4) unless bots must keep farms loaded: every chunk in view is kept
  loaded per bot.
- Leave `bots.vanilla-push: false` and keep FPP's own collision/separation enabled.
- Turn FPP's self-profiler (`performance.self-profiler`) off in production; it adds overhead per bot.

---

## Measured results

Measured on the dev server (Windows, 12 CPU threads, 4-5 GB heap) with FPP 0.28.1, all bots spawned
at the same spot - the worst case for crowd costs. MSPT = milliseconds per tick; 50 ms = full 20 TPS.

| Bots | Stock behaviour | After lightweight bots (round 1) | After pickup-scan fix (round 2) |
|---|---|---|---|
| 500 | ~71 ms | ~19 ms | ~14 ms |
| 2,000 | ~2,500 ms | ~111 ms | ~61 ms |
| 5,000 | out of memory (4 GB) | ~480 ms | ~150 ms |
| 10,000 | - | - | ~200 ms idle, ~600-740 ms all roaming |

In the 10,000-bot roaming profile, about half of the tick was mob nearest-player searches; the
[player grid](#player-grid-mob-nearest-player-searches), the command-tree/stat/container cuts and bot
worker threads were added after that and **have not been benchmarked yet**. Results on your host
depend on its single-core speed (the main thread), how spread out the bots are, and what else runs.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Bots behave like real players / no improvement | The startup log should show `Lightweight bots .. on`. `/focus` shows how many bots are online - if it says 0 while bots are online, the plugin's connection is a real socket (not a fake one) and can't be detected. |
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

The version is `<mcVersion>.local-SNAPSHOT` unless `BUILD_NUMBER` is set in the environment.

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
| `janky-paper-checkstyle/` | yes | Patch for Paper's checkstyle module build script (and optional patches to its sources/config) |
| `build-data/` | yes | `dev-imports.txt` (extra vanilla classes to import), `fork.at` (access transformers) |
| `paper-api/`, `paper-server/`, `paper-checkstyle/`, `.checkstyle/` | **no** | Paper's sources with our patches applied (generated by `applyAllPatches`) |
| `janky-paper-server/src/minecraft/` | **no** | Decompiled Minecraft sources with our patches applied (generated) |

Fork-only server code (no patches needed):

| Package | Contents |
|---|---|
| `dev.jankypaper.server.bot` | `BotPlayers` (detection, settings, lightweight-bot hooks), `PlayerGrid` (nearest-player index), `BotWorkerThreads` (worker threads + loaded-only reads) |
| `dev.jankypaper.server.focus` | `FocusManager` (state, tick, config file), `FocusSettings`, `FocusArea`, `FocusCommand` (`/focus`) |
| `dev.jankypaper.server.log` | `JankyLog`, `ServerBanners` |
| `src/log4jPlugins/java/dev/jankypaper/server/log` | `JankyPrefixConverter` (`%jankyPrefix`) |

Where the patches hook in (each change is marked `// Janky Paper - <why>` in the patched file):

| Patched file | Hook |
|---|---|
| `PlayerList` | bot detection on join, skipped broadcasts, latency refresh, join list |
| `ServerPlayer` | `jankyBot` flag, no vanilla push, no container sync / stats / debug check |
| `ChunkMap`, `NearbyPlayers`, `RegionizedPlayerChunkLoader` | bots out of entity tracking and unused maps, no chunk packets; focus chunk-rate scaling and priority |
| `ActivationRange` | focus activation scaling, one activation pass per chunk of bots |
| `EntityGetter`, `ServerEntityGetter`, `TargetingConditions`, `ServerLevel` | player grid |
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
   * `rebuildPaperPatches` - paper-api (and paper-checkstyle)
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
