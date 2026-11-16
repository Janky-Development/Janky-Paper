/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.world;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A cheap pre-check for {@code Entity#applyEffectsFromBlocks}: every tick, every moving entity walks each block its box
 * swept through and asks it to react ({@code entityInside} - cobwebs, fire, portals, pressure plates, berry bushes,
 * powder snow, fluids...). A bot or mob walking over open ground sweeps nothing but air, grass and the like, none of
 * which react, yet paid for the full step-by-step traversal (block lookups per step, a lambda per block, collision
 * clipping, an effect collector flush). The patched {@code Entity} reads the blocks of the swept bounding box once and
 * asks here whether every one of them is "inert": no fluid, and a block class that does not override
 * {@code entityInside}. Then the traversal has nothing to find and is skipped.
 *
 * <p>Exact, not an approximation: the box spanned by the entity's bounding box at every end point of the tick's movements
 * contains every block the traversal could visit, and an inert block's visit has no effect - except
 * {@code onInsideBlock}, which a real player uses for the "enter block" advancement trigger and an ender pearl for its own
 * logic, so those always take the full path (bots have no advancement listeners). Disabled entirely if the vanilla method
 * cannot be found by name, and for boxes bigger than {@value #MAX_CELLS} blocks.</p>
 */
public final class InertBlocks {

    private static final int MAX_CELLS = 48;
    private static final boolean AVAILABLE;

    static {
        boolean found;
        try {
            BlockBehaviour.class.getDeclaredMethod("entityInside", BlockState.class, Level.class, BlockPos.class, Entity.class,
                InsideBlockEffectApplier.class, boolean.class);
            found = true;
        } catch (final NoSuchMethodException | LinkageError e) {
            found = false;
        }
        AVAILABLE = found;
    }

    /** Whether a block class (or a superclass below {@link BlockBehaviour}) overrides {@code entityInside}. */
    private static final ClassValue<Boolean> REACTS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            for (Class<?> c = type; c != null && c != BlockBehaviour.class && c != Object.class; c = c.getSuperclass()) {
                for (final Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals("entityInside")) return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    /** Whether a block class (or a superclass below {@link net.minecraft.world.level.block.Block}) overrides {@code stepOn}. */
    private static final ClassValue<Boolean> STEP_ON = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            for (Class<?> c = type; c != null && c != net.minecraft.world.level.block.Block.class && c != Object.class; c = c.getSuperclass()) {
                for (final Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals("stepOn")) return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    };

    private InertBlocks() {
    }

    /** Whether standing on this block does something (magma, slime, turtle eggs, redstone ore, sculk...). */
    public static boolean reactsToStepOn(final BlockState state) {
        return !AVAILABLE || STEP_ON.get(state.getBlock().getClass());
    }

    /** Whether the shortcut may be used for this entity: its {@code onInsideBlock} does nothing. */
    public static boolean applies(final Entity entity) {
        if (!AVAILABLE) return false;
        return entity instanceof ServerPlayer player ? player.jankyBot : !(entity instanceof ThrownEnderpearl);
    }

    /** Whether nothing in this state reacts to an entity inside it. */
    public static boolean isInert(final BlockState state) {
        return isInert(state, false);
    }

    /**
     * As above; with {@code waterInert}, water (and waterlogged blocks) also counts as inert: water's only reaction is
     * putting out fire ({@code clearFire}, which does nothing to an entity whose fire ticks are already at or below
     * zero - the caller's condition), and the end of {@code applyEffectsFromBlocks} resets the fire ticks either way.
     */
    public static boolean isInert(final BlockState state, final boolean waterInert) {
        if (state.isAir()) return true;
        final net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !(waterInert && fluid.getType() instanceof net.minecraft.world.level.material.WaterFluid)) return false;
        return !REACTS.get(state.getBlock().getClass());
    }

    /** Whether every (loaded) block in the cell box is inert. False when a chunk is not loaded or the box is too big. */
    public static boolean allInert(final Level level, final int minX, final int minY, final int minZ,
                                   final int maxX, final int maxY, final int maxZ) {
        return allInert(level, minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    /** As above; {@code waterInert}: see {@link #isInert(BlockState, boolean)}. */
    public static boolean allInert(final Level level, final int minX, final int minY, final int minZ,
                                   final int maxX, final int maxY, final int maxZ, final boolean waterInert) {
        final long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells <= 0 || cells > MAX_CELLS) return false;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    final BlockState state = level.getBlockStateIfLoaded(pos.set(x, y, z));
                    if (state == null || !isInert(state, waterInert)) return false;
                }
            }
        }
        return true;
    }
}
