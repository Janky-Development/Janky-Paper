package dev.jankypaper.server.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Console/log output in Janky Paper's house style: true-color ANSI in the "amethyst dusk" palette,
 * a bracketed small-caps {@code [ᴊᴀɴᴋʏ]} tag, and structured banners (sections, dotted key/value
 * rows, status badges).
 *
 * <p>Palette (keep in sync with {@code JankyPrefixConverter} in the {@code log4jPlugins} source set):</p>
 * <ul>
 *   <li>{@code #B388FF} amethyst - the brand colour (tag, titles)</li>
 *   <li>{@code #D7B8FF} soft lilac - values</li>
 *   <li>{@code #F3ECFF} lilac white - message text</li>
 *   <li>{@code #9187A8} dusty mauve / {@code #5E5675} deep plum - secondary text, rules, dotted leaders</li>
 *   <li>{@code #3DF5A0} teal-green / {@code #FFB454} amber / {@code #FF5C8A} rose - ok / warning / error</li>
 * </ul>
 */
public final class JankyLog {

    public static final String NAME = "Janky Paper";

    private static final String ESC = "\u001b";
    static final String RESET = ESC + "[0m";
    static final String BOLD = ESC + "[1m";

    static final String PRIMARY = ESC + "[38;2;179;136;255m";  // #B388FF amethyst
    static final String SOFT = ESC + "[38;2;215;184;255m";     // #D7B8FF soft lilac
    static final String TEXT = ESC + "[38;2;243;236;255m";     // #F3ECFF lilac white
    static final String MUTED = ESC + "[38;2;145;135;168m";    // #9187A8 dusty mauve
    static final String DIM = ESC + "[38;2;94;86;117m";        // #5E5675 deep plum
    static final String SUCCESS = ESC + "[38;2;61;245;160m";   // #3DF5A0 teal-green
    static final String WARN = ESC + "[38;2;255;180;84m";      // #FFB454 amber
    static final String ERROR = ESC + "[38;2;255;92;138m";     // #FF5C8A rose

    static final String TAG = BOLD + PRIMARY + "[ᴊᴀɴᴋʏ]" + RESET;

    private static final int RULE_WIDTH = 50;
    private static final int KEY_WIDTH = 18;

    // Named "JankyPaper" on purpose: log4j2.xml lists it among the loggers printed without a [logger] prefix.
    private static final Logger LOGGER = LoggerFactory.getLogger("JankyPaper");

    private JankyLog() {
    }

    public static void info(String message) {
        LOGGER.info("{} {}{}{}", TAG, TEXT, message, RESET);
    }

    public static void success(String message) {
        LOGGER.info("{} {}{}{}", TAG, SUCCESS, message, RESET);
    }

    public static void warn(String message) {
        LOGGER.warn("{} {}{}{}", TAG, WARN, message, RESET);
    }

    public static void error(String message) {
        LOGGER.error("{} {}{}{}", TAG, ERROR, message, RESET);
    }

    public static void highlight(String message) {
        LOGGER.info("{} {}{}{}{}", TAG, BOLD, PRIMARY, message, RESET);
    }

    /** Thin separator: {@code ──────}. */
    public static void rule() {
        LOGGER.info("{} {}{}{}", TAG, DIM, "─".repeat(RULE_WIDTH), RESET);
    }

    /** Heavy separator: {@code ══════}. */
    public static void boldRule() {
        LOGGER.info("{} {}{}{}", TAG, PRIMARY, "═".repeat(RULE_WIDTH), RESET);
    }

    /** Section header: {@code ── Label ───────}. */
    public static void section(String label) {
        String dashes = "─".repeat(Math.max(0, RULE_WIDTH - label.length() - 4));
        LOGGER.info("{} {}── {}{}{}{} {}{}{}", TAG, DIM, RESET, BOLD, TEXT, label, DIM, dashes, RESET);
    }

    /** Key/value row with a dotted leader: {@code   Key ........ value}. */
    public static void kv(String key, Object value) {
        int dots = Math.max(1, KEY_WIDTH - key.length());
        LOGGER.info("{}   {}{} {}{}{} {}{}{}", TAG, TEXT, key, DIM, ".".repeat(dots), RESET, SOFT, value, RESET);
    }

    /** Status row with a badge: {@code   [+] Label ........ detail} (teal / amber / mauve by state). */
    public static void state(State state, String label, String detail) {
        String badge;
        String valueColor;
        switch (state) {
            case OK -> {
                badge = SUCCESS + "[+]" + RESET;
                valueColor = SUCCESS;
            }
            case WARN -> {
                badge = WARN + "[!]" + RESET;
                valueColor = WARN;
            }
            default -> {
                badge = MUTED + "[-]" + RESET;
                valueColor = MUTED;
            }
        }
        int dots = Math.max(1, KEY_WIDTH - label.length());
        LOGGER.info("{}   {} {}{} {}{}{} {}{}{}", TAG, badge, TEXT, label, DIM, ".".repeat(dots), RESET, valueColor, detail, RESET);
    }

    public static void state(boolean enabled, String label) {
        state(enabled ? State.OK : State.OFF, label, enabled ? "enabled" : "disabled");
    }

    /** Title line used at the top of banners: bold amethyst name, lilac-white suffix. */
    public static void title(String name, String suffix) {
        LOGGER.info("{}   {}{}{}{} {}{}", TAG, BOLD, PRIMARY, name, RESET, TEXT, suffix + RESET);
    }

    /** Muted mauve tagline under a title. */
    public static void tagline(String text) {
        LOGGER.info("{}   {}{}{}", TAG, MUTED, text, RESET);
    }

    public static String formatDuration(long ms) {
        long totalSec = ms / 1_000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static String formatSeconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.3fs", ms / 1000.0D);
    }

    public enum State {
        OK,
        WARN,
        OFF
    }
}
