/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.migration;

import dev.jankypaper.server.log.JankyLog;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import joptsimple.OptionSet;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Brings the files of a server that ran Paper (or an older Janky Paper) up to Janky Paper's defaults.
 *
 * <p>Janky Paper changes some of Paper's / Spigot's / vanilla's defaults, but a default only reaches a file
 * when the file is first written. Someone who swaps the jar of an existing Paper server keeps
 * {@code max-players=20}, {@code max-tnt-per-tick: 100} and so on. This runs once per config version, before
 * any of those files is loaded, and moves every value that still holds the <i>old</i> default to the new one.
 * A value the owner changed is left alone, and every change is logged.</p>
 *
 * <p>The version is stored as {@code config-version} in {@code config/janky-paper.yml} (also written by
 * {@code FocusManager}). A missing key means the server has never been migrated: either a Paper server
 * (there is no janky-paper.yml yet) or a Janky Paper build from before migrations existed. On a brand-new
 * server none of the files exist yet, so nothing is changed and the version is simply recorded.</p>
 *
 * <p>When a Janky default changes again: bump {@link #CURRENT} and add a step to {@link #migrate}.</p>
 */
public final class ConfigMigration {

    /** The config version this build writes. */
    public static final int CURRENT = 1;
    public static final String VERSION_KEY = "config-version";

    private static final String JANKY_FILE = "janky-paper.yml";

    private final List<String> changes = new ArrayList<>();

    private ConfigMigration() {
    }

    /** Called from {@code PluginInitializerManager.load}, before server.properties, spigot.yml and the Paper configs load. */
    public static void run(final OptionSet options) {
        try {
            final Path paperDir = ((File) options.valueOf("paper-settings-directory")).toPath();
            final Path jankyFile = paperDir.resolve(JANKY_FILE);
            final int from = readVersion(jankyFile);
            if (from >= CURRENT) {
                return;
            }
            final ConfigMigration migration = new ConfigMigration();
            migration.migrate(from,
                ((File) options.valueOf("config")).toPath(),
                ((File) options.valueOf("spigot-settings")).toPath(),
                paperDir.resolve("paper-global.yml"));
            writeVersion(jankyFile);
            if (!migration.changes.isEmpty()) {
                JankyLog.highlight("Moved this server's settings to Janky Paper defaults (config v" + from + " -> v" + CURRENT + "):");
                for (final String change : migration.changes) {
                    JankyLog.info("  " + change);
                }
                JankyLog.info("Only values still at the old default were changed. Edit the files to set them back.");
            }
        } catch (final RuntimeException ex) {
            // never stop the server over this - it just keeps the old values
            JankyLog.error("Config migration failed, keeping the current settings: " + ex);
        }
    }

    private void migrate(final int from, final Path serverProperties, final Path spigotYml, final Path paperGlobal) {
        if (from < 1) {
            // v1 - 2026-11-15 "Tune server defaults for large bot crowds"
            this.replaceLine(serverProperties, "max-players",
                "^max-players[ \\t]*[=:][ \\t]*20[ \\t]*(\\r?)$", "max-players=2000000$1", "20 -> 2000000");
            this.spigotWorldDefault(spigotYml, "max-tnt-per-tick", 100, Integer.MAX_VALUE);
            this.replaceLine(paperGlobal, "update-equipment-on-player-actions",
                "^([ \\t]*)update-equipment-on-player-actions:[ \\t]*true[ \\t]*(\\r?)$", "$1update-equipment-on-player-actions: false$2", "true -> false");
        }
    }

    // ---------------------------------------------------------------- steps

    /**
     * Rewrites the one line matching {@code regex} (multiline) in place, keeping the rest of the file - comments,
     * order, formatting - exactly as it was. Does nothing when the file is missing or the key is not exactly once
     * at its old value.
     */
    private void replaceLine(final Path file, final String key, final String regex, final String replacement, final String shown) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            // ISO-8859-1 maps every byte to one char and back, so whatever encoding the file has survives untouched
            final String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            final Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
            if (!matcher.find()) {
                return;
            }
            final int start = matcher.start();
            if (matcher.find()) {
                return; // the key twice - not a file we understand, leave it
            }
            matcher.reset();
            final String updated = matcher.replaceFirst(replacement);
            if (updated.equals(text)) {
                return;
            }
            write(file, updated);
            this.changes.add(file.getFileName() + " " + key + ": " + shown);
        } catch (final IOException ex) {
            JankyLog.warn("Config migration: could not update " + key + " in " + file + ": " + ex.getMessage());
        }
    }

    /** Moves {@code world-settings.default.<key>} in spigot.yml from {@code oldValue} to {@code newValue}. Per-world overrides are kept. */
    private void spigotWorldDefault(final Path file, final String key, final int oldValue, final int newValue) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        final String path = "world-settings.default." + key;
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (final IOException | InvalidConfigurationException ex) {
            // Spigot reports a broken spigot.yml itself; saving our copy here would wipe it
            JankyLog.warn("Config migration: could not read " + file + ", skipping " + key + ": " + ex.getMessage());
            return;
        }
        if (!yaml.isInt(path) || yaml.getInt(path) != oldValue) {
            return;
        }
        yaml.set(path, newValue);
        try {
            yaml.save(file.toFile());
            this.changes.add(file.getFileName() + " " + path + ": " + oldValue + " -> " + newValue);
        } catch (final IOException ex) {
            JankyLog.warn("Config migration: could not save " + file + ": " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- version marker

    private static int readVersion(final Path jankyFile) {
        if (!Files.isRegularFile(jankyFile)) {
            return 0;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(jankyFile.toFile());
        } catch (final IOException | InvalidConfigurationException ex) {
            return CURRENT; // unreadable: FocusManager reports it; do not touch the other files on a guess
        }
        return yaml.getInt(VERSION_KEY, 0);
    }

    private static void writeVersion(final Path jankyFile) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (Files.isRegularFile(jankyFile)) {
                yaml.load(jankyFile.toFile());
            }
            yaml.set(VERSION_KEY, CURRENT);
            Files.createDirectories(jankyFile.getParent());
            yaml.save(jankyFile.toFile());
        } catch (final IOException | InvalidConfigurationException ex) {
            JankyLog.warn("Config migration: could not record " + VERSION_KEY + " in " + jankyFile + ": " + ex.getMessage());
        }
    }

    private static void write(final Path file, final String text) throws IOException {
        final Path temp = file.resolveSibling(file.getFileName() + ".janky-tmp");
        Files.writeString(temp, text, StandardCharsets.ISO_8859_1);
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException atomicFailed) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
