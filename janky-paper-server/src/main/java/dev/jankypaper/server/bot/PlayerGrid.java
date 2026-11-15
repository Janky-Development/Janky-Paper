/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.bot;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Per-world spatial index of players for the "nearest player" searches mobs run every tick (despawn checks, target
 * selection, natural spawning, spawner activation). Vanilla walks every player in the world for each of those calls,
 * which is O(mobs x players) per tick and dominates once thousands of bots are online.
 *
 * <p>Players are bucketed into 16-block cells once per tick (lazily, on first use). A cell holding more than
 * {@link #DENSE} players is split into 4x4-block sub-cells, and those again into 1x1-block cells, so a search inside a
 * crowd only looks at the few players actually around it. The build is a counting sort into flat arrays - no per-cell
 * lists or sorting. A search walks square rings of cells outwards from the query, visits (sub-)cells nearest-first,
 * skips anything whose nearest edge is already further than the best match, and stops once no unvisited ring can hold
 * anything closer - so it returns the same player as the linear scan. If the rings would cost more than a plain scan
 * (players spread far apart, or nothing in range), it finishes with the linear scan instead.</p>
 *
 * <p>Positions are snapshotted at build time and players keep moving during the tick. Every bound keeps {@link #SLACK}
 * blocks of slack, and a player that moves further than that from its snapshot (checked in {@code Entity#setPosRaw}) is
 * put on an overflow list that every search also checks, so the result stays exact. The distance that decides the
 * winner is always the live one; the snapshot is only used to skip players that cannot win.</p>
 *
 * <p>Only used when a world has more than {@link #MIN_PLAYERS} players - below that the linear scan is cheaper.</p>
 */
public final class PlayerGrid {

    public static final int MIN_PLAYERS = 64;
    /** How far (per axis) a player may move from its snapshot before it goes on the overflow list. */
    static final double SLACK = 1.0;
    private static final int TOP_SHIFT = 4;
    private static final int DENSE = 24;
    /** Above this many overflowed players, the next search rebuilds the grid instead of scanning them all. */
    private static final int MAX_OVERFLOW = 64;

    /** Per-player snapshot bookkeeping, owned by the {@link ServerPlayer} ({@code jankyGridTracker}). */
    public static final class Tracker {
        private final ServerPlayer player;
        private @Nullable PlayerGrid grid;
        private double x;
        private double y;
        private double z;
        private boolean overflowed;

        public Tracker(final ServerPlayer player) {
            this.player = player;
        }

        /** Called from {@code Entity#setPosRaw} whenever the player's position changes. */
        public void moved(final double newX, final double newY, final double newZ) {
            final PlayerGrid owner = this.grid;
            if (owner != null && !this.overflowed
                && (Math.abs(newX - this.x) > SLACK || Math.abs(newZ - this.z) > SLACK || Math.abs(newY - this.y) > SLACK)) {
                this.overflowed = true;
                owner.overflow.add(this.player);
            }
        }
    }

    /** A square of the world: either split into {@code subs}, or a leaf owning snapshot slots [start, end). */
    private static final class Node {
        final double minX;
        final double minZ;
        final double size;
        // height range of the players in this square (snapshot): 3D searches skip squares entirely above or below them -
        // a mob in a cave under a crowd used to find every player of the crowd "horizontally close" and check them all
        final double minY;
        final double maxY;
        final int start;
        final int end;
        final Node @Nullable [] subs; // non-null when split; null entries are empty sub-cells
        // every player stands within half a block of the others (bots stacked on one spot): for nearest purposes they
        // are interchangeable, so a search takes the first one that qualifies
        final boolean tight;

        Node(final double minX, final double minZ, final double size, final double minY, final double maxY,
             final int start, final int end, final Node @Nullable [] subs, final boolean tight) {
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.minY = minY;
            this.maxY = maxY;
            this.start = start;
            this.end = end;
            this.subs = subs;
            this.tight = tight;
        }

        /**
         * Squared distance from (x, y, z) to this square's players, less the movement slack (a lower bound): horizontal
         * only, or with the height range when {@code horizontal} is false.
         */
        double minDistSq(final double x, final double y, final double z, final boolean horizontal) {
            final double dx = Math.max(0.0, Math.max(this.minX - x, x - (this.minX + this.size)) - SLACK);
            final double dz = Math.max(0.0, Math.max(this.minZ - z, z - (this.minZ + this.size)) - SLACK);
            if (horizontal) {
                return dx * dx + dz * dz;
            }
            final double dy = Math.max(0.0, Math.max(this.minY - y, y - this.maxY) - SLACK);
            return dx * dx + dz * dz + dy * dy;
        }
    }

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<Node> cells = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap cellOrdinals = new Long2IntOpenHashMap();
    final List<ServerPlayer> overflow = new ArrayList<>();
    private int builtTick = Integer.MIN_VALUE;
    private int builtSize = -1;
    private int minX, maxX, minZ, maxZ;

    // snapshot, grouped so every leaf owns one contiguous slice
    private ServerPlayer[] players = new ServerPlayer[0];
    private double[] xs = new double[0];
    private double[] ys = new double[0];
    private double[] zs = new double[0];
    // build scratch
    private ServerPlayer[] tmpPlayers = new ServerPlayer[0];
    private double[] tmpXs = new double[0];
    private double[] tmpYs = new double[0];
    private double[] tmpZs = new double[0];
    private int[] tmpKeys = new int[0];
    private int[] counts = new int[16];

    private double visibilityBoost = 1.0;
    private double visibilityBoostCycle = 1.0;
    private int visibilityCursor;

    private int spawnRadiusTick = Integer.MIN_VALUE;
    private double spawnRangeMax;

    public PlayerGrid(final ServerLevel level) {
        this.level = level;
        this.cellOrdinals.defaultReturnValue(-1);
    }

    public static boolean useFor(final ServerLevel level) {
        if (level.players().size() > MIN_PLAYERS) {
            return true;
        }
        level.jankyPlayerGrid.release();
        return false;
    }

    /**
     * Drops the snapshot once the world no longer uses the grid. It is only rebuilt while the world has more than
     * {@link #MIN_PLAYERS} players, so without this the last snapshot kept every player in it reachable - after a mass
     * bot despawn, hundreds of removed players stayed in memory until the world filled up again.
     */
    private void release() {
        if (!this.overflow.isEmpty()) {
            this.overflow.clear(); // trackers still pointing here add themselves once after a move
        }
        if (this.builtSize < 0) {
            return;
        }
        java.util.Arrays.fill(this.players, null);
        this.cells.clear();
        this.cellOrdinals.clear();
        this.overflow.clear();
        this.builtSize = -1;
        this.builtTick = Integer.MIN_VALUE;
    }

    private static long key(final int cx, final int cz) {
        return ((long)cz << 32) | (cx & 0xFFFFFFFFL);
    }

    private void ensureBuilt() {
        final List<ServerPlayer> all = this.level.players();
        final int tick = MinecraftServer.currentTick;
        if (tick == this.builtTick && all.size() == this.builtSize && this.overflow.size() <= MAX_OVERFLOW) {
            return;
        }
        this.build(all, tick);
    }

    private void build(final List<ServerPlayer> all, final int tick) {
        final int n = all.size();
        this.builtTick = tick;
        this.builtSize = n;
        this.cells.clear();
        this.cellOrdinals.clear();
        this.overflow.clear();
        if (this.players.length < n) {
            final int cap = Math.max(n, this.players.length + (this.players.length >> 1));
            this.players = new ServerPlayer[cap];
            this.xs = new double[cap];
            this.ys = new double[cap];
            this.zs = new double[cap];
            this.tmpPlayers = new ServerPlayer[cap];
            this.tmpXs = new double[cap];
            this.tmpYs = new double[cap];
            this.tmpZs = new double[cap];
            this.tmpKeys = new int[cap];
        }
        this.updateVisibilityBoost(all, n);
        this.minX = this.minZ = Integer.MAX_VALUE;
        this.maxX = this.maxZ = Integer.MIN_VALUE;

        // snapshot + top-cell ordinal per player
        int cellCount = 0;
        for (int i = 0; i < n; ++i) {
            final ServerPlayer player = all.get(i);
            final double x = player.getX();
            final double y = player.getY();
            final double z = player.getZ();
            final Tracker tracker = player.jankyGridTracker;
            tracker.grid = this;
            tracker.x = x;
            tracker.y = y;
            tracker.z = z;
            tracker.overflowed = false;
            final int cx = Mth.floor(x) >> TOP_SHIFT;
            final int cz = Mth.floor(z) >> TOP_SHIFT;
            final long cellKey = key(cx, cz);
            int ordinal = this.cellOrdinals.get(cellKey);
            if (ordinal < 0) {
                ordinal = cellCount++;
                this.cellOrdinals.put(cellKey, ordinal);
                if (ordinal >= this.counts.length) {
                    this.counts = java.util.Arrays.copyOf(this.counts, this.counts.length * 2);
                }
                this.counts[ordinal] = 0;
            }
            ++this.counts[ordinal];
            this.tmpPlayers[i] = player;
            this.tmpXs[i] = x;
            this.tmpYs[i] = y;
            this.tmpZs[i] = z;
            this.tmpKeys[i] = ordinal;
            this.minX = Math.min(this.minX, cx);
            this.maxX = Math.max(this.maxX, cx);
            this.minZ = Math.min(this.minZ, cz);
            this.maxZ = Math.max(this.maxZ, cz);
        }
        // counting sort by top cell: counts -> start offsets
        int offset = 0;
        for (int c = 0; c < cellCount; ++c) {
            final int size = this.counts[c];
            this.counts[c] = offset;
            offset += size;
        }
        for (int i = 0; i < n; ++i) {
            final int at = this.counts[this.tmpKeys[i]]++;
            this.players[at] = this.tmpPlayers[i];
            this.xs[at] = this.tmpXs[i];
            this.ys[at] = this.tmpYs[i];
            this.zs[at] = this.tmpZs[i];
            this.tmpPlayers[i] = null; // don't keep removed players reachable through the scratch array
        }
        // counts[c] is now the end of cell c; build every cell's node over its slice
        for (final Long2IntOpenHashMap.Entry entry : this.cellOrdinals.long2IntEntrySet()) {
            final long cellKey = entry.getLongKey();
            final int ordinal = entry.getIntValue();
            final int end = this.counts[ordinal];
            final int start = ordinal == 0 ? 0 : this.startOf(ordinal);
            this.cells.put(cellKey, this.buildNode(start, end, (int)cellKey << TOP_SHIFT, (int)(cellKey >> 32) << TOP_SHIFT, TOP_SHIFT));
        }
        // stale entries past n would keep removed players reachable
        java.util.Arrays.fill(this.players, n, this.players.length, null);
    }

    // after the scatter loop counts[c] holds the end of slice c, and slices are laid out in ordinal order
    private int startOf(final int ordinal) {
        return this.counts[ordinal - 1];
    }

    private Node buildNode(final int start, final int end, final int minX, final int minZ, final int shift) {
        final int count = end - start;
        double lowY = Double.MAX_VALUE, highY = -Double.MAX_VALUE;
        for (int i = start; i < end; ++i) {
            lowY = Math.min(lowY, this.ys[i]);
            highY = Math.max(highY, this.ys[i]);
        }
        if (count > DENSE && shift > 0) {
            final int childShift = Math.max(0, shift - 2);
            final int perAxis = 1 << (shift - childShift);
            final int children = perAxis * perAxis;
            // counting sort of the slice by child index, through the tmp arrays
            final int[] childCounts = new int[children + 1];
            for (int i = start; i < end; ++i) {
                final int sx = Math.min(perAxis - 1, Math.max(0, (Mth.floor(this.xs[i]) - minX) >> childShift));
                final int sz = Math.min(perAxis - 1, Math.max(0, (Mth.floor(this.zs[i]) - minZ) >> childShift));
                final int child = sz * perAxis + sx;
                this.tmpKeys[i] = child;
                ++childCounts[child + 1];
            }
            for (int c = 0; c < children; ++c) {
                childCounts[c + 1] += childCounts[c];
            }
            final int[] fill = java.util.Arrays.copyOf(childCounts, children);
            for (int i = start; i < end; ++i) {
                final int at = start + fill[this.tmpKeys[i]]++;
                this.tmpPlayers[at] = this.players[i];
                this.tmpXs[at] = this.xs[i];
                this.tmpYs[at] = this.ys[i];
                this.tmpZs[at] = this.zs[i];
            }
            System.arraycopy(this.tmpPlayers, start, this.players, start, count);
            System.arraycopy(this.tmpXs, start, this.xs, start, count);
            System.arraycopy(this.tmpYs, start, this.ys, start, count);
            System.arraycopy(this.tmpZs, start, this.zs, start, count);
            java.util.Arrays.fill(this.tmpPlayers, start, end, null);
            final Node[] subs = new Node[children];
            for (int c = 0; c < children; ++c) {
                final int childStart = start + childCounts[c];
                final int childEnd = start + childCounts[c + 1];
                if (childEnd > childStart) {
                    subs[c] = this.buildNode(childStart, childEnd, minX + ((c % perAxis) << childShift), minZ + ((c / perAxis) << childShift), childShift);
                }
            }
            return new Node(minX, minZ, 1 << shift, lowY, highY, start, end, subs, false);
        }
        double lowX = Double.MAX_VALUE, highX = -Double.MAX_VALUE, lowZ = Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
        for (int i = start; i < end; ++i) {
            lowX = Math.min(lowX, this.xs[i]);
            highX = Math.max(highX, this.xs[i]);
            lowZ = Math.min(lowZ, this.zs[i]);
            highZ = Math.max(highZ, this.zs[i]);
        }
        final boolean tight = count > DENSE && highX - lowX < 0.5 && highZ - lowZ < 0.5 && highY - lowY < 0.5;
        return new Node(minX, minZ, 1 << shift, lowY, highY, start, end, null, tight);
    }

    /**
     * The largest factor any player's equipment multiplies its visibility to mobs by (datapacks can set
     * {@code mob_visibility} up to 10 per item; vanilla never exceeds 1). Target-range bounds are scaled by it so the
     * grid never skips a target the linear scan would have found. Players are rescanned 1/20th per build (so a newly
     * equipped boost counts within about two seconds), instead of all of them in one tick.
     */
    public double visibilityBoost() {
        this.ensureBuilt();
        return this.visibilityBoost;
    }

    private void updateVisibilityBoost(final List<ServerPlayer> all, final int n) {
        if (n == 0) {
            return;
        }
        final int batch = Math.max(1, (n + 19) / 20);
        for (int k = 0; k < batch; ++k) {
            if (this.visibilityCursor >= n) {
                // a full pass is done: publish it (never lower than what was seen this pass)
                this.visibilityBoost = this.visibilityBoostCycle;
                this.visibilityBoostCycle = 1.0;
                this.visibilityCursor = 0;
            }
            this.visibilityBoostCycle = Math.max(this.visibilityBoostCycle, visibilityBoost(all.get(this.visibilityCursor++)));
        }
        this.visibilityBoost = Math.max(this.visibilityBoost, this.visibilityBoostCycle);
    }

    private static double visibilityBoost(final ServerPlayer player) {
        return 1.0; // before 26.3 (no mob_visibility item component) nothing raises a player's visibility above 100%
    }

    /**
     * The largest natural-spawning radius (in blocks) any player currently has, from the per-player
     * {@code PlayerNaturallySpawnCreaturesEvent}. Computed once per tick.
     */
    public double spawnRangeMax() {
        final int tick = MinecraftServer.currentTick;
        if (tick != this.spawnRadiusTick) {
            this.spawnRadiusTick = tick;
            int maxChunks = 0;
            for (final ServerPlayer player : this.level.players()) {
                final com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event = player.playerNaturallySpawnedEvent;
                if (event != null && !event.isCancelled()) {
                    maxChunks = Math.max(maxChunks, event.getSpawnRadius());
                }
            }
            this.spawnRangeMax = maxChunks << 4;
        }
        return this.spawnRangeMax;
    }

    /**
     * {@code ChunkMap#isChunkNearPlayer} through the grid: whether any player in the chunk's spawn-range map has the
     * chunk's centre within its own natural-spawning radius. The plain version walks every player in range of the chunk,
     * which in a crowd is every bot - for each spawning chunk, every tick - and only stops early when one qualifies.
     */
    public static boolean isChunkNearSpawningPlayer(final ServerLevel level, final int chunkX, final int chunkZ) {
        final PlayerGrid grid = level.jankyPlayerGrid;
        final double range = grid.spawnRangeMax();
        if (range <= 0.0) {
            return false;
        }
        final double centerX = (chunkX << 4) + 8.0;
        final double centerZ = (chunkZ << 4) + 8.0;
        final int trackRange = ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE;
        return grid.nearestHorizontal(centerX, centerZ, range, player -> {
            if (player.jankyBot && !BotPlayers.affectsMobSpawning()) {
                return false; // such bots are not in the spawn-range map
            }
            final com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent event = player.playerNaturallySpawnedEvent;
            if (event == null || event.isCancelled() || player.isSpectator()) {
                return false;
            }
            final net.minecraft.world.level.ChunkPos at = player.chunkPosition();
            if (Math.abs(at.x - chunkX) > trackRange || Math.abs(at.z - chunkZ) > trackRange) {
                return false; // outside the square the spawn-range map tracks
            }
            final double blockRange = (double)((event.getSpawnRadius() << 4) * (event.getSpawnRadius() << 4));
            final double dx = centerX - player.getX();
            final double dz = centerZ - player.getZ();
            return dx * dx + dz * dz < blockRange;
        }) != null;
    }

    /**
     * The player nearest to (x, y, z) by 3D distance that passes {@code predicate}, or null.
     *
     * @param maxRange search radius in blocks, or a negative value for unlimited. A player further than this is
     *                 never returned (the predicate may still reject closer ones).
     */
    public @Nullable ServerPlayer nearest(final double x, final double y, final double z, final double maxRange,
                                         final Predicate<? super ServerPlayer> predicate) {
        return this.search(x, y, z, false, maxRange, predicate);
    }

    /** Like {@link #nearest}, by horizontal (x/z) distance only. */
    public @Nullable ServerPlayer nearestHorizontal(final double x, final double z, final double maxRange,
                                                   final Predicate<? super ServerPlayer> predicate) {
        return this.search(x, 0.0, z, true, maxRange, predicate);
    }

    // search state; the grid is only used from the server thread
    private @Nullable ServerPlayer best;
    private double bestSq;
    private boolean horizontal;
    // nearest-first ordering scratch, one frame per active searchNode call (searches can nest through predicates)
    private double[][] distFrames = new double[8][16];
    private int[][] orderFrames = new int[8][16];
    private int frame;

    private @Nullable ServerPlayer search(final double x, final double y, final double z, final boolean horizontal,
                                          final double maxRange, final Predicate<? super ServerPlayer> predicate) {
        this.ensureBuilt();
        if (this.builtSize == 0) {
            return null;
        }
        final int cx = Mth.floor(x) >> TOP_SHIFT;
        final int cz = Mth.floor(z) >> TOP_SHIFT;
        int maxRing = Math.max(Math.max(Math.abs(cx - this.minX), Math.abs(this.maxX - cx)),
            Math.max(Math.abs(cz - this.minZ), Math.abs(this.maxZ - cz)));
        if (maxRange >= 0.0) {
            maxRing = Math.min(maxRing, (int)Math.ceil((maxRange + SLACK) / (1 << TOP_SHIFT)) + 1);
        }
        final double maxRangeSq = maxRange >= 0.0 ? maxRange * maxRange : Double.MAX_VALUE;

        // a predicate may itself run a nearest-player search; keep the caller's search state intact
        final ServerPlayer outerBest = this.best;
        final double outerBestSq = this.bestSq;
        final boolean outerHorizontal = this.horizontal;
        this.best = null;
        this.bestSq = maxRangeSq;
        this.horizontal = horizontal;
        try {
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
                        final Node cell = this.cells.get(key(cx + dx, cz + dz));
                        if (cell != null && cell.minDistSq(x, y, z, horizontal) < this.bestSq) {
                            this.searchNode(cell, x, y, z, predicate);
                        }
                        if (ring == 0) {
                            break;
                        }
                    }
                }
                // anything in ring + 1 is at least (ring * CELL - slack) blocks away horizontally
                final double nextBound = Math.max(0.0, ring * (double)(1 << TOP_SHIFT) - SLACK);
                if (this.best != null && this.bestSq <= nextBound * nextBound) {
                    break;
                }
            }
            if (useLinear) {
                return this.linear(x, y, z, maxRangeSq, predicate);
            }
            // players that moved further than the slack since the snapshot
            for (int i = 0, len = this.overflow.size(); i < len; ++i) {
                this.consider(this.overflow.get(i), x, y, z, predicate);
            }
            return this.best;
        } finally {
            this.best = outerBest;
            this.bestSq = outerBestSq;
            this.horizontal = outerHorizontal;
        }
    }

    private void searchNode(final Node node, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        final Node[] subs = node.subs;
        if (subs != null) {
            // nearest sub-cells first, so the best match tightens early and the far side of a crowd is pruned
            final int at = this.frame++;
            try {
                if (at >= this.distFrames.length) {
                    this.distFrames = java.util.Arrays.copyOf(this.distFrames, at * 2);
                    this.orderFrames = java.util.Arrays.copyOf(this.orderFrames, at * 2);
                }
                double[] dists = this.distFrames[at];
                int[] order = this.orderFrames[at];
                if (dists == null || dists.length < subs.length) {
                    this.distFrames[at] = dists = new double[subs.length];
                    this.orderFrames[at] = order = new int[subs.length];
                }
                int count = 0;
                for (int i = 0; i < subs.length; ++i) {
                    final Node sub = subs[i];
                    if (sub == null) {
                        continue;
                    }
                    final double dist = sub.minDistSq(x, y, z, this.horizontal);
                    if (dist >= this.bestSq) {
                        continue;
                    }
                    dists[i] = dist;
                    int k = count++;
                    while (k > 0 && dists[order[k - 1]] > dist) {
                        order[k] = order[k - 1];
                        --k;
                    }
                    order[k] = i;
                }
                for (int k = 0; k < count; ++k) {
                    if (dists[order[k]] >= this.bestSq) {
                        break;
                    }
                    this.searchNode(subs[order[k]], x, y, z, predicate);
                }
            } finally {
                --this.frame;
            }
            return;
        }
        final ServerPlayer[] players = this.players;
        final double[] xs = this.xs;
        final double[] ys = this.ys;
        final double[] zs = this.zs;
        final boolean horizontal = this.horizontal;
        for (int i = node.start, end = node.end; i < end; ++i) {
            // snapshot lower bound: the live position is within SLACK per axis (or the player is on the overflow list)
            final double gx = Math.abs(xs[i] - x) - SLACK;
            final double gz = Math.abs(zs[i] - z) - SLACK;
            double lower = (gx > 0.0 ? gx * gx : 0.0) + (gz > 0.0 ? gz * gz : 0.0);
            if (!horizontal) {
                final double gy = Math.abs(ys[i] - y) - SLACK;
                if (gy > 0.0) lower += gy * gy;
            }
            if (lower >= this.bestSq) {
                continue;
            }
            if (this.consider(players[i], x, y, z, predicate) && node.tight) {
                return; // the rest of the stack is just as far
            }
        }
    }

    /** Offers one player as the best match; true when it was taken. */
    private boolean consider(final ServerPlayer player, final double x, final double y, final double z, final Predicate<? super ServerPlayer> predicate) {
        if (player.level() != this.level || player.isRemoved()) {
            return false;
        }
        final double distSq;
        if (this.horizontal) {
            final double dx = player.getX() - x;
            final double dz = player.getZ() - z;
            distSq = dx * dx + dz * dz;
        } else {
            distSq = player.distanceToSqr(x, y, z);
        }
        if (distSq < this.bestSq && predicate.test(player)) {
            this.bestSq = distSq;
            this.best = player;
            return true;
        }
        return false;
    }

    private @Nullable ServerPlayer linear(final double x, final double y, final double z, final double maxRangeSq,
                                          final Predicate<? super ServerPlayer> predicate) {
        ServerPlayer result = null;
        double resultSq = maxRangeSq;
        for (final ServerPlayer player : this.level.players()) {
            final double distSq;
            if (this.horizontal) {
                final double dx = player.getX() - x;
                final double dz = player.getZ() - z;
                distSq = dx * dx + dz * dz;
            } else {
                distSq = player.distanceToSqr(x, y, z);
            }
            if (distSq < resultSq && predicate.test(player)) {
                resultSq = distSq;
                result = player;
            }
        }
        return result;
    }
}
