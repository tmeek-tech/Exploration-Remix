package com.explorationremix.world.feature;

import com.explorationremix.creature.BlueSpawnRules;
import com.explorationremix.creature.FireflyHomes;
import com.explorationremix.creature.FireflySwarm;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Blue Reaches' firefly swarms, set down as each chunk is made (0.19.2), so they are there before anyone comes and
 * stay when everyone leaves, as the Skeins do ({@link SkeinsFeature}): where the rules of {@link FireflyHomes} make a
 * spot of open cave air a swarm's home (more often by water and glowbells), a swarm of 30 to 60 fireflies (now and then
 * a huge one) rests on the best patch of rock beside it, pulsing, until a player comes near enough to wake it. The
 * Deep Blue Rift's great chasm keeps only some of the swarms its homes would have (0.20.8, {@link FireflyHomes#CHASM_KEEP}).
 *
 * <p>Runs last in every chunk, after the Skeins (band 1 is the Deep Blue Rift under every column, which carries it
 * everywhere, as it does the den lairs), once the chunk's plants, vines and ways are in. Each swarm is kept in the new
 * chunk and comes into the world with it.
 */
public final class FirefliesFeature extends Feature<NoneFeatureConfiguration> {
    /** For /remix fireflies homes: chunks looked at since the game started, and the swarms set down in them (by water or glowbells). */
    public static final AtomicLong CHUNKS = new AtomicLong(), PLACED = new AtomicLong(), DRAWN = new AtomicLong();
    /**
     * For /remix fireflies homes, how the tries went: those whose roll could make a home in their place, those of them
     * with room for a swarm, and the homes that found no rock to rest on in reach (the try is given up).
     */
    public static final AtomicLong LOOKED = new AtomicLong(), ROOMY = new AtomicLong(), NO_PERCH = new AtomicLong();
    /** For /remix fireflies homes: the homes in the rift's great chasm that keep no swarm (0.20.8, {@link FireflyHomes#CHASM_KEEP}). */
    public static final AtomicLong GIVEN_UP = new AtomicLong();

    public FirefliesFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        CHUNKS.incrementAndGet();
        int top = t.shape.top(Terrain.BLUE), bottom = t.shape.bottom(Terrain.BLUE);
        if (top - bottom < 8) return false;
        RandomSource r = RandomSource.create(FireflyHomes.chunkSeed(t.seed, cp.x, cp.z));
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        int span = 16 - 2 * FireflyHomes.EDGE;
        List<BlockPos> homes = new ArrayList<>(), rests = new ArrayList<>();
        double most = FireflyHomes.maxChance();
        for (int i = 0; i < FireflyHomes.TRIES && homes.size() < FireflyHomes.MAX_PER_CHUNK; i++) {
            // the same draws for every try, whatever comes of it (what a home needs more is drawn from the try's own
            // seed), so where one try's swarm went never moves the next
            int x = minX + FireflyHomes.EDGE + r.nextInt(span);
            int z = minZ + FireflyHomes.EDGE + r.nextInt(span);
            int y = bottom + 2 + r.nextInt(top - bottom - 3);
            double roll = r.nextDouble();
            long own = r.nextLong();
            if (roll >= most) continue;
            BlockPos at = new BlockPos(x, y, z);
            BlueSpawnRules.Place place = BlueSpawnRules.place(level.getBiome(at).unwrapKey().map(k -> k.location().toString()).orElse(""));
            if (roll >= FireflyHomes.chance(place, true)) continue;         // no home here even by water
            LOOKED.incrementAndGet();
            // open air round it (no floor near wanted, unlike a swarm the spawner brings out: it rests on rock in reach
            // or is no home), so a tall hall's walls are homes all the way up
            if (!FireflySwarm.openRound(level, at)) continue;
            ROOMY.incrementAndGet();
            // a roll under the bare chance is a home wherever it is; between the two, only by water or glowbells
            boolean drawn = false;
            if (roll >= FireflyHomes.chance(place, false) && !(drawn = drawn(level, at))) continue;
            boolean apart = true;
            for (BlockPos h : homes) if (!FireflyHomes.apart(x, y, z, h.getX(), h.getY(), h.getZ())) { apart = false; break; }
            if (!apart) continue;
            RandomSource mine = RandomSource.create(own);
            Perch p = perch(level, at, mine, minX, minZ, rests);
            if (p == null) { NO_PERCH.incrementAndGet(); continue; }
            int landTicks = FireflySwarm.LAND_MIN + mine.nextInt(FireflySwarm.LAND_MAX - FireflySwarm.LAND_MIN + 1);
            int count = BlueSpawnRules.swarmSize(mine.nextDouble()), seed = mine.nextInt();
            homes.add(at);
            rests.add(p.anchor);
            // the rift's great chasm keeps only some of its homes' swarms (0.20.8, FireflyHomes#CHASM_KEEP); a home it
            // gives up still keeps the chunk's other homes apart, so every swarm outside the chasm is where it was
            boolean byWater = drawn || drawn(level, at);
            if (place == BlueSpawnRules.Place.RIFT && t.inRiftChasm(x, y, z) && !FireflyHomes.keptInChasm(seed, byWater)) {
                GIVEN_UP.incrementAndGet();
                continue;
            }
            FireflySwarm s = EREntities.FIREFLY_SWARM.get().create(level.getLevel());
            if (s == null) return true;
            s.setDownAtGeneration(Vec3.atCenterOf(at), p.anchor, p.rock, count, seed, landTicks);
            level.addFreshEntity(s);
            PLACED.incrementAndGet();
            if (byWater) DRAWN.incrementAndGet();
        }
        return !homes.isEmpty();
    }

    /** Whether water or a glowbell lies within {@link FireflyHomes#DRAW_REACH} of {@code at} (and its reach down and up). */
    public static boolean drawn(BlockGetter level, BlockPos at) {
        Block glowbell = ERBlocks.GLOWBELL.get();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int reach = FireflyHomes.DRAW_REACH;
        for (int dy = -FireflyHomes.DRAW_DOWN; dy <= FireflyHomes.DRAW_UP; dy++)
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx * dx + dz * dz > reach * reach) continue;
                    q.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    BlockState s = level.getBlockState(q);
                    if (s.is(glowbell) || s.getFluidState().is(FluidTags.WATER)) return true;
                }
        return false;
    }

    private record Perch(BlockPos anchor, Direction rock) {}

    /** Whether {@code p} lies within {@link FireflyHomes#PERCH_APART} of where another of the chunk's swarms rests. */
    private static boolean taken(BlockPos p, List<BlockPos> rests) {
        double d = FireflyHomes.PERCH_APART * FireflyHomes.PERCH_APART;
        for (BlockPos q : rests) if (p.distSqr(q) < d) return true;
        return false;
    }

    /**
     * The patch of rock a home's swarm rests on, as a swarm picks one ({@link FireflySwarm#patch}): of the open blocks
     * within {@link FireflyHomes#PERCH_REACH} of the home (inside the chunk, in the home's own biome, and
     * {@link FireflyHomes#PERCH_APART} from where the chunk's other swarms rest) with a face of rock beside them, the
     * one showing the most faces round it, nearer ones first, in sight of the home; its rock whichever face it has.
     * Keeping to the home's biome (0.19.8) stops a rift swarm by the Deep Dark resting over the edge in it.
     */
    @Nullable
    private static Perch perch(WorldGenLevel level, BlockPos at, RandomSource r, int minX, int minZ, List<BlockPos> rests) {
        int reach = FireflyHomes.PERCH_REACH;
        Vec3 c = Vec3.atCenterOf(at);
        Optional<ResourceKey<Biome>> home = level.getBiome(at).unwrapKey();
        Perch best = null;
        double bestScore = 2.5;
        List<Direction> rock = new ArrayList<>(6);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-reach, -reach, -reach), at.offset(reach, reach, reach))) {
            if (p.getX() < minX || p.getX() > minX + 15 || p.getZ() < minZ || p.getZ() > minZ + 15) continue;
            if (!FireflySwarm.open(level, p) || taken(p, rests)) continue;
            rock.clear();
            for (Direction d : Direction.values()) if (FireflySwarm.sturdy(level, p, d)) rock.add(d);
            if (rock.isEmpty()) continue;
            double score = FireflySwarm.patch(level, p) - 0.35 * Math.sqrt(p.distToCenterSqr(c));
            if (score <= bestScore) continue;
            Direction d = rock.get(r.nextInt(rock.size()));
            if (home.isPresent() && !level.getBiome(p).is(home.get())) continue;
            Vec3 land = FireflySwarm.landingPoint(p, d);
            if (level.clip(new ClipContext(c, land, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null)).getType() != HitResult.Type.MISS) continue;
            best = new Perch(p.immutable(), d);
            bestScore = score;
        }
        return best;
    }
}
