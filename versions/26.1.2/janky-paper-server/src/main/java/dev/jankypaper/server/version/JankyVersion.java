/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.version;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.jankypaper.server.log.JankyLog;
import io.papermc.paper.ServerBuildInfo;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code /version} ({@code /ver}, {@code /about}, {@code /paper version}) shows, and the update check behind it,
 * the start-up notice and {@link JankyUpdater}. Replaces Paper's checks, which asked PaperMC's downloads API and the
 * PaperMC/Paper repository about builds and commits that only exist in Janky Paper.
 *
 * <p>The check reads the {@value #MANIFEST} of the newest release on {@value #REPOSITORY}: for every Minecraft version
 * it names the {@code main} commit and folder the released jar's code came from and the jar's file name. GitHub then
 * compares this jar's commit with that one; when the release is ahead and changed this version's code (its folder,
 * without docs), this jar is outdated. Comparing code instead of commits matters: every version is built from
 * {@code main}, so a change to another Minecraft version must not make this one look old. Builds from commits GitHub
 * does not know (local or unpushed) report "custom build"; a failed request never prints a stack trace.</p>
 */
@NullMarked
public final class JankyVersion {

    public static final String REPOSITORY = "Janky-Development/Janky-Paper";
    public static final String REPOSITORY_URL = "https://github.com/" + REPOSITORY;
    public static final String RELEASES_URL = REPOSITORY_URL + "/releases";
    public static final String PAPER_URL = "https://github.com/PaperMC/Paper";
    static final String MANIFEST = "release-manifest.json";
    private static final String API = "https://api.github.com/repos/" + REPOSITORY;

    // Chat colours: the same "amethyst dusk" palette JankyLog uses in the console.
    private static final TextColor PRIMARY = TextColor.color(0xB388FF);
    private static final TextColor SOFT = TextColor.color(0xD7B8FF);
    private static final TextColor TEXT = TextColor.color(0xF3ECFF);
    private static final TextColor MUTED = TextColor.color(0x9187A8);
    private static final TextColor SUCCESS = TextColor.color(0x3DF5A0);
    private static final TextColor WARN = TextColor.color(0xFFB454);

    private static final int TIMEOUT_MS = 5000;
    /** GitHub lists at most this many changed files in a comparison; a full list means "maybe more". */
    private static final int COMPARE_FILE_LIMIT = 300;
    /** Files whose changes never change a server jar (relative to a version's folder) - same list as janky_lines.py. */
    private static final Pattern NOT_CODE = Pattern.compile(
        "^(.*\\.md|\\.github/.*|\\.idea/.*|\\.gitignore|\\.gitattributes|\\.editorconfig|LICENSE.*|versions\\.txt|build-all-versions\\.(sh|bat))$");
    private static final DateTimeFormatter BUILD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Logger LOGGER = LoggerFactory.getLogger(JankyVersion.class);
    private static final Gson GSON = new Gson();

    private JankyVersion() {
    }

    /**
     * A released server jar for this Minecraft version.
     *
     * @param version the Janky Paper release that holds the jar ({@code 1.0.9})
     * @param newest the newest Janky Paper release (a patch release may keep an older release's jar for this version)
     * @param asset the jar's file name ({@code janky-paper-26.3-1.0.9.jar})
     * @param sha256 the jar's SHA-256 as GitHub reports it, or null when it does not
     * @param pageUrl the newest release's page
     * @param build the released jar's build id ({@code Janky-Build-Id}), or null for releases from before build ids
     */
    public record Release(String version, String newest, String asset, String downloadUrl, long size, @Nullable String sha256, String pageUrl, @Nullable String build) {

        /** What tells this jar apart from every other build: its build id, else its SHA-256, else its file name. */
        public String identity() {
            return this.build != null ? this.build : this.sha256 != null ? this.sha256 : this.asset;
        }

        /** {@code 1.0.9 (build 3fa9c1d20b7e)} - the build id matters when a release number is reused. */
        public String describe() {
            return this.version + (this.build != null ? " (build " + this.build + ")" : "");
        }
    }

    private static final String BUILD_ID_ATTRIBUTE = "Janky-Build-Id";
    private static @Nullable String buildId;
    private static boolean buildIdRead;

    /**
     * This jar's build id: random for every build ({@code Janky-Build-Id} in the server jar's manifest; CI writes the
     * same id into the release manifest). {@code local-...} for builds made outside CI; null for jars from before
     * build ids.
     */
    public static synchronized @Nullable String buildId() {
        if (!buildIdRead) {
            buildIdRead = true;
            try {
                final String value = io.papermc.paper.util.JarManifests.manifest(org.bukkit.craftbukkit.CraftServer.class)
                    .getMainAttributes().getValue(BUILD_ID_ATTRIBUTE);
                buildId = value == null || value.isBlank() ? null : value.trim();
            } catch (final RuntimeException ex) {
                buildId = null;
            }
        }
        return buildId;
    }

    /** Whether this jar was built outside CI (by hand, an IDE, a fork) - such jars are never replaced automatically. */
    public static boolean localBuild() {
        final String id = buildId();
        return id != null && id.startsWith("local-");
    }

    /** Where this build stands against the newest release. */
    public sealed interface Status {
        /** The newest release's code for this Minecraft version. */
        record Latest(String release) implements Status {}

        /** A newer release changed this Minecraft version's code. */
        record Outdated(Release release) implements Status {}

        /** Built from newer code than the newest release (a CI or local build of main). */
        record Ahead(String release) implements Status {}

        /** No release has a jar for this Minecraft version yet. */
        record NoRelease() implements Status {}

        /** Not comparable: no git info, a commit GitHub doesn't have, or a commit off main. */
        record Custom() implements Status {}

        /** The update checker is turned off (paper-global.yml update-checker.enabled or janky-paper.yml updates.check). */
        record Disabled() implements Status {}

        /** GitHub could not be asked (offline, rate-limited, unexpected answer). */
        record Failed() implements Status {}
    }

    /** Asks GitHub where this build stands. Blocking (network); call off the main thread. */
    public static Status checkForUpdates() {
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().updateChecker.enabled || !JankyUpdater.checkEnabled()) {
            return new Status.Disabled();
        }
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        // -Djankypaper.updater.testCommit=<sha>: check as if this jar were built from that commit (testing the updater)
        final Optional<String> commit = Optional.ofNullable(System.getProperty("jankypaper.updater.testCommit")).or(info::gitCommit);
        if (commit.isEmpty()) {
            return new Status.Custom();
        }
        try {
            final JsonObject latest = getJson(API + "/releases/latest");
            if (latest == null) {
                return new Status.NoRelease();
            }
            final String newest = version(latest.get("tag_name").getAsString());
            final JsonObject manifestAsset = asset(latest, MANIFEST);
            if (manifestAsset == null) {
                return new Status.NoRelease();
            }
            final JsonObject manifest = getJson(manifestAsset.get("browser_download_url").getAsString());
            final JsonObject lines = manifest == null ? null : manifest.getAsJsonObject("lines");
            final JsonObject entry = lines == null ? null : lines.getAsJsonObject(info.minecraftVersionName());
            if (entry == null) {
                return new Status.NoRelease();
            }
            final String released = entry.get("release").getAsString();
            final String path = entry.has("path") ? entry.get("path").getAsString() : "";
            final String releasedBuild = entry.has("build") ? entry.get("build").getAsString() : null;
            if (releasedBuild != null && releasedBuild.equals(buildId())) {
                return new Status.Latest(released + ", build " + releasedBuild); // exactly the released jar
            }

            final JsonObject comparison = getJson(API + "/compare/" + commit.get() + "..." + entry.get("commit").getAsString());
            if (comparison == null) {
                return new Status.Custom(); // a commit never pushed to the Janky Paper repository
            }
            return switch (comparison.get("status").getAsString()) {
                case "identical" -> new Status.Latest(released);
                case "behind" -> new Status.Ahead(released); // the release's commit is older than this jar's
                case "ahead" -> changesCode(comparison, path)
                    ? new Status.Outdated(release(entry, newest, latest))
                    : new Status.Latest(released); // only other versions (or docs) changed since this jar was built
                default -> new Status.Custom(); // "diverged": built from a branch that isn't main
            };
        } catch (final IOException | JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException | ClassCastException e) {
            LOGGER.debug("Janky Paper update check failed", e);
            return new Status.Failed();
        }
    }

    /** Whether a comparison touches the code of the version in {@code path} ('' = the repository root, the old layout). */
    private static boolean changesCode(final JsonObject comparison, final String path) {
        final JsonArray files = comparison.getAsJsonArray("files");
        if (files == null || files.size() >= COMPARE_FILE_LIMIT) {
            return true; // GitHub cut the list short: assume this version changed
        }
        final String prefix = path.isEmpty() ? "" : path + "/";
        for (final JsonElement file : files) {
            final String name = file.getAsJsonObject().get("filename").getAsString();
            if (!name.startsWith(prefix) || (prefix.isEmpty() && name.startsWith("versions/"))) {
                continue;
            }
            if (!NOT_CODE.matcher(name.substring(prefix.length())).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The release holding this version's jar: the newest one, or an older one whose jar a patch release kept. */
    private static Release release(final JsonObject entry, final String newest, final JsonObject latest) throws IOException {
        final String version = entry.get("release").getAsString();
        final String assetName = entry.get("asset").getAsString();
        JsonObject holder = latest;
        if (!version.equals(newest)) {
            holder = getJson(API + "/releases/tags/v" + version);
            if (holder == null) {
                throw new IOException("release v" + version + " not found");
            }
        }
        final JsonObject jar = asset(holder, assetName);
        if (jar == null) {
            throw new IOException(assetName + " not found in release v" + version);
        }
        final String digest = jar.has("digest") && !jar.get("digest").isJsonNull() ? jar.get("digest").getAsString() : null;
        return new Release(version, newest, assetName, jar.get("browser_download_url").getAsString(), jar.get("size").getAsLong(),
            digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null,
            latest.get("html_url").getAsString(),
            entry.has("build") ? entry.get("build").getAsString() : null);
    }

    private static @Nullable JsonObject asset(final JsonObject release, final String name) {
        for (final JsonElement asset : release.getAsJsonArray("assets")) {
            if (name.equals(asset.getAsJsonObject().get("name").getAsString())) {
                return asset.getAsJsonObject();
            }
        }
        return null;
    }

    private static String version(final String tag) {
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /** GET as JSON; null on 404. Throws on any other non-200 answer (403/429 once rate-limited). */
    private static @Nullable JsonObject getJson(final String url) throws IOException {
        final HttpURLConnection connection = open(url, TIMEOUT_MS);
        if (url.startsWith("https://api.github.com/")) {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
        }
        final int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("GitHub answered " + code + " for " + url);
        }
        try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonObject.class);
        }
    }

    static HttpURLConnection open(final String url, final int readTimeoutMs) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(true); // release downloads redirect to GitHub's file host
        connection.setRequestProperty("User-Agent", "JankyPaper/" + ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE) + " (" + REPOSITORY_URL + ")");
        return connection;
    }

    /** Start-up notice, printed asynchronously once the server is up (MinecraftServer, if the update checker is on). */
    public static void logStartupStatus() {
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        if (info.buildNumber().isEmpty() && info.gitCommit().isEmpty()) {
            JankyLog.warn("Development build without version information - update checks skipped");
            return;
        }
        JankyUpdater.startupCheck();
    }

    /**
     * The whole {@code /version} card: title, Minecraft / build / API rows, update status, releases link, credits and,
     * after an update, the previous version. Blocking (network); call off the main thread.
     */
    public static Component versionMessage(final @Nullable String previousVersion) {
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        final TextComponent.Builder card = Component.text()
            .append(Component.text("[ᴊᴀɴᴋʏ] " + JankyLog.NAME, PRIMARY, TextDecoration.BOLD))
            .append(Component.text(" " + info.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE), SOFT));

        row(card, "Minecraft", Component.text(info.minecraftVersionName(), SOFT));
        row(card, "Build", Component.text(build(info), SOFT));
        row(card, "API", Component.text(Bukkit.getBukkitVersion(), SOFT));
        row(card, "Updates", updates(info));
        row(card, "Releases", link(RELEASES_URL.substring("https://".length()), RELEASES_URL));

        card.appendNewline().append(Component.text("  Created and owned by ", MUTED))
            .append(person("Pepe-tf"))
            .append(Component.text(" · code by ", MUTED))
            .append(person("CamerG1011"));
        card.appendNewline().append(Component.text("  Built on ", MUTED))
            .append(link("PaperMC/Paper", PAPER_URL));

        if (previousVersion != null) {
            card.appendNewline().append(Component.text("  Previous version: " + previousVersion, MUTED, TextDecoration.ITALIC));
        }
        return card.build();
    }

    private static Component updates(final ServerBuildInfo info) {
        if (info.buildNumber().isEmpty() && info.gitCommit().isEmpty()) {
            return Component.text("Development build without version information", WARN);
        }
        return switch (checkForUpdates()) {
            case Status.Latest latest -> Component.text("You are running the latest release (" + latest.release() + ")", SUCCESS);
            case Status.Outdated outdated -> JankyUpdater.installed(outdated.release())
                ? Component.text("Janky Paper " + outdated.release().describe() + " is installed - restart the server to run it", SUCCESS)
                : Component.text("Janky Paper " + outdated.release().describe() + " is out - update from the releases below", WARN);
            case Status.Ahead ahead -> Component.text("Newer than the latest release (" + ahead.release() + ") - a development build", MUTED);
            case Status.NoRelease ignored -> Component.text("No release for Minecraft " + info.minecraftVersionName() + " yet", MUTED);
            case Status.Custom ignored -> Component.text("Can't check - this commit isn't on the public Janky Paper repository", MUTED);
            case Status.Disabled ignored -> Component.text("Update checker disabled (paper-global.yml / janky-paper.yml)", MUTED);
            case Status.Failed ignored -> Component.text("Could not reach GitHub to check", WARN);
        };
    }

    /** {@code #142 · main@a1b2c3d · id 3fa9c1d20b7e · 2026-11-14 10:22 UTC}; DEV when there is no build number. */
    private static String build(final ServerBuildInfo info) {
        final StringBuilder sb = new StringBuilder();
        sb.append(info.buildNumber().isPresent() ? "#" + info.buildNumber().getAsInt() : "DEV");
        if (info.gitCommit().isPresent()) {
            sb.append(" · ");
            info.gitBranch().ifPresent(branch -> sb.append(branch).append('@'));
            sb.append(info.gitCommit().get());
        }
        final String id = buildId();
        if (id != null) {
            sb.append(" · id ").append(id);
        }
        sb.append(" · ").append(BUILD_TIME.format(info.buildTime().truncatedTo(ChronoUnit.MINUTES)));
        return sb.toString();
    }

    private static void row(final TextComponent.Builder card, final String label, final Component value) {
        card.appendNewline()
            .append(Component.text("  " + label + ": ", MUTED))
            .append(value);
    }

    private static Component link(final String text, final String url) {
        return Component.text(text, PRIMARY, TextDecoration.UNDERLINED)
            .hoverEvent(HoverEvent.showText(Component.text("Click to open " + url, TEXT)))
            .clickEvent(ClickEvent.openUrl(url));
    }

    private static Component person(final String name) {
        return Component.text(name, SOFT)
            .hoverEvent(HoverEvent.showText(Component.text("https://github.com/" + name, TEXT)))
            .clickEvent(ClickEvent.openUrl("https://github.com/" + name));
    }

}
