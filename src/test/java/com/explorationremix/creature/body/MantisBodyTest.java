package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stilt mantis's body (0.20.0) over the offline clips' scenes ({@link MantisClips}: flat ground, a walk, turns,
 * grazing, watching, the startle display and bolt, steps up and down, rubble, a ledge, dying), without drawing: its
 * planted feet stand on rock, its legs reach their feet, its knees stay arched over its hips while it lives, every
 * foot steps as it walks, and nothing of its pose goes astray (no NaN, nothing far from its body).
 */
class MantisBodyTest {
    /**
     * A knee may come level with its hip (a leg reaching down a step or off a ledge straightens), and a little under it
     * for a moment (a hind leg left down a step as the body climbs, or behind as it bolts from a stand), but never hangs
     * well under it, nor for long: the stilts stay arched. Blocks, and the share of frames.
     */
    static final double KNEE_DROP = 0.2, KNEE_DROP_MAX = 0.6, KNEE_FRAMES = 0.02;

    @Test
    void feetOnRockLegsReachKneesArched() {
        MantisRig rig = MantisRig.load(MantisSpec.GAME_SCALE);
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (MantisClips.Scene s : MantisClips.scenes()) {
            MantisBody body = new MantisBody(rig.spec);
            MantisPose pose = new MantisPose(rig);
            TerrainPatch rock = new TerrainPatch();
            MantisBody.Drive drive = new MantisBody.Drive();
            int[] steps = new int[4];
            body.setFootfalls((ft, running, speed) -> steps[ft.index]++);
            MantisPose.Inputs in = new MantisPose.Inputs();
            int[] bounds = new int[6];
            int seg = 0, segT = 0, ticks = 0, feetOff = 0, kneesLow = 0, frames = 0;
            double worstMiss = 0, moved = 0, kneeDrop = 0;
            Vector3d start = null;
            while (seg < s.script().size()) {
                if (!s.script().get(seg).tick(s.driver(), segT++)) { seg++; segT = 0; }
                s.driver().drive(drive);
                boolean dying = s.dieAt() >= 0 && ticks >= s.dieAt();
                if (dying) drive.mode = MantisBody.Mode.DEAD;
                body.bounds(drive, bounds);
                rock.build(s.world(), bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
                body.tick(rock, drive);
                ticks++;
                if (start == null) start = new Vector3d(drive.x, drive.y, drive.z);
                moved = Math.max(moved, start.distance(drive.x, drive.y, drive.z));
                if (!dying)
                    for (MantisBody.Foot ft : body.feet)
                        if (ft.planted && !rock.solid(ft.at.x, ft.at.y - 0.05, ft.at.z)) feetOff++;
                for (double pt : new double[]{0.0, 0.5}) {
                    in.age = ticks + pt;
                    in.dying = dying ? Math.min(1.0, (ticks - s.dieAt() + pt) / 20.0) : 0.0;
                    pose.compute(body, pt, in, 0, 0, 0, null, 0xF000F0);
                    assertTrue(pose.ready(), s.name());
                    frames++;
                    for (int i = 0; i < 4; i++) {
                        if (!dying) {
                            worstMiss = Math.max(worstMiss, pose.reachMiss[i]);
                            double drop = pose.hipAt[i].y - pose.kneeAt[i].y;
                            kneeDrop = Math.max(kneeDrop, drop);
                            if (drop > KNEE_DROP) kneesLow++;
                            if ((drop > KNEE_DROP || pose.reachMiss[i] > 0.05) && Boolean.getBoolean("mantis.debug"))
                                System.out.printf(Locale.ROOT, "  %s t%d+%.1f leg %d: drop %.2f miss %.3f planted %b swing %.2f speed %.3f run %b foot %.2f %.2f %.2f hip %.2f %.2f %.2f%n",
                                        s.name(), ticks, pt, i, drop, pose.reachMiss[i], body.feet[i].planted, body.feet[i].swingT(pt), body.speed(pt), body.running(),
                                        pose.footAt[i].x, pose.footAt[i].y, pose.footAt[i].z, pose.hipAt[i].x, pose.hipAt[i].y, pose.hipAt[i].z);
                        }
                        assertTrue(Double.isFinite(pose.footAt[i].x + pose.footAt[i].y + pose.footAt[i].z), s.name() + ": a foot astray");
                        assertTrue(pose.kneeAt[i].distance(pose.hipAt[i]) < rig.spec.femur + 0.01, s.name() + ": a femur stretched");
                    }
                    assertTrue(Double.isFinite(pose.headAt.x + pose.headAt.y + pose.headAt.z), s.name() + ": the head astray");
                }
            }
            System.out.printf(Locale.ROOT, "%s: %d ticks, moved %.1f, steps %d %d %d %d, worst reach miss %.3f, knees under hips %d (lowest %.2f under), feet off rock %d%n",
                    s.name(), ticks, moved, steps[0], steps[1], steps[2], steps[3], worstMiss, kneesLow, kneeDrop, feetOff);
            if (feetOff > 0) bad.add(s.name() + ": " + feetOff + " planted feet off the rock");
            if (worstMiss >= 0.08) bad.add(s.name() + ": a leg short of its foot by " + worstMiss);
            if (kneesLow > KNEE_FRAMES * frames * 4) bad.add(s.name() + ": knees under the hips " + kneesLow + " times in " + frames + " frames");
            if (kneeDrop > KNEE_DROP_MAX) bad.add(s.name() + ": a knee " + kneeDrop + " under its hip");
            if (moved > 6.0)
                for (int i = 0; i < 4; i++) if (steps[i] == 0) bad.add(s.name() + ": foot " + i + " never stepped");
        }
        assertTrue(bad.isEmpty(), String.join("; ", bad));
    }
}
