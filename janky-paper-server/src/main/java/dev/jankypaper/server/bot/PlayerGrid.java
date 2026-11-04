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
 * <p>Players are bucketed into chunk-sized ({@link #CELL}-block) cells once per tick (lazily, on first use). A search
 * walks square rings of cells outwards from the query, skips any cell whose nearest edge is already further than the
 * best match, and stops once no unvisited ring can hold anything closer - so it returns the same player as the linear
 * scan. If a search has visited more cells than there are players (players spread far apart, or nothing in range),
 * it finishes with the linear scan instead.</p>
 *
 * <p>Dense crowds: a cell holding more than {@link #DENSE_CELL} players is split into 4x4-block sub-cells, searched
 * nearest-first with the same edge pruning, so a search from outside a crowd only looks at the crowd's near side.
 * Inside a (sub-)cell, players are sorted by X; a search binary-searches its own X and sweeps outwards, stopping once
 * the X gap alone exceeds the best distance found. The (possibly expensive) predicate only runs on players that
 * would beat the current best.</p>
 *
 * <p>Positions are snapshotted when the grid is built and players keep moving during the tick, so every bound keeps
 * {@link #SLACK} blocks of slack. A player teleported further than that mid-tick can be missed until the next tick.</p>
 *
 * <p>Only used when a world has more than {@link #MIN_PLAYERS} players - below that the linear scan is cheaper.</p>
 */
public final class PlayerGrid {

    public static final int MIN_PLAYERS = 64;
    private static final double SLACK = 4.0;
    private static final int CELL_SHIFT = 4;
    private static final double CELL = 1 << CELL_SHIFT;
    private static final int DENSE_CELL = 32;
    private static final int SUB_SHIFT = 2;
    private static final int SUBS_PER_AXIS = 1 << (CELL_SHIFT - SUB_SHIFT);

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<List<ServerPlayer>> building = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private int builtTick = Integer.MIN_VALUE;
    private int builtSize = -1;
    private int minX, maxX, minZ, maxZ;
    private double visibilityBoost = 1.0;
    private int visibilityScannedTick = Integer.MIN_VALUE / 2; // far in the past without overflowing tick - it

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
        // equipment rarely changes and boosts above 1 only come from datapacks: rescan it once a second, not every tick
        final boolean rescanVisibility = tick - this.visibilityScannedTick >= 20 || tick < this.visibilityScannedTick;
        if (rescanVisibility) {
            this.visibilityBoost = 1.0;
            this.visibilityScannedTick = tick;
        }
        for (int i = 0, len = players.size(); i < len; ++i) {
            final ServerPlayer player = players.get(i);
            if (rescanVisibility) {
                this.visibilityBoost = Math.max(this.visibilityBoost, visibilityBoost(player));
            }
            final int cx = Mth.floor(player.getX()) >> CELL_SHIFT;
            final int cz = Mth.floor(player.getZ()) >> CELL_SHIFT;
            this.building.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(player);
            this.minX = Math.min(this.minX, cx);
            this.maxX = Math.max(this.maxX, cx);
            this.minZ = Math.min(this.minZ, cz);
            this.maxZ = Math.max(this.maxZ, cz);
        }
        for (final Long2ObjectOpenHashMap.Entry<List<ServerPlayer>> entry : this.building.long2ObjectEntrySet()) {
            final long cellKey = entry.getLongKey();
            this.cells.put(cellKey, Cell.of(entry.getValue(), (int)cellKey << CELL_SHIFT, (int)(cellKey >> 32) << CELL_SHIFT, CELL_SHIFT));
        }
        this.building.clear();
    }

    /**
     * The players of one square of the world starting at block ({@code minX}, {@code minZ}), {@code 1 << sizeShift}
     * blocks wide: either sorted by their X at build time, or - when dense - split into smaller sub-cells.
     */
    private static final class Cell {
        final double minX;
        final double minZ;
        final double size;
        final ServerPlayer[] players;
        final double[] xs;
        final Cell[] subs; // non-null when split; null entries are empty sub-cells
        // every player stands within half a block of the others (bots freshly spawned on one spot): for nearest
        // purposes they are interchangeable, so a search takes the first one that qualifies
        final boolean tight;

        private Cell(final double minX, final double minZ, final double size, final ServerPlayer[] players, final double[] xs, final Cell[] subs, final boolean tight) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.players = players;
            this.xs = xs;
            this.subs = subs;
            this.tight = tight;
        }

        static Cell of(final List<ServerPlayer> list, final int minX, final int minZ, final int sizeShift) {
            if (list.size() > DENSE_CELL && sizeShift > SUB_SHIFT) {
                final List<List<ServerPlayer>> parts = new ArrayList<>(SUBS_PER_AXIS * SUBS_PER_AXIS);
                for (int i = 0; i < SUBS_PER_AXIS * SUBS_PER_AXIS; ++i) {
                    parts.add(null);
                }
                for (final ServerPlayer player : list) {
                    final int sx = Math.min(SUBS_PER_AXIS - 1, Math.max(0, (Mth.floor(player.getX()) - minX) >> SUB_SHIFT));
                    final int sz = Math.min(SUBS_PER_AXIS - 1, Math.max(0, (Mth.floor(player.getZ()) - minZ) >> SUB_SHIFT));
                    final int index = sz * SUBS_PER_AXIS + sx;
                    List<ServerPlayer> part = parts.get(index);
                    if (part == null) {
                        parts.set(index, part = new ArrayList<>());
                    }
                    part.add(player);
                }
                final Cell[] subs = new Cell[parts.size()];
                for (int index = 0; index < subs.length; ++index) {
                    final List<ServerPlayer> part = parts.get(index);
                    if (part != null) {
                        subs[index] = Cell.of(part, minX + ((index % SUBS_PER_AXIS) << SUB_SHIFT), minZ + ((index / SUBS_PER_AXIS) << SUB_SHIFT), SUB_SHIFT);
                    }
                }
                return new Cell(minX, minZ, 1 << sizeShift, null, null, subs, false);
            }
            list.sort((a, b) -> Double.compare(a.getX(), b.getX()));
            final ServerPlayer[] players = list.toArray(new ServerPlayer[0]);
            final double[] xs = new double[players.length];
            double lowZ = Double.MAX_VALUE;
            double highZ = -Double.MAX_VALUE;
            for (int i = 0; i < players.length; ++i) {
                xs[i] = players[i].getX();
                lowZ = Math.min(lowZ, players[i].getZ());
                highZ = Math.max(highZ, players[i].getZ());
            }
            final boolean tight = players.length > 1 && xs[xs.length - 1] - xs[0] < 0.5 && highZ - lowZ < 0.5;
            return new Cell(minX, minZ, 1 << sizeShift, players, xs, null, tight);
        }

        /** Squared horizontal distance from (x, z) to this square, less the movement slack (a lower bound). */
        double minDistSq(final double x, final double z) {
            final double dx = Math.max(0.0, Math.max(this.minX - x, x - (this.minX + this.size)) - SLACK);
            final double dz = Math.max(0.0, Math.max(this.minZ - z, z - (this.minZ + this.size)) - SLACK);
            return dx * dx + dz * dz;
        }
    }

    /**
     * The largest factor any player's equipment multiplies its visibility to mobs by (datapacks can set
     * {@code mob_visibility} up to 10 per item; vanilla never exceeds 1). Target-range bounds are scaled by it so the
     * grid never skips a target the linear scan would have found. Rescanned once a second, so a newly equipped boost
     * counts at most a second late.
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
        final int cx = Mth.floor(x) >> CELL_SHIFT;
        final int cz = Mth.floor(z) >> CELL_SHIFT;
        int maxRing = Math.max(Math.max(Math.abs(cx - this.minX), Math.abs(this.maxX - cx)),
            Math.max(Math.abs(cz - this.minZ), Math.abs(this.maxZ - cz)));
        if (maxRange >= 0.0) {
            maxRing = Math.min(maxRing, (int)Math.ceil((maxRange + SLACK) / CELL) + 1);
        }
        final double maxRangeSq = maxRange >= 0.0 ? maxRange * maxRange : Double.MAX_VALUE;

        // a predicate may itself run a nearest-player search; keep the caller's search state intact
        final ServerPlayer outerBest = this.best;
        final double outerBestSq = this.bestSq;
        this.best = null;
        this.bestSq = maxRangeSq;
        long visitedCells = 0L;
        boolean useLinear = false;
        for (int ring = 0; ring <= maxRing; ++ring) {
            visitedCells += ring == 0 ? 1L : 8L * ring;
            if (visitedCells > this.builtSize) {
                useLinear = true; // the rings cost more than a plain scan from here on
                break;
            }
            for (int dx = -ring; dx <= ring; ++dx) {
                final boolean edgeX = dx == -ring || dx == ring;
                for (int dz = -ring; dz <= ring; dz += edgeX ? 1 : 2 * ring) {
                    final Cell cell = this.cells.get(key(cx + dx, cz + dz));
                    if (cell != null && cell.minDistSq(x, z) < this.bestSq) {
                        this.searchCell(cell, x, y, z, predicate);
                    }
                    if (ring == 0) {
                        break;
                    }
                }
            }
            // anything in ring + 1 is at least (ring * CELL - slack) blocks away horizontally
            final double nextBound = Math.max(0.0, ring * CELL - SLACK);
            if (this.best != null && this.bestSq <= nextBound * nextBound) {
                break;
            }
        }
        final ServerPlayer result = useLinear ? this.linear(x, y, z, maxRangeSq, predicate) : this.best;
        this.best = outerBest;
        this.bestSq = outerBestSq;
        return result;
    }

    // search state; the grid is only used from the server thread
    private @Nullable ServerPlayer best;
    private double bestSq;

    private void searchCell(final Cell cell, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        if (cell.subs != null) {
            // nearest sub-cells first, so the best match tightens early and the far side of a crowd is pruned
            final Cell[] subs = cell.subs;
            final double[] dists = new double[subs.length];
            final int[] order = new int[subs.length];
            int count = 0;
            for (int i = 0; i < subs.length; ++i) {
                if (subs[i] != null) {
                    dists[i] = subs[i].minDistSq(x, z);
                    int at = count++;
                    while (at > 0 && dists[order[at - 1]] > dists[i]) {
                        order[at] = order[at - 1];
                        --at;
                    }
                    order[at] = i;
                }
            }
            for (int k = 0; k < count; ++k) {
                if (dists[order[k]] >= this.bestSq) {
                    break;
                }
                this.searchCell(subs[order[k]], x, y, z, predicate);
            }
            return;
        }
        if (cell.tight) {
            for (final ServerPlayer player : cell.players) {
                if (player.level() != this.level || player.isRemoved()) {
                    continue;
                }
                final double distSq = player.distanceToSqr(x, y, z);
                if (distSq >= this.bestSq) {
                    return; // the rest of the cluster is just as far
                }
                if (predicate.test(player)) {
                    this.bestSq = distSq;
                    this.best = player;
                    return;
                }
            }
            return;
        }
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

    /** Offers one player as the best match; true when it was taken. */
    private boolean consider(final ServerPlayer player, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        if (player.level() != this.level || player.isRemoved()) {
            return false;
        }
        final double distSq = player.distanceToSqr(x, y, z);
        if (distSq < this.bestSq && predicate.test(player)) {
            this.bestSq = distSq;
            this.best = player;
            return true;
        }
        return false;
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

    private static long key(final int cx, final int cz) {
        return ((long)cz << 32) | (cx & 0xFFFFFFFFL);
    }
}
