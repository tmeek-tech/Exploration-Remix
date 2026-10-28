package com.explorationremix.devtools;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * /remix: commands for looking around the deep world (operators only).
 * <ul>
 *     <li>{@code /remix layers}: the layer table</li>
 *     <li>{@code /remix info}: where you are</li>
 *     <li>{@code /remix tp <1-4>}: the landing at the top of a layer, under its band</li>
 *     <li>{@code /remix tp gate <1-4>}: the landing above a band, where its gate passage starts</li>
 *     <li>{@code /remix tp bedrock}: the bedrock landing at the end of the last layer's route</li>
 *     <li>{@code /remix tp biome <name>}: the nearest spot of a deep biome in this cell</li>
 *     <li>{@code /remix slice [x|z] [half width]}: a picture of the blocks under you, written to remix_slices/</li>
 * </ul>
 */
public final class RemixCommands {
    private RemixCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("remix").requires(s -> s.hasPermission(2))
                .then(Commands.literal("layers").executes(RemixCommands::layers))
                .then(Commands.literal("info").executes(RemixCommands::info))
                .then(Commands.literal("tp")
                        .then(Commands.argument("layer", IntegerArgumentType.integer(1, 8)).executes(c -> tpLayer(c, IntegerArgumentType.getInteger(c, "layer"))))
                        .then(Commands.literal("gate").then(Commands.argument("band", IntegerArgumentType.integer(1, 8))
                                .executes(c -> tpGate(c, IntegerArgumentType.getInteger(c, "band")))))
                        .then(Commands.literal("bedrock").executes(RemixCommands::tpBedrock))
                        .then(Commands.literal("biome").then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(DeepBiome.values()).map(RemixCommands::shortName), b))
                                .executes(c -> tpBiome(c, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("slice")
                        .executes(c -> slice(c, "x", 160))
                        .then(Commands.argument("axis", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"x", "z"}, b))
                                .executes(c -> slice(c, StringArgumentType.getString(c, "axis"), 160))
                                .then(Commands.argument("halfWidth", IntegerArgumentType.integer(16, 1024))
                                        .executes(c -> slice(c, StringArgumentType.getString(c, "axis"), IntegerArgumentType.getInteger(c, "halfWidth")))))));
    }

    static String shortName(DeepBiome b) {
        String id = b.id;
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    private static Terrain terrain(CommandContext<CommandSourceStack> c) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) {
            c.getSource().sendFailure(Component.literal("The deep world is not installed in this world (see the server log)."));
            return null;
        }
        return t;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int layers(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        Shape s = t.shape;
        say(c, "Deep world: floor y " + s.floorY + ", pack floor y " + s.packFloor + ", bands " + s.bandThickness + " blocks thick");
        for (int i = 0; i < s.layerCount; i++) {
            say(c, String.format(Locale.ROOT, "  %d. %s: y %d to %d, %s at y %d; band above it y %d to %d", i + 1, s.id(i), s.top(i), s.bottom(i),
                    s.lava(i) ? "lava" : "water", s.waterY(i) - 1, s.bandTop(i + 1), s.bandBottom(i + 1)));
        }
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        Vec3 p = c.getSource().getPosition();
        int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
        Shape s = t.shape;
        int li = s.layerAt(y), band = s.bandAt(y);
        String where = y >= s.packFloor ? "above the first band (the pack's own caves)"
                : li >= 0 ? "layer " + (li + 1) + " (" + s.id(li) + ")" : band > 0 ? "inside band " + band : "below the floor";
        int cx = t.plans.cellOf(x), cz = t.plans.cellOf(z);
        say(c, "At " + x + " " + y + " " + z + ": " + where + ", cell " + cx + " " + cz);
        DeepBiome b = t.biomeAt(x, y, z);
        if (b != null) say(c, "  deep biome: " + shortName(b) + " (" + b.role.name().toLowerCase(Locale.ROOT) + ")");
        if (li >= 0) {
            double[] path = t.routePathAt(li, x, z);
            if (path != null) say(c, String.format(Locale.ROOT, "  route: %.0f blocks from the walking line, path floor y %.0f", path[0], path[1]));
            Plans.Gate exit = li + 2 <= s.layerCount ? t.plans.gate(li + 2, cx, cz) : null;
            if (exit != null) say(c, String.format(Locale.ROOT, "  this layer's way down: gate %d at %.0f %.0f %.0f (%.0f blocks away)",
                    li + 2, exit.ux, exit.uFloor + 1, exit.uz, Math.hypot(exit.ux - x, exit.uz - z)));
        }
        return 1;
    }

    private static int tpLayer(CommandContext<CommandSourceStack> c, int layer) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        if (layer > t.shape.layerCount) {
            c.getSource().sendFailure(Component.literal("There are only " + t.shape.layerCount + " layers."));
            return 0;
        }
        ServerPlayer pl = c.getSource().getPlayerOrException();
        Plans.Gate g = gateHere(t, pl, layer);
        teleport(pl, g.lx, g.lFloor + 1, g.lz);
        say(c, "Layer " + layer + " (" + t.shape.id(layer - 1) + "): the landing under band " + layer + ".");
        return 1;
    }

    private static int tpGate(CommandContext<CommandSourceStack> c, int band) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        if (band > t.shape.layerCount) {
            c.getSource().sendFailure(Component.literal("There are only " + t.shape.layerCount + " bands."));
            return 0;
        }
        ServerPlayer pl = c.getSource().getPlayerOrException();
        Plans.Gate g = gateHere(t, pl, band);
        teleport(pl, g.ux, g.uFloor + 1, g.uz);
        say(c, "Gate " + band + ": the landing above band " + band + ". The passage starts in its floor.");
        return 1;
    }

    private static int tpBedrock(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerPlayer pl = c.getSource().getPlayerOrException();
        int last = t.shape.layerCount - 1;
        Route r = t.plans.route(last, t.plans.cellOf((int) pl.getX()), t.plans.cellOf((int) pl.getZ()));
        teleport(pl, r.endX, r.endFloor + 1, r.endZ);
        say(c, "The bedrock landing at the end of " + t.shape.id(last) + ".");
        return 1;
    }

    private static int tpBiome(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        DeepBiome target = null;
        for (DeepBiome b : DeepBiome.values()) if (shortName(b).equalsIgnoreCase(name)) target = b;
        if (target == null) {
            c.getSource().sendFailure(Component.literal("Unknown deep biome " + name));
            return 0;
        }
        ServerPlayer pl = c.getSource().getPlayerOrException();
        int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
        Shape s = t.shape;
        // Search outwards in rings for an open spot of that biome with rock under it.
        for (int ring = 0; ring <= 40; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                int x = px + i * 12, z = pz + j * 12;
                for (int li = 0; li < s.layerCount; li++) {
                    for (int y = s.top(li) - 4; y > s.bottom(li) + 4; y -= 3) {
                        if (t.biomeAt(x, y, z) != target) continue;
                        if (t.rawDensity(x, y, z) > -1 || t.rawDensity(x, y + 1, z) > -1) continue;
                        int fy = y;
                        while (fy > s.bottom(li) && t.rawDensity(x, fy - 1, z) < 0) fy--;
                        if (t.fluidAt(x, fy, z) != Terrain.AIR) continue;
                        BlockPos stand = findStand((ServerLevel) pl.level(), x, fy, z);
                        if (stand == null) continue;
                        teleport(pl, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                        say(c, shortName(target) + " at " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
                        return 1;
                    }
                }
            }
        }
        c.getSource().sendFailure(Component.literal("No " + name + " found within 480 blocks."));
        return 0;
    }

    /** A real block position with air for feet and head and something solid underneath, near (x, y, z). */
    private static BlockPos findStand(ServerLevel level, int x, int y, int z) {
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 12; dy++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int yy = y + dy * sgn;
                BlockState feet = chunk.getBlockState(p.set(x, yy, z)), head = chunk.getBlockState(p.set(x, yy + 1, z)), under = chunk.getBlockState(p.set(x, yy - 1, z));
                if (feet.isAir() && head.isAir() && under.isSolid()) return new BlockPos(x, yy, z);
            }
        }
        return null;
    }

    private static Plans.Gate gateHere(Terrain t, ServerPlayer pl, int band) {
        return t.plans.gate(band, t.plans.cellOf((int) Math.floor(pl.getX())), t.plans.cellOf((int) Math.floor(pl.getZ())));
    }

    private static void teleport(ServerPlayer pl, double x, double y, double z) {
        ServerLevel level = pl.server.getLevel(Level.OVERWORLD);
        if (level == null) return;
        level.getChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4, ChunkStatus.FULL, true);
        pl.teleportTo(level, x, y, z, pl.getYRot(), pl.getXRot());
    }

    private static int slice(CommandContext<CommandSourceStack> c, String axis, int half) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        if (!axis.equals("x") && !axis.equals("z")) {
            c.getSource().sendFailure(Component.literal("Axis must be x or z"));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        Vec3 p = c.getSource().getPosition();
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        File file = new File(level.getServer().getServerDirectory(), "remix_slices/slice_" + axis + "_" + x + "_" + z + ".png");
        try {
            BlockImages.write(BlockImages.slice(level, axis, x, z, half, t.shape.floorY, Math.min(level.getMaxBuildHeight() - 1, 128)), file, 1);
        } catch (IOException e) {
            c.getSource().sendFailure(Component.literal("Could not write " + file + ": " + e.getMessage()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("Wrote " + file.getPath()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
