/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.version;

import dev.jankypaper.server.log.JankyLog;
import io.papermc.paper.ServerBuildInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Update checks in the log, and automatic updates ({@code updates} in {@code janky-paper.yml}).
 *
 * <p>Once the server is up, and then every {@code check-interval-hours}, {@link JankyVersion#checkForUpdates()} asks
 * GitHub whether a newer release changed this Minecraft version's code; the result goes to the console (the periodic
 * checks only log when something changed, so a server that is up to date stays quiet).</p>
 *
 * <p>With {@code auto-update} on, an outdated server downloads the newest jar <b>for its own Minecraft version</b>
 * (never another one), checks its size and SHA-256 against what GitHub reports, keeps a copy of the running jar in
 * {@code cache/janky-update/previous/} and puts the new jar in place of the one the server was started with, so the
 * next restart runs it. On Linux and macOS the swap happens right away (the running server keeps the old file open);
 * Windows locks a running jar, so there a small script swaps it as soon as this server process has exited. Nothing is
 * restarted for you. Only servers started as {@code java -jar <paperclip jar>} can update themselves.</p>
 */
@NullMarked
public final class JankyUpdater {

    private static final Path WORK_DIR = Path.of("cache", "janky-update");
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30_000;

    // settings (janky-paper.yml, section "updates")
    private static volatile boolean checkEnabled = true;
    private static volatile int intervalHours = 6;
    private static volatile boolean autoUpdate = true;

    private static @Nullable ScheduledExecutorService scheduler;
    /** What the last check logged, so periodic checks only speak up when it changes. */
    private static @Nullable String lastLogged;
    /** {@link JankyVersion.Release#identity()} of the jar already in place (or waiting for this process to exit on Windows). */
    private static volatile @Nullable String installedRelease;
    private static boolean windowsHookArmed;

    private JankyUpdater() {
    }

    // ---------------------------------------------------------------- settings

    public static void read(final ConfigurationSection section) {
        checkEnabled = section.getBoolean("check", true);
        intervalHours = Math.max(0, Math.min(24 * 7, section.getInt("check-interval-hours", 6)));
        autoUpdate = section.getBoolean("auto-update", true);
    }

    public static void write(final ConfigurationSection section) {
        section.set("check", checkEnabled);
        section.set("check-interval-hours", intervalHours);
        section.set("auto-update", autoUpdate);
    }

    public static boolean checkEnabled() {
        return checkEnabled;
    }

    /** Whether this release's jar is already installed for the next restart (by build, not by release number). */
    public static boolean installed(final JankyVersion.Release release) {
        return release.identity().equals(installedRelease);
    }

    // ---------------------------------------------------------------- checks

    /** The start-up check (async, from {@link JankyVersion#logStartupStatus()}); also starts the periodic checks. */
    public static void startupCheck() {
        check(true);
        startPeriodicChecks();
    }

    private static synchronized void startPeriodicChecks() {
        if (scheduler != null || intervalHours <= 0 || !checkEnabled) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "Janky Paper update checker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                check(false);
            } catch (final RuntimeException ex) {
                JankyLog.warn("Update check failed: " + ex);
            }
        }, intervalHours, intervalHours, TimeUnit.HOURS);
    }

    private static synchronized void check(final boolean startup) {
        final JankyVersion.Status status = JankyVersion.checkForUpdates();
        final String key = switch (status) {
            case JankyVersion.Status.Outdated outdated -> "outdated:" + outdated.release().identity();
            case JankyVersion.Status.Failed ignored -> null; // a hiccup: say nothing new until it works again
            default -> status.getClass().getSimpleName();
        };
        if (!startup && (key == null || key.equals(lastLogged))) {
            return;
        }
        if (key != null) {
            lastLogged = key;
        }
        final String mc = ServerBuildInfo.buildInfo().minecraftVersionName();
        switch (status) {
            case JankyVersion.Status.Latest latest -> JankyLog.success("You are running the latest Janky Paper release for Minecraft " + mc + " (" + latest.release() + ")");
            case JankyVersion.Status.Outdated outdated -> outdated(outdated.release(), mc);
            case JankyVersion.Status.Ahead ahead -> JankyLog.info("This build is newer than the latest Janky Paper release (" + ahead.release() + ") - a development build, not updated automatically");
            case JankyVersion.Status.NoRelease ignored -> JankyLog.info("No Janky Paper release for Minecraft " + mc + " yet - see " + JankyVersion.RELEASES_URL);
            case JankyVersion.Status.Custom ignored -> JankyLog.info("Update check skipped - this commit isn't on the public Janky Paper repository");
            case JankyVersion.Status.Failed ignored -> JankyLog.warn("Could not check for Janky Paper updates (GitHub unreachable or rate-limited) - trying again " + (intervalHours > 0 ? "in " + intervalHours + "h" : "on the next start"));
            case JankyVersion.Status.Disabled ignored -> {
            }
        }
    }

    private static void outdated(final JankyVersion.Release release, final String mc) {
        if (installed(release)) {
            JankyLog.success("Janky Paper " + release.describe() + " is installed - restart the server to run it");
            return;
        }
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        final String ownId = JankyVersion.buildId();
        JankyLog.warn("Janky Paper " + release.describe() + " is out for Minecraft " + mc + " (this server runs "
            + info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE) + (ownId != null ? ", build " + ownId : "") + ")");
        if (JankyVersion.localBuild() && System.getProperty("jankypaper.updater.testCommit") == null) {
            JankyLog.info("This jar was built locally, so it is not replaced automatically. Download the release from " + release.pageUrl());
            return;
        }
        if (!autoUpdate) {
            JankyLog.warn("Download it from " + release.pageUrl() + " (or set updates.auto-update: true in config/janky-paper.yml)");
            return;
        }
        try {
            install(release);
        } catch (final IOException | RuntimeException ex) {
            JankyLog.error("Automatic update to " + release.describe() + " failed: " + ex.getMessage());
            JankyLog.warn("Download it yourself from " + release.pageUrl());
        }
    }

    // ---------------------------------------------------------------- auto-update

    private static void install(final JankyVersion.Release release) throws IOException {
        final Path jar = launchedJar();
        if (jar == null) {
            JankyLog.warn("Not updating automatically: the server wasn't started with 'java -jar <Janky Paper jar>'. Download it from " + release.pageUrl());
            return;
        }
        final Path downloaded = download(release);

        final Path previous = WORK_DIR.resolve("previous");
        Files.createDirectories(previous);
        Files.copy(jar, previous.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        // stage next to the jar, so the final move stays on one file system and can be atomic
        final Path staged = jar.resolveSibling("." + jar.getFileName() + ".janky-update");
        Files.copy(downloaded, staged, StandardCopyOption.REPLACE_EXISTING);
        try {
            moveOver(staged, jar);
            installedRelease = release.identity();
            JankyLog.success("Janky Paper " + release.describe() + " installed as " + jar.getFileName() + " - restart the server to run it");
            JankyLog.info("The previous jar is kept in " + previous.resolve(jar.getFileName()));
        } catch (final FileSystemException locked) {
            if (!isWindows()) {
                Files.deleteIfExists(staged);
                throw locked;
            }
            armWindowsSwap(staged, jar);
            installedRelease = release.identity();
            JankyLog.success("Janky Paper " + release.describe() + " downloaded - it replaces " + jar.getFileName() + " as soon as the server stops; restart to run it");
            JankyLog.info("The previous jar is kept in " + previous.resolve(jar.getFileName()));
        }
    }

    private static void moveOver(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The jar this server was started from with {@code java -jar}, when it is a Paperclip jar; null otherwise. */
    private static @Nullable Path launchedJar() {
        final String classPath = System.getProperty("java.class.path", "");
        if (classPath.isEmpty() || classPath.contains(java.io.File.pathSeparator) || !classPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return null; // started from an IDE, Gradle (runDevServer) or a custom launcher
        }
        final Path jar = Path.of(classPath).toAbsolutePath();
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            final String main = file.getManifest() == null ? null : file.getManifest().getMainAttributes().getValue("Main-Class");
            return main != null && main.startsWith("io.papermc.paperclip.") ? jar : null;
        } catch (final IOException ex) {
            return null;
        }
    }

    /** Downloads the release's jar into cache/janky-update/ (or reuses an earlier verified download) and verifies it. */
    private static Path download(final JankyVersion.Release release) throws IOException {
        Files.createDirectories(WORK_DIR);
        final Path target = WORK_DIR.resolve(release.asset());
        if (Files.isRegularFile(target) && verified(target, release)) {
            return target;
        }
        JankyLog.info(String.format(Locale.ROOT, "Downloading %s (%.1f MB)...", release.asset(), release.size() / 1_048_576.0));
        final Path part = WORK_DIR.resolve(release.asset() + ".part");
        final HttpURLConnection connection = JankyVersion.open(release.downloadUrl(), DOWNLOAD_READ_TIMEOUT_MS);
        final int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("GitHub answered " + code + " for " + release.downloadUrl());
        }
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
            in.transferTo(out);
        }
        if (!verified(part, release)) {
            Files.deleteIfExists(part);
            throw new IOException("the downloaded jar does not match the release (size or SHA-256) - not installed");
        }
        moveOver(part, target);
        JankyLog.info("Downloaded and verified " + release.asset() + (release.sha256() != null ? " (SHA-256 matches the release)" : ""));
        return target;
    }

    private static boolean verified(final Path file, final JankyVersion.Release release) throws IOException {
        if (Files.size(file) != release.size()) {
            return false;
        }
        if (release.sha256() != null && !release.sha256().equalsIgnoreCase(sha256(file))) {
            return false;
        }
        try (JarFile jar = new JarFile(file.toFile())) {
            return jar.getManifest() != null; // a real jar, not an error page
        } catch (final IOException ex) {
            return false;
        }
    }

    private static String sha256(final Path file) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                final byte[] buffer = new byte[1 << 16];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
    }

    // ---------------------------------------------------------------- Windows: swap after exit

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Windows keeps a running jar locked, so the swap waits for this process: a shutdown hook starts a script that
     * waits until this process id is gone, then moves the staged jar over the server jar (retrying for a minute, in
     * case a restart script grabs the jar first - then the next start downloads nothing and simply arms it again).
     */
    private static synchronized void armWindowsSwap(final Path staged, final Path jar) throws IOException {
        if (windowsHookArmed) {
            return;
        }
        final Path script = WORK_DIR.resolve("apply-update.cmd").toAbsolutePath();
        final String sys = "\"%SystemRoot%\\System32\\"; // full paths: a PATH with Git or Cygwin tools first would run their find/ping
        final String wait = "  " + sys + "ping.exe\" -n 2 127.0.0.1 >nul";
        final String body = String.join("\r\n",
            "@echo off",
            "rem Janky Paper - puts the downloaded update in place once the server process has exited.",
            "setlocal",
            "set \"PID=%~1\"",
            "for /l %%i in (1,1,300) do (",
            "  " + sys + "tasklist.exe\" /FI \"PID eq %PID%\" /NH 2>nul | " + sys + "find.exe\" \" %PID% \" >nul || goto swap",
            wait,
            ")",
            ":swap",
            "for /l %%i in (1,1,60) do (",
            "  move /Y \"" + staged.toAbsolutePath() + "\" \"" + jar + "\" >nul 2>&1 && goto done",
            wait,
            ")",
            "echo Could not replace " + jar + " - the update stays in " + staged.toAbsolutePath(),
            "exit /b 1",
            ":done",
            "echo Installed the Janky Paper update as " + jar,
            "");
        Files.writeString(script, body, StandardCharsets.UTF_8);
        final String pid = Long.toString(ProcessHandle.current().pid());
        final Path log = WORK_DIR.resolve("apply-update.log").toAbsolutePath();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder("cmd.exe", "/c", script.toString(), pid)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            } catch (final IOException ex) {
                System.err.println("Janky Paper: could not start the update swap (" + ex.getMessage() + ") - the update is in " + staged);
            }
        }, "Janky Paper update swap"));
        windowsHookArmed = true;
    }
}
