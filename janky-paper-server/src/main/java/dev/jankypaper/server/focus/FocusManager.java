package dev.jankypaper.server.focus;

import dev.jankypaper.server.log.JankyLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Focus mode: pour server resources into the region a recorder / content creator is filming, and only
 * take them away from everyone else when the server actually needs it.
 *
 * <p>Players are "focused" when they were added with {@code /focus add} or stand inside a focus area
 * ({@code /focus here}). Focused players always get the focused tier: a larger view and simulation
 * distance, faster and more concurrent chunk loading/generation, wider entity activation ranges and
 * higher chunk-load priority. Everyone else gets the "others" tier (smaller simulation distance, slower
 * chunk loading, tighter activation ranges) only while throttling: in {@code auto} mode that is while
 * the average MSPT is above {@code engage-mspt}, until it drops back under {@code release-mspt}; in
 * {@code always} mode it is permanent; {@code off} disables everything. When nothing is throttled the
 * others keep the world's normal settings, so they can still fly around and load chunks at full speed.</p>
 *
 * <p>All state is touched on the main thread only. The hook methods ({@link #chunkRateScale},
 * {@link #activationScale}, {@link #isFocused}) are called from the player chunk loader and the
 * entity activation code every tick, so they only read two volatile snapshots.</p>
 */
public final class FocusManager {

    public enum Mode { AUTO, ALWAYS, OFF }

    private static final String FILE_NAME = "janky-paper.yml";
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final String HEADER = """
        Janky Paper settings.

        focus: pour resources into the players / areas being recorded.
          mode: auto   - focused players always get the 'focused' tier; everyone else gets the 'others'
                         tier only while average MSPT is above pressure.engage-mspt (until it falls
                         below pressure.release-mspt)
                always - everyone not focused always gets the 'others' tier
                off    - focus mode does nothing
          A distance of -1 keeps the world's own value. Simulation distance is capped by view distance.
          chunk-rate-multiplier scales Paper's chunk-loading-basic/advanced per-player limits.
          activation-range-multiplier scales the entity activation ranges from spigot.yml.
          targets are managed with /focus (add, remove, here, clear) and saved here.

        bots: players on a fake connection (fake-player plugins) get no chunk packets, no entity
          tracking and no advancement tracking, and are skipped by the player-list / sound / particle
          broadcasts - only what a bot actually needs is computed for it.
          lightweight: false turns this off. view-distance / simulation-distance are the defaults a
          bot joins with (-1 = world value); the plugin may still change them per bot.
          vanilla-push: true restores vanilla bot-on-bot pushing (costs bots x bots per tick).
          Bots are left out of focus mode unless added with /focus add.""";

    private static FocusSettings settings = FocusSettings.DEFAULTS;
    private static Path file;
    private static final Set<UUID> focusedPlayers = new LinkedHashSet<>();
    private static final List<FocusArea> areas = new ArrayList<>();
    private static final Map<UUID, int[]> applied = new HashMap<>();

    // snapshots read by the hooks
    private static volatile Set<UUID> focusedNow = Set.of();
    private static volatile boolean throttling;
    private static volatile FocusSettings activeSettings = FocusSettings.DEFAULTS;

    private FocusManager() {
    }

    // ---------------------------------------------------------------- hooks

    /** Multiplier for the player's chunk load/generate/send rate limits and concurrency limits. */
    public static double chunkRateScale(final Entity player) {
        if (focusedNow.contains(player.getUUID())) {
            return activeSettings.focused().chunkRateMultiplier();
        }
        return throttling ? activeSettings.others().chunkRateMultiplier() : 1.0;
    }

    /** Multiplier for the entity activation ranges around the player. */
    public static double activationScale(final Entity player) {
        if (focusedNow.contains(player.getUUID())) {
            return activeSettings.focused().activationRangeMultiplier();
        }
        return throttling ? activeSettings.others().activationRangeMultiplier() : 1.0;
    }

    public static boolean isFocused(final Entity player) {
        return focusedNow.contains(player.getUUID());
    }

    // ---------------------------------------------------------------- lifecycle

    public static void init(final Path configDir) {
        file = configDir.resolve(FILE_NAME);
        load();
        JankyLog.kv("Focus mode", describeMode());
        JankyLog.kv("Lightweight bots", dev.jankypaper.server.bot.BotPlayers.describe());
    }

    public static void tick(final MinecraftServer server) {
        if (file == null || server.getTickCount() % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }
        final FocusSettings s = settings;
        final List<ServerPlayer> players = server.getPlayerList().getPlayers();

        // who is focused right now
        final Set<UUID> focused = new HashSet<>();
        if (s.mode() != Mode.OFF) {
            for (final ServerPlayer player : players) {
                if (focusedPlayers.contains(player.getUUID()) || (!player.jankyBot && inArea(player))) {
                    focused.add(player.getUUID());
                }
            }
        }

        // should everyone else give up resources?
        final double mspt = server.getAverageTickTimeNanos() / 1.0E6;
        final boolean wasThrottling = throttling;
        final boolean throttle = switch (s.mode()) {
            case OFF -> false;
            case ALWAYS -> true;
            case AUTO -> wasThrottling ? mspt > s.releaseMspt() : mspt >= s.engageMspt();
        };
        if (s.mode() == Mode.AUTO && throttle != wasThrottling) {
            if (throttle) {
                JankyLog.warn(String.format(Locale.ROOT, "Focus: server under pressure (%.1f mspt) - throttling %d unfocused player(s)",
                    mspt, players.size() - focused.size() - dev.jankypaper.server.bot.BotPlayers.online()));
            } else {
                JankyLog.info(String.format(Locale.ROOT, "Focus: pressure gone (%.1f mspt) - everyone back to full settings", mspt));
            }
        }

        activeSettings = s;
        focusedNow = Set.copyOf(focused);
        throttling = throttle;

        // per-player view / simulation distances
        final Set<UUID> online = new HashSet<>();
        for (final ServerPlayer player : players) {
            online.add(player.getUUID());
            if (player.jankyBot && !focused.contains(player.getUUID())) {
                continue; // bots keep the distances they joined with
            }
            final FocusSettings.Tier tier = focused.contains(player.getUUID()) ? s.focused() : throttle ? s.others() : null;
            applyDistances(player, tier == null ? -1 : tier.viewDistance(), tier == null ? -1 : tier.simulationDistance());
        }
        applied.keySet().retainAll(online);
    }

    private static boolean inArea(final ServerPlayer player) {
        if (areas.isEmpty()) {
            return false;
        }
        final String world = player.level().getWorld().getName();
        final int chunkX = player.chunkPosition().x();
        final int chunkZ = player.chunkPosition().z();
        for (final FocusArea area : areas) {
            if (area.contains(world, chunkX, chunkZ)) {
                return true;
            }
        }
        return false;
    }

    private static void applyDistances(final ServerPlayer player, final int view, final int simulation) {
        final int[] previous = applied.get(player.getUUID());
        if (previous == null ? (view == -1 && simulation == -1) : (previous[0] == view && previous[1] == simulation)) {
            return; // never touch players we have not changed, so plugin-set distances survive
        }
        io.papermc.paper.FeatureHooks.setViewDistance(player, view);
        io.papermc.paper.FeatureHooks.setSimulationDistance(player, simulation);
        if (view == -1 && simulation == -1) {
            applied.remove(player.getUUID());
        } else {
            applied.put(player.getUUID(), new int[] {view, simulation});
        }
    }

    // ---------------------------------------------------------------- targets (main thread / commands)

    public static boolean addPlayer(final UUID player) {
        final boolean changed = focusedPlayers.add(player);
        if (changed) {
            save();
        }
        return changed;
    }

    public static boolean removePlayer(final UUID player) {
        final boolean changed = focusedPlayers.remove(player);
        if (changed) {
            save();
        }
        return changed;
    }

    public static void addArea(final FocusArea area) {
        areas.add(area);
        save();
    }

    public static void clear() {
        focusedPlayers.clear();
        areas.clear();
        save();
    }

    public static void setMode(final Mode mode) {
        settings = settings.withMode(mode);
        throttling = false; // re-evaluated on the next update
        save();
    }

    public static FocusSettings settings() {
        return settings;
    }

    public static Set<UUID> focusedPlayers() {
        return Set.copyOf(focusedPlayers);
    }

    public static List<FocusArea> areas() {
        return List.copyOf(areas);
    }

    public static boolean isThrottling() {
        return throttling;
    }

    public static String describeMode() {
        return settings.mode().name().toLowerCase(Locale.ROOT) + " (" + focusedPlayers.size() + " player(s), " + areas.size() + " area(s))";
    }

    // ---------------------------------------------------------------- persistence

    public static void load() {
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        settings = FocusSettings.read(yaml);
        dev.jankypaper.server.bot.BotPlayers.read(section(yaml, "bots"));
        focusedPlayers.clear();
        for (final String raw : yaml.getStringList("focus.targets.players")) {
            try {
                focusedPlayers.add(UUID.fromString(raw.trim()));
            } catch (final IllegalArgumentException ex) {
                JankyLog.warn("Focus: ignoring invalid player UUID '" + raw + "' in " + FILE_NAME);
            }
        }
        areas.clear();
        for (final String raw : yaml.getStringList("focus.targets.areas")) {
            final FocusArea area = FocusArea.parse(raw);
            if (area == null) {
                JankyLog.warn("Focus: ignoring invalid area '" + raw + "' in " + FILE_NAME + " (expected world;chunkX;chunkZ;radius)");
            } else {
                areas.add(area);
            }
        }
        save(); // writes defaults and normalised values back
    }

    private static org.bukkit.configuration.ConfigurationSection section(final YamlConfiguration yaml, final String path) {
        final org.bukkit.configuration.ConfigurationSection existing = yaml.getConfigurationSection(path);
        return existing != null ? existing : yaml.createSection(path);
    }

    private static void save() {
        if (file == null) {
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(HEADER.split("\n")));
        settings.write(yaml);
        dev.jankypaper.server.bot.BotPlayers.write(section(yaml, "bots"));
        yaml.set("focus.targets.players", focusedPlayers.stream().map(UUID::toString).toList());
        yaml.set("focus.targets.areas", areas.stream().map(FocusArea::serialize).toList());
        try {
            Files.createDirectories(file.getParent());
            yaml.save(file.toFile());
        } catch (final IOException ex) {
            JankyLog.error("Focus: could not save " + file + ": " + ex.getMessage());
        }
    }
}
