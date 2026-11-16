/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.version;

import com.google.gson.Gson;
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
 * What {@code /version} ({@code /ver}, {@code /about}, {@code /paper version}) shows, and the update check behind it
 * and behind the start-up notice. Replaces Paper's checks, which asked PaperMC's downloads API and the PaperMC/Paper
 * repository about builds and commits that only exist in Janky Paper.
 *
 * <p>The check asks GitHub how far this jar's commit is behind the branch it was built from on
 * {@value #REPOSITORY} ({@code main}, {@code ver/26.2}, ...). Builds from commits GitHub does not know (local or
 * unpushed) report "custom build"; a failed request never prints a stack trace.</p>
 */
@NullMarked
public final class JankyVersion {

    public static final String REPOSITORY = "Janky-Development/Janky-Paper";
    public static final String REPOSITORY_URL = "https://github.com/" + REPOSITORY;
    public static final String RELEASES_URL = REPOSITORY_URL + "/releases";
    public static final String PAPER_URL = "https://github.com/PaperMC/Paper";

    // Chat colours: the same "amethyst dusk" palette JankyLog uses in the console.
    private static final TextColor PRIMARY = TextColor.color(0xB388FF);
    private static final TextColor SOFT = TextColor.color(0xD7B8FF);
    private static final TextColor TEXT = TextColor.color(0xF3ECFF);
    private static final TextColor MUTED = TextColor.color(0x9187A8);
    private static final TextColor SUCCESS = TextColor.color(0x3DF5A0);
    private static final TextColor WARN = TextColor.color(0xFFB454);

    private static final int TIMEOUT_MS = 5000;
    private static final DateTimeFormatter BUILD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Logger LOGGER = LoggerFactory.getLogger(JankyVersion.class);
    private static final Gson GSON = new Gson();

    private JankyVersion() {
    }

    /** Where this build stands against its branch on GitHub. */
    public sealed interface Status {
        /** Same commit as the branch tip. */
        record Latest(String branch) implements Status {}

        /** {@code commits} behind the branch tip. */
        record Behind(String branch, int commits) implements Status {}

        /** Not comparable: no git info, a commit GitHub doesn't have, or a commit off the branch. */
        record Custom() implements Status {}

        /** The update checker is turned off in paper-global.yml. */
        record Disabled() implements Status {}

        /** GitHub could not be asked (offline, rate-limited, unexpected answer). */
        record Failed() implements Status {}
    }

    /** Asks GitHub where this build stands. Blocking (network); call off the main thread. */
    public static Status checkForUpdates() {
        if (!io.papermc.paper.configuration.GlobalConfiguration.get().updateChecker.enabled) {
            return new Status.Disabled();
        }
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        final Optional<String> branch = info.gitBranch();
        final Optional<String> commit = info.gitCommit();
        if (branch.isEmpty() || commit.isEmpty() || branch.get().equals("HEAD")) {
            return new Status.Custom();
        }
        return compare(branch.get(), commit.get());
    }

    private static Status compare(final String branch, final String commit) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create(
                "https://api.github.com/repos/%s/compare/%s...%s".formatted(REPOSITORY, branch, commit)).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "JankyPaper/" + ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE) + " (" + REPOSITORY_URL + ")");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            final int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return new Status.Custom(); // a commit (or branch) never pushed to the Janky Paper repository - or the repository isn't public
            }
            if (code != HttpURLConnection.HTTP_OK) {
                LOGGER.debug("GitHub answered {} to the Janky Paper update check", code); // 403/429 once rate-limited
                return new Status.Failed();
            }
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                final JsonObject json = GSON.fromJson(reader, JsonObject.class);
                // "behind" = our commit is behind the branch tip; "ahead"/"diverged" = built from changes not on the branch
                return switch (json.get("status").getAsString()) {
                    case "identical" -> new Status.Latest(branch);
                    case "behind" -> new Status.Behind(branch, json.get("behind_by").getAsInt());
                    default -> new Status.Custom();
                };
            }
        } catch (final IOException | JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
            LOGGER.debug("Janky Paper update check failed", e);
            return new Status.Failed();
        }
    }

    /** Start-up notice, printed asynchronously once the server is up (MinecraftServer, if the update checker is on). */
    public static void logStartupStatus() {
        final ServerBuildInfo info = ServerBuildInfo.buildInfo();
        if (info.buildNumber().isEmpty() && info.gitCommit().isEmpty()) {
            JankyLog.warn("Development build without version information - update checks skipped");
            return;
        }
        switch (checkForUpdates()) {
            case Status.Latest latest -> JankyLog.success("You are running the latest Janky Paper build (" + latest.branch() + ")");
            case Status.Behind behind -> {
                JankyLog.warn("This Janky Paper build is " + plural(behind.commits(), "commit") + " behind " + behind.branch());
                JankyLog.warn("Get the latest build at " + RELEASES_URL);
            }
            case Status.Custom ignored -> JankyLog.info("Update check skipped - this commit isn't on the public Janky Paper repository");
            case Status.Failed ignored -> JankyLog.warn("Could not check for Janky Paper updates (GitHub unreachable or rate-limited)");
            case Status.Disabled ignored -> {
            }
        }
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
            case Status.Latest ignored -> Component.text("You are running the latest build", SUCCESS);
            case Status.Behind behind -> Component.text(plural(behind.commits(), "commit") + " behind " + behind.branch() + " - update from the releases below", WARN);
            case Status.Custom ignored -> Component.text("Can't check - this commit isn't on the public Janky Paper repository", MUTED);
            case Status.Disabled ignored -> Component.text("Update checker disabled in paper-global.yml", MUTED);
            case Status.Failed ignored -> Component.text("Could not reach GitHub to check", WARN);
        };
    }

    /** {@code #142 · main@a1b2c3d · 2026-11-14 10:22 UTC}; DEV when there is no build number. */
    private static String build(final ServerBuildInfo info) {
        final StringBuilder sb = new StringBuilder();
        sb.append(info.buildNumber().isPresent() ? "#" + info.buildNumber().getAsInt() : "DEV");
        if (info.gitCommit().isPresent()) {
            sb.append(" · ");
            info.gitBranch().ifPresent(branch -> sb.append(branch).append('@'));
            sb.append(info.gitCommit().get());
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

    private static String plural(final int count, final String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
