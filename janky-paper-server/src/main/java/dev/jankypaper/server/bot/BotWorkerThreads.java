package dev.jankypaper.server.bot;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Worker threads for plugins that search the world for bots off the main thread (FPP's path searches).
 *
 * <p>Block and fluid reads made from these threads go straight to loaded chunks through the lock-free chunk map and
 * <b>never load or generate a chunk</b>: a block in an unloaded chunk reads as a barrier (solid, so a path search
 * routes around unknown terrain instead of stalling on a synchronous load), and its fluid as empty. Reads from every
 * other thread keep vanilla behaviour, so other plugins are unaffected.</p>
 *
 * <p>Plugins find this class by reflection ({@code Class.forName("dev.jankypaper.server.bot.BotWorkerThreads")}) and
 * call {@link #threadFactory(String)}; it does not exist on other servers, where they keep searching on the main
 * thread. {@link #API_VERSION} is bumped on incompatible changes.</p>
 */
public final class BotWorkerThreads {

    public static final int API_VERSION = 1;

    private BotWorkerThreads() {
    }

    /** Marker type: a read is "off-thread bot work" exactly when it runs on one of these. */
    public static final class WorkerThread extends Thread {
        WorkerThread(final Runnable task, final String name) {
            super(task, name);
            this.setDaemon(true);
            // below the server and chunk threads: searches use idle cores, never compete for the main thread's
            this.setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    public static ThreadFactory threadFactory(final String namePrefix) {
        final AtomicInteger ids = new AtomicInteger();
        return task -> {
            final WorkerThread thread = new WorkerThread(task, namePrefix + " #" + ids.incrementAndGet());
            thread.setUncaughtExceptionHandler((t, ex) -> dev.jankypaper.server.log.JankyLog.error(
                "Uncaught exception in " + t.getName() + ": " + ex));
            return thread;
        };
    }

    public static boolean isWorker() {
        return Thread.currentThread() instanceof WorkerThread;
    }

    /** {@code Level#getBlockState} on a worker thread: loaded chunks only. */
    public static BlockState blockState(final Level level, final BlockPos pos) {
        final LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        return chunk == null ? Blocks.BARRIER.defaultBlockState() : chunk.getBlockState(pos);
    }

    /** {@code Level#getFluidState} on a worker thread: loaded chunks only. */
    public static FluidState fluidState(final Level level, final BlockPos pos) {
        final LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }
}
