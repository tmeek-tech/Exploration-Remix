package com.explorationremix.rune;

import com.explorationremix.rune.armor.WornRunes;
import com.explorationremix.rune.engine.ArmorRunes;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.Piece;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.RuneKind;
import com.explorationremix.rune.item.RuneItem;
import com.explorationremix.rune.menu.RuneTableMenu;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
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
 *   <li>{@code set <runes>}: sets the sockets of the weapon in hand, e.g. {@code set spark wide overflow} (runes by id,
 *   in any order: each goes to the socket of its kind; the runes are not taken from you);</li>
 *   <li>{@code armor <piece> <runes>}: sets the sockets of the piece worn there, e.g. {@code armor boots leap mend vigor}
 *   ({@code _} alone empties it);</li>
 *   <li>{@code tool <rune|clear>}: sets the socket of the tool in your main hand;</li>
 *   <li>{@code clear}: empties the weapon's sockets (the runes are not given back);</li>
 *   <li>{@code energy [amount]}: fills the weapon (or sets its energy);</li>
 *   <li>{@code table <pos>}: opens the rune sockets of what you hold and wear at the enchanting table there;</li>
 *   <li>{@code info}: the weapon's runes read out at your XP level, with its energy;</li>
 *   <li>{@code worn}: what the runes you wear do, and how far each collect set has come.</li>
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
                .then(Commands.literal("set").then(Commands.argument("runes", StringArgumentType.greedyString())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names().subList(1, names().size()), b.createOffset(lastWord(b.getInput()))))
                        .executes(RuneCommands::set)))
                .then(Commands.literal("armor").then(Commands.argument("piece", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("helmet", "chestplate", "leggings", "boots"), b))
                        .then(Commands.argument("runes", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(armorNames(), b.createOffset(lastWord(b.getInput()))))
                                .executes(RuneCommands::armor))))
                .then(Commands.literal("tool").then(Commands.argument("rune", StringArgumentType.word())
                        .suggests((c, b) -> {
                            List<String> n = new ArrayList<>(List.of("clear"));
                            for (RuneId r : RuneId.ofKind(RuneKind.TOOL)) n.add(r.id);
                            return SharedSuggestionProvider.suggest(n, b);
                        })
                        .executes(RuneCommands::tool)))
                .then(Commands.literal("worn").executes(RuneCommands::worn))
                .then(Commands.literal("clear").executes(RuneCommands::clear))
                .then(Commands.literal("energy").executes(c -> energy(c, -1.0F))
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0F)).executes(c -> energy(c, FloatArgumentType.getFloat(c, "amount")))))
                .then(Commands.literal("table").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(RuneCommands::table)))
                .then(Commands.literal("info").executes(RuneCommands::info));
    }

    /** Where the last word of a command being typed starts (suggestions for {@code set} go there). */
    private static int lastWord(String input) { return input.lastIndexOf(' ') + 1; }

    private static List<String> armorNames() {
        List<String> out = new ArrayList<>(List.of("_"));
        for (RuneId r : RuneId.runes()) if (r.kind.armor()) out.add(r.id);
        return out;
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

    /** The weapon in the main hand, else the off hand. */
    private static ItemStack gear(ServerPlayer p) {
        ItemStack s = p.getItemInHand(InteractionHand.MAIN_HAND);
        if (RuneGear.isGear(s)) return s;
        s = p.getItemInHand(InteractionHand.OFF_HAND);
        return RuneGear.isGear(s) ? s : ItemStack.EMPTY;
    }

    private static int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack g = gear(p);
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) {
            c.getSource().sendFailure(Component.literal("Hold a sword, bow or crossbow"));
            return 0;
        }
        Loadout l;
        try {
            l = Loadout.parse(StringArgumentType.getString(c, "runes"));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        RuneGear.setLoadout(g, l);
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
        RuneGear.setLoadout(g, Loadout.EMPTY);
        c.getSource().sendSuccess(() -> Component.literal("Sockets emptied"), false);
        return 1;
    }

    private static int table(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        if (!RuneTableMenu.isTable(p.level().getBlockState(pos))) {
            c.getSource().sendFailure(Component.literal("No enchanting table there"));
            return 0;
        }
        RuneTableMenu.open(p, pos);
        c.getSource().sendSuccess(() -> Component.literal("Rune table opened"), false);
        return 1;
    }

    private static int armor(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String which = StringArgumentType.getString(c, "piece").toLowerCase(java.util.Locale.ROOT);
        Piece piece = null;
        for (Piece pc : Piece.values()) if (pc.word.equals(which) || pc.name().toLowerCase(java.util.Locale.ROOT).equals(which)) piece = pc;
        if (piece == null) {
            c.getSource().sendFailure(Component.literal("No piece called " + which + ": helmet, chestplate, leggings or boots"));
            return 0;
        }
        ItemStack worn = p.getItemBySlot(RuneGear.slot(piece));
        if (RuneGear.piece(worn) != piece) {
            c.getSource().sendFailure(Component.literal("Wear " + piece.withArticle() + " first"));
            return 0;
        }
        ArmorRunes a;
        try {
            a = ArmorRunes.parse(StringArgumentType.getString(c, "runes"));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        if (a.effect() != null && !a.effect().fits(piece)) {
            c.getSource().sendFailure(Component.literal(a.effect().title + " is not " + piece.withArticle() + " rune"));
            return 0;
        }
        RuneGear.setArmor(worn, a);
        return worn(c);
    }

    private static int tool(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ItemStack held = p.getMainHandItem();
        if (!RuneGear.isTool(held)) {
            c.getSource().sendFailure(Component.literal("Hold a pickaxe, axe, shovel or hoe"));
            return 0;
        }
        String which = StringArgumentType.getString(c, "rune");
        RuneId r = which.equals("clear") ? null : RuneId.byId(which);
        if (!which.equals("clear") && (r == null || !r.isTool())) {
            c.getSource().sendFailure(Component.literal("No tool rune called " + which));
            return 0;
        }
        RuneGear.setTool(held, r);
        c.getSource().sendSuccess(() -> Component.literal(r == null ? "Tool socket emptied" : r.title + " set"), false);
        return 1;
    }

    private static int worn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ArmorSet set = WornRunes.of(p);
        for (Piece pc : Piece.values()) {
            ArmorRunes a = set.piece(pc);
            String line = pc.title + ": " + (a == null ? "nothing that takes runes" : a.toString());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        for (RuneId r : set.collects()) {
            String line = Explain.collectLine(r, set.count(r));
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
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
        // as if a hit had just filled it: it holds for the fade's delay before going back to the floor
        s.lastGain = now;
        RuneGear.setState(g, s);
        float e = s.energy;
        c.getSource().sendSuccess(() -> Component.literal("Energy " + Math.round(e) + " of " + Math.round(spec.maxEnergy())), false);
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
        Reading r = RuneGear.reading(g, p);
        long now = p.level().getGameTime();
        float e = CastMachine.energyAt(RuneGear.state(g, spec, now), spec, now);
        c.getSource().sendSuccess(() -> Component.literal(RuneGear.loadout(g) + "  (level " + p.experienceLevel + ", energy "
                + Math.round(e) + " of " + Math.round(spec.maxEnergy()) + ")"), false);
        for (String line : Explain.lines(r)) c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
