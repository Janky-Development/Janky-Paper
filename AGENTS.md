# AGENTS.md

Janky Paper is a paperweight 2 fork of PaperMC/Paper (pinned by `paperRef` in `gradle.properties`).
Nothing from Paper is vendored: the root repo tracks only `.patch` files, `build-data/`, and the
fork's own sources. `README.md` is accurate for the human workflow; the notes below are the
non-obvious parts. Upstream rules (patch style, imports, ATs) live in
`.gradle/caches/paperweight/upstreams/paper/CONTRIBUTING.md` after a first apply. That cache
also contains Paper's own `AGENTS.md` (about contributing to PaperMC/Paper); it is not this
repo's policy, and its "do not submit" rules apply to upstream Paper, not to this fork.

## First-run / commands

- On Windows use `gradlew.bat` and run `git config --global core.longpaths true` first, or
  `applyAllPatches` fails with `Filename too long`.
- Nothing compiles until `./gradlew applyAllPatches` has generated `paper-api/`, `paper-server/`,
  `paper-checkstyle/`, `.checkstyle/`, `janky-paper-server/src/minecraft/` and the three
  `janky-paper-*/build.gradle.kts`. All of those are gitignored; never commit them.
- `./gradlew build` compiles + tests everything. CI (`.github/workflows/build.yml`) runs exactly
  `applyAllPatches` then `build`.
- Server jar: `./gradlew createPaperclipJar` ->
  `janky-paper-server/build/libs/jankypaper-paperclip-<ver>.jar`.
- Dev server: `./gradlew :janky-paper-server:runDevServer` (working dir `run/`, `--nogui`,
  needs a JetBrains JDK 25 which foojay auto-provisions). Knobs: `-Ppaper.runWorkDir=...`,
  `-Ppaper.runMemoryGb=2`, `-Ppaper.runDisableWatchdog=true`.
- Tests: `:janky-paper-server:test` only picks up classes named `*TestSuite` and excludes the
  `Slow` tag; `:janky-paper-api:test` and `:janky-paper-checkstyle:test` are normal JUnit 5.
  Checkstyle (Paper's config) runs on the API via `:janky-paper-api:checkstyleMain`.
- Version is `<mcVersion>.local-SNAPSHOT` unless `BUILD_NUMBER` is set in the environment.
- Configuration cache and build cache are on; `./gradlew tasks --all` is slow (minutes).

## Where code lives and how it gets tracked

Three different edit/commit models coexist. Pick the right one before editing.

1. **Fork-only code (no patches).** `janky-paper-server/src/main/java`,
   `janky-paper-server/src/log4jPlugins/java`, `janky-paper-api/src/main/java`. Normal files,
   commit them in the root repo. Paper and Minecraft code can reference these classes.
2. **Paper sources** (`paper-api/`, `paper-server/`, `paper-checkstyle/`). Each is its own git repo
   whose HEAD is the `... File Patches` commit on top of `Import repo from upstream`.
   - Small edits: edit, then `./gradlew fixupPaperServerFilePatches` (or
     `fixupPaperApiFilePatches`) folds the working tree into that commit, then rebuild.
   - A new `git commit` in that repo becomes a feature patch (`features/NNNN-name.patch`).
   - Rebuild: `rebuildPaperServerPatches` (server), `rebuildPaperPatches` (api + checkstyle).
   - Patch output: `janky-paper-server/paper-patches/files/<path>.patch`,
     `janky-paper-api/paper-patches/`.
3. **Minecraft sources.** The git repos are at `janky-paper-server/src/minecraft/java/.git` and
   `src/minecraft/resources/.git` (not `src/minecraft` itself). Same fixup flow:
   `fixupMinecraftSourcePatches` / `fixupMinecraftResourcePatches`, then
   `rebuildMinecraftPatches`. Output: `janky-paper-server/minecraft-patches/sources/<path>.patch`.

Other patch facts:

- `rebuildServerPatches` only rebuilds paper-server patches (README says "both"); use
  `rebuildAllServerPatches` for paper-server + Minecraft, or `rb.sh` / `rb.bat` which run all
  rebuild tasks.
- `janky-paper-*/build.gradle.kts` are generated from the sibling `build.gradle.kts.patch`
  (defined as `patchFile` blocks in the root `build.gradle.kts`). Edit the generated file, then
  `./gradlew rebuildPaperSingleFilePatches` to update the `.patch`; editing the `.patch` by hand
  or the generated file alone is lost on the next apply.
- The fork's paperweight `forks.register("jankypaper")` block (which wires `paper-server/` as a
  source dir of `janky-paper-server`) lives inside `janky-paper-server/build.gradle.kts.patch`,
  not in the root build script.
- `build-data/fork.at` (access transformers) and `build-data/dev-imports.txt` (extra vanilla
  classes to decompile) are applied by `runJankypaperSetup` during `applyAllPatches`.
  Do not hand-edit `fork.at`: add `// Paper-AT: <at line>` next to the member in the Minecraft
  source; `rebuildMinecraftPatches` moves it into `fork.at` and strips the comment.
  `dev-imports.txt` is hand-edited.
- After rebuilding, the tracked `.patch` files are what you commit in the root repo. Patch
  hunks use `@@ -N,M +_,M @@` headers; do not hand-edit them.

## Patch code conventions (inherited from Paper)

- Mark every change in Paper/Minecraft files: `// Janky Paper - <why>` on one-line edits,
  `// Janky Paper start - <why>` / `// Janky Paper end - <why>` around blocks.
- In vanilla/Paper classes use fully qualified names (e.g.
  `dev.jankypaper.server.log.JankyLog.info(...)`) instead of adding imports, to keep hunks small
  and avoid import conflicts on upstream updates.
- Keep hunks minimal; every extra changed line is future merge pain when `paperRef` moves.

## Console / log output

- Lifecycle output goes through `JankyLog` (`info/success/warn/error/highlight/kv/section/
  rule/state/title`) and `ServerBanners` in `janky-paper-server/src/main/java/dev/jankypaper/server/log/`.
  The per-line `%jankyPrefix` converter is `JankyPrefixConverter` in `src/log4jPlugins/java`
  and is wired by the `log4j2.xml` file patch. The colour palette is duplicated in `JankyLog`
  and `JankyPrefixConverter`; change both.
- Keep `logs/latest.log` in Paper's standard format and keep the literal
  `Done (...)! For help, type "help"` startup line; hosting panels and log parsers depend on it.

## Updating upstream

Bump `paperRef`, run `applyAllPatches`, resolve any `*.rej` files in the generated repos, rebuild
patches, commit the patches. `applyOrMove*FilePatches` / `apply*FilePatchesFuzzy` tasks exist
for partially-applying updates.
