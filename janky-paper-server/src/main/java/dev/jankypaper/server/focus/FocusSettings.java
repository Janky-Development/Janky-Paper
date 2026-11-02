package dev.jankypaper.server.focus;

import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The tunables of focus mode, read from the {@code focus} section of {@code janky-paper.yml}.
 *
 * @param mode           when the "others" tier applies
 * @param engageMspt     average MSPT at which auto mode starts throttling the others tier
 * @param releaseMspt    average MSPT at which auto mode stops throttling again (hysteresis)
 * @param focused        settings for focused players (manually focused or standing in a focus area)
 * @param others         settings for everyone else while throttling
 */
public record FocusSettings(FocusManager.Mode mode, double engageMspt, double releaseMspt, Tier focused, Tier others) {

    /**
     * One set of per-player limits. A distance of {@code -1} keeps the world's own value.
     *
     * @param viewDistance              chunk view distance (2-32, or -1)
     * @param simulationDistance        chunk simulation distance (2-32, or -1); capped by the view distance
     * @param chunkRateMultiplier       multiplier on Paper's per-player chunk load/generate/send rates and
     *                                  concurrent chunk loads/generations
     * @param activationRangeMultiplier multiplier on the entity activation ranges around the player
     */
    public record Tier(int viewDistance, int simulationDistance, double chunkRateMultiplier, double activationRangeMultiplier) {

        static Tier read(final ConfigurationSection section, final Tier def) {
            return new Tier(
                distance(section.getInt("view-distance", def.viewDistance)),
                distance(section.getInt("simulation-distance", def.simulationDistance)),
                Math.max(0.05, section.getDouble("chunk-rate-multiplier", def.chunkRateMultiplier)),
                Math.max(0.05, section.getDouble("activation-range-multiplier", def.activationRangeMultiplier))
            );
        }

        void write(final ConfigurationSection section) {
            section.set("view-distance", this.viewDistance);
            section.set("simulation-distance", this.simulationDistance);
            section.set("chunk-rate-multiplier", this.chunkRateMultiplier);
            section.set("activation-range-multiplier", this.activationRangeMultiplier);
        }

        private static int distance(final int value) {
            return value < 0 ? -1 : Math.max(2, Math.min(32, value));
        }
    }

    public static final FocusSettings DEFAULTS = new FocusSettings(
        FocusManager.Mode.AUTO, 40.0, 30.0,
        new Tier(16, 10, 4.0, 2.0),
        new Tier(-1, 4, 0.5, 0.5)
    );

    static FocusSettings read(final YamlConfiguration yaml) {
        final ConfigurationSection focus = section(yaml, "focus");
        FocusManager.Mode mode;
        try {
            mode = FocusManager.Mode.valueOf(focus.getString("mode", DEFAULTS.mode.name()).toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            mode = DEFAULTS.mode;
        }
        final double engage = Math.max(1.0, focus.getDouble("pressure.engage-mspt", DEFAULTS.engageMspt));
        final double release = Math.min(engage, Math.max(0.0, focus.getDouble("pressure.release-mspt", DEFAULTS.releaseMspt)));
        return new FocusSettings(mode, engage, release,
            Tier.read(section(focus, "focused"), DEFAULTS.focused),
            Tier.read(section(focus, "others"), DEFAULTS.others));
    }

    void write(final YamlConfiguration yaml) {
        final ConfigurationSection focus = section(yaml, "focus");
        focus.set("mode", this.mode.name().toLowerCase(Locale.ROOT));
        focus.set("pressure.engage-mspt", this.engageMspt);
        focus.set("pressure.release-mspt", this.releaseMspt);
        this.focused.write(section(focus, "focused"));
        this.others.write(section(focus, "others"));
    }

    FocusSettings withMode(final FocusManager.Mode mode) {
        return new FocusSettings(mode, this.engageMspt, this.releaseMspt, this.focused, this.others);
    }

    private static ConfigurationSection section(final ConfigurationSection parent, final String path) {
        final ConfigurationSection existing = parent.getConfigurationSection(path);
        return existing != null ? existing : parent.createSection(path);
    }
}
