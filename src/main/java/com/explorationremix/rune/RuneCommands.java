package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /remix rune ...} (operators): trying runes out without finding them.
 * <ul>
 *   <li>{@code give <rune|all> [count]}: rune items;</li>
 *   <li>{@code row <runes>}: writes a row onto the gear in hand, e.g. {@code row fan spark on_hit burst | orb} (runes by
 *   id, {@code _} for an empty slot, {@code |} for a cast break);</li>
 *   <li>{@code clear}: empties the gear's row (the runes are not given back);</li>
 *   <li>{@code energy [amount]}: fills the gear (or sets its energy);</li>
 *   <li>{@code depth <0|1|2>}: sets your run depth (how many slots are awake) and keeps it there wherever you go;
 *   {@code depth auto} goes back to following the run;</li>
 *   <li>{@code info}: the gear's row read out, with its energy.</li>
 * </ul>
 */
public final class RuneCommands {
    private RuneCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("rune")
                .then(Commands.literal("give")
                        .then(Commands.argument("rune", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> give(c, StringArgumentType.getString(c, "rune"), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> give(c, StringArgumentType.getString(c, "rune"), IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("row").then(Commands.argument("runes", StringArgumentType.greedyString()).executes(RuneCommands::row)))
                .then(Commands.literal("clear").executes(RuneCommands::clear))
                .then(Commands.literal("energy").executes(c -> energy(c, -1.0F))
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0F)).executes(c -> energy(c, FloatArgumentType.getFloat(c, "amount")))))
                .then(Commands.literal("depth")
                        .then(Commands.literal("auto").executes(RuneCommands::depthAuto))
                        .then(Commands.argument("depth", IntegerArgumentType.integer(0, RunDepth.MAX)).executes(RuneCommands::depth)))
                .then(Commands.literal("info").executes(RuneCommands::info));
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        out.add("all");
        for (RuneId r : RuneId.runes()) out.add(r.id);
        return out;
    }

    private static int give(CommandContext<CommandSourceStack> c, String which, int count) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        List<RuneId> runes = new ArrayList<>();
        if (which.equals("all")) runes.addAll(RuneId.runes());
        else {
            RuneId r = RuneId.byId(which);
            if (r == null) {
                c.getSource().sendFailure(Component.literal("No rune called " + which));
                return 0;
            }
            runes.add(r);
        }
        for (RuneId r : runes) {
            ItemStack s = RuneItem.stack(r, count);
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        c.getSource().sendSuccess(() -> Component.literal("Gave " + (runes.size() == 1 ? count + " " + runes.get(0).title : "every") + " rune"
                + (count > 1 || runes.size() > 1 ? "s" : "")), false);
        return runes.size();
    }

    /** The runed gear in the main hand, else the off hand. */
    private static ItemStack gear(ServerPlayer p) {
        ItemStack s = p.getItemInHand(InteractionHand.MAIN_HAND);
        if (RuneGear.isGear(s)) return s;
        s = p.getItemInHand(InteractionHand.OFF_HAND);
        return RuneGear.isGear(s) ? s : ItemStack.EMPTY;
    }

    private static int row(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack g = gear(p);
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) {
            c.getSource().sendFailure(Component.literal("Hold a sword, bow or crossbow"));
            return 0;
        }
        Row row;
        try {
            row = Row.parse(StringArgumentType.getString(c, "runes"));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        if (row.size() > spec.slotCount()) {
            c.getSource().sendFailure(Component.literal("This " + spec.host().word + " has " + spec.slotCount() + " slots"));
            return 0;
        }
        RuneGear.setRow(g, row.resized(spec.slotCount()));
        resetCursor(p, g, spec);
        return info(c);
    }

    private static int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack g = gear(p);
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) {
            c.getSource().sendFailure(Component.literal("Hold a sword, bow or crossbow"));
            return 0;
        }
        RuneGear.setRow(g, Row.empty(spec.slotCount()));
        c.getSource().sendSuccess(() -> Component.literal("Row cleared"), false);
        return 1;
    }

    private static void resetCursor(ServerPlayer p, ItemStack g, HostSpec spec) {
        long now = p.level().getGameTime();
        CastState s = RuneGear.state(g, spec, now);
        s.cursor = 0;
        s.readyAt = now;
        RuneGear.setState(g, s);
    }

    private static int energy(CommandContext<CommandSourceStack> c, float amount) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack g = gear(p);
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) {
            c.getSource().sendFailure(Component.literal("Hold a sword, bow or crossbow"));
            return 0;
        }
        long now = p.level().getGameTime();
        CastState s = RuneGear.state(g, spec, now);
        CastMachine.settle(s, spec, now);
        s.energy = amount < 0 ? spec.maxEnergy() : Math.min(amount, spec.maxEnergy());
        RuneGear.setState(g, s);
        float e = s.energy;
        c.getSource().sendSuccess(() -> Component.literal("Energy " + Math.round(e) + " of " + Math.round(spec.maxEnergy())), false);
        return 1;
    }

    private static int depth(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int d = IntegerArgumentType.getInteger(c, "depth");
        RunDepth.pin(p, d);
        c.getSource().sendSuccess(() -> Component.literal("Run depth " + d + " (" + Explain.depthName(d) + "), kept until /remix rune depth auto"), false);
        return 1;
    }

    private static int depthAuto(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        RunDepth.unpin(p);
        int d = RunDepth.get(p);
        c.getSource().sendSuccess(() -> Component.literal("Run depth follows the run again: " + d + " (" + Explain.depthName(d) + ")"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack g = gear(p);
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) {
            c.getSource().sendFailure(Component.literal("Hold a sword, bow or crossbow"));
            return 0;
        }
        int depth = RunDepth.get(p);
        Reading r = RuneGear.reading(g, depth);
        long now = p.level().getGameTime();
        float e = CastMachine.energyAt(RuneGear.state(g, spec, now), spec, now);
        c.getSource().sendSuccess(() -> Component.literal(RuneGear.row(g) + "  (" + r.awake() + " of " + spec.slotCount() + " slots awake, energy "
                + Math.round(e) + " of " + Math.round(spec.maxEnergy()) + ")"), false);
        for (String line : Explain.lines(r)) c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
