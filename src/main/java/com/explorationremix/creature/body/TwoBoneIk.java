package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * A two-bone reach (an upper leg and a lower leg) from a hip or shoulder to a foot: where the knee or elbow goes. It
 * lies in the plane through the root, the target and the pole (the way the joint should bend: out and up for a
 * sprawling lizard leg). A target too far is reached for in a straight line as far as the leg goes; one too near
 * folds the leg as far as it folds.
 */
public final class TwoBoneIk {
    private TwoBoneIk() {}

    /**
     * Puts the joint into {@code joint} and the end of the lower bone into {@code end} (the target, or as near to it as
     * the leg reaches). Returns how far the target lay beyond the leg's reach (0 when reached).
     */
    public static double solve(Vector3d root, Vector3d target, Vector3d pole, double upper, double lower, Vector3d joint, Vector3d end) {
        double tx = target.x - root.x, ty = target.y - root.y, tz = target.z - root.z;
        double d = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double max = upper + lower - 1.0E-4, min = Math.abs(upper - lower) + 1.0E-4;
        double ux, uy, uz;
        if (d < 1.0E-6) {
            // the target sits on the root: reach along the pole
            double pl = pole.length();
            ux = pole.x / pl; uy = pole.y / pl; uz = pole.z / pl;
            d = 0.0;
        } else {
            ux = tx / d; uy = ty / d; uz = tz / d;
        }
        double over = Math.max(0.0, d - max);
        double reach = Math.max(min, Math.min(max, d));
        // the bend: the pole's part across the line to the target
        double pd = pole.x * ux + pole.y * uy + pole.z * uz;
        double bx = pole.x - ux * pd, by = pole.y - uy * pd, bz = pole.z - uz * pd;
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl < 1.0E-6) {
            // pole along the line: bend any way across it
            if (Math.abs(uy) < 0.9) { bx = -uz; by = 0; bz = ux; } else { bx = 1; by = 0; bz = 0; }
            double l2 = Math.sqrt(bx * bx + by * by + bz * bz);
            bx /= l2; by /= l2; bz /= l2;
        } else {
            bx /= bl; by /= bl; bz /= bl;
        }
        double a = (upper * upper - lower * lower + reach * reach) / (2.0 * reach);
        double h = Math.sqrt(Math.max(0.0, upper * upper - a * a));
        joint.set(root.x + ux * a + bx * h, root.y + uy * a + by * h, root.z + uz * a + bz * h);
        double ex = root.x + ux * reach - joint.x, ey = root.y + uy * reach - joint.y, ez = root.z + uz * reach - joint.z;
        double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (el < 1.0E-9) el = 1.0;
        end.set(joint.x + ex / el * lower, joint.y + ey / el * lower, joint.z + ez / el * lower);
        return over;
    }
}
