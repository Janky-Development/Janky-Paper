/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.bot;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/**
 * Parallel bot movement: the movement physics of all bots a fake-player plugin ticks run together on worker threads,
 * after the plugin's tick loop, instead of one after another on the main thread.
 *
 * <p>A bot's movement ({@code Player#travel}: friction, gravity, the collision sweep against blocks and the world
 * border, fluids, climbing, the post-move adjustments) only reads the world and only writes the bot's own fields -
 * except for four things that touch shared structures: the entity lookup's section bookkeeping and the locator-bar /
 * player-grid updates in {@code Entity#setPosRaw}, and the sounds and game events a step emits. Those four are deferred
 * to the main thread. Everything else of the bot's tick (fire, air, effects, food, item pickup, inventory, AI inputs)
 * keeps running where it always did.</p>
 *
 * <p>Protocol, per server tick, driven by the plugin (FPP) through the reflective hooks on this class:</p>
 * <ol>
 *   <li>{@link #defer(org.bukkit.entity.Player)} right before the bot's {@code doTick()} arms it: the bot's next
 *       {@code travel()} records its input and returns without moving (the bot stands still for the rest of its own
 *       tick, exactly as a real player's server-side entity does - a real player is moved by its client's packets).</li>
 *   <li>{@link #flush()} after the plugin ticked every bot: all captured bots travel in parallel (phase 2, the server
 *       thread joins in), then the main thread applies each bot's deferred side effects and the block effects of the
 *       move (phase 3), then runs the deferred sounds and game events.</li>
 *   <li>The plugin reads positions, runs its own fall-damage check and its AI with the bots moved.</li>
 * </ol>
 *
 * <p>Phase 2 is the only time entities move off the main thread, and nothing else runs on the server thread meanwhile -
 * it is inside the plugin's scheduler task - so the world is read-only for the duration: no chunk unloads, no block
 * changes, no entity adds/removes. Chunk reads from the workers go to loaded chunks only (an unloaded chunk reads as a
 * barrier, see {@link BotWorkerThreads}); a bot standing next to an unloaded chunk simply doesn't walk into it.</p>
 *
 * <p>Bots that are riding, gliding (elytra flight damages the bot on impact - an event), sleeping, flying, in
 * spectator mode or dead are not captured and move inline as before. Off by default
 * ({@code bots.parallel-movement}); a server without a plugin that calls the hooks is unaffected either way.</p>
 */
public final class BotMovement {

    /** Bumped on incompatible changes to the reflective hooks. */
    public static final int API_VERSION = 1;

    // ServerPlayer#jankyMoveState
    /** Normal: {@code travel()} moves the bot inline. */
    public static final byte NONE = 0;
    /** {@link #defer} was called: the next {@code travel()} is captured. */
    public static final byte ARMED = 1;
    /** {@code travel()} recorded its input and returned; the bot has not moved yet this tick. */
    public static final byte CAPTURED = 2;
    /** {@code travel()} is running on a movement worker (or the server thread's share of phase 2). */
    public static final byte EXECUTING = 3;
    /** Moved off-thread; its deferred side effects wait for phase 3. */
    public static final byte MOVED = 4;

    private static volatile boolean enabled;
    private static volatile boolean parallelPhase;
    private static final List<ServerPlayer> armed = new ArrayList<>();
    private static final List<ServerPlayer> captured = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Runnable> deferredWorldEffects = new ConcurrentLinkedQueue<>();
    private static ExecutorService pool;
    private static int poolSize;
    private static final AtomicInteger failures = new AtomicInteger();
    private static volatile Throwable lastFailure;
    private static long lastFailureReport;
    private static long lastFlushNanos;
    private static int lastFlushBots;

    private BotMovement() {
    }

    /** The threads phase 2 runs on. A subclass of the path-search worker so block reads stay loaded-chunk-only. */
    static final class MovementWorker extends BotWorkerThreads.WorkerThread {
        MovementWorker(final Runnable task, final String name) {
            super(task, name);
            this.setPriority(Thread.NORM_PRIORITY); // the server thread waits on these: not below it
        }
    }

    public static boolean isMovementWorker() {
        return Thread.currentThread() instanceof MovementWorker;
    }

    // ---------------------------------------------------------------- configuration

    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    public static boolean enabled() {
        return enabled;
    }

    /** For the startup line and /focus: worker thread count, or 0 before the first flush. */
    public static int workers() {
        return poolSize;
    }

    /** How long the last flush took (nanoseconds) and how many bots it moved - for diagnostics. */
    public static long lastFlushNanos() {
        return lastFlushNanos;
    }

    public static int lastFlushBots() {
        return lastFlushBots;
    }

    // ---------------------------------------------------------------- plugin hooks (reflection)

    /** Whether a plugin should use the hooks at all: enabled in the config and lightweight bots are on. */
    public static boolean isAvailable() {
        return enabled && BotPlayers.enabled();
    }

    /**
     * Arms {@code bot} so that its next {@code travel()} this tick is captured instead of run. No-op for real players,
     * when the feature is off, or when the bot is already armed. Main thread only.
     */
    public static void defer(final org.bukkit.entity.Player bot) {
        if (!enabled || !(bot instanceof CraftPlayer craft)) {
            return;
        }
        final ServerPlayer player = craft.getHandle();
        if (!player.jankyBot || player.jankyMoveState != NONE) {
            return;
        }
        player.jankyMoveState = ARMED;
        armed.add(player);
    }

    /** Whether {@code bot}'s movement this tick was captured and is waiting for {@link #flush()}. */
    public static boolean isDeferred(final org.bukkit.entity.Player bot) {
        return bot instanceof CraftPlayer craft && craft.getHandle().jankyMoveState == CAPTURED;
    }

    /**
     * Called from {@code Player#travel}: records the travel input of an armed bot and reports that the caller must
     * return without moving. Bots that must not move off-thread are disarmed here and move inline.
     */
    public static boolean capture(final ServerPlayer player, final Vec3 input) {
        if (player.jankyMoveState != ARMED) {
            return false;
        }
        if (player.isPassenger() || player.isFallFlying() || player.noPhysics || player.getAbilities().flying
            || player.isSleeping() || player.isRemoved() || !player.valid || player.isDeadOrDying()) {
            player.jankyMoveState = NONE;
            return false;
        }
        player.jankyTravelInput = input;
        player.jankyMoveState = CAPTURED;
        captured.add(player);
        return true;
    }

    /**
     * Moves every captured bot (in parallel), applies their deferred side effects, and resets every armed bot. Main
     * thread only; a no-op when nothing is armed.
     */
    public static void flush() {
        TickThread.ensureTickThread("Bot movement flush off-main");
        if (armed.isEmpty()) {
            return;
        }
        final long start = System.nanoTime();
        try {
            if (!captured.isEmpty()) {
                runParallel(captured);
                for (int i = 0, len = captured.size(); i < len; ++i) {
                    apply(captured.get(i));
                }
                Runnable effect;
                while ((effect = deferredWorldEffects.poll()) != null) {
                    try {
                        effect.run();
                    } catch (final Throwable t) {
                        report(t);
                    }
                }
            }
        } finally {
            lastFlushBots = captured.size();
            lastFlushNanos = System.nanoTime() - start;
            for (int i = 0, len = armed.size(); i < len; ++i) {
                armed.get(i).jankyMoveState = NONE;
            }
            armed.clear();
            captured.clear();
            parallelPhase = false;
        }
    }

    /** {@link #flush} if a plugin armed bots and never flushed them (called once per server tick as a safety net). */
    public static void flushIfPending() {
        if (!armed.isEmpty()) {
            flush();
        }
    }

    /**
     * Sounds and game events raised during phase 2 are queued and run on the main thread in phase 3 (they broadcast to
     * players / notify sculk sensors). Returns true when the caller must stop: the effect was taken over.
     */
    public static boolean deferWorldEffect(final Runnable effect) {
        if (!parallelPhase) {
            return false;
        }
        deferredWorldEffects.add(effect);
        return true;
    }

    // ---------------------------------------------------------------- phases

    private static void runParallel(final List<ServerPlayer> bots) {
        final int count = bots.size();
        ensurePool();
        final int slices = Math.min(count, poolSize + 1); // the server thread takes a slice too
        final int perSlice = (count + slices - 1) / slices;
        final CountDownLatch done = new CountDownLatch(slices - 1);
        parallelPhase = true;
        try {
            for (int slice = 1; slice < slices; ++slice) {
                final int from = slice * perSlice;
                final int to = Math.min(count, from + perSlice);
                pool.execute(() -> {
                    try {
                        moveRange(bots, from, to);
                    } finally {
                        done.countDown();
                    }
                });
            }
            moveRange(bots, 0, Math.min(count, perSlice));
            boolean interrupted = false;
            while (true) {
                try {
                    done.await();
                    break;
                } catch (final InterruptedException e) {
                    interrupted = true; // the workers hold entities half-moved: never leave without them
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            parallelPhase = false;
        }
    }

    private static void moveRange(final List<ServerPlayer> bots, final int from, final int to) {
        for (int i = from; i < to; ++i) {
            final ServerPlayer bot = bots.get(i);
            if (bot.jankyMoveState != CAPTURED || bot.isRemoved() || !bot.valid) {
                continue; // despawned between its tick and the flush: nothing to move
            }
            bot.jankyMoveState = EXECUTING;
            try {
                bot.travel(bot.jankyTravelInput);
                bot.jankyComputeInertHint(); // read-only: lets the main thread skip block effects that cannot do anything
            } catch (final Throwable t) {
                report(t);
            } finally {
                bot.jankyMoveState = MOVED;
            }
        }
    }

    /** Phase 3, main thread: what the move left for us. */
    private static void apply(final ServerPlayer bot) {
        if (bot.jankyMoveState != MOVED || bot.isRemoved()) {
            bot.jankyInertHint = false; // never let a hint outlive this tick
            return;
        }
        try {
            if (bot.jankyMovedOffThread) {
                bot.jankyMovedOffThread = false;
                bot.jankyApplyDeferredMoveCallbacks(); // entity lookup section, locator bar, player grid
            }
            bot.jankyApplyDeferredBlockEffects(); // stepOn / inside-block effects of this tick's movement
        } catch (final Throwable t) {
            report(t);
        }
    }

    private static void ensurePool() {
        if (pool != null) {
            return;
        }
        poolSize = Math.max(1, Math.min(15, Runtime.getRuntime().availableProcessors() - 1));
        final AtomicInteger ids = new AtomicInteger();
        pool = Executors.newFixedThreadPool(poolSize, task -> {
            final MovementWorker thread = new MovementWorker(task, "Janky Paper Bot Movement #" + ids.incrementAndGet());
            thread.setUncaughtExceptionHandler((t, ex) -> dev.jankypaper.server.log.JankyLog.error("Uncaught exception in " + t.getName() + ": " + ex));
            return thread;
        });
        dev.jankypaper.server.log.JankyLog.info("Parallel bot movement: " + poolSize + " worker thread(s) + the server thread");
    }

    private static void report(final Throwable t) {
        failures.incrementAndGet();
        lastFailure = t;
        final long now = System.currentTimeMillis();
        if (now - lastFailureReport >= 5000L) {
            lastFailureReport = now;
            final int count = failures.getAndSet(0);
            dev.jankypaper.server.log.JankyLog.error("Parallel bot movement: " + count + " bot move(s) failed in the last 5 s - last: " + t);
            t.printStackTrace();
        }
    }

    /** The last failure during a flush, if any (for diagnostics). */
    public static Throwable lastFailure() {
        return lastFailure;
    }
}
