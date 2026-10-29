package com.explorationremix.devtools;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Rootvaults;
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
 *     <li>{@code /remix info}: where you are; in a deep layer also the network's node and way there (the chamber you
 *     are in or the nearest, the nearest tunnel or walk, a climb, the rift's ledge path); above the old floor the
 *     ground and surface biome over you and the Rootvaults grotto under you</li>
 *     <li>{@code /remix tp <1-3>}: the landing at the top of a layer, under its band</li>
 *     <li>{@code /remix tp gate <1-3>}: the landing above a band, where its gate passage starts</li>
 *     <li>{@code /remix tp bedrock}: the bedrock landing of the last layer's network in this cell</li>
 *     <li>{@code /remix tp biome <name>}: a deep biome's nearest chamber in the networks of the nine cells round you
 *     (set pieces first), standing on its floor in open air; or the Silver Fern Rift, the Ivory Root Cavern or the
 *     Rootvaults</li>
 *     <li>{@code /remix view <name> [n]}: a spot in that biome with a wide view into it, facing the view (for
 *     screenshots); n picks the Rootvaults look (0 root halls, 1 teal grottos, 2 ravines) or the n-th place of a deep
 *     biome (set pieces first, then nearest first)</li>
 *     <li>{@code /remix slice [x|z] [half width]}: a picture of the blocks under you, written to remix_slices/</li>
 *     <li>{@code /remix census [radius]}: how overgrown and lit the caves around you are, per biome (PlantCensus)</li>
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
                .then(PlantCensus.command())
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
        if (y >= s.packFloor) upperInfo(c, x, z);
        if (li >= 0) {
            for (String line : t.network.describe(li, x, y, z)) say(c, "  " + line);
            Plans.Gate exit = li + 2 <= s.layerCount ? t.plans.gate(li + 2, cx, cz) : null;
            if (exit != null) say(c, String.format(Locale.ROOT, "  this layer's way down: gate %d at %.0f %.0f %.0f (%.0f blocks away)",
                    li + 2, exit.ux, exit.uFloor + 1, exit.uz, Math.hypot(exit.ux - x, exit.uz - z)));
        }
        return 1;
    }

    /** Above the old floor: what the pack has over the column (as the Rootvaults see it), and the grotto under it. */
    private static void upperInfo(CommandContext<CommandSourceStack> c, int x, int z) {
        SurfaceOracle o = DeepWorld.oracle();
        if (o != null) {
            Overhead.Surface s = o.at(x, z);
            String biome = o.biome(x, z).unwrapKey().map(k -> k.location().toString()).orElse("?");
            say(c, String.format(Locale.ROOT, "  over this column: ground y %d, %s, %s trees%s%s", s.y(), biome, s.trees().id,
                    s.wet() ? ", wet" : "", s.ravineOk() ? ", ravines allowed" : ""));
        }
        Rootvaults r = DeepWorld.roots();
        if (r == null) return;
        Rootvaults.Grotto g = r.grottoAt(x, z);
        if (g != null) say(c, String.format(Locale.ROOT, "  rootvaults: %s under %s trees, water y %.0f, roof at most y %.0f, ground %d to %d, %d skylight(s)",
                g.lookName(), g.kind.id, g.water, g.top(), g.groundMin, g.groundMax, g.skylights.length));
        else if (r.anyNear(x, z)) say(c, "  rootvaults: a tunnel or a grotto's edge runs under this column");
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
        Net n = t.network(last, t.plans.cellOf((int) Math.floor(pl.getX())), t.plans.cellOf((int) Math.floor(pl.getZ())));
        Net.Chamber b = n.ch.get(n.exit);
        int x = (int) Math.floor(b.x), z = (int) Math.floor(b.z);
        BlockPos stand = findStand((ServerLevel) pl.level(), x, (int) Math.floor(b.f0) + 1, z);
        if (stand != null) teleport(pl, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        else teleport(pl, b.x, b.f0 + 1, b.z);
        say(c, String.format(Locale.ROOT, "The bedrock landing of %s's network in this cell, at %.0f %.0f %.0f.", t.shape.id(last), b.x, b.f0 + 1, b.z));
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
        // The biome's places in the networks of the nine cells round you, set pieces first: stand on the first open
        // floor (feet and head in air, a solid block under them) the world's blocks show there.
        ServerLevel level = (ServerLevel) pl.level();
        int tried = 0;
        for (Network.Place p : t.network.places(target, px, pz)) {
            if (tried++ >= MAX_PLACES) break;
            BlockPos stand = standIn(t, level, p, target);
            if (stand == null) continue;
            teleport(pl, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            say(c, shortName(target) + " (" + p.what + ") at " + stand.getX() + " " + stand.getY() + " " + stand.getZ());
            return 1;
        }
        c.getSource().sendFailure(Component.literal("No open floor of " + name + " found in the networks of the nine cells round you"
                + (tried == 0 ? " (none of their chambers has that biome)." : " (" + tried + " places tried).")));
        return 0;
    }

    /** How many places of a deep biome the commands try before giving up (each may generate chunks). */
    private static final int MAX_PLACES = 8;

    /** The first spot of a place that is an open floor of the biome in the world: feet and head in air, rock under. */
    @javax.annotation.Nullable
    private static BlockPos standIn(Terrain t, ServerLevel level, Network.Place p, DeepBiome target) {
        for (double[] q : p.stands) {
            BlockPos stand = findStand(level, (int) Math.floor(q[0]), (int) Math.floor(q[1]), (int) Math.floor(q[2]));
            if (stand != null && t.biomeAt(stand.getX(), stand.getY(), stand.getZ()) == target) return stand;
        }
        return null;
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

    /** The nearest Rootvaults grotto: onto a bank beside its water. */
    private static int tpRootvaults(CommandContext<CommandSourceStack> c, Terrain t) throws CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        Rootvaults roots = DeepWorld.roots();
        if (roots == null) {
            c.getSource().sendFailure(Component.literal("There are no Rootvaults in this world (replaceVanillaCaveBiomes is off)."));
            return 0;
        }
        Rootvaults.Grotto g = nearestGrotto(roots, pl, -1);
        if (g == null) {
            c.getSource().sendFailure(Component.literal("No Rootvaults found within " + SEARCH_CELLS * Rootvaults.CELL + " blocks."));
            return 0;
        }
        BlockPos stand = standIn((ServerLevel) pl.level(), g);
        BlockPos at = stand != null ? stand : BlockPos.containing(g.cx, g.water + 2, g.cz);
        teleport(pl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        say(c, String.format(Locale.ROOT, "rootvaults (%s under %s trees, ground %d to %d) at %d %d %d", g.lookName(), g.kind.id, g.groundMin, g.groundMax,
                at.getX(), at.getY(), at.getZ()));
        return 1;
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
            look = n > 2 ? -1 : n;
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.ROOTVAULTS);
            Rootvaults roots = DeepWorld.roots();
            Rootvaults.Grotto g = roots != null ? nearestGrotto(roots, pl, look) : null;
            if (g == null) {
                c.getSource().sendFailure(Component.literal("No Rootvaults" + (look >= 0 ? " with look " + look : "") + " found within "
                        + SEARCH_CELLS * Rootvaults.CELL + " blocks."));
                return 0;
            }
            around = BlockPos.containing(g.cx, g.water + 2, g.cz);
            radius = (int) Math.min(40, Math.max(g.a, g.b) + 6);
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
        Rootvaults rv = DeepWorld.roots();
        ViewFinder.View v = ViewFinder.find(level, around, p -> level.getBiome(p).is(k) && (lk < 0 || (rv != null && rv.look(p.getX(), p.getZ()) == lk)),
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
        // The deep biomes: the n-th place of the biome in the networks round you (set pieces first, then nearest
        // first), centred on the first open floor of it the world shows, searched over the place's reach.
        java.util.List<Network.Place> places = t.network.places(target, pl.getX(), pl.getZ());
        ServerLevel level = (ServerLevel) pl.level();
        for (int i = Math.min(nth, Math.max(0, places.size() - 1)), tried = 0; i < places.size() && tried < MAX_PLACES; i++, tried++) {
            Network.Place p = places.get(i);
            BlockPos stand = standIn(t, level, p, target);
            if (stand != null) return new double[]{stand.getX(), stand.getY(), stand.getZ(), Math.max(12, p.reach * 0.8)};
        }
        return null;
    }

    /** How far the Rootvaults commands search, in grotto cells. */
    private static final int SEARCH_CELLS = 24;

    /** The grotto nearest the player (with the given look, or any when look is -1), or null. */
    @javax.annotation.Nullable
    private static Rootvaults.Grotto nearestGrotto(Rootvaults roots, ServerPlayer pl, int look) {
        int px = (int) Math.floor(pl.getX()), pz = (int) Math.floor(pl.getZ());
        int ci = Math.floorDiv(px, Rootvaults.CELL), cj = Math.floorDiv(pz, Rootvaults.CELL);
        Rootvaults.Grotto best = null;
        double bd = 1e18;
        // Rings of cells outwards; one more ring after the first find, since a grotto in it may still be nearer.
        int found = -1;
        for (int ring = 0; ring <= SEARCH_CELLS && (found < 0 || ring <= found + 1); ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                Rootvaults.Grotto g = roots.grotto(ci + i, cj + j);
                if (g == null || (look >= 0 && g.look != look)) continue;
                double d = Math.hypot(g.cx - px, g.cz - pz);
                if (d < bd) { bd = d; best = g; }
                if (found < 0) found = ring;
            }
        }
        return best;
    }

    /** A standing spot on a grotto's floor, above its water: on a bank part way out from its middle, or null. */
    @javax.annotation.Nullable
    private static BlockPos standIn(ServerLevel level, Rootvaults.Grotto g) {
        Rootvaults roots = DeepWorld.roots();
        int y = (int) Math.floor(g.water) + 1;
        for (double f : new double[]{0.45, 0.3, 0.6, 0.15}) {
            for (int k = 0; k < 8; k++) {
                double th = k * Math.PI / 4;
                int x = (int) Math.floor(g.cx + f * g.a * Math.cos(th)), z = (int) Math.floor(g.cz + f * g.a * Math.sin(th));
                if (roots != null && roots.grottoAt(x, z) != g) continue;
                BlockPos stand = findStand(level, x, y, z);
                if (stand != null && stand.getY() >= g.water) return stand;
            }
        }
        return null;
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
