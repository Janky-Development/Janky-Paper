package dev.jankypaper.server.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.jspecify.annotations.Nullable;

/**
 * Fast path for {@code World#getNearbyEntitiesByType}. Paper answers it by fetching every entity in the box and then
 * filtering by class, so asking "any arrows near this bot?" walks every bot in a crowd of thousands. When the requested
 * class covers only a few entity types (arrows, TNT minecarts, items, ...), this queries the per-type entity index for
 * each of them instead. Broad classes ({@code Entity}, {@code LivingEntity}, {@code Mob}, ...) keep the original scan.
 *
 * <p>Results are still filtered with {@code clazz.isInstance}, so callers get exactly the entities the original scan
 * returned.</p>
 */
public final class TypedEntityLookup {

    /** A class covering more entity types than this is answered by the original box scan. */
    private static final int MAX_TYPES = 8;
    private static final List<EntityType<?>> BROAD = List.of();
    private static final Map<Class<?>, List<EntityType<?>>> TYPES = new ConcurrentHashMap<>();

    private TypedEntityLookup() {
    }

    /**
     * The entities of {@code clazz} in the box around (x, y, z), or {@code null} when {@code clazz} is too broad for a
     * typed lookup (the caller then runs the original scan).
     */
    @SuppressWarnings("unchecked")
    public static <T extends org.bukkit.entity.Entity> @Nullable Collection<T> nearby(
        final ServerLevel level, final @Nullable Class<? extends T> clazz,
        final double x, final double y, final double z,
        final double xRadius, final double yRadius, final double zRadius,
        final @Nullable Predicate<? super T> predicate
    ) {
        if (clazz == null) {
            return null;
        }
        final List<EntityType<?>> types = TYPES.computeIfAbsent(clazz, TypedEntityLookup::resolve);
        if (types == BROAD) {
            return null;
        }
        final AABB box = new AABB(x - xRadius, y - yRadius, z - zRadius, x + xRadius, y + yRadius, z + zRadius);
        final List<Entity> found = new ArrayList<>();
        for (final EntityType<?> type : types) {
            level.getEntities((EntityType<Entity>)type, box, entity -> true, found);
        }
        final List<T> out = new ArrayList<>(found.size());
        for (final Entity entity : found) {
            final org.bukkit.entity.Entity bukkit = entity.getBukkitEntity();
            if (clazz.isInstance(bukkit) && (predicate == null || predicate.test((T)bukkit))) {
                out.add((T)bukkit);
            }
        }
        return out;
    }

    private static List<EntityType<?>> resolve(final Class<?> clazz) {
        final List<EntityType<?>> types = new ArrayList<>();
        for (final org.bukkit.entity.EntityType bukkitType : org.bukkit.entity.EntityType.values()) {
            final Class<?> entityClass = bukkitType.getEntityClass();
            if (bukkitType == org.bukkit.entity.EntityType.UNKNOWN || entityClass == null || !clazz.isAssignableFrom(entityClass)) {
                continue;
            }
            types.add(CraftEntityType.bukkitToMinecraft(bukkitType));
            if (types.size() > MAX_TYPES) {
                return BROAD;
            }
        }
        // no matching type: an interface the entity type classes don't declare - only the original scan is safe
        return types.isEmpty() ? BROAD : List.copyOf(types);
    }
}
