package com.explorationremix.world.feature;

import com.explorationremix.creature.EmberSpots;
import com.explorationremix.creature.EmberUrchinEntity;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The Wasteland's ember urchins (0.23.7), set down as each chunk is made, so they are walking its tunnels before anyone
 * comes: about one to every hundred blocks of tunnel (the cards' "About one every hundred blocks or so of tunnel"), in
 * every sheet of them, in its caves too, never in the Ash Hollows.
 *
 * <p>Each quarter of the chunk has a column picked at random, and every floor down it in the Wasteland with room for an
 * urchin over it ({@link EmberSpots#floors}) gets one at a chance of {@link #SHARE} over the way's width there: a
 * tunnel w blocks wide crosses about w of a quarter's 64 columns for each block of it, so this comes to one to each
 * {@link #PER} blocks along it, however wide (a cave's floor gets one to each {@link #PER} blocks of its narrower span).
 * Only a floor it could walk on from is taken ({@link EmberSpots#wayThrough}, {@link EmberSpots#roams}), and in the
 * ways it does walk about half are (those by a wall, or in a pinch, are not), so the chance is over {@link #KEPT} too.
 *
 * <p>Runs last in the Wasteland's chunks (top layer), once the chunk's blocks are all in; only the chunks round this
 * one are read.
 */
public final class EmberUrchinsFeature extends Feature<NoneFeatureConfiguration> {
    /** Blocks of tunnel to an urchin. */
    public static final double PER = 100.0;
    /** A quarter's columns over that: a floor found takes an urchin at SHARE / width. */
    static final double SHARE = 64.0 / PER;
    /**
     * The share of the floors along the ways it walks that it is set down on (measured over the Wasteland of two seeds,
     * EmberWastelandTest: near a floor it is set down on, about half of the floors pass its spot tests).
     */
    public static final double KEPT = 0.5;
    /** For /remix ember counts: floors looked at since the game started, and the urchins set down on them. */
    public static final AtomicLong FLOORS = new AtomicLong(), PLACED = new AtomicLong();

    public EmberUrchinsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.wasteland == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int bottom = t.shape.bottom(Terrain.WASTELAND), top = t.shape.top(Terrain.WASTELAND);
        EmberSpots.RegionGround g = new EmberSpots.RegionGround(level, cp);
        RandomSource r = ctx.random();
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            int x = cp.getMinBlockX() + (q & 1) * 8 + r.nextInt(8), z = cp.getMinBlockZ() + (q >> 1) * 8 + r.nextInt(8);
            for (EmberSpots.Spot s : EmberSpots.floors(g, x, z, top - 1, bottom + 1)) {
                FLOORS.incrementAndGet();
                double w = EmberSpots.width(g, s.x(), s.y(), s.z());
                if (r.nextDouble() * w * KEPT >= SHARE || !EmberSpots.wayThrough(g, s) || !EmberSpots.roams(g, s)) continue;
                EmberUrchinEntity e = EREntities.EMBER_URCHIN.get().create(level.getLevel());
                if (e == null) return any;
                e.setDown(s.x(), s.y(), s.z(), r.nextDouble() * Math.PI * 2.0);
                e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(s.x(), s.y(), s.z())), MobSpawnType.CHUNK_GENERATION, null, null);
                level.addFreshEntityWithPassengers(e);
                PLACED.incrementAndGet();
                any = true;
            }
        }
        return any;
    }
}
