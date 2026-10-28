package com.explorationremix.creature;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * How loud a noise is when it reaches a point: sound travels through cave air and dies in stone. Every solid block
 * on the straight line costs five points of loudness, every block of distance a little over half a point.
 */
public final class SoundReach {
    private SoundReach() {}

    public static double effective(Level level, Vec3 from, Vec3 to, double loudness) {
        double dist = from.distanceTo(to);
        if (dist > loudness * 1.6 + 4) return 0;
        int steps = (int) Math.min(64, Math.ceil(dist));
        int solid = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            pos.set(Mth.floor(from.x + (to.x - from.x) * t), Mth.floor(from.y + (to.y - from.y) * t), Mth.floor(from.z + (to.z - from.z) * t));
            if (!level.hasChunkAt(pos)) return 0;
            BlockState s = level.getBlockState(pos);
            if (!s.getCollisionShape(level, pos).isEmpty()) solid++;
            if (solid * 5 > loudness) return 0;
        }
        return Math.max(0, loudness - dist * 0.6 - solid * 5);
    }
}
