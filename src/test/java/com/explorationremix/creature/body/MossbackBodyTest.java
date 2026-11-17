package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mossback's body over the clips' scenes (a floor, a turn, steps, a wall, a ceiling, a ledge, over a low wall,
 * rubble; the steps, the low wall and a wall also as the game's server moves its box and its client sees it, see
 * {@link GameDriver}), run without drawing: what must always hold, whatever the rock.
 */
class MossbackBodyTest {
    private static MossbackRig rig;
    private static final List<Run> RUNS = new ArrayList<>();

    /** One scene's run: what was seen, tick by tick. */
    static final class Run {
        final String name;
        int ticks, nan, inRock, slid, overBent, footTicks, shortFeet;
        double worstLength, worstRoll;
        /**
         * Steps begun, landings heard, steps cut short by a fall (the box off the rock), steps that came down in the air
         * with no rock in the leg's reach (the box perched on a block in rubble), and steps that came down in the air with rock in reach.
         */
        final int[] steps = new int[4], landings = new int[4], cut = new int[4], hung = new int[4], intoAir = new int[4];
        final List<String> notes = new ArrayList<>();

        Run(String name) { this.name = name; }

        void note(String s) { if (notes.size() < 12) notes.add(s); }
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
                if (drive.mode == MossbackBody.Mode.GROUND) roll(run, body, drive);
                for (int k = 0; k < 4; k++) {
                    MossbackBody.Foot f = body.feet[k];
                    // a foot that stays down stays where it is
                    if (wasPlanted[k] && f.planted && !f.swinging() && f.at.distance(was[k]) > 1.0E-9) {
                        run.slid++;
                        run.note("foot " + k + " slid " + f.at.distance(was[k]) + " at tick " + run.ticks);
                    }
                    if (f.swinging && !wasSwinging[k]) run.steps[k]++;
                    if (wasSwinging[k] && !f.swinging && !f.planted) {
                        if (drive.mode != MossbackBody.Mode.GROUND) run.cut[k]++;
                        else if (!body.rockInReach(rock, f)) run.hung[k]++;
                        else {
                            run.intoAir[k]++;
                            run.note(String.format("foot %d stepped into the air at tick %d at (%.2f %.2f %.2f), box (%.2f %.2f %.2f)", k, run.ticks,
                                    f.at.x, f.at.y, f.at.z, drive.x, drive.y, drive.z));
                        }
                    }
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

    /** How far the trunk rolls about its spine from its box's way out, where the spine runs across that way (not up a wall). */
    private static void roll(Run run, MossbackBody body, MossbackBody.Drive drive) {
        Vector3d p = new Vector3d(), u = new Vector3d();
        for (int i = 1; i <= MossbackSpec.TAIL_BASE; i++) {
            body.joint(i, 1.0, p, u);
            Vector3d fwd = new Vector3d(body.jointNow(i - 1)).sub(body.jointNow(i + 1)).normalize();
            if (Math.abs(fwd.dot(drive.up)) > 0.7) continue;
            Vector3d a = new Vector3d(u).fma(-u.dot(fwd), fwd), b = new Vector3d(drive.up).fma(-drive.up.dot(fwd), fwd);
            if (a.lengthSquared() < 1.0E-6 || b.lengthSquared() < 1.0E-6) continue;
            double deg = Math.toDegrees(a.normalize().angle(b.normalize()));
            if (deg > run.worstRoll + 1.0E-9) {
                run.worstRoll = deg;
                if (deg > 45) run.note(String.format("joint %d rolled %.0f deg at tick %d", i, deg, run.ticks));
            }
        }
    }

    private static String report() {
        StringBuilder b = new StringBuilder();
        for (Run r : RUNS) {
            b.append(String.format("%-8s %4d ticks  steps %d/%d/%d/%d  landed %d/%d/%d/%d  cut %d/%d/%d/%d  hung %d/%d/%d/%d  short %d of %d  length %.1e  %s%n",
                    r.name, r.ticks, r.steps[0], r.steps[1], r.steps[2], r.steps[3], r.landings[0], r.landings[1], r.landings[2], r.landings[3],
                    r.cut[0], r.cut[1], r.cut[2], r.cut[3], r.hung[0], r.hung[1], r.hung[2], r.hung[3], r.shortFeet, r.footTicks, r.worstLength, r.notes));
            b.setLength(b.length() - System.lineSeparator().length());
            b.append(String.format("  roll %.0f deg%n", r.worstRoll));
        }
        return b.toString();
    }

    @Test
    void report_() {
        System.out.print(report());
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
    void theTrunkNeverRollsOntoItsSide() {
        // over rubble it leans a little toward a block or away; on its side (its belly to a block) never
        for (Run r : RUNS) assertTrue(r.worstRoll < 50, r.name + " rolled its trunk " + r.worstRoll + " degrees\n" + report());
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
                // a step comes down on rock whenever there is rock in reach, and only a fall cuts one short (and one may be
                // under way as a scene ends); each landing sounds once
                assertEquals(0, r.intoAir[k], r.name + ": foot " + k + " stepped into the air with rock in reach\n" + r.notes);
                assertTrue(r.landings[k] >= r.steps[k] - r.cut[k] - r.hung[k] - 1 && r.landings[k] <= r.steps[k], r.name + ": foot " + k
                        + " stepped " + r.steps[k] + " times (" + r.cut[k] + " cut short by a fall, " + r.hung[k] + " finding no rock) and landed "
                        + r.landings[k] + "\n" + r.notes);
            }
            // only rubble (the box perched on a block, out of the legs' reach of the rock round it) leaves a foot hanging
            if (!r.name.equals("rubble")) for (int k = 0; k < 4; k++) assertEquals(0, r.hung[k], r.name + ": foot " + k + " found no rock\n" + report());
        }
    }

    /** A body laid on a flat floor and stood a few ticks, its box at (0.5, ., 0.5) heading south. */
    private static MossbackBody standing(BlockWorld world, TerrainPatch rock, MossbackBody.Drive drive, int[] bounds) {
        MossbackBody body = new MossbackBody(rig.spec);
        drive.x = 0.5; drive.y = 0.45; drive.z = 0.5;
        for (int t = 0; t < 6; t++) tick(body, world, rock, drive, bounds);
        return body;
    }

    private static void tick(MossbackBody body, BlockWorld world, TerrainPatch rock, MossbackBody.Drive drive, int[] bounds) {
        body.bounds(drive, bounds);
        rock.build(world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        body.tick(rock, drive);
    }

    @Test
    void aDyingBodyIsCarriedWithItsBox() {
        // killed, it is knocked back and falls off a ledge: the corpse goes with its box, just as it lay
        BlockWorld world = new BlockWorld().fill(-8, -1, -8, 8, -1, 8).fill(-8, -4, 9, 8, -4, 20);
        TerrainPatch rock = new TerrainPatch();
        MossbackBody.Drive drive = new MossbackBody.Drive();
        int[] bounds = new int[6];
        MossbackBody body = standing(world, rock, drive, bounds);
        Vector3d[] was = new Vector3d[body.joints];
        for (int i = 0; i < body.joints; i++) was[i] = new Vector3d(body.jointNow(i)).sub(drive.x, drive.y, drive.z);
        drive.mode = MossbackBody.Mode.DEAD;
        for (int t = 0; t < 12; t++) {
            drive.z += 0.35;
            drive.y -= 0.02 * t;
            tick(body, world, rock, drive, bounds);
            for (int i = 0; i < body.joints; i++) {
                Vector3d rel = new Vector3d(body.jointNow(i)).sub(drive.x, drive.y, drive.z);
                assertTrue(rel.distance(was[i]) < 1.0E-9, "joint " + i + " left behind by " + rel.distance(was[i]) + " at tick " + t);
            }
        }
    }

    @Test
    void aRearedHeadStaysUpAsItLeaps() {
        // reared on the floor, then off the rock in one tick (a lunge): the head does not drop flat at once
        BlockWorld world = new BlockWorld().fill(-8, -1, -8, 8, -1, 8);
        TerrainPatch rock = new TerrainPatch();
        MossbackBody.Drive drive = new MossbackBody.Drive();
        int[] bounds = new int[6];
        MossbackBody body = standing(world, rock, drive, bounds);
        drive.rear = 1.0;
        for (int t = 0; t < 10; t++) tick(body, world, rock, drive, bounds);
        double reared = body.jointNow(MossbackSpec.HEAD).y - body.jointNow(MossbackSpec.MIDDLE).y;
        drive.mode = MossbackBody.Mode.AIR;
        drive.rear = 0.0;
        drive.z += 0.3;
        tick(body, world, rock, drive, bounds);
        double leaping = body.jointNow(MossbackSpec.HEAD).y - body.jointNow(MossbackSpec.MIDDLE).y;
        assertTrue(reared > 0.15, "the head was not reared: " + reared);
        assertTrue(leaping > reared * 0.5, "the head dropped from " + reared + " to " + leaping + " above the middle in one tick");
    }

    @Test
    void plantedFeetReachTheirRock() {
        // a leg may fall a little short for a moment over rubble; mostly every planted foot is reached
        for (Run r : RUNS) assertTrue(r.shortFeet <= r.footTicks * 0.03, r.name + " left feet short too often\n" + report());
    }
}
