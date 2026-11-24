package com.explorationremix.world.feature;

import com.explorationremix.creature.ChimneyLurkerEntity;
import com.explorationremix.creature.LurkerVents;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The Deep Gardens' vent urchins (0.21.3; 0.21.0's chimney lurkers), set down as each chunk is made, so each sits in
 * its vent's mouth before anyone comes: about half the live vents hold one (Taran's card: "About half of them"; which,
 * by each vent's own key, {@link LurkerVents#held}). The chunk holding a vent's top (its axis) sets its urchin down, on
 * the seat read from the vent's blocks ({@link LurkerVents#find}): only the chunks round this one are read or written.
 *
 * <p>Runs last in the vent field's chunks (top layer), once the chunk's blocks are all in.
 */
public final class ChimneyLurkersFeature extends Feature<NoneFeatureConfiguration> {
    /** For /remix lurker vents: held vents met since the game started, and the lurkers set down at them. */
    public static final AtomicLong VENTS = new AtomicLong(), PLACED = new AtomicLong();

    public ChimneyLurkersFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || t.gardens == null) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int bottom = t.shape.bottom(Terrain.GARDENS);
        boolean any = false;
        for (GardensField.LiveVent v : t.gardens.liveVents(cp.getMinBlockX(), cp.getMinBlockZ(), cp.getMinBlockX() + 16, cp.getMinBlockZ() + 16)) {
            if (!LurkerVents.held(v)) continue;
            VENTS.incrementAndGet();
            LurkerVents.Home h = LurkerVents.find(level, v, bottom, (x, z) -> Math.abs((x >> 4) - cp.x) <= 1 && Math.abs((z >> 4) - cp.z) <= 1);
            if (h == null) continue;
            ChimneyLurkerEntity e = EREntities.CHIMNEY_LURKER.get().create(level.getLevel());
            if (e == null) break;
            e.settleAt(h);
            BlockPos at = BlockPos.containing(h.waitX(), h.waitY(), h.waitZ());
            e.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.CHUNK_GENERATION, null, null);
            level.addFreshEntityWithPassengers(e);
            PLACED.incrementAndGet();
            any = true;
        }
        return any;
    }
}
