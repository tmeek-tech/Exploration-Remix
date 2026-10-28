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
 *     <li>{@code /remix tp <1-3>}: the landing at the top of a layer, under its band</li>
 *     <li>{@code /remix tp gate <1-3>}: the landing above a band, where its gate passage starts</li>
 *     <li>{@code /remix tp bedrock}: the bedrock landing at the end of the last layer's route</li>
 *     <li>{@code /remix tp biome <name>}: the nearest spot of a deep biome, the Silver Fern Rift, the Ivory Root
 *     Cavern or the Rootvaults</li>
 *     <li>{@code /remix view <name> [n]}: a spot in that biome with a wide view into it, facing the view (for
 *     screenshots); n picks the Rootvaults look (0 root halls, 1 teal grottos, 2 ravines) or another set piece</li>
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
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                        Arrays.stream(DeepBiome.values()).map(RemixCommands::shortName), java.util.stream.Stream.of("rootvaults")), b))
                                .executes(c -> tpBiome(c, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("view").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                Arrays.stream(DeepBiome.values()).map(RemixCommands::shortName), java.util.stream.Stream.of("rootvaults")), b))
                        .executes(c -> view(c, StringArgumentType.getString(c, "name"), -1))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 8))
                                .executes(c -> view(c, StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "n"))))))
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
        if (name.equalsIgnoreCase("rootvaults")) return tpRootvaults(c, t);
        DeepBiome target = null;
        for (DeepBiome b : DeepBiome.values()) if (shortName(b).equalsIgnoreCase(name)) target = b;
        if (target == null) {
            c.getSource().sendFailure(Component.literal("Unknown deep biome " + name));
            return 0;
        }
        ServerPlayer pl = c.getSource().getPlayerOrException();
        int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
        if (target.role == DeepBiome.Role.SAFE) return tpUpper(c, t, pl, target, px, pz);
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

    /** The nearest Silver Fern Rift or Ivory Root Cavern: onto a ledge of the rift, or a terrace above the lake. */
    private static int tpUpper(CommandContext<CommandSourceStack> c, Terrain t, ServerPlayer pl, DeepBiome target, int px, int pz) {
        int pcx = t.plans.cellOf(px), pcz = t.plans.cellOf(pz);
        com.explorationremix.world.terrain.Upper.Piece best = null;
        double bd = 1e18;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            com.explorationremix.world.terrain.Upper.Piece p = t.plans.upper(pcx + dx, pcz + dz);
            if (p == null || p.biome() != target) continue;
            double d = Math.hypot(p.centerX() - px, p.centerZ() - pz);
            if (d < bd) { bd = d; best = p; }
        }
        if (best == null) {
            c.getSource().sendFailure(Component.literal("No " + shortName(target) + " within three cells."));
            return 0;
        }
        ServerLevel level = (ServerLevel) pl.level();
        double[][] tries;
        if (best instanceof com.explorationremix.world.terrain.Upper.Cavern cv) {
            tries = new double[][]{{cv.cx + cv.rxz * 0.72, cv.cz}, {cv.cx - cv.rxz * 0.72, cv.cz}, {cv.cx, cv.cz + cv.rxz * 0.72}, {cv.cx, cv.cz - cv.rxz * 0.72}};
        } else {
            com.explorationremix.world.terrain.Upper.Rift r = (com.explorationremix.world.terrain.Upper.Rift) best;
            int m = r.points() / 2;
            double dx = r.pointX(m + 1) - r.pointX(m - 1), dz = r.pointZ(m + 1) - r.pointZ(m - 1), l = Math.max(1e-6, Math.hypot(dx, dz));
            double nx = -dz / l, nz = dx / l;
            tries = new double[4][];
            for (int k = 0; k < 4; k++) {
                double off = (k % 2 == 0 ? 1 : -1) * (r.streamHalf + 4 + 3 * (k / 2));
                tries[k] = new double[]{r.pointX(m) + nx * off, r.pointZ(m) + nz * off};
            }
        }
        for (double[] q : tries) {
            int x = (int) Math.floor(q[0]), z = (int) Math.floor(q[1]);
            for (int y = (int) best.waterY + 1; y < best.topY; y += 6) {
                BlockPos stand = findStand(level, x, y, z);
                if (stand == null) continue;
                teleport(pl, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                say(c, shortName(target) + " at " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
                return 1;
            }
        }
        teleport(pl, best.centerX(), best.waterY + 3, best.centerZ());
        say(c, shortName(target) + " around " + (int) best.centerX() + " " + (int) (best.waterY + 3) + " " + (int) best.centerZ());
        return 1;
    }

    /** The nearest Rootvaults: where the pack's own biomes put the deep dark, which the Rootvaults replace. */
    private static int tpRootvaults(CommandContext<CommandSourceStack> c, Terrain t) throws CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) pl.level();
        net.minecraft.world.level.biome.BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
        int qy = net.minecraft.core.QuartPos.fromBlock(t.shape.packFloor + 24);
        for (int ring = 0; ring <= 64; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                int x = px + i * 32, z = pz + j * 32;
                if (!src.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), qy, net.minecraft.core.QuartPos.fromBlock(z), sampler)
                        .is(net.minecraft.world.level.biome.Biomes.DEEP_DARK)) continue;
                for (int y = t.shape.packFloor + 8; y < t.shape.packFloor + 70; y += 4) {
                    BlockPos stand = findStand(level, x, y, z);
                    if (stand == null) continue;
                    teleport(pl, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                    say(c, "rootvaults at " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
                    return 1;
                }
            }
        }
        c.getSource().sendFailure(Component.literal("No Rootvaults found within 2000 blocks."));
        return 0;
    }

    // ============================================================================================== views

    /** Moves you to a spot with a wide view into a biome and turns you to face it. */
    private static int view(CommandContext<CommandSourceStack> c, String name, int n) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerPlayer pl = c.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) pl.level();
        BlockPos around;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> key;
        int look = -1, radius = 24;
        boolean rootvaults = name.equalsIgnoreCase("rootvaults");
        if (rootvaults) {
            look = n;
            radius = 32;
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.ROOTVAULTS);
            around = findRootvaults(level, t, pl, look);
            if (around == null) {
                c.getSource().sendFailure(Component.literal("No Rootvaults" + (look >= 0 ? " with look " + look : "") + " found within 2000 blocks."));
                return 0;
            }
        } else {
            DeepBiome target = null;
            for (DeepBiome b : DeepBiome.values()) if (shortName(b).equalsIgnoreCase(name)) target = b;
            if (target == null) {
                c.getSource().sendFailure(Component.literal("Unknown biome " + name));
                return 0;
            }
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.id(target));
            double[] piece = setPiece(t, pl, target, Math.max(0, n));
            if (piece != null) {
                around = BlockPos.containing(piece[0], piece[1], piece[2]);
                radius = (int) Math.max(12, Math.min(40, piece[3]));
            } else {
                if (tpBiome(c, name) == 0) return 0;
                around = pl.blockPosition();
            }
        }
        final int lk = look;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> k = key;
        ViewFinder.View v = ViewFinder.find(level, around, p -> level.getBiome(p).is(k) && (lk < 0 || t.palette.rootvaultsLook(p.getX(), p.getZ()) == lk),
                radius, 14);
        if (v == null) {
            BlockPos stand = findStand(level, around.getX(), around.getY(), around.getZ());
            BlockPos at = stand != null ? stand : around;
            teleport(pl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            say(c, name + ": no wide view found near " + at.toShortString());
            return 1;
        }
        level.getChunk(((int) Math.floor(v.x())) >> 4, ((int) Math.floor(v.z())) >> 4, ChunkStatus.FULL, true);
        pl.teleportTo(level, v.x(), v.y(), v.z(), v.yaw(), v.pitch());
        say(c, String.format(Locale.ROOT, "%s view at %.0f %.0f %.0f facing %.0f, pitch %.0f, %.0f blocks deep", name, v.x(), v.y(), v.z(), v.yaw(), v.pitch(), v.reach()));
        return 1;
    }

    /** The set piece a biome is built around, nearest the player first: {x, y, z, search radius}, or null. */
    private static double[] setPiece(Terrain t, ServerPlayer pl, DeepBiome target, int nth) {
        if (target == DeepBiome.SILVER_FERN_RIFT || target == DeepBiome.IVORY_ROOT_CAVERN) {
            // The nearest rift or cavern: the middle of the rift between its ledges, or the cavern's lake shore.
            int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
            int pcx = t.plans.cellOf(px), pcz = t.plans.cellOf(pz);
            com.explorationremix.world.terrain.Upper.Piece best = null;
            double bd = 1e18;
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                com.explorationremix.world.terrain.Upper.Piece p = t.plans.upper(pcx + dx, pcz + dz);
                if (p == null || p.biome() != target) continue;
                double d = Math.hypot(p.centerX() - px, p.centerZ() - pz);
                if (d < bd) { bd = d; best = p; }
            }
            if (best instanceof com.explorationremix.world.terrain.Upper.Cavern cv)
                return new double[]{cv.cx, cv.waterY + 4, cv.cz, cv.rxz};
            if (best instanceof com.explorationremix.world.terrain.Upper.Rift r)
                return new double[]{r.centerX(), (r.waterY + r.ledgeTop) * 0.5, r.centerZ(), 28};
            return null;
        }
        int li = switch (target) {
            case LANTERN_LAKE, RUST_SPRINGS -> Terrain.GARDENS;
            case DEEP_BLUE_RIFT, ASURINE_GEODES -> Terrain.BLUE;
            case BASALT_GALLERIES, OBSIDIAN_TUBES -> Terrain.BASALT;
            default -> -1;
        };
        if (li < 0) return null;
        Route r = t.plans.route(li, t.plans.cellOf((int) Math.floor(pl.getX())), t.plans.cellOf((int) Math.floor(pl.getZ())));
        java.util.List<double[]> out = new java.util.ArrayList<>();
        switch (target) {
            case LANTERN_LAKE -> { for (Route.Hall h : r.halls) if (h.lake()) out.add(new double[]{h.x(), h.lakeY() + 2, h.z(), h.rxz()}); }
            case RUST_SPRINGS -> { for (Route.Spring sp : r.springs) out.add(new double[]{sp.x(), sp.floorY() + 2, sp.z(), sp.rxz()}); }
            case ASURINE_GEODES -> { for (Route.Geode g : r.geodes) out.add(new double[]{g.x(), g.y() - g.r() * 0.4, g.z(), g.r()}); }
            case OBSIDIAN_TUBES -> { for (Route.Tube tb : r.tubes) out.add(new double[]{(tb.x1() + tb.x2()) / 2, (tb.y1() + tb.y2()) / 2 - tb.r() * 0.5, (tb.z1() + tb.z2()) / 2, tb.r() * 3}); }
            default -> {
                for (double f : new double[]{0.35, 0.55, 0.75, 0.2, 0.9}) {
                    double[] p = r.pathAt(r.walkLength * f);
                    out.add(new double[]{p[0], p[2] + 2, p[1], 24});
                }
            }
        }
        if (out.isEmpty()) return null;
        if (target != DeepBiome.DEEP_BLUE_RIFT && target != DeepBiome.BASALT_GALLERIES)
            out.sort(java.util.Comparator.comparingDouble(q -> Math.hypot(q[0] - pl.getX(), q[2] - pl.getZ())));
        return out.get(Math.min(nth, out.size() - 1));
    }

    /** A standing spot in the Rootvaults (with the given look, or any when look is -1), nearest the player first. */
    @javax.annotation.Nullable
    private static BlockPos findRootvaults(ServerLevel level, Terrain t, ServerPlayer pl, int look) {
        net.minecraft.world.level.biome.BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> rv =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.ROOTVAULTS);
        int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
        int qy = net.minecraft.core.QuartPos.fromBlock(t.shape.packFloor + 24);
        // Score every candidate in the first few rings that have any, and keep the most open one.
        BlockPos best = null;
        double bestScore = 0;
        int firstRing = -1, tried = 0;
        for (int ring = 0; ring <= 64 && tried < 24; ring++) {
            if (firstRing >= 0 && ring > firstRing + 3) break;
            for (int i = -ring; i <= ring && tried < 24; i++) for (int j = -ring; j <= ring && tried < 24; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                int x = px + i * 32, z = pz + j * 32;
                if (look >= 0 && t.palette.rootvaultsLook(x, z) != look) continue;
                if (!src.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), qy, net.minecraft.core.QuartPos.fromBlock(z), sampler)
                        .is(net.minecraft.world.level.biome.Biomes.DEEP_DARK)) continue;
                if (firstRing < 0) firstRing = ring;
                tried++;
                double[] sc = scoreRootvaults(level, t, x, z, rv, look);
                if (sc != null && sc[0] > bestScore) {
                    bestScore = sc[0];
                    best = new BlockPos((int) sc[1], (int) sc[2], (int) sc[3]);
                }
            }
        }
        return best;
    }

    /**
     * How good a chunk of Rootvaults is for a view: its open air in the Rootvaults, with tall spaces, daylight, light
     * and water counting extra. Returns {score, x, y, z} of the best place to stand in it, or null if there is none.
     */
    private static double[] scoreRootvaults(ServerLevel level, Terrain t, int x, int z,
                                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> rv, int look) {
        ChunkAccess ch = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        int bx = (x >> 4) << 4, bz = (z >> 4) << 4, top = 4;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double score = 0, bestSpot = -1;
        int sx = 0, sy = 0, sz = 0;
        for (int dx = 1; dx < 16; dx += 3) for (int dz = 1; dz < 16; dz += 3) {
            int wx = bx + dx, wz = bz + dz;
            if (look >= 0 && t.palette.rootvaultsLook(wx, wz) != look) continue;
            int run = 0;
            for (int y = t.shape.packFloor + 1; y <= top + 1; y++) {
                BlockState s = y <= top ? ch.getBlockState(p.set(wx, y, wz)) : null;
                if (s != null && s.isAir()) { run++; continue; }
                if (s != null && s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) score += 0.5;
                if (run >= 5) {
                    int floorY = y - run;
                    BlockPos stand = new BlockPos(wx, floorY, wz);
                    if (level.getBiome(stand).is(rv) && ch.getBlockState(p.set(wx, floorY - 1, wz)).isSolid()) {
                        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, stand.above(2));
                        int glow = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, stand.above());
                        double spot = Math.min(run, 30) + (sky >= 8 ? 15 : 0) + glow;
                        score += Math.min(run, 30);
                        if (spot > bestSpot) { bestSpot = spot; sx = wx; sy = floorY; sz = wz; }
                    }
                }
                run = 0;
            }
        }
        return bestSpot < 0 ? null : new double[]{score + bestSpot * 3, sx, sy, sz};
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
