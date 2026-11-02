package dev.jankypaper.server.bot;

import io.netty.channel.Channel;
import io.netty.channel.socket.DuplexChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
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
    private static int online;

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

    /** Called from {@code PlayerList#placeNewPlayer} before the player is added to its world. */
    public static void onPlace(final ServerPlayer player, final Connection connection) {
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
     * TICK_VIEW_DISTANCE and SPAWN_RANGE stay, so bots still tick chunks and spawn mobs like players.
     */
    public static boolean skipsNearbyMap(final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType type) {
        return switch (type) {
            case VIEW_DISTANCE, GENERAL, GENERAL_SMALL, GENERAL_REALLY_SMALL -> true;
            default -> false;
        };
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
    }

    public static void write(final ConfigurationSection section) {
        section.set("lightweight", enabled);
        section.set("view-distance", viewDistance);
        section.set("simulation-distance", simulationDistance);
        section.set("vanilla-push", vanillaPush);
    }

    private static int distance(final int value) {
        return value < 0 ? -1 : Math.max(2, Math.min(32, value));
    }
}
