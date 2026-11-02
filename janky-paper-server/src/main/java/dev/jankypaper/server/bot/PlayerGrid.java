package dev.jankypaper.server.bot;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.component.MobVisibility;
import org.jspecify.annotations.Nullable;

/**
 * Per-world spatial index of players for the "nearest player" searches mobs run every tick (despawn checks, target
 * selection, spawner activation). Vanilla walks every player in the world for each of those calls, which is
 * O(mobs x players) per tick and dominates once thousands of bots are online.
 *
 * <p>Players are bucketed by chunk once per tick (lazily, on first use). A search walks square rings of chunks
 * outwards from the query and stops once no unvisited ring can hold anything closer than the best match so far,
 * so it returns the same player as the linear scan. When the rings to visit would cost more than the linear scan
 * (players spread far apart, or nothing in range), it falls back to the linear scan.</p>
 *
 * <p>Dense crowds (thousands of bots in one chunk) are handled inside each cell: a cell's players are sorted by X,
 * and a search binary-searches its own X and sweeps outwards, stopping once the X gap alone exceeds the best
 * distance found. The (possibly expensive) predicate only runs on players that would beat the current best.</p>
 *
 * <p>Positions are snapshotted when the grid is built and players keep moving during the tick, so every bound keeps
 * {@link #SLACK} blocks of slack. A player teleported further than that mid-tick can be missed until the next tick.</p>
 *
 * <p>Only used when a world has more than {@link #MIN_PLAYERS} players - below that the linear scan is cheaper.</p>
 */
public final class PlayerGrid {

    public static final int MIN_PLAYERS = 64;
    private static final double SLACK = 4.0;

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<List<ServerPlayer>> building = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private int builtTick = Integer.MIN_VALUE;
    private int builtSize = -1;
    private int minX, maxX, minZ, maxZ;
    private double visibilityBoost = 1.0;

    public PlayerGrid(final ServerLevel level) {
        this.level = level;
    }

    public static boolean useFor(final ServerLevel level) {
        return level.players().size() > MIN_PLAYERS;
    }

    private void ensureBuilt() {
        final List<ServerPlayer> players = this.level.players();
        final int tick = MinecraftServer.currentTick;
        if (tick == this.builtTick && players.size() == this.builtSize) {
            return;
        }
        this.builtTick = tick;
        this.builtSize = players.size();
        this.cells.clear();
        this.building.clear();
        this.minX = this.minZ = Integer.MAX_VALUE;
        this.maxX = this.maxZ = Integer.MIN_VALUE;
        this.visibilityBoost = 1.0;
        for (int i = 0, len = players.size(); i < len; ++i) {
            final ServerPlayer player = players.get(i);
            this.visibilityBoost = Math.max(this.visibilityBoost, visibilityBoost(player));
            final int cx = Mth.floor(player.getX()) >> 4;
            final int cz = Mth.floor(player.getZ()) >> 4;
            this.building.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(player);
            this.minX = Math.min(this.minX, cx);
            this.maxX = Math.max(this.maxX, cx);
            this.minZ = Math.min(this.minZ, cz);
            this.maxZ = Math.max(this.maxZ, cz);
        }
        for (final Long2ObjectOpenHashMap.Entry<List<ServerPlayer>> entry : this.building.long2ObjectEntrySet()) {
            this.cells.put(entry.getLongKey(), new Cell(entry.getValue()));
        }
        this.building.clear();
    }

    /** The players of one chunk, sorted by their X at build time. */
    private static final class Cell {
        final ServerPlayer[] players;
        final double[] xs;

        Cell(final List<ServerPlayer> list) {
            list.sort((a, b) -> Double.compare(a.getX(), b.getX()));
            this.players = list.toArray(new ServerPlayer[0]);
            this.xs = new double[this.players.length];
            for (int i = 0; i < this.players.length; ++i) {
                this.xs[i] = this.players[i].getX();
            }
        }
    }

    /**
     * The largest factor any player's equipment multiplies its visibility to mobs by (datapacks can set
     * {@code mob_visibility} up to 10 per item; vanilla never exceeds 1). Target-range bounds are scaled by it so the
     * grid never skips a target the linear scan would have found.
     */
    public double visibilityBoost() {
        this.ensureBuilt();
        return this.visibilityBoost;
    }

    private static double visibilityBoost(final ServerPlayer player) {
        double boost = 1.0;
        for (final EquipmentSlot slot : EquipmentSlot.VALUES) {
            final MobVisibility visibility = player.getItemBySlot(slot).get(DataComponents.MOB_VISIBILITY);
            if (visibility != null && visibility.visibility() > 1.0F) {
                boost *= visibility.visibility();
            }
        }
        return boost;
    }

    /**
     * The player nearest to (x, y, z) by 3D distance that passes {@code predicate}, or null.
     *
     * @param maxRange search radius in blocks, or a negative value for unlimited. A player further than this is
     *                 never returned (the predicate may still reject closer ones).
     */
    public @Nullable ServerPlayer nearest(final double x, final double y, final double z, final double maxRange,
                                         final Predicate<? super ServerPlayer> predicate) {
        this.ensureBuilt();
        if (this.builtSize == 0) {
            return null;
        }
        final int cx = Mth.floor(x) >> 4;
        final int cz = Mth.floor(z) >> 4;
        int maxRing = Math.max(Math.max(Math.abs(cx - this.minX), Math.abs(this.maxX - cx)),
            Math.max(Math.abs(cz - this.minZ), Math.abs(this.maxZ - cz)));
        if (maxRange >= 0.0) {
            maxRing = Math.min(maxRing, (int)Math.ceil((maxRange + SLACK) / 16.0) + 1);
        }
        final double maxRangeSq = maxRange >= 0.0 ? maxRange * maxRange : Double.MAX_VALUE;
        if (ringCells(maxRing) > this.builtSize) {
            return this.linear(x, y, z, maxRangeSq, predicate);
        }

        // a predicate may itself run a nearest-player search; keep the caller's search state intact
        final ServerPlayer outerBest = this.best;
        final double outerBestSq = this.bestSq;
        this.best = null;
        this.bestSq = maxRangeSq;
        for (int ring = 0; ring <= maxRing; ++ring) {
            for (int dx = -ring; dx <= ring; ++dx) {
                final boolean edgeX = dx == -ring || dx == ring;
                for (int dz = -ring; dz <= ring; dz += edgeX ? 1 : 2 * ring) {
                    final Cell cell = this.cells.get(key(cx + dx, cz + dz));
                    if (cell != null) {
                        this.searchCell(cell, x, y, z, predicate);
                    }
                    if (ring == 0) {
                        break;
                    }
                }
            }
            // anything in ring + 1 is at least (ring * 16 - slack) blocks away horizontally
            final double nextBound = Math.max(0.0, ring * 16.0 - SLACK);
            if (this.best != null && this.bestSq <= nextBound * nextBound) {
                break;
            }
        }
        final ServerPlayer result = this.best;
        this.best = outerBest;
        this.bestSq = outerBestSq;
        return result;
    }

    // search state; the grid is only used from the server thread
    private @Nullable ServerPlayer best;
    private double bestSq;

    private void searchCell(final Cell cell, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        final double[] xs = cell.xs;
        int lo = 0;
        int hi = xs.length;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (xs[mid] < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int i = lo; i < xs.length; ++i) {
            final double gap = xs[i] - x - SLACK;
            if (gap > 0.0 && gap * gap >= this.bestSq) {
                break;
            }
            this.consider(cell.players[i], x, y, z, predicate);
        }
        for (int i = lo - 1; i >= 0; --i) {
            final double gap = x - xs[i] - SLACK;
            if (gap > 0.0 && gap * gap >= this.bestSq) {
                break;
            }
            this.consider(cell.players[i], x, y, z, predicate);
        }
    }

    private void consider(final ServerPlayer player, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        if (player.level() != this.level || player.isRemoved()) {
            return;
        }
        final double distSq = player.distanceToSqr(x, y, z);
        if (distSq < this.bestSq && predicate.test(player)) {
            this.bestSq = distSq;
            this.best = player;
        }
    }

    private @Nullable ServerPlayer linear(final double x, final double y, final double z, final double maxRangeSq,
                                          final Predicate<? super ServerPlayer> predicate) {
        ServerPlayer best = null;
        double bestSq = Double.MAX_VALUE;
        for (final ServerPlayer player : this.level.players()) {
            final double distSq = player.distanceToSqr(x, y, z);
            if (distSq < bestSq && distSq < maxRangeSq && predicate.test(player)) {
                bestSq = distSq;
                best = player;
            }
        }
        return best;
    }

    private static long ringCells(final int maxRing) {
        final long side = 2L * maxRing + 1L;
        return side * side;
    }

    private static long key(final int cx, final int cz) {
        return ((long)cz << 32) | (cx & 0xFFFFFFFFL);
    }
}
