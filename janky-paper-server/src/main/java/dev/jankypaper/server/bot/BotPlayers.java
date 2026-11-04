package dev.jankypaper.server.bot;

import io.netty.channel.Channel;
import io.netty.channel.socket.DuplexChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lightweight bots: players whose connection is not a real socket (fake-player plugins such as FPP
 * attach a {@code FakeChannel}/{@code EmbeddedChannel}) have no client, so the server stops doing the
 * per-viewer work that only exists to feed a client:
 *
 * <ul>
 *   <li>no entity tracking: bots never become viewers of other entities (real players still see bots), and
 *       bots stay out of the unused GENERAL nearby-player maps</li>
 *   <li>no chunk packets: chunks are still loaded/ticked around bots, but never serialised or watched</li>
 *   <li>no advancement progress, criteria listeners or packets (about 0.5 MB per bot)</li>
 *   <li>no vanilla bot-on-bot pushing (O(bots^2)); mobs still push bots, and fake-player plugins
 *       usually run their own crowd separation</li>
 *   <li>no locator-bar receiver (bots still show up on real players' locator bars)</li>
 *   <li>the item-pickup scan only looks up entity classes that react to a player touch, and entity
 *       activation runs once per chunk of bots</li>
 *   <li>bots are skipped in the player-list latency refresh, the join player list, and positional
 *       sound / particle broadcasts, and never get the command tree packet</li>
 *   <li>mob nearest-player searches use a per-tick spatial grid ({@link PlayerGrid}) instead of scanning every
 *       player, which also covers thousands of bots packed into one chunk</li>
 *   <li>no per-tick container sync, no stat counter (stat scoreboards still update), no debug-subscription
 *       permission check</li>
 *   <li>a small default view distance (plugins may still change it per bot afterwards)</li>
 *   <li>optionally ({@code affect-mob-spawning: false}) no natural mob spawning around bots; crops and other random
 *       ticks near bots keep running</li>
 *   <li>a smaller entity-activation radius around bots ({@code activation-range-multiplier}, 0.5 by default): mobs
 *       only near bots run Spigot's cheap inactive tick until a bot is within half the usual range</li>
 * </ul>
 *
 * <p>Packets a plugin sends or that are addressed to the bot itself (chat, titles, knockback motion)
 * are untouched, so plugins that read them from their fake connection keep working.</p>
 */
public final class BotPlayers {

    private static boolean enabled = true;
    private static int viewDistance = 3;
    private static int simulationDistance = -1;
    private static boolean vanillaPush = false;
    private static boolean affectMobSpawning = true;
    private static double activationRangeMultiplier = 0.5;
    private static int spawnRepresentativeEvery = 1000;
    private static int online;

    // UUIDs a fake-player plugin announced (expectBot) right before constructing the bot's ServerPlayer
    private static final java.util.Set<java.util.UUID> expected = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** {@link #realPlayers} snapshot of one source list: at which tick and size it was built. Immutable, so any thread may read it. */
    private record RealPlayers(int tick, int size, List<ServerPlayer> players) {
    }

    // one snapshot per source list (the server's player list and each world's), looked up by identity
    private static final java.util.Map<List<ServerPlayer>, RealPlayers> realPlayers =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** Every vanilla entity class that overrides {@code Entity#playerTouch} (ThrownTrident and SulfurCube are subclasses). */
    private static final List<Class<? extends Entity>> TOUCHABLE = List.of(
        ItemEntity.class, ExperienceOrb.class, AbstractArrow.class, AbstractCubeMob.class, Pufferfish.class
    );

    private BotPlayers() {
    }

    public static boolean isFakeConnection(final Connection connection) {
        final Channel channel = connection.channel;
        // every real connection (TCP, unix domain socket) is a DuplexChannel
        return channel != null && !(channel instanceof DuplexChannel);
    }

    /**
     * Optional hook for fake-player plugins, called by reflection right before they construct a bot's
     * {@code ServerPlayer}: the server then skips loading that player's advancement progress (it would be thrown away
     * when the bot is placed). Bots are still detected by their connection; this only makes construction cheaper.
     */
    public static void expectBot(final java.util.UUID id) {
        if (enabled && id != null) {
            expected.add(id);
        }
    }

    public static boolean isExpectedBot(final java.util.UUID id) {
        return enabled && !expected.isEmpty() && expected.contains(id);
    }

    /** Called from {@code PlayerList#placeNewPlayer} before the player is added to its world. */
    public static void onPlace(final ServerPlayer player, final Connection connection) {
        expected.remove(player.getUUID());
        player.jankyBot = enabled && isFakeConnection(connection);
        if (!player.jankyBot) {
            return;
        }
        online++;
        if (viewDistance > 0) {
            io.papermc.paper.FeatureHooks.setViewDistance(player, viewDistance);
        }
        if (simulationDistance > 0) {
            io.papermc.paper.FeatureHooks.setSimulationDistance(player, simulationDistance);
        }
        player.getAdvancements().jankyReleaseForBot();
        if (!affectMobSpawning) {
            player.affectsSpawning = false; // despawn checks, spawners and the spawn-range map ignore the bot
        }
    }

    /**
     * The entities a bot's pickup scan in {@code Player#aiStep} can touch. Uses the per-class entity index, so a
     * crowd of bots no longer walks every other bot in range each tick (that was O(bots^2)).
     */
    public static List<Entity> touchableEntities(final Entity self, final AABB area) {
        final List<Entity> out = new ArrayList<>();
        for (final Class<? extends Entity> type : TOUCHABLE) {
            out.addAll(self.level().getEntitiesOfClass(type, area, entity -> entity != self));
        }
        return out;
    }

    /**
     * Nearby-player maps a bot is left out of. VIEW_DISTANCE feeds the entity tracker (bots view nothing); the
     * GENERAL maps are not read by the server and cost one array slot per bot in every chunk within 33 chunks.
     * SPAWN_RANGE (where mobs may spawn) only when bots don't affect mob spawning. TICK_VIEW_DISTANCE is only read by
     * Paper's per-player mob caps: with many bots online only a share of them are in it (see {@link #isSpawnRepresentative}).
     */
    public static boolean skipsNearbyMap(final ServerPlayer player, final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType type) {
        return switch (type) {
            case VIEW_DISTANCE, GENERAL, GENERAL_SMALL, GENERAL_REALLY_SMALL -> true;
            case SPAWN_RANGE -> !affectMobSpawning; // natural spawning only; random ticks don't use this map
            case TICK_VIEW_DISTANCE -> !affectMobSpawning || !isSpawnRepresentative(player);
            default -> false;
        };
    }

    /**
     * Whether this bot counts as a player for Paper's per-player mob caps. Every mob tick adds itself to the count of
     * every player in range, and every spawn attempt checks every player in range - mobs x bots each tick. Up to
     * {@code mob-spawn-representative-every} bots online, every bot counts; above that only one bot in
     * {@code online / mob-spawn-representative-every} does (chosen by UUID), so a crowd of 10,000 bots costs - and
     * spawns mobs - like a crowd of about 1,000. Decided when the bot is added to a world.
     */
    public static boolean isSpawnRepresentative(final ServerPlayer player) {
        if (spawnRepresentativeEvery <= 0) {
            return true;
        }
        final int share = Math.max(1, online / spawnRepresentativeEvery);
        return share == 1 || Math.floorMod(player.getUUID().hashCode(), share) == 0;
    }

    /**
     * {@code all} without the bots, for broadcast loops: a positional sound (a bot's footstep) or a packet sent to
     * everyone would otherwise walk every bot just to skip it, which is bots x broadcasts per tick. Rebuilt at most once
     * per tick, or when the list changes size. Returns {@code all} itself when no bots are online.
     *
     * <p>Only pass long-lived lists (the server's player list, a world's player list): one snapshot is kept per list
     * instance, so a fresh list on every call would pile up.</p>
     */
    public static List<ServerPlayer> realPlayers(final List<ServerPlayer> all) {
        if (online == 0) {
            return all;
        }
        final int tick = MinecraftServer.currentTick;
        final RealPlayers cached = realPlayers.get(all);
        if (cached != null && cached.tick() == tick && cached.size() == all.size()) {
            return cached.players();
        }
        final List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0, len = all.size(); i < len; ++i) {
            final ServerPlayer player = all.get(i);
            if (!player.jankyBot) {
                players.add(player);
            }
        }
        final List<ServerPlayer> snapshot = List.copyOf(players);
        realPlayers.put(all, new RealPlayers(tick, all.size(), snapshot));
        return snapshot;
    }

    /** Called from {@code PlayerList#remove}. */
    public static void onRemove(final ServerPlayer player) {
        if (player.jankyBot) {
            online = Math.max(0, online - 1);
        }
    }

    public static int online() {
        return online;
    }

    /** Multiplier on the spigot.yml entity activation ranges around a bot (see {@code bots.activation-range-multiplier}). */
    public static double activationRangeMultiplier() {
        return activationRangeMultiplier;
    }

    public static boolean vanillaPush() {
        return vanillaPush;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static String describe() {
        if (!enabled) {
            return "off";
        }
        return "on (view " + (viewDistance > 0 ? viewDistance : "world")
            + ", sim " + (simulationDistance > 0 ? simulationDistance : "world") + ")";
    }

    public static void read(final ConfigurationSection section) {
        enabled = section.getBoolean("lightweight", true);
        viewDistance = distance(section.getInt("view-distance", 3));
        simulationDistance = distance(section.getInt("simulation-distance", -1));
        vanillaPush = section.getBoolean("vanilla-push", false);
        affectMobSpawning = section.getBoolean("affect-mob-spawning", true);
        activationRangeMultiplier = Math.max(0.0, section.getDouble("activation-range-multiplier", 0.5));
        spawnRepresentativeEvery = Math.max(0, section.getInt("mob-spawn-representative-every", 1000));
    }

    public static void write(final ConfigurationSection section) {
        section.set("lightweight", enabled);
        section.set("view-distance", viewDistance);
        section.set("simulation-distance", simulationDistance);
        section.set("vanilla-push", vanillaPush);
        section.set("affect-mob-spawning", affectMobSpawning);
        section.set("activation-range-multiplier", activationRangeMultiplier);
        section.set("mob-spawn-representative-every", spawnRepresentativeEvery);
    }

    private static int distance(final int value) {
        return value < 0 ? -1 : Math.max(2, Math.min(32, value));
    }
}
