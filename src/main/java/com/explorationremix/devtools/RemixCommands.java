package com.explorationremix.devtools;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.JoinDeadEndsFeature;
import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.DeadEnds;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.PlantCaves;
import com.explorationremix.world.terrain.Rock;
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
import net.minecraft.world.level.block.Blocks;
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
 *     entrance (rootvaults, tropical_rootvaults, frozen_rootvaults, sinkhole, oasis_hollow) or the Frost Fern Grotto</li>
 *     <li>{@code /remix view <name> [n]}: a spot in that biome with a wide view into it, facing the view (for
 *     screenshots); n picks the Root Vaults' look (0 root halls, 1 teal grottos, 2 ravines; rootvaults outside the
 *     tropics, tropical_rootvaults under jungles and mangrove swamps), the frozen Root Vaults' snowy zone (0) or
 *     thawed inner halls (1), an oasis' rock (0 sand,
 *     1 red) or the n-th place of a deep biome (set pieces first, then nearest first). The Upper Caves' places are
 *     searched round the command's position, so {@code execute positioned} can aim the search.</li>
 *     <li>{@code /remix slice [x|z] [half width]}: a picture of the blocks under you, written to remix_slices/</li>
 *     <li>{@code /remix census [radius]}: how overgrown and lit the caves around you are, per biome (PlantCensus)</li>
 *     <li>{@code /remix entrances [cells]}: the entrance sites in the cells round you, why the other cells have none, and
 *     the nearest of each kind</li>
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
                .then(Commands.literal("entrances").executes(c -> entrances(c, 8))
                        .then(Commands.argument("cells", IntegerArgumentType.integer(1, 24)).executes(c -> entrances(c, IntegerArgumentType.getInteger(c, "cells")))))
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
            say(c, String.format(Locale.ROOT, "  over this column: ground y %d (lowest %d), %s, %s trees, cover %s%s%s%s", s.y(), o.low(x, z), biome,
                    s.trees().id, s.cover().name().toLowerCase(Locale.ROOT), s.cold() ? ", cold" : "", s.wet() ? ", wet" : "",
                    s.ravineOk() ? ", ravines allowed" : ""));
        }
        PlantCaves plants = DeepWorld.plants();
        if (plants != null && s != null) {
            int cy = (y & ~3) + 2, low = o.low(x, z);
            say(c, String.format(Locale.ROOT, "  plant caves here: %s (growth %.2f, %d under the ground, the cell's top %d under the lowest ground; "
                    + "plants need growth above 0 and %d or more)", plants.grows(x, cy, z, s.y(), low) ? "yes" : "no",
                    plants.growth(x, cy, z, s.y() - cy), s.y() - cy, low - (cy + 1), PlantCaves.MIN_DEPTH));
        }
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
    private static final String[] UPPER_PLACES = {"rootvaults", "tropical_rootvaults", "frozen_rootvaults", "sinkhole", "oasis_hollow",
            "frost_fern_grotto", "lush_veridium", "rootvaults_mouth"};
    /** How many of {@link #UPPER_PLACES}, from the first, are entrances. */
    private static final int ENTRANCES = 5;
    /** The view where a tropical Root Vault's descent opens into a lush cave, whose veridium runs on into it. */
    private static final String LUSH_VERIDIUM = "lush_veridium";
    private static final String FROST_GROTTO = "frost_fern_grotto";
    /** How many of the Frost Fern Grotto's nearest caves its view tries. */
    private static final int GROTTO_TRIES = 6;

    /** An entrance's name (its biome's), lower case, or null for any other name. */
    @javax.annotation.Nullable
    private static String entrance(String name) {
        for (int i = 0; i < ENTRANCES; i++) if (UPPER_PLACES[i].equalsIgnoreCase(name)) return UPPER_PLACES[i];
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
        java.util.List<BlockPos> found = findBiomes(level, from, id, 1, 0);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Up to max blocks of a biome as {@link #findBiome} finds them, nearest first, each at least apart blocks from the others. */
    private static java.util.List<BlockPos> findBiomes(ServerLevel level, BlockPos from, net.minecraft.resources.ResourceLocation id, int max, int apart) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        Terrain t = DeepWorld.terrain();
        if (t == null) return found;
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
                    if (!b.is(key)) continue;
                    BlockPos at = new BlockPos(x, y, z);
                    if (found.stream().noneMatch(f -> f.closerThan(at, apart))) found.add(at);
                    if (found.size() >= max) return found;
                    break;
                }
            }
        }
        return found;
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
        int look = -1, radius = 24, chill = -1;
        java.util.List<BlockPos> tries = null;
        if (name.equalsIgnoreCase("rootvaults_mouth")) return viewMouth(c, pl, level, n == 1);
        String ent = entrance(name);
        Entrances e = DeepWorld.entrances();
        if (ent != null) {
            if ((ent.equals("rootvaults") || ent.equals("tropical_rootvaults")) && n >= 0 && n <= 2) look = n;
            // A frozen cluster is snowed in round its openings and thaws further in: n 0 views the snowy zone, n 1 the
            // thawed halls deep inside.
            if (ent.equals("frozen_rootvaults") && (n == 0 || n == 1)) chill = n;
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
                c.getSource().sendFailure(Component.literal("No tropical Root Vault descent into a lush cave within " + SEARCH_CELLS * Entrances.CELL
                        + " blocks."));
                return 0;
            }
            radius = 20;
        } else if (name.equalsIgnoreCase(FROST_GROTTO)) {
            key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, com.explorationremix.world.gen.DeepBiomes.FROST_FERN_GROTTO);
            // The grotto's nearest caves, well apart: the widest view among them wins (the nearest is often a narrow tunnel).
            tries = findBiomes(level, BlockPos.containing(c.getSource().getPosition()), com.explorationremix.world.gen.DeepBiomes.FROST_FERN_GROTTO,
                    GROTTO_TRIES, 40);
            if (tries.isEmpty()) {
                c.getSource().sendFailure(Component.literal("No Frost Fern Grotto within 640 blocks."));
                return 0;
            }
            around = tries.get(0);
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
        final int lk = look, ch = chill;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> k = key;
        java.util.function.Predicate<BlockPos> in = p -> level.getBiome(p).is(k) && (lk < 0 || (e != null && e.look(p.getX(), p.getZ()) == lk))
                && (ch < 0 || e != null && thawed(e, p) == (ch == 1));
        ViewFinder.View v = null;
        String extra = "";
        if (name.equalsIgnoreCase(LUSH_VERIDIUM)) {
            // Where a descent meets a lush cave: stand in the lush cave in sight of the descent's end and face it, where
            // the veridium runs on.
            v = faceJunction(level, around, in);
            extra = String.format(Locale.ROOT, ", descent's end %d %d %d, %d veridium blocks within 16 of it", around.getX(), around.getY(), around.getZ(),
                    countBlocks(level, around, 16, DeepMaterials.state(Rock.VERIDIUM).getBlock()));
        }
        if (v == null) for (BlockPos at : tries != null ? tries : java.util.List.of(around)) {
            ViewFinder.View w = ViewFinder.find(level, at, in, radius, 14);
            if (w != null && (v == null || w.score() > v.score())) v = w;
        }
        if (v == null) {
            BlockPos stand = findStand(level, around.getX(), around.getY(), around.getZ());
            BlockPos at = stand != null ? stand : around;
            teleport(pl, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            say(c, name + ": no wide view found near " + at.toShortString());
            return 1;
        }
        level.getChunk(((int) Math.floor(v.x())) >> 4, ((int) Math.floor(v.z())) >> 4, ChunkStatus.FULL, true);
        pl.teleportTo(level, v.x(), v.y(), v.z(), v.yaw(), v.pitch());
        say(c, String.format(Locale.ROOT, "%s view at %.0f %.0f %.0f facing %.0f, pitch %.0f, %.0f blocks deep%s", name, v.x(), v.y(), v.z(), v.yaw(), v.pitch(), v.reach(), extra));
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

    /**
     * The nearest Root Vault mouth (a cold cluster's with n 1), seen from its own floor 10 to 18 blocks in from where it
     * comes out, looking up it at the daylight.
     */
    private static int viewMouth(CommandContext<CommandSourceStack> c, ServerPlayer pl, ServerLevel level, boolean cold) {
        Entrances e = DeepWorld.entrances();
        if (e == null) return 0;
        Vec3 from = c.getSource().getPosition();
        int ci = Math.floorDiv((int) Math.floor(from.x), Entrances.CELL), cj = Math.floorDiv((int) Math.floor(from.z), Entrances.CELL);
        Entrances.Tunnel best = null;
        double bd = 1e18;
        for (int i = -SEARCH_CELLS; i <= SEARCH_CELLS; i++) for (int j = -SEARCH_CELLS; j <= SEARCH_CELLS; j++) {
            Entrances.Site s = e.site(ci + i, cj + j);
            if (s == null || s.kind != Entrances.Kind.VAULTS || s.cold != cold) continue;
            for (Entrances.Tunnel t : e.tunnelsOf(s)) {
                if (t.way != Entrances.Way.MOUTH) continue;
                double[] end = t.end();
                double d = Math.hypot(end[0] - from.x, end[2] - from.z);
                if (d < bd) { bd = d; best = t; }
            }
        }
        if (best == null) {
            c.getSource().sendFailure(Component.literal("No " + (cold ? "frozen " : "") + "Root Vault mouth within " + SEARCH_CELLS * Entrances.CELL + " blocks."));
            return 0;
        }
        double[] end = best.end(), prev = best.point(Math.max(0, best.points() - 2));
        double dx = end[0] - prev[0], dz = end[2] - prev[2], l = Math.max(1e-6, Math.hypot(dx, dz));
        double ux = dx / l, uz = dz / l;
        // Stand in the tunnel 10 to 18 blocks in from where it comes out, on its floor, looking up it at the daylight
        // (from outside, a forest's leaves hide the opening).
        double cx = end[0], cy = end[1], cz = end[2];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean found = false;
        for (int back = 10; back <= 18 && !found; back++) {
            int gx = (int) Math.floor(end[0] - ux * back), gz = (int) Math.floor(end[2] - uz * back);
            level.getChunk(gx >> 4, gz >> 4, ChunkStatus.FULL, true);
            for (int y = (int) Math.floor(end[1]) + 2; y > end[1] - 20 && !found; y--) {
                if (e.carve(gx, y, gz) >= 0) continue;
                BlockState under = level.getBlockState(p.set(gx, y - 1, gz));
                if (!under.blocksMotion() || level.getBlockState(p.set(gx, y, gz)).blocksMotion()
                        || level.getBlockState(p.set(gx, y + 1, gz)).blocksMotion()) continue;
                cx = gx + 0.5;
                cy = y;
                cz = gz + 0.5;
                found = true;
            }
        }
        double tx = end[0] - cx, ty = end[1] - (cy + 1.62), tz = end[2] - cz;
        if (!found) {
            // No floor in the tunnel's last blocks: stand where it comes out, looking down into it.
            tx = -ux;
            ty = -0.4;
            tz = -uz;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-tx, tz)), pitch = (float) Math.toDegrees(Math.atan2(-ty, Math.hypot(tx, tz)));
        pl.teleportTo(level, cx, cy, cz, yaw, pitch);
        say(c, String.format(Locale.ROOT, "%sRoot Vault mouth at %.0f %.0f %.0f, seen from %.0f %.0f %.0f", cold ? "frozen " : "", end[0], end[1], end[2], cx, cy, cz));
        return 1;
    }

    /**
     * A spot 6 to 16 blocks from a point, standing in the biome in plain sight of it, facing it; the spot about 11 blocks
     * away with the most room across the frame wins (rock right beside the camera fills half the picture). Null if there
     * is none.
     */
    @javax.annotation.Nullable
    private static ViewFinder.View faceJunction(ServerLevel level, BlockPos j, java.util.function.Predicate<BlockPos> in) {
        ViewFinder.View best = null;
        double tx = j.getX() + 0.5, ty = j.getY() + 0.5, tz = j.getZ() + 0.5;
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) for (int dy = -10; dy <= 10; dy++) {
            feet.set(j.getX() + dx, j.getY() + dy, j.getZ() + dz);
            double ex = feet.getX() + 0.5, ey = feet.getY() + 1.62, ez = feet.getZ() + 0.5;
            double d = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
            if (d < 6 || d > 16) continue;
            double near = -0.5 * Math.abs(d - 11);
            if (best != null && near + ROOM <= best.score()) continue;
            if (!ViewFinder.standable(level, feet) || !in.test(feet) || !inSight(level, ex, ey, ez, tx, ty, tz)) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-(tx - ex), tz - ez));
            float pitch = (float) Math.toDegrees(Math.atan2(-(ty - ey), Math.hypot(tx - ex, tz - ez)));
            double room = ROOM;
            for (float off : new float[]{-35F, -17F, 17F, 35F}) room = Math.min(room, reach(level, ex, ey, ez, yaw + off, pitch, ROOM));
            double score = near + room;
            if (best == null || score > best.score()) best = new ViewFinder.View(ex, feet.getY(), ez, yaw, pitch, d, score);
        }
        return best;
    }

    /** How much room a junction view wants across its frame, in blocks. */
    private static final double ROOM = 8;

    /** How far one can see from a point in a direction before a block hides what is behind it, up to max blocks. */
    private static double reach(ServerLevel level, double x, double y, double z, float yaw, float pitch, double max) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double s = 0.5; s < max; s += 0.25) {
            if (level.getBlockState(p.set(Math.floor(x + dx * s), Math.floor(y + dy * s), Math.floor(z + dz * s))).canOcclude()) return s;
        }
        return max;
    }

    /** Whether no block that hides what is behind it stands between two points (water and plants do not). */
    private static boolean inSight(ServerLevel level, double ax, double ay, double az, double bx, double by, double bz) {
        double d = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double s = 0.5; s < d - 1; s += 0.25) {
            double f = s / d;
            p.set(Math.floor(ax + (bx - ax) * f), Math.floor(ay + (by - ay) * f), Math.floor(az + (bz - az) * f));
            if (level.getBlockState(p).canOcclude()) return false;
        }
        return true;
    }

    /** How many blocks of a kind lie within r blocks of a point. */
    private static int countBlocks(ServerLevel level, BlockPos o, int r, net.minecraft.world.level.block.Block block) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dy * dy + dz * dz > r * r) continue;
            if (level.getBlockState(p.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz)).is(block)) n++;
        }
        return n;
    }

    /**
     * The end of the nearest tropical Root Vault descent (under a jungle or a mangrove swamp: the only ones whose veridium
     * runs on) that opens into a lush cave (as the deep world picks the biome), or null.
     */
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
            if (s == null || s.kind != Entrances.Kind.VAULTS || !s.tropical()) continue;
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

    /** Deep inside a frozen Root Vault cluster, where it has thawed (true), or in the snowy zone round its openings (false). */
    private static boolean thawed(Entrances e, BlockPos p) {
        return e.chill(p.getX(), p.getY(), p.getZ()) >= Entrances.THAW_REACH;
    }

    /** How far the entrance commands search, in cells of 64 blocks. */
    private static final int SEARCH_CELLS = 12;

    /**
     * The nearest entrance chamber of a kind ("rootvaults" a warm Root Vault outside the tropics, n picks its look;
     * "tropical_rootvaults" one under a jungle or a mangrove swamp, likewise; "frozen_rootvaults" a cold one, n 0 in the
     * snowy zone round its openings and 1 thawed deep inside; "sinkhole"; "oasis_hollow", n 0 for sand and 1 for red
     * rock), or null.
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
                    case "rootvaults" -> s.kind == Entrances.Kind.VAULTS && !s.cold && !s.tropical();
                    case "tropical_rootvaults" -> s.kind == Entrances.Kind.VAULTS && !s.cold && s.tropical();
                    case "frozen_rootvaults" -> s.kind == Entrances.Kind.VAULTS && s.cold;
                    case "sinkhole" -> s.kind == Entrances.Kind.SINKHOLE;
                    default -> s.kind == Entrances.Kind.OASIS && (n < 0 || s.red == (n == 1));
                };
                if (!kind) continue;
                for (Entrances.Chamber g : s.chambers) {
                    if ((name.equals("rootvaults") || name.equals("tropical_rootvaults")) && n >= 0 && n <= 2 && g.look != n) continue;
                    if (name.equals("frozen_rootvaults") && (n == 0 || n == 1)
                            && (e.chill(s, g.cx, g.water + 2, g.cz) >= Entrances.THAW_REACH) != (n == 1)) continue;
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

    // ============================================================================================== entrances

    /**
     * The entrance sites in the cells within {@code cells} of the command's position: how many cells of each cover got
     * one and why the others did not, and the nearest sites of each kind with their positions. Works from the console.
     */
    private static int entrances(CommandContext<CommandSourceStack> c, int cells) {
        Entrances e = DeepWorld.entrances();
        if (e == null) {
            c.getSource().sendFailure(Component.literal("There are no entrances in this world (replaceVanillaCaveBiomes is off)."));
            return 0;
        }
        Vec3 from = c.getSource().getPosition();
        int ci = Math.floorDiv((int) Math.floor(from.x), Entrances.CELL), cj = Math.floorDiv((int) Math.floor(from.z), Entrances.CELL);
        java.util.Map<String, java.util.Map<String, Integer>> why = new java.util.TreeMap<>();
        java.util.Map<String, java.util.List<Entrances.Site>> kinds = new java.util.TreeMap<>();
        for (int i = -cells; i <= cells; i++) for (int j = -cells; j <= cells; j++) {
            String[] w = e.why(ci + i, cj + j);
            why.computeIfAbsent(w[0], k -> new java.util.TreeMap<>()).merge(w[1], 1, Integer::sum);
            Entrances.Site s = e.site(ci + i, cj + j);
            if (s == null) continue;
            String k = s.kind.name().toLowerCase(Locale.ROOT) + (s.cold ? " (cold)" : "");
            kinds.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(s);
        }
        int n = (2 * cells + 1) * (2 * cells + 1);
        say(c, String.format(Locale.ROOT, "%d cells of %d blocks round %.0f %.0f, by the cover at their centres:", n, Entrances.CELL, from.x, from.z));
        for (var w : why.entrySet()) say(c, "  " + w.getKey() + ": " + w.getValue());
        for (var k : kinds.entrySet()) {
            java.util.List<Entrances.Site> l = k.getValue();
            l.sort(java.util.Comparator.comparingDouble(s -> Math.hypot(s.cx - from.x, s.cz - from.z)));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(4, l.size()); i++) {
                Entrances.Site s = l.get(i);
                Entrances.Chamber g = s.chambers.get(0);
                b.append(String.format(Locale.ROOT, "%s%.0f %.0f %.0f", i == 0 ? "" : ", ", s.cx, g.water, s.cz));
            }
            say(c, String.format(Locale.ROOT, "%s: %d, nearest at %s", k.getKey(), l.size(), b));
        }
        return kinds.values().stream().mapToInt(java.util.List::size).sum();
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
        int chunks = 0, tips = 0, shallow = 0, rockTips = 0, lava = 0, caveLava = 0, lavaLo = Integer.MAX_VALUE, lavaHi = Integer.MIN_VALUE;
        java.util.Map<String, Integer> round = new java.util.HashMap<>(), kinds = new java.util.HashMap<>();
        int[] lavaAt = null;
        java.util.List<int[]> all = new java.util.ArrayList<>();
        var ground = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES; // trees are not caves
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(pcx + dx, pcz + dz);
            DeadEnds.Grid g = JoinDeadEndsFeature.read(level, cp, t.shape.packFloor + 8, ground);
            if (g == null) continue;
            chunks++;
            for (DeadEnds.Tip tip : DeadEnds.tips(g, 8, 15, 8, 15)) {
                int x = g.blockX(tip.i()), y = g.blockY(tip.j()), z = g.blockZ(tip.k());
                tips++;
                if (y > level.getHeight(ground, x, z) - 12) shallow++;
                all.add(new int[]{x, y, z});
                kinds.merge(roundTip(level, x, y, z, round), 1, Integer::sum);
            }
            DeadEnds.Grid rock = JoinDeadEndsFeature.read(level, cp, t.shape.packFloor + 8, ground, true);
            if (rock != null) rockTips += DeadEnds.tips(rock, 8, 15, 8, 15).size();
            ChunkAccess ch = level.getChunk(cp.x, cp.z);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = t.shape.packFloor; y < level.getMaxBuildHeight(); y++) {
                int si = ch.getSectionIndex(y);
                if (ch.getSection(si).hasOnlyAir()) { y |= 15; continue; }
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (!ch.getBlockState(p.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) continue;
                    lava++;
                    if (y >= ch.getHeight(ground, x, z) - 6) continue; // on the surface: lava lakes stay
                    caveLava++;
                    lavaLo = Math.min(lavaLo, y);
                    lavaHi = Math.max(lavaHi, y);
                    if (lavaAt == null) lavaAt = new int[]{p.getX(), y, p.getZ()};
                }
            }
        }
        say(c, joinedSoFar());
        if (chunks == 0) {
            c.getSource().sendFailure(Component.literal("No loaded chunks with their neighbours round " + at.toShortString() + "."));
            return 0;
        }
        int px = at.getX(), py = at.getY(), pz = at.getZ();
        all.sort(java.util.Comparator.comparingDouble(a -> Math.hypot(Math.hypot(a[0] - px, a[1] - py), a[2] - pz)));
        say(c, String.format(Locale.ROOT, "%d blind cave ends left in %d chunks (%.2f a chunk) above y %d, %d of them within 12 blocks of the ground", tips, chunks,
                tips / (double) chunks, t.shape.packFloor + 8, shallow));
        say(c, String.format(Locale.ROOT, "Lava blocks above the old floor: %d, %d of them in caves%s", lava, caveLava,
                lavaAt == null ? "" : String.format(Locale.ROOT, " (y %d to %d; one at %d %d %d)", lavaLo, lavaHi, lavaAt[0], lavaAt[1], lavaAt[2])));
        say(c, String.format(Locale.ROOT, "Surface-biome ore placements let into the plant caves' and entrances' rock since the world loaded: %d",
                com.explorationremix.world.gen.DeepBiomes.SURFACE_ORES.get()));
        say(c, String.format(Locale.ROOT, "Counting only natural rock and soil as rock: %d blind ends. Round the ones left: %s; blocks there: %s", rockTips,
                top(kinds, 6), top(round, 8)));
        for (int i = 0; i < Math.min(5, all.size()); i++) say(c, "  at " + all.get(i)[0] + " " + all.get(i)[1] + " " + all.get(i)[2]);
        return tips;
    }

    /** What lies within 3 blocks of a blind end: counts its blocks into {@code round} and names what made the end. */
    private static String roundTip(ServerLevel level, int x, int y, int z, java.util.Map<String, Integer> round) {
        String kind = "bare rock";
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
            BlockState s = level.getBlockState(p.set(x + dx, y + dy, z + dz));
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;
            String id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock()).getPath();
            round.merge(id, 1, Integer::sum);
            String k = s.is(net.minecraft.tags.BlockTags.PLANKS) || s.is(net.minecraft.tags.BlockTags.RAILS) || s.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
                    || s.is(Blocks.COBWEB) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.SPAWNER) ? "structure"
                    : s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.DRIPSTONE_BLOCK) ? "dripstone"
                    : s.is(net.minecraft.tags.BlockTags.ICE) || s.is(net.minecraft.tags.BlockTags.SNOW) ? "ice or snow"
                    : s.is(Blocks.AMETHYST_BLOCK) || s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.CALCITE) || s.is(Blocks.SMOOTH_BASALT) ? "geode"
                    : s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(Blocks.AZALEA) || s.is(Blocks.FLOWERING_AZALEA)
                    || s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.BIG_DRIPLEAF) || s.is(Blocks.BIG_DRIPLEAF_STEM) ? "plants or roots" : null;
            if (k != null && (kind.equals("bare rock") || k.equals("structure"))) kind = k;
        }
        return kind;
    }

    /** The biggest counts, as "name n, name n, ...". */
    private static String top(java.util.Map<String, Integer> m, int n) {
        return m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n)
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    /** What the dead-end joiner did in the chunks generated since the server started. */
    private static String joinedSoFar() {
        long seen = JoinDeadEndsFeature.CHUNKS.get(), nanos = JoinDeadEndsFeature.NANOS.get();
        return String.format(Locale.ROOT, "Generated since the server started: %d chunks, %d blind cave ends found, %d joined to another cave (%.1f ms a chunk); "
                        + "no cave in reach %d; tunnels turned down: water %d, ground %d, other blocks %d, chunk edge %d",
                seen, JoinDeadEndsFeature.FOUND.get(), JoinDeadEndsFeature.JOINED.get(), seen == 0 ? 0 : nanos / 1e6 / seen, JoinDeadEndsFeature.NO_CAVE.get(),
                JoinDeadEndsFeature.NO_WET.get(), JoinDeadEndsFeature.NO_GROUND.get(), JoinDeadEndsFeature.NO_BLOCKED.get(), JoinDeadEndsFeature.NO_EDGE.get());
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
