package dev.jankypaper.server.focus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /focus} - manage focus mode (see {@link FocusManager}).
 *
 * <pre>
 * /focus                        status
 * /focus add &lt;players&gt;          always give these players the focused tier
 * /focus remove &lt;players&gt;
 * /focus here [radius]          focus a square of chunks around you (default radius 8)
 * /focus clear                  remove all focused players and areas
 * /focus mode auto|always|off
 * /focus reload                 re-read janky-paper.yml
 * </pre>
 */
@NullMarked
public final class FocusCommand {

    public static final String DESCRIPTION = "Focus server resources on the players or area being recorded";
    public static final String PERMISSION = "jankypaper.command.focus";

    private static final TextColor PRIMARY = TextColor.color(0xB388FF);
    private static final TextColor SOFT = TextColor.color(0xD7B8FF);
    private static final TextColor MUTED = TextColor.color(0x9187A8);
    private static final int DEFAULT_RADIUS = 8;

    private FocusCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("focus")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .executes(FocusCommand::status)
            .then(Commands.literal("add")
                .then(Commands.argument("players", ArgumentTypes.players())
                    .executes(ctx -> players(ctx, true))))
            .then(Commands.literal("remove")
                .then(Commands.argument("players", ArgumentTypes.players())
                    .executes(ctx -> players(ctx, false))))
            .then(Commands.literal("here")
                .executes(ctx -> here(ctx, DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                    .executes(ctx -> here(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
            .then(Commands.literal("clear").executes(ctx -> {
                FocusManager.clear();
                reply(ctx.getSource().getSender(), "Cleared all focused players and areas.");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("mode")
                .then(mode(FocusManager.Mode.AUTO))
                .then(mode(FocusManager.Mode.ALWAYS))
                .then(mode(FocusManager.Mode.OFF)))
            .then(Commands.literal("reload").executes(ctx -> {
                FocusManager.load();
                reply(ctx.getSource().getSender(), "Reloaded janky-paper.yml - mode " + FocusManager.describeMode() + ".");
                return Command.SINGLE_SUCCESS;
            }))
            .build();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> mode(final FocusManager.Mode mode) {
        return Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
            FocusManager.setMode(mode);
            reply(ctx.getSource().getSender(), "Focus mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".");
            return Command.SINGLE_SUCCESS;
        });
    }

    private static int players(final CommandContext<CommandSourceStack> ctx, final boolean add) throws CommandSyntaxException {
        final List<Player> players = ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        int changed = 0;
        for (final Player player : players) {
            if (add ? FocusManager.addPlayer(player.getUniqueId()) : FocusManager.removePlayer(player.getUniqueId())) {
                changed++;
            }
        }
        reply(ctx.getSource().getSender(), (add ? "Focused " : "Unfocused ") + changed + " player(s).");
        return changed;
    }

    private static int here(final CommandContext<CommandSourceStack> ctx, final int radius) {
        final Location location = ctx.getSource().getLocation();
        final FocusArea area = new FocusArea(location.getWorld().getName(),
            location.getBlockX() >> 4, location.getBlockZ() >> 4, radius);
        FocusManager.addArea(area);
        reply(ctx.getSource().getSender(), "Focused area " + area.describe() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int status(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = ctx.getSource().getSender();
        final FocusSettings s = FocusManager.settings();
        final double mspt = net.minecraft.server.MinecraftServer.getServer().getAverageTickTimeNanos() / 1.0E6;

        sender.sendMessage(text("Focus mode ", PRIMARY).append(text(s.mode().name().toLowerCase(Locale.ROOT), SOFT)));
        sender.sendMessage(row("MSPT", String.format(Locale.ROOT, "%.1f (engage %.0f / release %.0f)", mspt, s.engageMspt(), s.releaseMspt())));
        sender.sendMessage(row("Throttling others", FocusManager.isThrottling() ? "yes" : "no"));
        sender.sendMessage(row("Focused tier", describe(s.focused())));
        sender.sendMessage(row("Others tier", describe(s.others())));
        sender.sendMessage(row("Lightweight bots", dev.jankypaper.server.bot.BotPlayers.describe()
            + " - " + dev.jankypaper.server.bot.BotPlayers.online() + " online"));
        for (final UUID id : FocusManager.focusedPlayers()) {
            final OfflinePlayer player = Bukkit.getOfflinePlayer(id);
            final String name = player.getName() != null ? player.getName() : id.toString();
            sender.sendMessage(row("Player", name + (player.isOnline() ? "" : " (offline)")));
        }
        for (final FocusArea area : FocusManager.areas()) {
            sender.sendMessage(row("Area", area.describe()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String describe(final FocusSettings.Tier tier) {
        return String.format(Locale.ROOT, "view %s, sim %s, chunks x%.2f, activation x%.2f",
            tier.viewDistance() < 0 ? "world" : tier.viewDistance(),
            tier.simulationDistance() < 0 ? "world" : tier.simulationDistance(),
            tier.chunkRateMultiplier(), tier.activationRangeMultiplier());
    }

    private static Component row(final String key, final String value) {
        return text("  " + key + ": ", MUTED).append(text(value, SOFT));
    }

    private static void reply(final CommandSender sender, final String message) {
        sender.sendMessage(text(message, SOFT));
    }
}
