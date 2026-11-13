/*
 * Janky Paper - created and owned by Pepe-tf (https://github.com/Pepe-tf),
 * code by CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors.
 * GNU GPL v3 with additional terms (LICENSE, NOTICE.md): every copy and fork must keep this credit.
 */
package dev.jankypaper.server.log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Masks player IP addresses in log lines ({@code logging.mask-player-ips} in {@code janky-paper.yml}).
 *
 * <p>The last two IPv4 octets - everything after the first two IPv6 groups - become one {@code *} per
 * digit, so {@code /85.226.123.45:54321} is logged as {@code /85.226.***.**:54321}. Enough stays to tell
 * players and networks apart in a support thread without a recording server's logs publishing anyone's
 * address. Paper funnels every address it logs (join line, login failures and disconnects, packet-handling
 * errors) through {@code Connection.getLoggableAddress}, which calls {@link #address(SocketAddress)}.</p>
 */
public final class IpMask {

    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])");

    private static volatile boolean enabled = true;

    private IpMask() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    public static void read(final ConfigurationSection section) {
        enabled = section.getBoolean("mask-player-ips", true);
    }

    public static void write(final ConfigurationSection section) {
        section.set("mask-player-ips", enabled);
    }

    /**
     * The address as Paper would log it ({@code /ip:port}), with the host masked while masking is enabled.
     */
    public static String address(final SocketAddress address) {
        if (address == null) {
            return "null";
        }
        if (!enabled) {
            return address.toString();
        }
        if (address instanceof InetSocketAddress socket) {
            final InetAddress inet = socket.getAddress();
            final String host = inet != null ? maskHost(inet) : mask(socket.getHostString());
            return "/" + host + ":" + socket.getPort();
        }
        return mask(address.toString());
    }

    private static String maskHost(final InetAddress inet) {
        final String host = inet.getHostAddress();
        if (inet instanceof Inet4Address) {
            return maskIpv4(host);
        }
        if (inet instanceof Inet6Address) {
            return "[" + maskIpv6(host) + "]";
        }
        return mask(host);
    }

    static String maskIpv4(final String host) {
        final String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return mask(host);
        }
        return octets[0] + "." + octets[1] + "." + stars(octets[2]) + "." + stars(octets[3]);
    }

    static String maskIpv6(String host) {
        final int scope = host.indexOf('%');
        if (scope >= 0) {
            host = host.substring(0, scope);
        }
        final String[] groups = host.split(":", -1);
        final StringBuilder out = new StringBuilder(host.length());
        for (int i = 0; i < groups.length; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(i < 2 ? groups[i] : stars(groups[i]));
        }
        return out.toString();
    }

    /**
     * Masks every IPv4 literal in free text - for addresses that did not arrive as an {@link InetSocketAddress}.
     */
    public static String mask(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        final Matcher matcher = IPV4.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        final StringBuilder out = new StringBuilder(text.length());
        do {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                matcher.group(1) + "." + matcher.group(2) + "." + stars(matcher.group(3)) + "." + stars(matcher.group(4))));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stars(final String digits) {
        return "*".repeat(digits.length());
    }
}
