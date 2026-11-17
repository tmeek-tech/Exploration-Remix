package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Runs a clip's scene without drawing it, reporting the first tick anything goes wrong. */
@Tag("slow")
class MossbackDebugRun {
    @Test
    void run() {
        String name = System.getProperty("mossback.only");
        Assumptions.assumeTrue(name != null && System.getProperty("mossback.clips") != null);
        MossbackRig rig = MossbackRig.load();
        for (MossbackClips.Scene s : MossbackClips.scenes()) {
            if (!List.of(name.split(",")).contains(s.name())) continue;
            MossbackBody body = new MossbackBody(rig.spec);
            MossbackPose pose = new MossbackPose(rig);
            TerrainPatch rock = new TerrainPatch();
            MossbackBody.Drive drive = new MossbackBody.Drive();
            MossbackPose.Inputs in = new MossbackPose.Inputs();
            int[] bounds = new int[6];
            int seg = 0, segT = 0, tick = 0;
            boolean told = false;
            while (seg < s.script().size()) {
                if (!s.script().get(seg).tick(s.driver(), segT++)) { seg++; segT = 0; }
                s.driver().drive(drive);
                body.bounds(drive, bounds);
                rock.build(s.world(), bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
                body.tick(rock, drive);
                tick++;
                pose.compute(body, 0.0, in, 0, 0, 0, null, 0);
                StringBuilder bad = new StringBuilder();
                for (int i = 0; i < body.joints; i++) {
                    Vector3d p = body.jointNow(i);
                    if (!Double.isFinite(p.x + p.y + p.z)) bad.append(" joint").append(i).append("=nan");
                    else if (rock.solid(p.x, p.y, p.z)) bad.append(" joint").append(i).append(" in rock ").append(fmt(p));
                }
                for (int k = 0; k < 4; k++) {
                    MossbackBody.Foot f = body.feet[k];
                    if (!Double.isFinite(f.at.x + f.at.y + f.at.z)) bad.append(" foot").append(k).append("=nan");
                    if (pose.reachMiss[k] > 0.05 || !Double.isFinite(pose.reachMiss[k]))
                        bad.append(String.format(" foot%d short %.3f (planted %s swing %s at %s)", k, pose.reachMiss[k], f.planted, f.swinging, fmt(f.at)));
                }
                if (bad.length() > 0) {
                    System.out.printf("%s tick %d box %s:%s%n", s.name(), tick, fmt(new Vector3d(drive.x, drive.y, drive.z)), bad);
                    if (!told) {
                        told = true;
                        for (int i = 0; i < body.joints; i++) System.out.printf("   j%d %s%n", i, fmt(body.jointNow(i)));
                    }
                }
            }
        }
    }

    static String fmt(Vector3d v) { return String.format("(%.2f %.2f %.2f)", v.x, v.y, v.z); }
}
