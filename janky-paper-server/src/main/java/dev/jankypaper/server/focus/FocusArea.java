package dev.jankypaper.server.focus;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A square of chunks in one world that counts as "focused": every player standing inside it gets the
 * focused tier. Stored in {@code janky-paper.yml} as {@code world;chunkX;chunkZ;radius}.
 */
public record FocusArea(String world, int chunkX, int chunkZ, int radius) {

    public boolean contains(final String world, final int chunkX, final int chunkZ) {
        return this.world.equals(world)
            && Math.abs(chunkX - this.chunkX) <= this.radius
            && Math.abs(chunkZ - this.chunkZ) <= this.radius;
    }

    public String serialize() {
        return this.world + ";" + this.chunkX + ";" + this.chunkZ + ";" + this.radius;
    }

    public static @Nullable FocusArea parse(final String raw) {
        final String[] parts = raw.split(";");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new FocusArea(parts[0], Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()),
                Math.max(0, Integer.parseInt(parts[3].trim())));
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s @ chunk %d, %d (radius %d)", this.world, this.chunkX, this.chunkZ, this.radius);
    }
}
