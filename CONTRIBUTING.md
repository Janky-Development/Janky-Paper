# Contributing to Janky Paper

Thanks for helping! Janky Paper is a Paper fork for recording servers: thousands of fake-player bots packed into
a few chunks, plus focus mode for the area being filmed. A good change makes that faster or more reliable
without changing gameplay for real players.

- Bugs and ideas: open an [issue](https://github.com/Janky-Development/Janky-Paper/issues/new/choose) first, especially before a bigger change, so we can
  agree on the approach before you spend time on it.
- Security problems: do not open a public issue. See [SECURITY.md](SECURITY.md).
- Problems that also happen on plain Paper belong to [Paper](https://github.com/PaperMC/Paper/issues).

## Setting up

You need Git and a JDK (17+) to run Gradle. Gradle downloads the JDK it compiles with (25, or 21 for 1.21.11).

```bash
git clone https://github.com/Janky-Development/Janky-Paper.git
cd Janky-Paper
./gradlew applyAllPatches      # checks out Paper, decompiles Minecraft, applies our patches
./gradlew build                # compiles and runs the tests, exactly what CI runs
./gradlew :janky-paper-server:runDevServer
```

**Windows:** run `git config --global core.longpaths true` before the first `applyAllPatches`.
Clone with Git; a downloaded zip does not build.

## Where code goes

| Kind of change | Where |
|---|---|
| New fork code that only calls into Paper/Minecraft | `janky-paper-server/src/main/java/dev/jankypaper/...` (no patch needed) |
| Changes to Minecraft classes | edit `janky-paper-server/src/minecraft/java`, then rebuild the patches |
| Changes to Paper classes | edit `paper-server/` or `paper-api/`, then rebuild the patches |

`paper-api/`, `paper-server/` and `janky-paper-server/src/minecraft/` are generated and not tracked. Your edits
there only reach Git through the patch files:

```bash
./gradlew :janky-paper-server:fixupMinecraftSourcePatches   # or fixupPaperServerFilePatches
./gradlew :janky-paper-server:rebuildMinecraftSourcePatches # or rebuildPaperServerFilePatches
```

Run the fixup and rebuild tasks as two separate commands. Then commit the changed `*.patch` files. The README
section [Making changes](README.md#making-changes) has the details.

Patch rules (the same as Paper's):

- Mark every change: `// Janky Paper - <why>` on a line, or `// Janky Paper start - <why>` /
  `// Janky Paper end - <why>` around a block.
- Use fully qualified class names in patched files instead of adding imports.
- Keep hunks as small as possible. Every changed line has to be ported to each Minecraft version and every
  Paper update.
- Put logic in `dev.jankypaper` classes and keep the patch to a call into them where you can.
- New `dev.jankypaper` source files start with the same credit header as the existing ones. Never remove or
  change the credits (the headers, `ServerBanners.CREDITS`, [CREDITS.md](CREDITS.md), the README's Credits
  section); keeping them is a condition of the license ([NOTICE.md](NOTICE.md)).

## Minecraft versions

Every supported Minecraft version lives on **`main`**: the newest is the repository root, the older ones are
complete projects in `versions/<version>/` (`versions.txt` lists them). Open pull requests against `main`; CI
builds the versions your pull request changes. The `ver/<version>` branches are mirrors the release workflow
writes - never open pull requests against them. Say in your pull request if you already know a change can't
work on an older version.

If you want to port a change to an older version yourself, make the same change in its `versions/<version>/`
folder (in the same pull request or a later one) and run `./gradlew applyAllPatches` inside that folder. Port
the hunks that fail, then fixup and rebuild the patches. The README section
[Keeping the versions in sync](README.md#keeping-the-versions-in-sync) lists the pitfalls.

## Testing

- `./gradlew build` must pass. CI runs it on every push and pull request.
- Bot or performance changes: say how you measured them (bot count, TPS/MSPT before and after, a spark
  profile if you have one).
- Behaviour changes: start the dev server and check it in game, or with a bot plugin such as FPP.

## Pull requests

- One topic per pull request, with a title that says what changes. For example: "Skip entity tracking for
  bots".
- Describe what changed and why, how you tested it, and which Minecraft versions it affects.
- Keep `README.md` up to date when behaviour, config keys or commands change.
- Do not change `releaseVersion` in `gradle.properties`. Releases are numbered automatically, and the
  maintainers raise that number on purpose for big releases (see [README - Releases](README.md#releases)).
- Do not commit generated directories, `run/`, or built jars.

## Releases

Releases are published automatically after CI passes (README section [Releases](README.md#releases)).
Contributors don't need to do anything for them.

## License of contributions

By opening a pull request you confirm that you wrote the change, or have the right to submit it, and that it
may be distributed under the project's license. That's GPL-3.0 with the additional terms in
[NOTICE.md](NOTICE.md). You keep the copyright of your contribution.

Janky Paper was created by and is owned by [Pepe-tf](https://github.com/Pepe-tf), with code by
[CamerG1011](https://github.com/CamerG1011) (see [CREDITS.md](CREDITS.md)).

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).
