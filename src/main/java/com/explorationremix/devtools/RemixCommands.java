package com.explorationremix.devtools;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.DeadEnds;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.PlantCaves;
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
 *     ground and surface biome over you, the entrance under you and how the plant caves grow there</li>
 *     <li>{@code /remix tp <1-3>}: the landing at the top of a layer, under its band</li>
 *     <li>{@code /remix tp gate <1-3>}: the landing above a band, where its gate passage starts</li>
 *     <li>{@code /remix tp bedrock}: the bedrock landing of the last layer's network in this cell</li>
 *     <li>{@code /remix tp biome <name>}: a deep biome's nearest chamber in the networks of the nine cells round you
 *     (set pieces first), standing on its floor in open air; or the Silver Fern Rift, the Ivory Root Cavern, an
 *     entrance (rootvaults, frozen_rootvaults, sinkhole, oasis_hollow) or the Frost Fern Grotto</li>
 *     <li>{@code /remix view <name> [n]}: a spot in that biome with a wide view into it, facing the view (for
 *     screenshots); n picks the Root Vaults' look (0 root halls, 1 teal grottos, 2 ravines), an oasis' rock (0 sand,
 *     1 red) or the n-th place of a deep biome (set pieces first, then nearest first). The Upper Caves' places are
 *     searched round the command's position, so {@code execute positioned} can aim the search.</li>
 *     <li>{@code /remix slice [x|z] [half width]}: a picture of the blocks under you, written to remix_slices/</li>
 *     <li>{@code /remix census [radius]}: how overgrown and lit the caves around you are, per biome (PlantCensus)</li>
 *     <li>{@code /remix deadends [radius]}: the blind cave ends left above the old floor in the loaded chunks round you
 *     (DeadEnds), with the nearest</li>
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
                .then(Commands.literal("deadends").executes(c -> deadEnds(c, 4))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 12)).executes(c -> deadEnds(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("tp")
                        .then(Commands.argument("layer", IntegerArgumentType.integer(1, 8)).executes(c -> tpLayer(c, IntegerArgumentType.getInteger(c, "layer"))))
                        .then(Commands.literal("gate").then(Commands.argument("band", IntegerArgumentType.integer(1, 8))
                                .executes(c -> tpGate(c, IntegerArgumentType.getInteger(c, "band")))))
                        .then(Commands.literal("bedrock").executes(RemixCommands::tpBedrock))
                        .then(Commands.literal("biome").then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                        Arrays.stream(DeepBiome.values()).map(RemixCommands::shortName), java.util.stream.Stream.of(UPPER_PLACES)), b))
                                .executes(c -> tpBiome(c, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("view").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                Arrays.stream(DeepBiome.values()).map(RemixCommands::shortName), java.util.stream.Stream.of(UPPER_PLACES)), b))
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
        if (y >= s.packFloor) upperInfo(c, x, y, z);
        if (li >= 0) {
            for (String line : t.network.describe(li, x, y, z)) say(c, "  " + line);
            Plans.Gate exit = li + 2 <= s.layerCount ? t.plans.gate(li + 2, cx, cz) : null;
            if (exit != null) say(c, String.format(Locale.ROOT, "  this layer's way down: gate %d at %.0f %.0f %.0f (%.0f blocks away)",
                    li + 2, exit.ux, exit.uFloor + 1, exit.uz, Math.hypot(exit.ux - x, exit.uz - z)));
        }
        return 1;
    }

    /** Above the old floor: what the pack has over the column (as the entrances see it), the entrance under it, and how the plant caves grow here. */
    private static void upperInfo(CommandContext<CommandSourceStack> c, int x, int y, int z) {
        SurfaceOracle o = DeepWorld.oracle();
        Overhead.Surface s = null;
        if (o != null) {
            s = o.at(x, z);
            String biome = o.biome(x, z).unwrapKey().map(k -> k.location().toString()).orElse("?");
            say(c, String.format(Locale.ROOT, "  over this column: ground y %d, %s, %s trees, cover %s%s%s%s", s.y(), biome, s.trees().id,
                    s.cover().name().toLowerCase(Locale.ROOT), s.cold() ? ", cold" : "", s.wet() ? ", wet" : "", s.ravineOk() ? ", ravines allowed" : ""));
        }
        PlantCaves plants = DeepWorld.plants();
        if (plants != null && s != null) say(c, String.format(Locale.ROOT, "  plant caves here: growth %.2f (%d under the ground; above 0 grows plants)",
                plants.growth(x, y, z, s.y() - y), s.y() - y));
        Entrances e = DeepWorld.entrances();
        if (e == null) return;
        Entrances.Chamber g = e.chamberAt(x, z);
        if (g != null) say(c, String.format(Locale.ROOT, "  entrance: %s, %s, water y %.0f, ground %d to %d, %d skylight(s)",
                g.site().describe(), g.lookName(), g.water, g.groundMin, g.groundMax, g.skylights.length));
        else if (e.anyNear(x, z)) say(c, "  entrance: a tunnel or a chamber's edge runs under this column");
        Entrances.Area a = e.areaAt(x, y, z);
        if (a != null) say(c, "  entrance biome here: " + a.name().toLowerCase(Locale.ROOT));
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
        if (entrance(name) != null) return tpEntrance(c, entrance(name), -1);
        if (name.equalsIgnoreCase(FROST_GROTTO)) return tpFrostGrotto(c);
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

    /** The Upper Caves' places the commands know besides the deep biomes: the entrances and the Frost Fern Grotto. */
    private static final String[] UPPER_PLACES = {"rootvaults", "frozen_rootvaults", "sinkhole", "oasis_hollow", "frost_fern_grotto", "lush_veridium"};
    /** The view where a Root Vault's descent opens into a lush cave, whose veridium runs on into it. */
    private static final String LUSH_VERIDIUM = "lush_veridium";
    private static final String FROST_GROTTO = "frost_fern_grotto";

    /** An entrance's name (its biome's), lower case, or null for any other name. */
    @javax.annotation.Nullable
    private static String entrance(String name) {
        for (int i = 0; i < 4; i++) if (UPPER_PLACES[i].equalsIgnoreCase(name)) return UPPER_PLACES[i];
        return null;
    }

    private static net.minecraft.resources.ResourceLocation entranceBiome(String name) {
        return switch (name) {
            case "frozen_rootvaults" -> com.explorationremix.world.gen.DeepBiomes.FROZEN_ROOTVAULTS;
            case "sinkhole" -> com.explorationremix.world.gen.DeepBiomes.SINKHOLE;
            case "oasis_hollow" -> com.explorationremix.world.gen.DeepBiomes.OASIS_HOLLOW;
            default -> com.explorationremix.world.gen.DeepBiomes.ROOTVAULTS;
        };
    }

    /** The nearest entrance of a kind: onto a bank beside its water. n picks a Root Vault's look or an oasis' rock. */
    private static int tpEntrance(CommandContext<CommandSourceStack> c, String name, int n) throws CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        Entrances e = DeepWorld.entrances();
        if (e == null) {
            c.getSource().sendFailure(Component.literal("There are no entrances in this world (replaceVanillaCaveBiomes is off)."));
            return 0;
        }
        Entrances.Chamber g = nearestEntrance(e, c.getSource().getPosition(), name, n);
        if (g == null) {
            c.getSource().sendFailure(Component.literal("No " + name + " found within " + SEARCH_CELLS * Entrances.CELL + " blocks."));
            return 0;
        }
        BlockPos stand = standIn((ServerLevel) pl.level(), e, g);
        BlockPos at = stand != null ? stand : BlockPos.containing(g.cx, g.water + 2, g.cz);
        teleport(pl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        say(c, String.format(Locale.ROOT, "%s (%s, ground %d to %d) at %d %d %d", g.site().describe(), g.lookName(), g.groundMin, g.groundMax,
                at.getX(), at.getY(), at.getZ()));
        return 1;
    }

    /** The nearest Frost Fern Grotto (found by its biome, within 640 blocks): onto its floor. */
    private static int tpFrostGrotto(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        BlockPos at = findBiome((ServerLevel) pl.level(), BlockPos.containing(c.getSource().getPosition()), com.explorationremix.world.gen.DeepBiomes.FROST_FERN_GROTTO);
        if (at == null) {
            c.getSource().sendFailure(Component.literal("No Frost Fern Grotto within 640 blocks."));
            return 0;
        }
        BlockPos stand = findStand((ServerLevel) pl.level(), at.getX(), at.getY(), at.getZ());
        BlockPos to = stand != null ? stand : at;
        teleport(pl, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        say(c, "frost_fern_grotto at " + to.getX() + " " + to.getY() + " " + to.getZ());
        return 1;
    }

    /**
     * The nearest block (to 16 blocks) of a biome above the old floor, as the deep world picks it (the game's own search
     * asks only the pack's biome source), within 640 blocks and from y 0 to 120 over the old floor; or null.
     */
    @javax.annotation.Nullable
    private static BlockPos findBiome(ServerLevel level, BlockPos from, net.minecraft.resources.ResourceLocation id) {
        Terrain t = DeepWorld.terrain();
        if (t == null) return null;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> key =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, id);
        net.minecraft.world.level.biome.BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        com.explorationremix.world.gen.DeepBiomeResolver r = new com.explorationremix.world.gen.DeepBiomeResolver(src::getNoiseBiome, t);
        int x0 = from.getX(), z0 = from.getZ();
        for (int ring = 0; ring <= 40; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                int x = x0 + i * 16, z = z0 + j * 16;
                for (int y = t.shape.packFloor + 8; y <= t.shape.packFloor + 184; y += 8) {
                    var b = r.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y),
                            net.minecraft.core.QuartPos.fromBlock(z), sampler);
                    if (b.is(key)) return new BlockPos(x, y, z);
                }
            }
        }
        return null;
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
        String ent = entrance(name);
        Entrances e = DeepWorld.entrances();
        if (ent != null) {
            if (ent.equals("rootvaults") && n >= 0 && n <= 2) look = n;
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, entranceBiome(ent));
            Entrances.Chamber g = e != null ? nearestEntrance(e, c.getSource().getPosition(), ent, n) : null;
            if (g == null) {
                c.getSource().sendFailure(Component.literal("No " + ent + (n >= 0 ? " " + n : "") + " found within "
                        + SEARCH_CELLS * Entrances.CELL + " blocks."));
                return 0;
            }
            around = BlockPos.containing(g.cx, g.water + 2, g.cz);
            radius = (int) Math.min(40, Math.max(g.a, g.b) + 6);
        } else if (name.equalsIgnoreCase(LUSH_VERIDIUM)) {
            key = net.minecraft.world.level.biome.Biomes.LUSH_CAVES;
            around = lushJunction(level, e, c.getSource().getPosition());
            if (around == null) {
                c.getSource().sendFailure(Component.literal("No Root Vault descent into a lush cave within " + SEARCH_CELLS * Entrances.CELL + " blocks."));
                return 0;
            }
            radius = 20;
        } else if (name.equalsIgnoreCase(FROST_GROTTO)) {
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.FROST_FERN_GROTTO);
            around = findBiome(level, BlockPos.containing(c.getSource().getPosition()), com.explorationremix.world.gen.DeepBiomes.FROST_FERN_GROTTO);
            if (around == null) {
                c.getSource().sendFailure(Component.literal("No Frost Fern Grotto within 640 blocks."));
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
        ViewFinder.View v = ViewFinder.find(level, around, p -> level.getBiome(p).is(k) && (lk < 0 || (e != null && e.look(p.getX(), p.getZ()) == lk)),
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

    /** The end of the nearest Root Vault descent that opens into a lush cave (as the deep world picks the biome), or null. */
    @javax.annotation.Nullable
    private static BlockPos lushJunction(ServerLevel level, @javax.annotation.Nullable Entrances e, Vec3 from) {
        Terrain t = DeepWorld.terrain();
        if (e == null || t == null) return null;
        net.minecraft.world.level.biome.BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        com.explorationremix.world.gen.DeepBiomeResolver r = new com.explorationremix.world.gen.DeepBiomeResolver(src::getNoiseBiome, t);
        int ci = Math.floorDiv((int) Math.floor(from.x), Entrances.CELL), cj = Math.floorDiv((int) Math.floor(from.z), Entrances.CELL);
        BlockPos best = null;
        double bd = 1e18;
        for (int i = -SEARCH_CELLS; i <= SEARCH_CELLS; i++) for (int j = -SEARCH_CELLS; j <= SEARCH_CELLS; j++) {
            Entrances.Site s = e.site(ci + i, cj + j);
            if (s == null || s.kind != Entrances.Kind.VAULTS) continue;
            for (Entrances.Tunnel tn : e.tunnelsOf(s)) {
                if (tn.way != Entrances.Way.DESCENT) continue;
                double[] end = tn.end();
                int x = (int) Math.floor(end[0]), y = (int) Math.floor(end[1]), z = (int) Math.floor(end[2]);
                var b = r.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y), net.minecraft.core.QuartPos.fromBlock(z), sampler);
                if (!b.is(net.minecraft.world.level.biome.Biomes.LUSH_CAVES)) continue;
                double d = Math.hypot(x - from.x, z - from.z);
                if (d < bd) { bd = d; best = new BlockPos(x, y, z); }
            }
        }
        return best;
    }

    /** How far the entrance commands search, in cells of 64 blocks. */
    private static final int SEARCH_CELLS = 12;

    /**
     * The nearest entrance chamber of a kind ("rootvaults" a warm Root Vault, n picks its look; "frozen_rootvaults" a
     * cold one; "sinkhole"; "oasis_hollow", n 0 for sand and 1 for red rock), or null.
     */
    @javax.annotation.Nullable
    private static Entrances.Chamber nearestEntrance(Entrances e, Vec3 from, String name, int n) {
        int px = (int) Math.floor(from.x), pz = (int) Math.floor(from.z);
        int ci = Math.floorDiv(px, Entrances.CELL), cj = Math.floorDiv(pz, Entrances.CELL);
        Entrances.Chamber best = null;
        double bd = 1e18;
        // Rings of cells outwards; one more ring after the first find, since a chamber in it may still be nearer.
        int found = -1;
        for (int ring = 0; ring <= SEARCH_CELLS && (found < 0 || ring <= found + 1); ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                Entrances.Site s = e.site(ci + i, cj + j);
                if (s == null) continue;
                boolean kind = switch (name) {
                    case "rootvaults" -> s.kind == Entrances.Kind.VAULTS && !s.cold;
                    case "frozen_rootvaults" -> s.kind == Entrances.Kind.VAULTS && s.cold;
                    case "sinkhole" -> s.kind == Entrances.Kind.SINKHOLE;
                    default -> s.kind == Entrances.Kind.OASIS && (n < 0 || s.red == (n == 1));
                };
                if (!kind) continue;
                for (Entrances.Chamber g : s.chambers) {
                    if (name.equals("rootvaults") && n >= 0 && n <= 2 && g.look != n) continue;
                    double d = Math.hypot(g.cx - px, g.cz - pz);
                    if (d < bd) { bd = d; best = g; }
                    if (found < 0) found = ring;
                }
            }
        }
        return best;
    }

    /** A standing spot on a chamber's floor, above its water: on a bank part way out from its middle, or null. */
    @javax.annotation.Nullable
    private static BlockPos standIn(ServerLevel level, Entrances e, Entrances.Chamber g) {
        int y = (int) Math.floor(g.water) + 1;
        for (double f : new double[]{0.45, 0.3, 0.6, 0.75, 0.15}) {
            for (int k = 0; k < 8; k++) {
                double th = k * Math.PI / 4;
                int x = (int) Math.floor(g.cx + f * g.a * Math.cos(th)), z = (int) Math.floor(g.cz + f * g.a * Math.sin(th));
                if (e.chamberAt(x, z) != g) continue;
                BlockPos stand = findStand(level, x, y, z);
                if (stand != null && stand.getY() >= g.water) return stand;
            }
        }
        return null;
    }

    // ============================================================================================== dead ends

    /**
     * The blind cave ends left above the old floor in the loaded chunks within radius chunks of the command's position,
     * the nearest of them, and any lava there; and how many blind ends the chunks generated since the server started
     * had and how many were joined. Works from the console too (with forceloaded chunks).
     */
    private static int deadEnds(CommandContext<CommandSourceStack> c, int radius) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        int pcx = at.getX() >> 4, pcz = at.getZ() >> 4;
        int chunks = 0, tips = 0, lava = 0;
        int[] lavaAt = null;
        java.util.List<int[]> all = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(pcx + dx, pcz + dz);
            DeadEnds.Grid g = com.explorationremix.world.feature.JoinDeadEndsFeature.read(level, cp, t.shape.packFloor + 8,
                    net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR);
            if (g == null) continue;
            chunks++;
            for (DeadEnds.Tip tip : DeadEnds.tips(g, 8, 15, 8, 15)) {
                tips++;
                all.add(new int[]{g.blockX(tip.i()), g.blockY(tip.j()), g.blockZ(tip.k())});
            }
            ChunkAccess ch = level.getChunk(cp.x, cp.z);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = t.shape.packFloor; y < level.getMaxBuildHeight(); y++) {
                int si = ch.getSectionIndex(y);
                if (ch.getSection(si).hasOnlyAir()) { y |= 15; continue; }
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (!ch.getBlockState(p.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                    lava++;
                    if (lavaAt == null) lavaAt = new int[]{p.getX(), y, p.getZ()};
                }
            }
        }
        long seen = com.explorationremix.world.feature.JoinDeadEndsFeature.CHUNKS.get(), found = com.explorationremix.world.feature.JoinDeadEndsFeature.FOUND.get(),
                joined = com.explorationremix.world.feature.JoinDeadEndsFeature.JOINED.get();
        say(c, String.format(Locale.ROOT, "Generated since the server started: %d chunks, %d blind cave ends found, %d joined to another cave", seen, found, joined));
        if (chunks == 0) {
            c.getSource().sendFailure(Component.literal("No loaded chunks with their neighbours round " + at.toShortString() + "."));
            return 0;
        }
        int px = at.getX(), py = at.getY(), pz = at.getZ();
        all.sort(java.util.Comparator.comparingDouble(a -> Math.hypot(Math.hypot(a[0] - px, a[1] - py), a[2] - pz)));
        say(c, String.format(Locale.ROOT, "%d blind cave ends left in %d chunks (%.2f a chunk) above y %d; lava blocks above the old floor: %d%s", tips, chunks,
                tips / (double) chunks, t.shape.packFloor + 8, lava, lavaAt == null ? "" : " (one at " + lavaAt[0] + " " + lavaAt[1] + " " + lavaAt[2] + ")"));
        for (int i = 0; i < Math.min(5, all.size()); i++) say(c, "  at " + all.get(i)[0] + " " + all.get(i)[1] + " " + all.get(i)[2]);
        return tips;
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
