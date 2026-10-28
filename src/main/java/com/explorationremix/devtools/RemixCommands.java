package com.explorationremix.devtools;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.CellPlan;
import com.explorationremix.world.plan.Chamber;
import com.explorationremix.world.plan.Gate;
import com.explorationremix.world.plan.LayerPlans;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** /remix: debug and development commands. */
public final class RemixCommands {
    private RemixCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("remix").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(RemixCommands::info))
                .then(Commands.literal("layers").executes(RemixCommands::layers))
                .then(Commands.literal("gate").then(Commands.argument("band", IntegerArgumentType.integer(1, 8)).executes(RemixCommands::gate)))
                .then(Commands.literal("tp").then(Commands.argument("layer", StringArgumentType.word()).executes(RemixCommands::tp)))
                .then(Commands.literal("cell").executes(RemixCommands::cell))
                .then(Commands.literal("run")
                        .then(Commands.literal("start").executes(RemixCommands::runStart))
                        .then(Commands.literal("end").executes(RemixCommands::runEnd))
                        .then(Commands.literal("info").executes(RemixCommands::runInfo)))
                .then(Commands.literal("rune")
                        .then(Commands.literal("add").then(Commands.argument("id", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                                        com.explorationremix.rune.RuneRegistry.all().stream().map(com.explorationremix.rune.Rune::id), b))
                                .executes(RemixCommands::runeAdd)))
                        .then(Commands.literal("clear").executes(RemixCommands::runeClear))
                        .then(Commands.literal("list").executes(RemixCommands::runeList)))
                .then(Commands.literal("xp").then(Commands.argument("amount", IntegerArgumentType.integer()).executes(RemixCommands::xp)))
                .then(Commands.literal("book").then(Commands.argument("attunement", StringArgumentType.word())
                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(com.explorationremix.rune.AttunementBook.ALL, b))
                        .executes(RemixCommands::book)))
                .then(Commands.literal("slots").then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(RemixCommands::slots)))
                .then(Commands.literal("slice")
                        .executes(c -> slice(c, 256, "x"))
                        .then(Commands.argument("width", IntegerArgumentType.integer(32, 2048))
                                .executes(c -> slice(c, IntegerArgumentType.getInteger(c, "width"), "x"))
                                .then(Commands.argument("axis", StringArgumentType.word())
                                        .executes(c -> slice(c, IntegerArgumentType.getInteger(c, "width"), StringArgumentType.getString(c, "axis")))))));
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        CommandSourceStack s = c.getSource();
        if (!DeepWorld.installed()) { s.sendSuccess(() -> Component.literal("Deep world is not installed in this overworld."), false); return 0; }
        int y = (int) Math.floor(s.getPosition().y);
        int x = (int) Math.floor(s.getPosition().x), z = (int) Math.floor(s.getPosition().z);
        Layer l = DeepWorld.layerAt(y);
        int band = DeepWorld.bandAt(y);
        LayerPlans plans = DeepWorld.plans();
        StringBuilder sb = new StringBuilder();
        sb.append("Pack floor ").append(DeepWorld.packFloor()).append(", new floor ").append(DeepWorld.floorY()).append(". ");
        if (band != 0) sb.append("You are in band ").append(band).append(". ");
        else if (l != null) {
            CellPlan plan = plans.cellAt(l.index(), x, z);
            sb.append("Layer ").append(l.id()).append(" (y ").append(l.bottom()).append("..").append(l.top()).append("), cell ")
                    .append(plan.cellX).append(",").append(plan.cellZ).append(", role ").append(plan.roleAt(x, z))
                    .append(", biome ").append(plans.biomeAt(x, y, z)).append(", route distance ").append((int) plan.distToRouteXZ(x, z)).append(". ");
        } else sb.append("You are above the first band. ");
        String msg = sb.toString();
        s.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int layers(CommandContext<CommandSourceStack> c) {
        for (Layer l : DeepWorld.layers()) {
            String line = l.id() + ": y " + l.bottom() + ".." + l.top() + ", band above " + DeepWorld.bandBottomY(l.index() + 1) + ".." + DeepWorld.bandTopY(l.index() + 1)
                    + ", fluid " + l.fluid() + " at " + (l.bottom() + l.fluidLevel()) + ", style " + l.style();
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int gate(CommandContext<CommandSourceStack> c) {
        int band = IntegerArgumentType.getInteger(c, "band");
        if (!DeepWorld.installed() || band > DeepWorld.bandCount()) { c.getSource().sendFailure(Component.literal("No such band")); return 0; }
        Gate g = DeepWorld.plans().gateAt(band, c.getSource().getPosition().x, c.getSource().getPosition().z);
        String line = String.format("Gate %d of this cell: upper landing %.0f %.0f %.0f, lower landing %.0f %.0f %.0f", band, g.upperX(), g.upperY(), g.upperZ(), g.lowerX(), g.lowerY(), g.lowerZ());
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "layer");
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null || !DeepWorld.installed()) return 0;
        LayerPlans plans = DeepWorld.plans();
        if (id.equals("gate") || id.equals("upper")) {
            Gate g = plans.gateAt(1, p.getX(), p.getZ());
            p.teleportTo(g.upperX(), g.upperY(), g.upperZ());
            return 1;
        }
        for (Layer l : DeepWorld.layers()) {
            if (l.id().equals(id) || String.valueOf(l.index() + 1).equals(id)) {
                CellPlan plan = plans.cellAt(l.index(), p.getX(), p.getZ());
                Gate g = plan.entryGate;
                p.teleportTo(g.lowerX(), g.lowerY(), g.lowerZ());
                return 1;
            }
        }
        if (id.equals("bedrock")) {
            Layer last = DeepWorld.layers().get(DeepWorld.layers().size() - 1);
            CellPlan plan = plans.cellAt(last.index(), p.getX(), p.getZ());
            if (plan.bedrockLanding != null) p.teleportTo(plan.bedrockLanding.x(), plan.bedrockLanding.y(), plan.bedrockLanding.z());
            return 1;
        }
        c.getSource().sendFailure(Component.literal("Unknown layer; use gate, 1-4, a layer id, or bedrock"));
        return 0;
    }

    // ------------------------------------------------------------------ runs and runes

    private static int runStart(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        com.explorationremix.run.RunState s = com.explorationremix.run.RunManager.state(p);
        if (s.active) { c.getSource().sendFailure(Component.literal("Already on run " + s.runId)); return 0; }
        com.explorationremix.run.RunManager.start(p, s);
        c.getSource().sendSuccess(() -> Component.literal("Run " + s.runId + " started"), true);
        return 1;
    }

    private static int runEnd(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        com.explorationremix.run.RunState s = com.explorationremix.run.RunManager.state(p);
        if (!s.active) { c.getSource().sendFailure(Component.literal("Not on a run")); return 0; }
        com.explorationremix.run.RunManager.end(p, s, "command");
        c.getSource().sendSuccess(() -> Component.literal("Run ended"), true);
        return 1;
    }

    private static int runInfo(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        com.explorationremix.run.RunState s = com.explorationremix.run.RunManager.state(p);
        String line = s.active
                ? String.format("Run %d: deepest layer %d, gates %d, kills %d, XP earned %d spent %d (%d points held), rune blocks woken %d, slots per sentence %d",
                        s.runId, s.deepestLayer, s.gatesPassed, s.kills, s.xpEarned, s.xpSpent, com.explorationremix.run.RunXp.points(p), s.runeBlocksWoken,
                        com.explorationremix.run.RunManager.slotsFor(s) / Math.max(1, com.explorationremix.config.ERConfig.INSTANCE.startingSentences.get()))
                : "Not on a run" + (s.runId > 0 ? " (last run " + s.runId + " ended: " + s.endReason + ")" : "");
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int runeAdd(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        net.minecraft.resources.ResourceLocation id = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "id");
        if (com.explorationremix.rune.RuneRegistry.get(id) == null) { c.getSource().sendFailure(Component.literal("Unknown rune " + id)); return 0; }
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (!com.explorationremix.rune.RuneTool.canHoldRunes(held)) { c.getSource().sendFailure(Component.literal("Hold a tool or weapon")); return 0; }
        long run = com.explorationremix.run.RunManager.currentRun(p);
        if (run == 0L) { c.getSource().sendFailure(Component.literal("Start a run first (/remix run start)")); return 0; }
        if (!com.explorationremix.rune.RuneTool.addRune(held, id, run)) { c.getSource().sendFailure(Component.literal("No free slot on this tool")); return 0; }
        c.getSource().sendSuccess(() -> Component.literal("Added " + id + " to " + held.getHoverName().getString()), true);
        return 1;
    }

    private static int runeClear(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (!com.explorationremix.rune.RuneTool.canHoldRunes(held)) { c.getSource().sendFailure(Component.literal("Hold a tool or weapon")); return 0; }
        com.explorationremix.rune.RuneTool.setRunes(held, java.util.List.of(), com.explorationremix.run.RunManager.currentRun(p));
        c.getSource().sendSuccess(() -> Component.literal("Runes cleared"), true);
        return 1;
    }

    private static int runeList(CommandContext<CommandSourceStack> c) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (com.explorationremix.rune.Rune r : com.explorationremix.rune.RuneRegistry.all()) ids.add(r.id().toString() + " (" + r.kind().name().toLowerCase() + ", tier " + r.tier() + ")");
        java.util.Collections.sort(ids);
        String line = ids.size() + " runes: " + String.join(", ", ids);
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return ids.size();
    }

    private static int xp(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        int amount = IntegerArgumentType.getInteger(c, "amount");
        com.explorationremix.run.RunState s = com.explorationremix.run.RunManager.state(p);
        if (amount >= 0) com.explorationremix.run.RunXp.award(p, amount, s.active ? s : null);
        else com.explorationremix.run.RunXp.spend(p, -amount, s.active ? s : null);
        int held = com.explorationremix.run.RunXp.points(p);
        c.getSource().sendSuccess(() -> Component.literal("Run XP now " + held + " points"), true);
        return 1;
    }

    private static int book(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        String att = StringArgumentType.getString(c, "attunement");
        if (!java.util.Arrays.asList(com.explorationremix.rune.AttunementBook.ALL).contains(att)) { c.getSource().sendFailure(Component.literal("Unknown attunement " + att)); return 0; }
        long run = com.explorationremix.run.RunManager.currentRun(p);
        net.minecraft.world.item.ItemStack book = com.explorationremix.item.AttunementBookItem.of(com.explorationremix.registry.ERItems.ATTUNEMENT_BOOK.get(), att, run);
        if (!p.getInventory().add(book)) p.drop(book, false);
        c.getSource().sendSuccess(() -> Component.literal("Gave a " + att + " book" + (run == 0L ? " (it will crumble: no run)" : "")), true);
        return 1;
    }

    private static int slots(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) return 0;
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (!com.explorationremix.rune.RuneTool.canHoldRunes(held)) { c.getSource().sendFailure(Component.literal("Hold a tool or weapon")); return 0; }
        int n = IntegerArgumentType.getInteger(c, "count");
        com.explorationremix.rune.RuneTool.setSlots(held, n);
        c.getSource().sendSuccess(() -> Component.literal("Slots set to " + n), true);
        return 1;
    }

    private static int cell(CommandContext<CommandSourceStack> c) {
        if (!DeepWorld.installed()) return 0;
        int x = (int) Math.floor(c.getSource().getPosition().x), z = (int) Math.floor(c.getSource().getPosition().z);
        for (Layer l : DeepWorld.layers()) {
            CellPlan plan = DeepWorld.plans().cellAt(l.index(), x, z);
            StringBuilder sb = new StringBuilder(l.id()).append(": route ").append((int) plan.routeLength()).append(" blocks; ");
            for (Chamber ch : plan.chambers) sb.append(ch.kind().name().toLowerCase()).append(String.format(" @%.0f,%.0f,%.0f ", ch.x(), ch.y(), ch.z()));
            if (plan.bedrockLanding != null) sb.append(String.format("bedrock landing @%.0f,%.0f,%.0f", plan.bedrockLanding.x(), plan.bedrockLanding.y(), plan.bedrockLanding.z()));
            String line = sb.toString();
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Renders a vertical cross-section of the final density around the player straight from the noise router. */
    private static int slice(CommandContext<CommandSourceStack> c, int width, String axis) {
        CommandSourceStack s = c.getSource();
        ServerLevel level = s.getLevel();
        RandomState rs = level.getChunkSource().randomState();
        DensityFunction density = rs.router().finalDensity();
        int cx = (int) Math.floor(s.getPosition().x), cz = (int) Math.floor(s.getPosition().z);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int height = maxY - minY;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        boolean alongX = !axis.equalsIgnoreCase("z");
        long start = System.nanoTime();
        for (int i = 0; i < width; i++) {
            int x = alongX ? cx - width / 2 + i : cx;
            int z = alongX ? cz : cz - width / 2 + i;
            for (int y = minY; y < maxY; y++) {
                double d = density.compute(new DensityFunction.SinglePointContext(x, y, z));
                int rgb;
                if (d > 0) {
                    int band = DeepWorld.installed() ? DeepWorld.bandAt(y) : 0;
                    Layer l = DeepWorld.installed() ? DeepWorld.layerAt(y) : null;
                    if (y < DeepWorld.floorY() + 5 && DeepWorld.installed()) rgb = 0x202020;
                    else if (band != 0) rgb = 0x101018;
                    else if (l != null) rgb = switch (l.index()) { case 0 -> 0x6a5a40; case 1 -> 0x4a5a70; case 2 -> 0x70402a; default -> 0x3a3a40; };
                    else rgb = 0x7a7a7a;
                } else {
                    rgb = 0x000000;
                    if (DeepWorld.installed() && y < DeepWorld.packFloor()) {
                        Layer l = DeepWorld.layerAt(y);
                        if (l != null) {
                            int table = com.explorationremix.world.gen.DeepFluids.tableLevel(l, DeepWorld.plans(), x, z);
                            if (y < table && !DeepWorld.plans().cellAt(l.index(), x, z).insideRefuge(x, y, z)) rgb = l.fluid() == Layer.FluidKind.LAVA ? 0xc04010 : 0x2050c0;
                        }
                    }
                }
                img.setRGB(i, maxY - 1 - y, rgb);
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        File dir = new File(level.getServer().getServerDirectory(), "remix_slices");
        dir.mkdirs();
        File out = new File(dir, "slice_" + cx + "_" + cz + "_" + axis + ".png");
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            ExplorationRemix.LOGGER.error("Could not write slice", e);
            s.sendFailure(Component.literal("Could not write " + out));
            return 0;
        }
        String msg = "Wrote " + out.getPath() + " (" + width + "x" + height + ", " + ms + " ms)";
        s.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
