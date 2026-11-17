package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Clips of the Mossback's body in made-up places (walking, trotting, steps and slabs, a wall, a ceiling, a cliff edge,
 * rubble), drawn with its real texture and written as videos, to judge how it moves. Runs only when asked:
 * {@code ./gradlew test --tests '*MossbackClips*' -PslowTests -Dmossback.clips=<dir>} (and -Dmossback.only=walk,trot).
 */
@Tag("slow")
class MossbackClips {
    interface Seg { boolean tick(ClipDriver d, int t); }

    static Seg walk(double dist, double speed) {
        double[] left = {dist};
        return (d, t) -> { double s = Math.min(speed, left[0]); d.move(s); left[0] -= s; return left[0] > 1.0E-9; };
    }

    static Seg arc(double deg, double radius, double speed) {
        double total = Math.toRadians(Math.abs(deg)) * radius;
        double[] left = {total};
        return (d, t) -> {
            double s = Math.min(speed, left[0]);
            d.turn(Math.signum(deg) * Math.toDegrees(s / radius));
            d.move(s);
            left[0] -= s;
            return left[0] > 1.0E-9;
        };
    }

    static Seg spin(double deg, double rate) {
        double[] left = {Math.abs(deg)};
        return (d, t) -> { double a = Math.min(rate, left[0]); d.turn(Math.signum(deg) * a); left[0] -= a; return left[0] > 1.0E-9; };
    }

    static Seg idle(int ticks) { return (d, t) -> t < ticks - 1; }

    static Seg set(Runnable r) { return (d, t) -> { r.run(); return false; }; }

    record Scene(String name, BlockWorld world, ClipDriver driver, List<Seg> script, Vector3d camOffset, int extraTicks, Vector3d eye) {
        Scene(String name, BlockWorld world, ClipDriver driver, List<Seg> script, Vector3d camOffset, int extraTicks) {
            this(name, world, driver, script, camOffset, extraTicks, null);
        }
    }

    @Test
    void clips() throws Exception {
        String out = System.getProperty("mossback.clips");
        Assumptions.assumeTrue(out != null, "no clip directory given");
        String only = System.getProperty("mossback.only", "");
        int fps = Integer.getInteger("mossback.fps", 60);
        File dir = new File(out);
        dir.mkdirs();
        SoftRaster.Tex tex = SoftRaster.Tex.load(new File("src/main/resources/assets/explorationremix/textures/entity/mossback/mossback.png"));
        MossbackRig rig = MossbackRig.load();
        for (Scene s : scenes()) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(s.name)) continue;
            run(s, rig, tex, new File(dir, s.name), fps);
        }
    }

    // ------------------------------------------------------------------------------------------------ scenes

    static List<Scene> scenes() {
        List<Scene> list = new ArrayList<>();
        {   // walking on a flat floor: straight, a turn, a stop, a turn on the spot, on again
            BlockWorld w = new BlockWorld().fill(-12, -1, -12, 12, -1, 12);
            ClipDriver d = new ClipDriver(w, -3, 0, -4, 0, 1);
            list.add(new Scene("walk", w, d, List.of(walk(3.5, 0.1), arc(-90, 2.5, 0.1), walk(2.0, 0.1), idle(40), spin(140, 6), idle(10),
                    walk(2.5, 0.1), idle(30)), new Vector3d(-3.2, 2.2, -2.4), 0));
        }
        {   // from the side: a straight walk, then a trot
            BlockWorld w = new BlockWorld().fill(-6, -1, -14, 6, -1, 30);
            ClipDriver d = new ClipDriver(w, 0, 0, -10, 0, 1);
            list.add(new Scene("side", w, d, List.of(walk(5, 0.1), set(() -> d.hurry = true), walk(8, 0.24), set(() -> d.hurry = false), walk(1, 0.1), idle(30)),
                    new Vector3d(4.6, 0.9, 0.0), 0));
        }
        {   // hunting: a trot, weaving
            BlockWorld w = new BlockWorld().fill(-16, -1, -16, 16, -1, 16);
            ClipDriver d = new ClipDriver(w, -6, 0, -8, 0, 1);
            d.hurry = true;
            list.add(new Scene("trot", w, d, List.of(walk(4, 0.24), arc(60, 3, 0.24), arc(-100, 3, 0.24), walk(3, 0.24), arc(50, 3, 0.24),
                    set(() -> d.hurry = false), walk(1.0, 0.12), idle(30)), new Vector3d(-4.0, 2.6, -2.8), 0));
        }
        {   // steps, slabs and a drop
            BlockWorld w = new BlockWorld().fill(-6, -1, -8, 6, -1, 18);
            for (int x = -6; x <= 6; x++) {
                w.slab(x, 0, 1);
                w.fill(x, 0, 3, x, 0, 6);
                w.slab(x, 1, 5);
                w.slab(x, 1, 6);
                w.fill(x, 0, 7, x, 1, 9);
                w.fill(x, 0, 12, x, 0, 13);
            }
            ClipDriver d = new ClipDriver(w, 0, 0, -4, 0, 1);
            list.add(new Scene("steps", w, d, List.of(walk(25, 0.1), idle(30)), new Vector3d(-3.6, 2.0, -1.2), 0));
        }
        {   // up a wall and over its top
            BlockWorld w = new BlockWorld().fill(-8, -1, -6, 10, -1, 6).fill(4, 0, -6, 10, 2, 6);
            ClipDriver d = new ClipDriver(w, -2, 0, 0, 1, 0);
            list.add(new Scene("wall", w, d, List.of(
                    (dd, t) -> { dd.move(0.1); return dd.c.x + ClipDriver.HW < 4.0 - 1.0E-6; },
                    set(() -> { d.c.x = 4.0 - ClipDriver.HW; d.up.set(-1, 0, 0); d.heading.set(0, 1, 0); }),
                    (dd, t) -> { dd.move(0.085); return dd.c.y - ClipDriver.HH < 3.0; },
                    set(() -> { d.up.set(0, 1, 0); d.heading.set(1, 0, 0); d.c.y = 3.0 + ClipDriver.HH; }),
                    walk(3.0, 0.1), idle(30)), new Vector3d(-1.2, 1.8, -4.6), 0));
        }
        {   // up a wall onto a ceiling, and along it
            BlockWorld w = new BlockWorld().fill(-8, -1, -6, 8, -1, 6).fill(4, 0, -6, 8, 3, 6).fill(-8, 3, -6, 8, 3, 6);
            ClipDriver d = new ClipDriver(w, -2, 0, 0, 1, 0);
            list.add(new Scene("ceiling", w, d, List.of(
                    (dd, t) -> { dd.move(0.1); return dd.c.x + ClipDriver.HW < 4.0 - 1.0E-6; },
                    set(() -> { d.c.x = 4.0 - ClipDriver.HW; d.up.set(-1, 0, 0); d.heading.set(0, 1, 0); }),
                    (dd, t) -> { dd.move(0.085); return dd.c.y + ClipDriver.HH < 3.0 - 1.0E-6; },
                    set(() -> { d.c.y = 3.0 - ClipDriver.HH; d.up.set(0, -1, 0); d.heading.set(-1, 0, 0); }),
                    walk(3.5, 0.085), idle(40)), new Vector3d(-0.6, -0.4, -5.2), 0, new Vector3d(-1.5, 1.4, -5.5)));
        }
        {   // head first down a step a block high, a while at its foot still clinging to it (as the game holds one against
            // a short block in its way), then off across the floor
            BlockWorld w = new BlockWorld().fill(-8, -1, -6, 8, -1, 8).fill(-8, 0, 2, 8, 0, 8);
            ClipDriver d = new ClipDriver(w, 0, 0, 0, 0, 1);
            list.add(new Scene("down", w, d, List.of(
                    set(() -> { d.c.set(0, 1.7, 2.0 - ClipDriver.HW); d.up.set(0, 0, -1); d.heading.set(0, -1, 0); d.clientUp.set(d.up);
                        d.clientFwd.set(d.heading); d.mode = MossbackBody.Mode.GROUND; }),
                    (dd, t) -> { dd.move(0.085); return dd.c.y - ClipDriver.HH > 0.02; },
                    idle(50),
                    set(() -> { d.up.set(0, 1, 0); d.heading.set(0, 0, -1); d.c.y = ClipDriver.HH; }),
                    walk(4.0, 0.1), idle(30)), new Vector3d(-3.6, 1.4, -1.6), 0));
        }
        {   // over a wall a block high and a block thick (as round a pen): up onto it, across, down the far side
            BlockWorld w = new BlockWorld().fill(-8, -1, -8, 8, -1, 12).fill(-8, 0, 2, 8, 0, 2);
            ClipDriver d = new ClipDriver(w, 0.3, 0, -2.5, 0, 1);
            list.add(new Scene("over", w, d, List.of(walk(10.0, 0.1), idle(40)), new Vector3d(-3.4, 1.6, 0.6), 0, new Vector3d(-5.2, 2.4, 1.4)));
        }
        {   // along a cliff edge, the left feet reaching down its face
            BlockWorld w = new BlockWorld().fill(-8, -1, -8, 0, -1, 14).fill(-8, -4, -8, 0, -2, 14).fill(1, -5, -8, 8, -5, 14);
            ClipDriver d = new ClipDriver(w, 0.15, 0, -4, 0, 1);
            list.add(new Scene("edge", w, d, List.of(walk(8, 0.1), idle(30)), new Vector3d(3.2, 1.6, -2.6), 0));
        }
        {   // rubble: blocks, slabs and carpets of moss at random
            Random r = new Random(7);
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 16);
            for (int x = -10; x <= 10; x++)
                for (int z = -6; z <= 16; z++) {
                    double k = r.nextDouble();
                    if (k < 0.18) w.slab(x, 0, z);
                    else if (k < 0.28) w.block(x, 0, z);
                    else if (k < 0.33) w.box(x, 0, z, 0, 0, 0, 1, 1.0 / 16, 1);
                    else if (k < 0.36) { w.block(x, 0, z); w.slab(x, 1, z); }
                }
            ClipDriver d = new ClipDriver(w, 0, 0, -4, 0, 1);
            list.add(new Scene("rubble", w, d, List.of(walk(5, 0.1), arc(-40, 4, 0.1), walk(5, 0.1), idle(30)), new Vector3d(-3.4, 2.3, -2.0), 0));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------ running

    static void run(Scene s, MossbackRig rig, SoftRaster.Tex tex, File dir, int fps) throws Exception {
        dir.mkdirs();
        for (File f : dir.listFiles()) if (f.getName().endsWith(".png")) f.delete();
        MossbackBody body = new MossbackBody(rig.spec);
        MossbackPose pose = new MossbackPose(rig);
        TerrainPatch rock = new TerrainPatch();
        MossbackBody.Drive drive = new MossbackBody.Drive();
        int[] steps = new int[4];
        body.setFootfalls((ft, trot, speed) -> steps[ft.leg.front ? (ft.leg.side > 0 ? 0 : 1) : (ft.leg.side > 0 ? 2 : 3)]++);
        SoftRaster r = new SoftRaster(640, 400, 2);
        MossbackPose.Inputs in = new MossbackPose.Inputs();
        Vector3d cam = null;
        int frame = 0, ticks = 0, seg = 0, segT = 0;
        double worstMiss = 0, missSum = 0;
        int missFrames = 0, inRock = 0;
        int per = Math.max(1, fps / 20);
        int[] bounds = new int[6];
        List<Seg> script = s.script;
        while (seg < script.size() || segT < s.extraTicks) {
            if (seg < script.size()) {
                if (!script.get(seg).tick(s.driver, segT++)) { seg++; segT = 0; }
            } else segT++;
            s.driver.drive(drive);
            body.bounds(drive, bounds);
            rock.build(s.world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            body.tick(rock, drive);
            ticks++;
            for (int i = 0; i < body.joints; i++) if (rock.solid(body.jointNow(i).x, body.jointNow(i).y, body.jointNow(i).z)) inRock++;
            for (int k = 0; k < per; k++) {
                double pt = (double) k / per;
                in.age = ticks + pt;
                in.frill = s.driver.hurry ? 1.0 : 0.0;
                pose.compute(body, pt, in, 0, 0, 0, null, 0xF000F0);
                for (double m : pose.reachMiss) { worstMiss = Math.max(worstMiss, m); if (m > 0.02) missFrames++; missSum += m; }
                Vector3d mid = new Vector3d();
                body.joint(MossbackSpec.MIDDLE, pt, mid, new Vector3d());
                if (cam == null) cam = new Vector3d(mid);
                else cam.lerp(mid, 0.06);
                Vector3d eye = s.eye != null ? new Vector3d(s.eye) : new Vector3d(s.camOffset).mul(1.45).add(cam);
                r.camera(eye, cam, 46);
                r.clear(0x1b2028);
                drawWorld(r, s.world, cam);
                drawPose(r, pose, tex);
                r.save(new File(dir, String.format("f%05d.png", frame++)));
            }
        }
        System.out.printf("%s: %d ticks, steps FL %d FR %d HL %d HR %d, worst reach miss %.3f, frames short %d, joints in rock %d%n",
                s.name, ticks, steps[0], steps[1], steps[2], steps[3], worstMiss, missFrames, inRock);
        File mp4 = new File(dir.getParentFile(), s.name + ".mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
                new File(dir, "f%05d.png").getPath(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", mp4.getPath())
                .inheritIO().start();
        p.waitFor();
    }

    static void drawWorld(SoftRaster r, BlockWorld w, Vector3d near) {
        w.forEach(b -> {
            if (Math.abs(b[0] - near.x) > 9 || Math.abs(b[2] - near.z) > 9 || Math.abs(b[1] - near.y) > 7) return;
            for (int f = 0; f < 6; f++) face(r, w, b, f);
        });
    }

    /** One face of a box, cut into half-block squares in a checker (so feet that slide show it). */
    static void face(SoftRaster r, BlockWorld w, double[] b, int f) {
        int axis = f / 2;
        boolean hi = f % 2 == 1;
        double plane = hi ? b[3 + axis] : b[axis];
        // skip a face pressed flat against a full neighbour
        double[] probe = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        probe[axis] = plane + (hi ? 0.01 : -0.01);
        int px = (int) Math.floor(probe[0]), py = (int) Math.floor(probe[1]), pz = (int) Math.floor(probe[2]);
        if (w.has(px, py, pz)) {
            double[] top = {Double.NaN};
            w.boxes(px, py, pz, (x0, y0, z0, x1, y1, z1) -> {
                if (probe[0] > x0 && probe[0] < x1 && probe[1] > y0 && probe[1] < y1 && probe[2] > z0 && probe[2] < z1) top[0] = 1;
            });
            if (!Double.isNaN(top[0])) return;
        }
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        double s0 = b[a1], s1 = b[3 + a1], u0 = b[a2], u1 = b[3 + a2];
        double step = 0.5;
        for (double s = Math.floor(s0 / step) * step; s < s1 - 1.0E-9; s += step)
            for (double u = Math.floor(u0 / step) * step; u < u1 - 1.0E-9; u += step) {
                double sa = Math.max(s, s0), sb = Math.min(s + step, s1), ua = Math.max(u, u0), ub = Math.min(u + step, u1);
                if (sb - sa < 1.0E-9 || ub - ua < 1.0E-9) continue;
                boolean dark = ((int) Math.floor(s / step + 1000) + (int) Math.floor(u / step + 1000)) % 2 == 0;
                int base = axis == 1 ? (dark ? 0x59606b : 0x666e7a) : (dark ? 0x4a5059 : 0x535a64);
                double[][] q = new double[4][3];
                double[][] st = {{sa, ua}, {sb, ua}, {sb, ub}, {sa, ub}};
                for (int i = 0; i < 4; i++) { q[i][axis] = plane; q[i][a1] = st[i][0]; q[i][a2] = st[i][1]; }
                double nx = axis == 0 ? (hi ? 1 : -1) : 0, ny = axis == 1 ? (hi ? 1 : -1) : 0, nz = axis == 2 ? (hi ? 1 : -1) : 0;
                r.tri(q[0], q[1], q[2], null, null, null, null, base, nx, ny, nz, 0);
                r.tri(q[0], q[2], q[3], null, null, null, null, base, nx, ny, nz, 0);
            }
    }

    static void drawPose(SoftRaster r, MossbackPose pose, SoftRaster.Tex tex) {
        double[][] p = new double[4][3], uv = new double[4][2];
        double[] n = new double[3];
        int[] k = {0};
        pose.emit((x, y, z, u, v, nx, ny, nz, light) -> {
            int i = k[0]++;
            p[i][0] = x; p[i][1] = y; p[i][2] = z; uv[i][0] = u; uv[i][1] = v;
            n[0] = nx; n[1] = ny; n[2] = nz;
            if (k[0] == 4) {
                k[0] = 0;
                r.tri(p[0], p[1], p[2], uv[0], uv[1], uv[2], tex, 0, n[0], n[1], n[2], 0);
                r.tri(p[0], p[2], p[3], uv[0], uv[2], uv[3], tex, 0, n[0], n[1], n[2], 0);
            }
        }, 0xF000F0, false);
    }
}
