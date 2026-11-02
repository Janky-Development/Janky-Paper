package dev.jankypaper.server.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * {@code %jankyPrefix} - the themed line prefix every console line gets: {@code HH:mm:ss LEVEL │ }
 * with a dim plum timestamp, the level coloured by severity and a thin plum bar.
 *
 * <p>Lives in the {@code log4jPlugins} source set so the annotation processor registers it.
 * The palette mirrors {@code JankyLog} in the main source set - keep the two in sync.</p>
 */
@Plugin(name = "jankyPrefix", category = PatternConverter.CATEGORY)
@ConverterKeys({"jankyPrefix"})
public final class JankyPrefixConverter extends LogEventPatternConverter {

    private static final String ESC = "\u001b";
    private static final String RESET = ESC + "[0m";
    private static final String BOLD = ESC + "[1m";
    private static final String SOFT = ESC + "[38;2;215;184;255m";   // #D7B8FF soft lilac
    private static final String MUTED = ESC + "[38;2;145;135;168m";  // #9187A8 dusty mauve
    private static final String DIM = ESC + "[38;2;94;86;117m";      // #5E5675 deep plum
    private static final String WARN = ESC + "[38;2;255;180;84m";    // #FFB454 amber
    private static final String ERROR = ESC + "[38;2;255;92;138m";   // #FF5C8A rose

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private JankyPrefixConverter() {
        super("jankyPrefix", "jankyPrefix");
    }

    public static JankyPrefixConverter newInstance(final String[] options) {
        return new JankyPrefixConverter();
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final Level level = event.getLevel();
        toAppendTo.append(DIM).append(TIME.format(Instant.ofEpochMilli(event.getTimeMillis()))).append(RESET)
            .append(' ')
            .append(levelStyle(level)).append(padLevel(level.name())).append(RESET)
            .append(' ')
            .append(DIM).append('│').append(RESET)
            .append(' ');
    }

    private static String levelStyle(final Level level) {
        if (level.isMoreSpecificThan(Level.ERROR)) return BOLD + ERROR;
        if (level == Level.WARN) return WARN;
        if (level == Level.INFO) return SOFT;
        return MUTED; // DEBUG / TRACE
    }

    private static String padLevel(final String name) {
        return name.length() >= 5 ? name.substring(0, 5) : name + " ".repeat(5 - name.length());
    }
}
