/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.bot;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import io.papermc.paper.entity.activation.ActivationType;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import org.spigotmc.SpigotWorldConfig;

/**
 * Entity activation around bots, in one pass.
 *
 * <p>Spigot's activation range fetches every entity in a large box around each player and marks the ones close enough
 * as active. Done per bot - or even per chunk of bots - in a crowd, every box holds the whole crowd, so the pass walked
 * every bot once per chunk the crowd stands in. Here the chunks holding bots are collected first, every chunk within
 * reach of one is visited once, and an entity is active when its chunk is within its activation range of a chunk that
 * holds a bot - ranges counted in whole chunks (rounded up), scaled by {@code bots.activation-range-multiplier}. Real
 * players and focused bots keep Spigot's exact per-player boxes.</p>
 */
public final class BotActivation {

    private static final ActivationType[] TYPES = ActivationType.values();

    private BotActivation() {
    }

    private static int range(final SpigotWorldConfig config, final ActivationType type) {
        return switch (type) {
            case WATER -> config.waterActivationRange;
            case FLYING_MONSTER -> config.flyingMonsterActivationRange;
            case VILLAGER -> config.villagerActivationRange;
            case MONSTER -> config.monsterActivationRange;
            case ANIMAL -> config.animalActivationRange;
            case RAIDER -> config.raiderActivationRange;
            case MISC -> config.miscActivationRange;
        };
    }

    /**
     * @param botChunks chunk keys holding at least one unfocused bot
     * @param maxRange  Spigot's cap on any activation range for this world (blocks)
     * @param scale     the activation-range multiplier for unfocused bots
     */
    public static void activate(final Level world, final LongOpenHashSet botChunks, final int maxRange, final double scale) {
        if (botChunks.isEmpty()) {
            return;
        }
        final SpigotWorldConfig config = world.spigotConfig;
        // per type: how many chunks out from a bot's chunk entities stay active (-1: never through range)
        final int[] reach = new int[TYPES.length];
        int maxReach = 0;
        for (final ActivationType type : TYPES) {
            final int range = Math.min(range(config, type), maxRange);
            final int chunks = range <= 0 ? -1 : (int)Math.ceil(range * scale / 16.0);
            reach[type.ordinal()] = chunks;
            maxReach = Math.max(maxReach, chunks);
        }

        // chunk -> Chebyshev distance (in chunks) to the nearest chunk holding a bot
        final Long2IntOpenHashMap distance = new Long2IntOpenHashMap(botChunks.size() * 4);
        distance.defaultReturnValue(Integer.MAX_VALUE);
        for (final LongIterator iterator = botChunks.iterator(); iterator.hasNext();) {
            final long chunk = iterator.nextLong();
            final int chunkX = CoordinateUtils.getChunkX(chunk);
            final int chunkZ = CoordinateUtils.getChunkZ(chunk);
            for (int dz = -maxReach; dz <= maxReach; ++dz) {
                for (int dx = -maxReach; dx <= maxReach; ++dx) {
                    final long key = CoordinateUtils.getChunkKey(chunkX + dx, chunkZ + dz);
                    final int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d < distance.get(key)) {
                        distance.put(key, d);
                    }
                }
            }
        }

        final long tick = MinecraftServer.currentTick;
        final boolean tickMarkers = world.paperConfig().entities.markers.tick;
        final EntityLookup lookup = ((ChunkSystemLevel)world).moonrise$getEntityLookup();
        for (final Long2IntMap.Entry entry : distance.long2IntEntrySet()) {
            final long chunk = entry.getLongKey();
            final ChunkEntitySlices slices = lookup.getChunk(CoordinateUtils.getChunkX(chunk), CoordinateUtils.getChunkZ(chunk));
            if (slices == null) {
                continue;
            }
            final int d = entry.getIntValue();
            final List<Entity> entities = slices.getAllEntities();
            for (int i = 0, len = entities.size(); i < len; ++i) {
                final Entity entity = entities.get(i);
                if (tick <= entity.activatedTick) {
                    continue;
                }
                if (entity.defaultActivationState) {
                    entity.activatedTick = tick;
                    continue;
                }
                if (!tickMarkers && entity instanceof Marker) {
                    continue;
                }
                final int typeReach = reach[entity.activationType.ordinal()];
                if (typeReach >= 0 && d <= typeReach) {
                    entity.activatedTick = tick;
                }
            }
        }
    }
}
