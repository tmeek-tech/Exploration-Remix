package com.explorationremix.rune.stone;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.RuneStonePondsFeature;
import com.explorationremix.world.feature.RuneStoneSites;
import com.explorationremix.world.feature.RuneStoneStructures;
import com.explorationremix.world.feature.RuneStoneVentsFeature;
import com.explorationremix.world.terrain.GalleriesRuins;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * /remix runestones (0.23.1): the rune stones, for trying them out (operators only, under /remix).
 * <ul>
 *     <li>{@code /remix runestones}: how many were set as the world was made since the game started, by kind and place
 *     (and how many of the ponds and vents met hide one), and how many were broken and runes freed</li>
 *     <li>{@code /remix runestones near [radius]}: the stones in the loaded chunks round you, nearest first</li>
 *     <li>{@code /remix runestones tp [n]}: in front of the n-th nearest stone in the loaded chunks (96 blocks round),
 *     within reach of it and facing it</li>
 *     <li>{@code /remix runestones find <place> [n]}: the places of a kind that hide stones round the command's position
 *     (ruins, vents, ponds: nearest first, from n on; city, stronghold: the nearest one's stones), each stone where it is
 *     known (the ruins' are planned; the others' are read from the blocks once their chunk is made) or the chunk that
 *     will set it</li>
 *     <li>{@code /remix runestones goto <place> [n]}: in front of the n-th of those stones (nearest place first),
 *     making its chunks if they are not yet (a fresh vent field tile can hold the server a minute: forceload it first)</li>
 * </ul>
 */
public final class RuneStoneCommands {
    private RuneStoneCommands() {}

    static final String[] PLACES = {"ruins", "vents", "ponds", "city", "stronghold"};
    /** How far round the position the ruins, vents and ponds are looked for (blocks). */
    static final int SEARCH = 384;

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("runestones").executes(RuneStoneCommands::stats)
                .then(Commands.literal("near").executes(c -> near(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 160)).executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("tp").executes(c -> tpNear(c, 1))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> tpNear(c, IntegerArgumentType.getInteger(c, "n")))))
                .then(Commands.literal("find").then(place(false)))
                .then(Commands.literal("goto").then(place(true)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> place(boolean go) {
        return Commands.argument("place", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(PLACES, b))
                .executes(c -> find(c, StringArgumentType.getString(c, "place"), 1, go))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 64))
                        .executes(c -> find(c, StringArgumentType.getString(c, "place"), IntegerArgumentType.getInteger(c, "n"), go)));
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        StringBuilder s = new StringBuilder("Rune stones since the game started. Set as the world was made: ");
        s.append(String.format(Locale.ROOT, "Upper Caves %d (strongholds %d), ", RuneStones.placedCount(RuneStoneKind.UPPER_CAVES),
                RuneStoneStructures.STRONGHOLDS.get()));
        s.append(String.format(Locale.ROOT, "Blue Reaches %d (ponds %d, in %d ponds that hide one; ancient cities %d), ",
                RuneStones.placedCount(RuneStoneKind.BLUE_REACHES), RuneStonePondsFeature.SET.get(), RuneStonePondsFeature.PONDS.get(),
                RuneStoneStructures.CITIES.get()));
        s.append(String.format(Locale.ROOT, "Deep Gardens %d (in %d vents that hide one), ", RuneStones.placedCount(RuneStoneKind.DEEP_GARDENS),
                RuneStoneVentsFeature.VENTS.get()));
        s.append(String.format(Locale.ROOT, "Galleries %d (ruins). Broken %d, runes freed %d.", RuneStones.placedCount(RuneStoneKind.GALLERIES),
                RuneStones.BROKEN.get(), RuneStones.FREED.get()));
        String out = s.toString();
        c.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static int near(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        BlockPos from = BlockPos.containing(src.getPosition());
        List<BlockPos> found = RuneStones.near(level, from, radius, 4096);
        int[] byKind = new int[RuneStoneKind.values().length];
        for (BlockPos p : found) {
            RuneStoneKind k = kindAt(level, p);
            if (k != null) byKind[k.ordinal()]++;
        }
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%d rune stone(s) in the loaded chunks within %d blocks", found.size(), radius));
        for (RuneStoneKind k : RuneStoneKind.values()) if (byKind[k.ordinal()] > 0) s.append(", ").append(byKind[k.ordinal()]).append(' ').append(k.id);
        for (int i = 0; i < Math.min(16, found.size()); i++) s.append("\n ").append(i + 1).append(". ").append(describe(level, found.get(i), from));
        String out = s.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return found.size();
    }

    private static int tpNear(CommandContext<CommandSourceStack> c, int n) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Only a player can be stood by a stone"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        List<BlockPos> found = RuneStones.near(level, BlockPos.containing(src.getPosition()), 96, n);
        if (found.size() < n) {
            src.sendFailure(Component.literal("Only " + found.size() + " rune stone(s) in the loaded chunks within 96 blocks"));
            return 0;
        }
        return standBy(src, player, level, found.get(n - 1), "") ? 1 : 0;
    }

    /**
     * A stone of a place: where it is if that is known ({@code at}), else round {@code near} within {@code reach}, or in
     * {@code box} where that is given; the chunk that sets it, and what place it is in.
     */
    record Stone(@Nullable BlockPos at, BlockPos near, int reach, @Nullable BoundingBox box, ChunkPos chunk, String what) {}

    private static int find(CommandContext<CommandSourceStack> c, String place, int n, boolean go) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        if (go && player == null) {
            src.sendFailure(Component.literal("Only a player can be stood by a stone"));
            return 0;
        }
        Vec3 from = src.getPosition();
        switch (place) {
            case "city", "stronghold" -> {
                List<Stone> stones = structure(src, level, from, place.equals("city"));
                if (stones == null) return 0;
                done(src, level, player, place, stones, n, go);
                return 1;
            }
            case "ruins", "vents", "ponds" -> {
                Terrain t = DeepWorld.terrain();
                if (t == null || !DeepWorld.installed()) {
                    src.sendFailure(Component.literal("The deep world is not installed in this world"));
                    return 0;
                }
                MinecraftServer server = src.getServer();
                Supplier<List<Stone>> work = switch (place) {
                    case "ruins" -> () -> ruins(t, from);
                    case "vents" -> () -> vents(t, from);
                    default -> () -> ponds(t, from);
                };
                CompletableFuture.supplyAsync(work, Util.backgroundExecutor()).whenComplete((stones, err) -> server.execute(() -> {
                    if (err != null) src.sendFailure(Component.literal("Could not work out the " + place + ": " + err.getMessage()));
                    else if (player != null && (player.hasDisconnected() || player.level() != level)) return;
                    else done(src, level, player, place, stones, n, go);
                }));
                return 1;
            }
            default -> {
                src.sendFailure(Component.literal("Unknown place " + place + " (one of " + String.join(", ", PLACES) + ")"));
                return 0;
            }
        }
    }

    /** Reports the stones from the n-th on, or stands the player by the n-th. */
    private static void done(CommandSourceStack src, ServerLevel level, @Nullable ServerPlayer player, String place, List<Stone> stones, int n, boolean go) {
        if (stones.size() < n) {
            src.sendFailure(Component.literal("Only " + stones.size() + " " + place + " stone(s) found"));
            return;
        }
        BlockPos from = BlockPos.containing(src.getPosition());
        if (!go) {
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%d %s stone(s) round %s", stones.size(), place, from.toShortString()));
            int set = 0, none = 0, notYet = 0;
            for (int i = 0; i < stones.size(); i++) {
                Stone st = stones.get(i);
                BlockPos at = resolve(level, st, false);
                String state;
                if (at != null) {
                    set++;
                    state = describe(level, at, from);
                } else if (level.getChunkSource().getChunkNow(st.chunk.x, st.chunk.z) == null) {
                    notYet++;
                    state = "chunk " + st.chunk.x + " " + st.chunk.z + " not loaded";
                } else {
                    none++;
                    state = "none there (" + st.near.toShortString() + ")";
                }
                if (i >= n - 1 && i < n - 1 + 24) s.append("\n ").append(i + 1).append(". ").append(st.what).append(": ").append(state);
            }
            s.append(String.format(Locale.ROOT, "\n%d set, %d with none, %d not loaded", set, none, notYet));
            String out = s.toString();
            src.sendSuccess(() -> Component.literal(out), false);
            return;
        }
        Stone st = stones.get(n - 1);
        BlockPos at = resolve(level, st, true);
        if (at == null) {
            src.sendFailure(Component.literal(st.what + ": no rune stone there (" + st.near.toShortString() + ")"));
            return;
        }
        standBy(src, player, level, at, st.what + ", ");
    }

    /**
     * Where stone st is in the world: its block if it is a rune stone, else the nearest within its reach; null if
     * none. With {@code make}, its chunk and the ones round it are made first, else only loaded chunks are read.
     */
    @Nullable
    private static BlockPos resolve(ServerLevel level, Stone st, boolean make) {
        if (make)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(st.chunk.x + dx, st.chunk.z + dz);
        if (st.at != null) {
            if (level.getChunkSource().getChunkNow(st.at.getX() >> 4, st.at.getZ() >> 4) == null) return null;
            return level.getBlockState(st.at).getBlock() instanceof RuneStoneBlock ? st.at : null;
        }
        if (st.box != null) {
            // the piece's blocks in the chunk that sets its stone
            if (level.getChunkSource().getChunkNow(st.chunk.x, st.chunk.z) == null) return null;
            BoundingBox b = st.box;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            for (int x = Math.max(b.minX(), st.chunk.getMinBlockX()); x <= Math.min(b.maxX(), st.chunk.getMaxBlockX()); x++)
                for (int z = Math.max(b.minZ(), st.chunk.getMinBlockZ()); z <= Math.min(b.maxZ(), st.chunk.getMaxBlockZ()); z++)
                    for (int y = Math.max(b.minY(), level.getMinBuildHeight()); y <= Math.min(b.maxY(), level.getMaxBuildHeight() - 1); y++)
                        if (level.getBlockState(q.set(x, y, z)).getBlock() instanceof RuneStoneBlock) return q.immutable();
            return null;
        }
        List<BlockPos> got = RuneStones.near(level, st.near, st.reach, 1);
        if (!got.isEmpty()) return got.get(0);
        boolean there = level.getChunkSource().getChunkNow(st.near.getX() >> 4, st.near.getZ() >> 4) != null;
        return there && level.getBlockState(st.near).getBlock() instanceof RuneStoneBlock ? st.near : null;
    }

    /** The ruin clusters' planned stones round from, nearest cluster first. */
    static List<Stone> ruins(Terrain t, Vec3 from) {
        GalleriesRuins r = GalleriesRuins.of(t);
        List<GalleriesRuins.Plan> plans = new ArrayList<>();
        int ci = GalleriesRuins.cell(Mth.floor(from.x)), cj = GalleriesRuins.cell(Mth.floor(from.z));
        for (int i = ci - 1; i <= ci + 1; i++)
            for (int j = cj - 1; j <= cj + 1; j++) {
                GalleriesRuins.Plan p = r.plan(i, j);
                if (p != null) plans.add(p);
            }
        plans.sort(Comparator.comparingDouble(p -> sq(p.cx - from.x, p.gy - from.y, p.cz - from.z)));
        List<Stone> out = new ArrayList<>();
        for (GalleriesRuins.Plan p : plans)
            for (int[] q : p.runes) {
                BlockPos at = new BlockPos(q[0], q[1], q[2]);
                out.add(new Stone(at, at, 0, null, new ChunkPos(at), String.format(Locale.ROOT, "ruins at %d %d %d", p.cx, p.gy, p.cz)));
            }
        return out;
    }

    /** The vents that hide a stone round from, nearest first: their chamber, under the sill. */
    static List<Stone> vents(Terrain t, Vec3 from) {
        List<Stone> out = new ArrayList<>();
        if (t.gardens == null) return out;
        int bottom = t.shape.bottom(Terrain.GARDENS);
        List<GardensField.VentChamber> vs = new ArrayList<>(t.gardens.ventChambers(from.x - SEARCH, from.z - SEARCH, from.x + SEARCH, from.z + SEARCH));
        vs.removeIf(v -> !RuneStoneSites.ventHides(v));
        vs.sort(Comparator.comparingDouble(v -> sq(v.x() - from.x, 0, v.z() - from.z)));
        for (GardensField.VentChamber v : vs) {
            BlockPos sill = BlockPos.containing(v.x(), bottom + v.sillRow(), v.z());
            out.add(new Stone(null, sill, RuneStoneVentsFeature.REACH + 4, null, new ChunkPos(sill),
                    String.format(Locale.ROOT, "vent at %.0f %d %.0f (%s)", v.x(), sill.getY(), v.z(), v.live() ? "live" : "dead")));
        }
        return out;
    }

    /** The ponds that hide a stone round from, nearest first: their middle, at their water's top. */
    static List<Stone> ponds(Terrain t, Vec3 from) {
        List<Stone> out = new ArrayList<>();
        int x0 = Mth.floor(from.x) - SEARCH, z0 = Mth.floor(from.z) - SEARCH;
        List<Network.PondInfo> ps = new ArrayList<>();
        for (Network.PondInfo p : t.network.pondsIn(x0, z0, x0 + 2 * SEARCH, z0 + 2 * SEARCH)) if (RuneStoneSites.pondHides(t.seed, p)) ps.add(p);
        ps.sort(Comparator.comparingDouble(p -> sq(p.x() - from.x, 0, p.z() - from.z)));
        for (Network.PondInfo p : ps) {
            BlockPos mid = BlockPos.containing(p.x(), p.top(), p.z());
            out.add(new Stone(null, mid, 8, null, new ChunkPos(mid), String.format(Locale.ROOT, "pond at %.0f %d %.0f (r %.1f, depth %d%s)", p.x(), p.top(), p.z(),
                    p.r(), p.depth(), p.main() ? ", Deep Blue Rift" : "")));
        }
        return out;
    }

    /** The nearest stronghold's or ancient city's stones: each chosen piece, read where its home chunk sets it. */
    @Nullable
    private static List<Stone> structure(CommandSourceStack src, ServerLevel level, Vec3 from, boolean city) {
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResourceKey<Structure> key = city ? BuiltinStructures.ANCIENT_CITY : BuiltinStructures.STRONGHOLD;
        Holder<Structure> holder = reg.getHolder(key).orElse(null);
        if (holder == null) {
            src.sendFailure(Component.literal("No " + key.location() + " in this world's registry"));
            return null;
        }
        Pair<BlockPos, Holder<Structure>> got = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(holder), BlockPos.containing(from), 100, false);
        if (got == null) {
            src.sendFailure(Component.literal("No " + key.location() + " within 100 chunks"));
            return null;
        }
        BlockPos sp = got.getFirst();
        ChunkAccess startChunk = level.getChunk(sp.getX() >> 4, sp.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS);
        StructureStart start = startChunk.getStartForStructure(holder.value());
        if (start == null || !start.isValid()) {
            src.sendFailure(Component.literal("The " + key.location() + " at " + sp.toShortString() + " has no start"));
            return null;
        }
        RuneStoneStructures.Site site = city ? RuneStoneStructures.Site.CITY : RuneStoneStructures.Site.STRONGHOLD;
        List<StructurePiece> pieces = start.getPieces();
        List<Stone> out = new ArrayList<>();
        BoundingBox all = start.getBoundingBox();
        String where = String.format(Locale.ROOT, "%s at %d %d %d", city ? "city" : "stronghold", all.getCenter().getX(), all.getCenter().getY(),
                all.getCenter().getZ());
        for (int i : RuneStoneStructures.chosen(start, level.getSeed(), site)) {
            StructurePiece piece = pieces.get(i);
            BoundingBox b = piece.getBoundingBox();
            ChunkPos home = RuneStoneStructures.homeOf(b);
            BlockPos mid = new BlockPos(Mth.clamp(b.getCenter().getX(), home.getMinBlockX(), home.getMaxBlockX()), b.getCenter().getY(),
                    Mth.clamp(b.getCenter().getZ(), home.getMinBlockZ(), home.getMaxBlockZ()));
            out.add(new Stone(null, mid, 0, b, home, where + ", " + pieceName(piece)));
        }
        return out;
    }

    private static String pieceName(StructurePiece piece) {
        if (piece instanceof PoolElementStructurePiece pe) {
            String e = pe.getElement().toString();
            int a = e.lastIndexOf('/'), b = e.indexOf(']', Math.max(a, 0));
            return a >= 0 && b > a ? e.substring(a + 1, b) : e;
        }
        return piece.getClass().getSimpleName();
    }

    /**
     * Stands the player within reach of the stone at {@code at}, on a floor in sight of one of its faces, facing it:
     * the first spot of the rings round it, dry first; true if one was found.
     */
    static boolean standBy(CommandSourceStack src, ServerPlayer player, ServerLevel level, BlockPos at, String what) {
        Vec3 c = Vec3.atCenterOf(at);
        for (boolean wet : new boolean[]{false, true})
            for (double r : new double[]{2.5, 3.0, 2.0, 3.5, 1.5}) {
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8.0;
                    int x = Mth.floor(c.x + Math.cos(a) * r), z = Mth.floor(c.z + Math.sin(a) * r);
                    BlockPos feet = floor(level, x, at.getY() + 4, z, 9, wet);
                    if (feet == null) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    if (eye.distanceTo(c) > 4.2) continue;
                    BlockHitResult hit = level.clip(new ClipContext(eye, c, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(at)) continue;
                    double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, yaw, pitch);
                    String out = what + describe(level, at, feet) + ", from " + feet.toShortString();
                    src.sendSuccess(() -> Component.literal(out), true);
                    return true;
                }
            }
        src.sendFailure(Component.literal(what + "no floor in reach of the stone at " + at.toShortString()));
        return false;
    }

    /** The highest floor from y down {@code depth} rows in column (x, z) with room to stand over it (in water, if wet). */
    @Nullable
    private static BlockPos floor(ServerLevel level, int x, int y, int z, int depth, boolean wet) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int yy = y; yy > y - depth; yy--) {
            if (!level.getBlockState(q.set(x, yy - 1, z)).isCollisionShapeFullBlock(level, q)) continue;
            if (!room(level, q.set(x, yy, z), wet) || !room(level, q.set(x, yy + 1, z), wet)) continue;
            return new BlockPos(x, yy, z);
        }
        return null;
    }

    private static boolean room(ServerLevel level, BlockPos p, boolean wet) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && (wet || s.getFluidState().isEmpty());
    }

    @Nullable
    private static RuneStoneKind kindAt(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        for (RuneStoneKind k : RuneStoneKind.values()) if (s.is(k.block())) return k;
        return null;
    }

    private static String describe(ServerLevel level, BlockPos p, BlockPos from) {
        BlockState s = level.getBlockState(p);
        RuneStoneKind k = kindAt(level, p);
        String host = k == null ? "?" : s.getValue(k.host).getSerializedName();
        return String.format(Locale.ROOT, "%s in %s at %s, %.0f blocks off", k == null ? s.getBlock().toString() : k.id, host, p.toShortString(),
                Math.sqrt(p.distSqr(from)));
    }

    private static double sq(double x, double y, double z) { return x * x + y * y + z * z; }
}
