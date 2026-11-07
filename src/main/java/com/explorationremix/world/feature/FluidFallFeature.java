package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A waterfall or lava fall: a short row of fluid sources set into a cave wall, high enough above the floor for a long
 * drop. The falling column is laid down at once, so a lava fall is there when you arrive instead of creeping down the
 * wall for minutes; the game takes over its flow when the chunk loads. A lava fall always ends in lava, in a pool or in
 * a one-block sump under its foot, so its lava never spreads over the floor.
 *
 * <p>The source is set into the wall the origin faces in a random direction ({@code mode} {@code wall}), into the
 * nearest wall in any of the four directions ({@code nearest_wall}: the falls down the Basalt Galleries' cliffs, whose
 * tries start anywhere in a tall hall), or into the vault right over the origin ({@code ceiling}: the falls that pour
 * from the Galleries' high vaults into the middle of a hall), where it is sealed on all four sides and above and pours
 * straight down.
 *
 * <p>A fall with {@code tries} over 1 tries again round its origin until one places: each later try moves the origin up
 * to {@code spread} columns each way (to the vault over the moved column, in {@code ceiling} mode); and its row of
 * sources follows the rough rock, each source a row up or down the vault or a block in or out of the wall from the one
 * before (with a single try every source keeps the first one's row and face, as before). A try's sources are all
 * planned before any is set. A fall with a {@code min_width} over 1 is one curtain: its row ends at the first source
 * that cannot pour, and a try whose row is shorter than min_width sets none, so the next try goes on looking and the
 * fall is never a lone thread. A fall placed at a site of a set of
 * {@code sites} ({@link FallSites}: the Galleries' falls, at spaced sites) keeps every block of its sources and its
 * column to its own site's share of the ground ({@link FallSites#clear}), so no two falls of the set come within the
 * set's keep of each other; its origin is the column the site's fall pours from, the site's own or the one under a
 * hanging mass by it ({@link FallSites#aim}), and the share stays the site's ({@link FallSites#siteOf}).
 *
 * <p>A lava fall keeps every block it sets, its sources, its column and its sump, in the chunk being decorated (fix
 * round 3): a try whose row would reach over the chunk's edge sets only the sources on this side of it, and a source
 * whose face or column lies over the edge does not pour. A block set in a neighbour that is not decorated yet would be
 * there before that neighbour's own decorations, and Terrain Slabs' slab pass, which takes any block with a fluid in it
 * for a puddle, makes a sump there into a waterlogged slab with the falling lava over it (0.8.0, 8 of 50 falls).
 * So every lava fall meets its own chunk's slabs, which that pass laid before it (at underground_structures): a top slab
 * under a step of the vault, a bottom slab on a step of the floor. A lava fall takes such a slab, dry, for the open
 * block it was laid in, as the voxels the tests decorate have it (they hold no slabs): room where its face or its column
 * passes (the fall's lava takes its place, the sump going into the floor block under it), and no seal where a source or
 * a sump must be closed in, so a slab neither stops a fall the voxels pour nor seals one they refuse. Without the rule
 * the pack run's region held 27 Galleries falls where the voxels place 40, and a slab stood in the way at 10 of the 14
 * missing; with a slab taken for rock beside a source or a sump as well (lava never runs into one) it held 44 Galleries
 * falls, 14 of them closed in by a slab, one of those pouring within two blocks of a way down's ramp, where the region's
 * voxels hold no lava, and 56 Wasteland falls, 20 closed in by a slab (36 needed none). Water falls (the upper layers')
 * are as they were.
 */
public final class FluidFallFeature extends Feature<FluidFallFeature.Config> {
    public record Config(BlockState fluid, HolderSet<Block> rock, int minDrop, int search, IntProvider width, int minWidth, Mode mode, int tries,
                         int spread, Optional<FallSites.Spec> sites) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("fluid").forGetter(Config::fluid),
                RegistryCodecs.homogeneousList(Registries.BLOCK).fieldOf("rock").forGetter(Config::rock),
                Codec.intRange(0, 256).optionalFieldOf("min_drop", 8).forGetter(Config::minDrop),
                Codec.intRange(1, 15).optionalFieldOf("search", 10).forGetter(Config::search),
                IntProvider.codec(1, 6).optionalFieldOf("width", net.minecraft.util.valueproviders.ConstantInt.of(1)).forGetter(Config::width),
                Codec.intRange(1, 6).optionalFieldOf("min_width", 1).forGetter(Config::minWidth),
                Mode.CODEC.optionalFieldOf("mode", Mode.WALL).forGetter(Config::mode),
                Codec.intRange(1, 32).optionalFieldOf("tries", 1).forGetter(Config::tries),
                Codec.intRange(0, 8).optionalFieldOf("spread", 0).forGetter(Config::spread),
                FallSites.Spec.CODEC.optionalFieldOf("sites").forGetter(Config::sites)
        ).apply(i, Config::new));
    }

    /** Where the source goes: see the class comment. */
    public enum Mode implements StringRepresentable {
        WALL("wall"), NEAREST_WALL("nearest_wall"), CEILING("ceiling");

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        private final String name;

        Mode(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }
    }

    /**
     * How far a lava fall keeps from the head of a way down into the Galleries and from its hole's throat, past the
     * guard's own keep ({@link Walkway#nearWayDown}).
     */
    public static final double HEAD_KEEP = 10;

    public FluidFallFeature(Codec<Config> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos origin = ctx.origin();
        if (!(c.fluid().getFluidState().getType() instanceof FlowingFluid fluid)) return false;
        // the site's own share of the ground (a set of sites: the origin's column is where the site's fall pours from)
        Share share = null;
        if (c.sites().isPresent()) {
            Terrain t = DeepWorld.terrain();
            if (t != null && DeepWorld.installed()) {
                int[] site = FallSites.siteOf(t, c.sites().get(), origin.getX(), origin.getZ());
                share = new Share(t, c.sites().get(), site[0], site[1]);
            }
        }
        // A lava fall's blocks all lie in the chunk being decorated (see the class comment).
        ChunkPos own = fluid.isSame(Fluids.LAVA) ? decorating(level, origin) : null;
        if (placeAt(level, c, fluid, random, origin, share, own)) return true;
        for (int k = 1; k < c.tries(); k++) {
            int dx = random.nextInt(2 * c.spread() + 1) - c.spread(), dz = random.nextInt(2 * c.spread() + 1) - c.spread();
            BlockPos o = moved(level, c, origin.offset(dx, 0, dz));
            if (o != null && placeAt(level, c, fluid, random, o, share, own)) return true;
        }
        return false;
    }

    /** The chunk being decorated: the region's centre, or the origin's chunk on a level that is no region. */
    static ChunkPos decorating(WorldGenLevel level, BlockPos origin) {
        return level instanceof WorldGenRegion r ? r.getCenter() : new ChunkPos(origin);
    }

    /** Whether p lies in chunk c. */
    static boolean inChunk(ChunkPos c, BlockPos p) {
        return (p.getX() >> 4) == c.x && (p.getZ() >> 4) == c.z;
    }

    /** A site's share of the ground, for {@link FallSites#clear}. */
    private record Share(Terrain t, FallSites.Spec spec, int x, int z) {
        boolean clear(BlockPos p) { return FallSites.clear(t, spec, x, z, p.getX(), p.getZ()); }
    }

    /**
     * A later try's origin in the moved column: there, or in {@code ceiling} mode the open block under the vault nearest
     * over or under it (within the search); null if none.
     */
    private static BlockPos moved(WorldGenLevel level, Config c, BlockPos p) {
        if (c.mode() != Mode.CEILING) return room(c, level.getBlockState(p)) ? p : null;
        BlockPos.MutableBlockPos q = p.mutable();
        if (room(c, level.getBlockState(q))) {
            for (int k = 0; k < c.search(); k++) {
                q.move(Direction.UP);
                if (!room(c, level.getBlockState(q))) return q.below();
            }
            return null;
        }
        for (int k = 0; k < c.search(); k++) {
            q.move(Direction.DOWN);
            if (room(c, level.getBlockState(q))) return q.immutable();
        }
        return null;
    }

    /** One try at origin: the fall's row of sources into its wall or vault, or false. */
    private static boolean placeAt(WorldGenLevel level, Config c, FlowingFluid fluid, RandomSource random, BlockPos origin, Share share,
                                   ChunkPos own) {
        if (!room(c, level.getBlockState(origin))) return false;
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        boolean ceiling = c.mode() == Mode.CEILING;
        BlockPos wall;
        if (ceiling) {
            // The vault over the origin; dir only lays out a wider fall's row of sources.
            wall = origin.above();
            if (!level.getBlockState(wall).is(c.rock())) return false;
        } else {
            if (c.mode() == Mode.NEAREST_WALL) {
                // The nearest wall of the four, the first found in turn from dir on a tie.
                int best = Integer.MAX_VALUE;
                Direction bestDir = null;
                for (int t = 0; t < 4; t++, dir = dir.getClockWise()) {
                    int k = wallDistance(level, c, origin, dir);
                    if (k < best) { best = k; bestDir = dir; }
                }
                if (bestDir == null) return false;
                dir = bestDir;
            }
            int k = wallDistance(level, c, origin, dir);
            if (k == Integer.MAX_VALUE) return false;
            wall = origin.relative(dir, k);
        }
        Direction side = dir.getClockWise();
        int width = c.width().sample(random);
        // Every source is planned before any is set, so a try that would pour fewer than min_width sources sets none.
        List<Pour> pours = new ArrayList<>();
        WalkWindow.Change walks = new WalkWindow.Change();
        for (int w = 0; w < width; w++) {
            BlockPos src = wall.relative(side, w);
            // a wider fall's later sources follow the rough rock, a row up or down the vault, a block in or out of the wall
            if (w > 0 && c.tries() > 1) src = face(level, c, src, ceiling ? Direction.UP : dir);
            Pour p = src == null ? null : plan(level, c, fluid, src, dir, ceiling, share, own, walks);
            if (p == null) {
                if (w == 0) return false;
                if (c.minWidth() > 1) break;   // one curtain: the row ends at a gap
                continue;
            }
            pours.add(p);
            if (p.walks() != null) walks = p.walks();
        }
        if (pours.size() < c.minWidth()) return false;
        for (Pour p : pours) p.place(level, fluid);
        return true;
    }

    /**
     * The rock face nearest p along {@code into} (UP for a vault, the wall's direction for a wall): p itself, or up to 2
     * blocks into the rock or out of it, a block of rock with open air in front of it; null if none.
     */
    private static BlockPos face(WorldGenLevel level, Config c, BlockPos p, Direction into) {
        for (int k : new int[]{0, 1, -1, 2, -2}) {
            BlockPos q = p.relative(into, k);
            if (level.getBlockState(q).is(c.rock()) && room(c, level.getBlockState(q.relative(into.getOpposite())))) return q;
        }
        return null;
    }

    /** Steps from the origin to the wall in this direction, if the first block that is not air is rock within reach. */
    private static int wallDistance(WorldGenLevel level, Config c, BlockPos origin, Direction dir) {
        BlockPos.MutableBlockPos p = origin.mutable();
        for (int k = 1; k <= c.search(); k++) {
            p.move(dir);
            BlockState s = level.getBlockState(p);
            if (room(c, s)) continue;
            return s.is(c.rock()) ? k : Integer.MAX_VALUE;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * One source of a fall, planned: its block, the open face in front of it, the drop under the face, the sump under the
     * foot (or null) and, in the rows whose walks are kept, the change to them of this source and those planned before it
     * in its row.
     */
    private record Pour(BlockPos src, BlockPos front, int drop, BlockPos sump, boolean ceiling, WalkWindow.Change walks) {
        void place(WorldGenLevel level, FlowingFluid fluid) {
            BlockState falling = fluid.getFlowing(8, true).createLegacyBlock();
            level.setBlock(src, fluid.getSource(false).createLegacyBlock(), 2);
            // Out of a wall the fluid spills one block forward before it falls; out of a vault it falls from the start.
            level.setBlock(front, ceiling ? falling : fluid.getFlowing(7, false).createLegacyBlock(), 2);
            BlockPos.MutableBlockPos q = front.mutable();
            for (int k = 0; k < drop; k++) level.setBlock(q.move(Direction.DOWN), falling, 2);
            if (sump != null) level.setBlock(sump, fluid.getSource(false).createLegacyBlock(), 2);
            Fluid f = fluid.getSource();
            level.scheduleTick(src, f, 0);
            level.scheduleTick(front, f, 0);
            level.scheduleTick(q.immutable(), f, 0);
        }
    }

    /**
     * The source at src, if it may pour: the checks below, made on the level as it stands with the row's earlier sources
     * not yet set ({@code walks}: their change to the kept walks). Null if it may not. {@code own}: for a lava fall, the
     * chunk being decorated, which the source and its face (and so the column and the sump under the face) keep to.
     */
    private static Pour plan(WorldGenLevel level, Config c, FlowingFluid fluid, BlockPos src, Direction dir, boolean ceiling, Share share,
                             ChunkPos own, WalkWindow.Change walks) {
        // The open face: in front of the wall, or under the vault.
        BlockPos front = ceiling ? src.below() : src.relative(dir.getOpposite());
        if (own != null && (!inChunk(own, src) || !inChunk(own, front))) return null;
        if (!level.getBlockState(src).is(c.rock()) || !room(c, level.getBlockState(front))) return null;
        // Its own site's share of the ground, source and column.
        if (share != null && (!share.clear(src) || !share.clear(front))) return null;
        // Sealed on every side but the open face, so the fluid only pours out of the rock.
        if (ceiling) {
            if (!sealed(level, c, src.above())) return null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = src.relative(d);
                if (!sealed(level, c, n) && !level.getFluidState(n).getType().isSame(fluid)) return null;
            }
        } else {
            if (!sealed(level, c, src.above()) || !sealed(level, c, src.below()) || !sealed(level, c, src.relative(dir))) return null;
            for (Direction d : new Direction[]{dir.getClockWise(), dir.getCounterClockWise()}) {
                BlockState s = level.getBlockState(src.relative(d));
                if (!(s.is(c.rock()) || s.getFluidState().getType().isSame(fluid))) return null;
            }
        }
        // The drop below the open face.
        BlockPos.MutableBlockPos p = front.mutable();
        int drop = 0;
        while (drop < 300 && room(c, level.getBlockState(p.move(Direction.DOWN)))) drop++;
        if (drop < c.minDrop()) return null;
        // A fall never pours onto the way down (in the Wasteland, onto a tunnel's walking channel), and a lava fall keeps
        // 3 columns or more from the openings into the Galleries. Nor does a lava fall pour in front of a way down from
        // the Wasteland, near its head or the hole's throat, where it would hide the way from the cavern.
        if (Walkway.near(front.getX(), front.getZ(), front.getY() - drop, front.getY(), 2)) return null;
        if (fluid.isSame(Fluids.LAVA) && Walkway.nearOpening(front.getX(), front.getZ(), front.getY(), 3)) return null;
        if (fluid.isSame(Fluids.LAVA) && Walkway.nearWayDown(front.getX(), front.getZ(), front.getY() - drop, front.getY(), 2, HEAD_KEEP)) return null;
        BlockState below = level.getBlockState(p);
        boolean intoPool = below.getFluidState().getType().isSame(fluid);
        if (!intoPool && !below.getFluidState().isEmpty()) return null; // never lava into water or the other way round
        // Lava would run on from the foot of its fall once the chunk loads, three blocks every way and down every step,
        // onto the walks that cross the room. So a lava fall that does not end in lava ends in a sump: the floor block
        // under its foot becomes a lava source, closed in by rock below and on all four sides (or by the sump of the
        // fall beside it). Falling lava over a lava source spreads no further, as over a pool.
        BlockPos sump = null;
        if (!intoPool && fluid.isSame(Fluids.LAVA)) {
            sump = p.immutable();
            if (!below.is(c.rock()) || !sealed(level, c, sump.below())) return null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = sump.relative(d);
                FluidState fs = level.getFluidState(n);
                if (!sealed(level, c, n) && !(fs.isSource() && fs.getType().isSame(fluid))) return null;
            }
            // In the Wasteland, whose ways no plan keeps, the sump takes the standing place at the fall's foot only where
            // the places round it stay joined without it: a fall never closes the mouth of a passage.
            if (Walkway.channelled(front.getY()) && !Detour.around(WalkWindow.with(WalkWindow.of(level), walks), front.getX(), sump.getY() + 1, front.getZ())) return null;
        }
        // Nor does a lava fall there leave a walk round it cut, one way or a pit: the place at its foot goes, and so do the
        // steps and drops into its column, so a place beside the column that walked out only through it would be left
        // with no way out (0.8.0). The walks round the fall, with the row's earlier sources, must stay as they were
        // (WalkWindow).
        WalkWindow.Change kept = null;
        if (fluid.isSame(Fluids.LAVA) && Walkway.channelled(front.getY())) {
            kept = walks.copy().add(src.getX(), src.getY(), src.getZ(), Detour.OTHER);
            for (int k = 0; k <= drop; k++) kept.add(front.getX(), front.getY() - k, front.getZ(), Detour.OTHER);
            if (sump != null) kept.add(sump.getX(), sump.getY(), sump.getZ(), Detour.OTHER);
            if (!WalkWindow.keeps(WalkWindow.of(level), kept)) return null;
        }
        return new Pour(src, front, drop, sump, ceiling, kept);
    }

    /** Rock, or a full block with no fluid in it (a laid slab is not: see the class comment). */
    private static boolean sealed(WorldGenLevel level, Config c, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.is(c.rock()) || (s.isSolidRender(level, pos) && s.getFluidState().isEmpty());
    }

    /**
     * Room for a fall's face and column: an open block (air or a glow), or for a lava fall a laid slab as well
     * ({@link #laidSlab}), which the fall's lava takes the place of (see the class comment).
     */
    private static boolean room(Config c, BlockState s) {
        return GlowBlock.open(s) || (lava(c) && laidSlab(s));
    }

    private static boolean lava(Config c) { return c.fluid().getFluidState().getType().isSame(Fluids.LAVA); }

    /**
     * A dry slab another mod's slab pass laid in the terrain ({@link LavaPocketsFeature#generated}: Terrain Slabs' top
     * slabs under the vaults' steps and bottom slabs on the floors'), in a block that was open before it.
     */
    static boolean laidSlab(BlockState s) {
        return LavaPocketsFeature.generated(s) && !LavaPocketsFeature.wet(s);
    }
}
