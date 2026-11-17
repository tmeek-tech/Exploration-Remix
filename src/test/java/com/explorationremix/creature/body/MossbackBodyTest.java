package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mossback's body over the clips' scenes (a floor, a turn, steps, a wall, a ceiling, a ledge, rubble), run without
 * drawing: what must always hold, whatever the rock.
 */
class MossbackBodyTest {
    private static MossbackRig rig;
    private static final List<Run> RUNS = new ArrayList<>();

    /** One scene's run: what was seen, tick by tick. */
    static final class Run {
        final String name;
        int ticks, nan, inRock, slid, overBent, footTicks, shortFeet;
        double worstLength;
        final int[] steps = new int[4], landings = new int[4];
        final List<String> notes = new ArrayList<>();

        Run(String name) { this.name = name; }

        void note(String s) { if (notes.size() < 6) notes.add(s); }
    }

    @BeforeAll
    static void runScenes() {
        rig = MossbackRig.load();
        MossbackSpec spec = rig.spec;
        for (MossbackClips.Scene s : MossbackClips.scenes()) {
            Run run = new Run(s.name());
            RUNS.add(run);
            MossbackBody body = new MossbackBody(spec);
            MossbackPose pose = new MossbackPose(rig);
            TerrainPatch rock = new TerrainPatch();
            MossbackBody.Drive drive = new MossbackBody.Drive();
            MossbackPose.Inputs in = new MossbackPose.Inputs();
            body.setFootfalls((foot, trotting, speed) -> run.landings[index(body, foot)]++);
            int[] bounds = new int[6];
            boolean[] wasPlanted = new boolean[4];
            Vector3d[] was = {new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()};
            boolean[] wasSwinging = new boolean[4];
            int seg = 0, segT = 0;
            while (seg < s.script().size()) {
                if (!s.script().get(seg).tick(s.driver(), segT++)) { seg++; segT = 0; }
                s.driver().drive(drive);
                body.bounds(drive, bounds);
                rock.build(s.world(), bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
                body.tick(rock, drive);
                run.ticks++;
                pose.compute(body, 0.5, in, 0, 0, 0, null, 0);
                check(run, body, pose, rock, spec);
                for (int k = 0; k < 4; k++) {
                    MossbackBody.Foot f = body.feet[k];
                    // a foot that stays down stays where it is
                    if (wasPlanted[k] && f.planted && !f.swinging() && f.at.distance(was[k]) > 1.0E-9) {
                        run.slid++;
                        run.note("foot " + k + " slid " + f.at.distance(was[k]) + " at tick " + run.ticks);
                    }
                    if (f.swinging && !wasSwinging[k]) run.steps[k]++;
                    wasPlanted[k] = f.planted;
                    wasSwinging[k] = f.swinging;
                    was[k].set(f.at);
                }
            }
        }
    }

    private static int index(MossbackBody body, MossbackBody.Foot foot) {
        for (int k = 0; k < 4; k++) if (body.feet[k] == foot) return k;
        throw new IllegalStateException();
    }

    private static void check(Run run, MossbackBody body, MossbackPose pose, TerrainPatch rock, MossbackSpec spec) {
        for (int i = 0; i < body.joints; i++) {
            Vector3d p = body.jointNow(i);
            if (!Double.isFinite(p.x + p.y + p.z)) { run.nan++; run.note("joint " + i + " is not a number at tick " + run.ticks); continue; }
            if (rock.solid(p.x, p.y, p.z)) { run.inRock++; run.note("joint " + i + " in rock at tick " + run.ticks); }
            if (i > 0) {
                double off = Math.abs(p.distance(body.jointNow(i - 1)) - spec.length(i - 1, i));
                run.worstLength = Math.max(run.worstLength, off);
            }
        }
        // the trunk never bends past its limits
        double[] limit = {0, MossbackBody.BEND_NECK, MossbackBody.BEND_SHOULDERS, MossbackBody.BEND_MIDDLE, MossbackBody.BEND_HIPS};
        for (int i = 1; i <= MossbackSpec.HIPS; i++) {
            Vector3d a = body.jointNow(i - 1), b = body.jointNow(i), c = body.jointNow(i + 1);
            double bend = new Vector3d(b).sub(a).angle(new Vector3d(c).sub(b));
            if (bend > limit[i] + 1.0E-3) { run.overBent++; run.note(String.format("joint %d bent %.1f deg at tick %d", i, Math.toDegrees(bend), run.ticks)); }
        }
        for (int k = 0; k < 4; k++) {
            MossbackBody.Foot f = body.feet[k];
            if (!Double.isFinite(f.at.x + f.at.y + f.at.z) || !Double.isFinite(pose.reachMiss[k])) { run.nan++; run.note("foot " + k + " is not a number"); }
            if (f.planted) {
                run.footTicks++;
                if (pose.reachMiss[k] > 0.15) run.shortFeet++;
            }
        }
    }

    private static String report() {
        StringBuilder b = new StringBuilder();
        for (Run r : RUNS) {
            b.append(String.format("%-8s %4d ticks  steps %d/%d/%d/%d  short %d of %d  length %.1e  %s%n", r.name, r.ticks, r.steps[0], r.steps[1],
                    r.steps[2], r.steps[3], r.shortFeet, r.footTicks, r.worstLength, r.notes));
        }
        return b.toString();
    }

    @Test
    void nothingGoesNaNAndNoJointEverLiesInRock() {
        for (Run r : RUNS) {
            assertEquals(0, r.nan, r.name + ": " + r.notes);
            assertEquals(0, r.inRock, r.name + ": " + r.notes);
        }
    }

    @Test
    void theSpineKeepsItsLengthsAndItsTrunkItsBendLimits() {
        for (Run r : RUNS) {
            assertTrue(r.worstLength < 1.0E-3, r.name + " stretched a segment by " + r.worstLength);
            assertEquals(0, r.overBent, r.name + ": " + r.notes);
        }
    }

    @Test
    void aPlantedFootNeverSlides() {
        for (Run r : RUNS) assertEquals(0, r.slid, r.name + ": " + r.notes);
    }

    @Test
    void everyFootStepsAndEveryLandingIsHeard() {
        for (Run r : RUNS) {
            for (int k = 0; k < 4; k++) {
                assertTrue(r.steps[k] >= 3, r.name + ": foot " + k + " stepped " + r.steps[k] + " times\n" + report());
                // every step ends on rock in these scenes (none falls), and each landing sounds once
                assertTrue(r.landings[k] >= r.steps[k] - 1 && r.landings[k] <= r.steps[k], r.name + ": foot " + k + " stepped "
                        + r.steps[k] + " times and landed " + r.landings[k]);
            }
        }
    }

    @Test
    void plantedFeetReachTheirRock() {
        // a leg may fall a little short for a moment over rubble; mostly every planted foot is reached
        for (Run r : RUNS) assertTrue(r.shortFeet <= r.footTicks * 0.03, r.name + " left feet short too often\n" + report());
    }
}
