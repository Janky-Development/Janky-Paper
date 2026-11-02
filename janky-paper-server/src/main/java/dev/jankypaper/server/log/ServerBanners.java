package dev.jankypaper.server.log;

import io.papermc.paper.ServerBuildInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The structured startup / shutdown banners the server prints, in the same layout as the
 * Fake-Player plugin's banners (sections, dotted rows, status badges, closing "ready" line).
 */
public final class ServerBanners {

    private static final int MAX_LISTED_NAMES = 6;

    private ServerBanners() {
    }

    /** Printed by {@code PaperBootstrap} before anything else - who we are and what we run on. */
    public static void printBootHeader() {
        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        JankyLog.boldRule();
        JankyLog.title(JankyLog.NAME, "v" + bi.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE));
        JankyLog.tagline("a jankier Paper - built on PaperMC/Paper for Minecraft " + bi.minecraftVersionId());
        JankyLog.rule();
        JankyLog.section("Environment");
        JankyLog.kv("Java", System.getProperty("java.specification.version")
            + " (" + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ")");
        String vendorVersion = System.getProperty("java.vendor.version");
        JankyLog.kv("Vendor", System.getProperty("java.vendor") + (vendorVersion != null ? " " + vendorVersion : ""));
        JankyLog.kv("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " (" + System.getProperty("os.arch") + ")");
        JankyLog.kv("Memory", (Runtime.getRuntime().maxMemory() / 1024L / 1024L) + " MB max");
        JankyLog.kv("CPU threads", Runtime.getRuntime().availableProcessors());
        JankyLog.rule();
    }

    /** Printed once the server is fully up (replaces Paper's plain "Done" line). */
    public static void printStartup(MinecraftServer server, long startupMs) {
        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();

        JankyLog.boldRule();
        JankyLog.title(JankyLog.NAME, "v" + bi.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE)
            + JankyLog.MUTED + "  -  Minecraft " + bi.minecraftVersionId());
        JankyLog.rule();

        JankyLog.section("Network");
        String ip = server.getLocalIp();
        JankyLog.kv("Listening on", (ip == null || ip.isEmpty() ? "*" : ip) + ":" + server.getPort());
        JankyLog.state(server.usesAuthentication() ? JankyLog.State.OK : JankyLog.State.WARN,
            "Online mode", server.usesAuthentication() ? "enabled" : "OFFLINE - usernames are not verified");
        boolean velocity = io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled;
        boolean bungee = org.spigotmc.SpigotConfig.bungee;
        JankyLog.state(velocity || bungee ? JankyLog.State.OK : JankyLog.State.OFF,
            "Proxy", velocity ? "Velocity (modern forwarding)" : bungee ? "BungeeCord" : "none");
        JankyLog.state(server.getPlayerList().isUsingWhitelist(), "Whitelist");
        JankyLog.kv("Max players", server.getPlayerList().getMaxPlayers());

        JankyLog.section("World");
        List<String> worldNames = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            worldNames.add(level.getWorld().getName());
        }
        JankyLog.kv("Worlds", worldNames.size() + summarize(worldNames));
        JankyLog.kv("Difficulty", server.getWorldData().getDifficulty().name().toLowerCase(java.util.Locale.ROOT));
        JankyLog.kv("Game mode", server.getWorldData().getGameType().getName());
        JankyLog.kv("View distance", server.getPlayerList().getViewDistance() + " chunks");
        JankyLog.kv("Simulation", server.getPlayerList().getSimulationDistance() + " chunks");

        JankyLog.section("Plugins");
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        List<String> pluginNames = new ArrayList<>();
        int disabled = 0;
        for (Plugin plugin : plugins) {
            pluginNames.add(plugin.getName());
            if (!plugin.isEnabled()) disabled++;
        }
        JankyLog.kv("Loaded", plugins.length + summarize(pluginNames));
        if (disabled > 0) {
            JankyLog.state(JankyLog.State.WARN, "Failed to enable", disabled + " plugin(s) - check the log above");
        }

        JankyLog.rule();
        // Keep the exact ")! For help, type \"help\"" phrase: hosting panels detect a finished start-up by it.
        JankyLog.success("  Done (" + JankyLog.formatSeconds(startupMs) + ")! For help, type \"help\"");
        JankyLog.boldRule();
    }

    /** Printed when the server begins shutting down (replaces the plain "Stopping server" line). */
    public static void printShutdownHeader(MinecraftServer server) {
        long uptimeMs = System.currentTimeMillis() - org.bukkit.craftbukkit.Main.BOOT_TIME.toEpochMilli();
        int online = server.getPlayerList() != null ? server.getPlayerList().getPlayerCount() : 0;
        int worlds = 0;
        for (ServerLevel ignored : server.getAllLevels()) worlds++;

        JankyLog.boldRule();
        JankyLog.highlight("  " + JankyLog.NAME + "  -  stopping server");
        JankyLog.rule();
        JankyLog.section("Session");
        JankyLog.kv("Uptime", JankyLog.formatDuration(uptimeMs));
        JankyLog.kv("Players online", online);
        JankyLog.kv("Worlds to save", worlds);
        JankyLog.rule();
    }

    /** Printed as the very last thing before the process winds down. */
    public static void printShutdownFooter() {
        JankyLog.rule();
        JankyLog.info("  Everything saved - see you next time!");
        JankyLog.boldRule();
    }

    private static String summarize(List<String> names) {
        if (names.isEmpty()) return "";
        List<String> shown = names.size() > MAX_LISTED_NAMES ? names.subList(0, MAX_LISTED_NAMES) : names;
        String more = names.size() > MAX_LISTED_NAMES ? ", +" + (names.size() - MAX_LISTED_NAMES) + " more" : "";
        return " " + JankyLog.MUTED + "(" + String.join(", ", shown) + more + ")";
    }
}
