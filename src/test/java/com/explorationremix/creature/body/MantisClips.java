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
 * Clips of the stilt mantis's body in made-up places (walking, running, grazing, watching, its startle and bolt, steps
 * and slabs, rubble, dying), drawn with its real texture and written as videos, to judge how it moves. Runs only when
 * asked: {@code ./gradlew test --tests '*MantisClips*' -PslowTests -Dmantis.clips=<dir>} (and -Dmantis.only=walk,graze).
 */
@Tag("slow")
class MantisClips {
    /** Moves a mantis's box over a block world as the game's ground movement does: up a block at a step, down by falling. */
    static final class Driver {
        final BlockWorld world;
        final Vector3d at = new Vector3d();
        double yaw, headYaw, headPitch, vy;
        boolean hurry, graze, display, watch, airborne;
        double crouch, alert;
        Vector3d lookAt;
        static final double HW = 0.45;

        Driver(BlockWorld world, double x, double y, double z, double yawDeg) {
            this.world = world;
            at.set(x, y, z);
            yaw = Math.toRadians(yawDeg);
            headYaw = yaw;
        }

        double floorTop(double below) {
            return world.top(at.x - HW, at.z - HW, at.x + HW, at.z + HW, below);
        }

        void move(double step) {
            double nx = at.x - Math.sin(yaw) * step, nz = at.z + Math.cos(yaw) * step;
            double ox = at.x, oz = at.z;
            at.x = nx; at.z = nz;
            double top = floorTop(at.y + 1.0 + 1.0E-6);
            // too high a step: blocked
            if (Steps.any(world, at.x - HW + 1.0E-6, Math.max(top, at.y) + 1.0E-6, at.z - HW + 1.0E-6, at.x + HW - 1.0E-6,
                    Math.max(top, at.y) + 2.9, at.z + HW - 1.0E-6)) { at.x = ox; at.z = oz; return; }
            settle(top);
        }

        void settle(double top) {
            if (top >= at.y - 1.0E-6) { at.y = top; vy = 0; airborne = false; return; }
            vy = Math.min(vy + 0.08, 1.0);
            at.y = Math.max(top, at.y - vy);
            airborne = at.y > top + 0.1;
            if (at.y <= top + 1.0E-9) vy = 0;
        }

        void turn(double deg) { yaw += Math.toRadians(deg); }

        void drive(MantisBody.Drive d) {
            if (vy == 0) settle(floorTop(at.y + 1.0E-6));
            d.x = at.x; d.y = at.y; d.z = at.z;
            d.bodyYaw = yaw;
            if (lookAt != null) {
                double dx = lookAt.x - at.x, dy = lookAt.y - (at.y + 2.7), dz = lookAt.z - at.z;
                d.headYaw = Math.atan2(-dx, dz);
                d.headPitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
            } else {
                d.headYaw = yaw;
                d.headPitch = headPitch;
            }
            d.mode = airborne ? MantisBody.Mode.AIR : MantisBody.Mode.GROUND;
            d.hurry = hurry; d.graze = graze; d.display = display; d.watch = watch; d.crouch = crouch; d.alert = alert;
        }
    }

    interface Seg { boolean tick(Driver d, int t); }

    static Seg walk(double dist, double speed) {
        double[] left = {dist};
        return (d, t) -> { double s = Math.min(speed, left[0]); d.move(s); left[0] -= s; return left[0] > 1.0E-9; };
    }

    static Seg arc(double deg, double radius, double speed) {
        double[] left = {Math.toRadians(Math.abs(deg)) * radius};
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

    static Seg set(java.util.function.Consumer<Driver> r) { return (d, t) -> { r.accept(d); return false; }; }

    record Scene(String name, BlockWorld world, Driver driver, List<Seg> script, Vector3d camOffset, int dieAt) {
        Scene(String name, BlockWorld world, Driver driver, List<Seg> script, Vector3d camOffset) {
            this(name, world, driver, script, camOffset, -1);
        }
    }

    @Test
    void clips() throws Exception {
        String out = System.getProperty("mantis.clips");
        Assumptions.assumeTrue(out != null, "no clip directory given");
        String only = System.getProperty("mantis.only", "");
        String colour = System.getProperty("mantis.colour", "violet");
        int fps = Integer.getInteger("mantis.fps", 40);
        File dir = new File(out);
        dir.mkdirs();
        SoftRaster.Tex tex = SoftRaster.Tex.load(new File("src/main/resources/assets/explorationremix/textures/entity/stilt_mantis/stilt_mantis_"
                + colour + ".png"));
        MantisRig rig = MantisRig.load(MantisSpec.GAME_SCALE);
        for (Scene s : scenes()) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(s.name)) continue;
            run(s, rig, tex, new File(dir, s.name), fps);
        }
    }

    // ------------------------------------------------------------------------------------------------ scenes

    static List<Scene> scenes() {
        List<Scene> list = new ArrayList<>();
        {   // walking on a flat floor: straight, a turn, a stop, a turn on the spot, on again
            BlockWorld w = new BlockWorld().fill(-14, -1, -14, 14, -1, 14);
            Driver d = new Driver(w, -3, 0, -6, 0);
            list.add(new Scene("walk", w, d, List.of(idle(10), walk(4.0, 0.075), arc(-90, 3.0, 0.075), walk(2.0, 0.075), idle(40), spin(140, 4),
                    idle(20), walk(2.5, 0.075), idle(30)), new Vector3d(-4.6, 3.0, -3.4)));
        }
        {   // from the side: a walk, then a bolt
            BlockWorld w = new BlockWorld().fill(-6, -1, -14, 6, -1, 40);
            Driver d = new Driver(w, 0, 0, -10, 0);
            list.add(new Scene("side", w, d, List.of(idle(10), walk(4, 0.075), set(dd -> dd.hurry = true), walk(14, 0.32),
                    set(dd -> dd.hurry = false), walk(1.0, 0.075), idle(30)), new Vector3d(6.5, 1.6, 0.0)));
        }
        {   // grazing: it crouches, picks at the floor with each arm in turn, and rises again
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 10);
            Driver d = new Driver(w, 0, 0, 0, 0);
            list.add(new Scene("graze", w, d, List.of(idle(20), set(dd -> dd.graze = true), idle(200), set(dd -> dd.graze = false), idle(50)),
                    new Vector3d(3.6, 1.6, 2.6)));
        }
        {   // watching: its head follows something moving round it in flicks, tilting
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 10);
            Driver d = new Driver(w, 0, 0, 0, 0);
            Vector3d target = new Vector3d(4, 1.6, 4);
            list.add(new Scene("watch", w, d, List.of(idle(10), set(dd -> { dd.lookAt = target; dd.watch = true; dd.alert = 1; }),
                    (dd, t) -> {
                        double a = Math.toRadians(70) * Math.sin(t * 0.035);
                        target.set(5 * Math.sin(a), 1.6 + 0.8 * Math.sin(t * 0.05), 5 * Math.cos(a));
                        return t < 220;
                    }, set(dd -> { dd.lookAt = null; dd.watch = false; dd.alert = 0; }), idle(30)), new Vector3d(-3.2, 2.0, 4.2)));
        }
        {   // startled: the display, then it bolts
            BlockWorld w = new BlockWorld().fill(-8, -1, -10, 8, -1, 40);
            Driver d = new Driver(w, 0, 0, 0, 0);
            list.add(new Scene("startle", w, d, List.of(idle(20), set(dd -> dd.display = true), idle(14), set(dd -> { dd.display = false; dd.hurry = true; }),
                    walk(16, 0.32), set(dd -> dd.hurry = false), walk(0.8, 0.075), idle(30)), new Vector3d(5.0, 2.0, 3.0)));
        }
        {   // steps, slabs and a drop
            BlockWorld w = MossbackClips.stepsWorld();
            Driver d = new Driver(w, 0, 0, -5, 0);
            list.add(new Scene("steps", w, d, List.of(idle(10), walk(23, 0.075), idle(30)), new Vector3d(-5.4, 2.4, -1.6)));
        }
        {   // rubble: blocks, slabs and carpets at random
            Random r = new Random(7);
            BlockWorld w = new BlockWorld().fill(-12, -1, -12, 12, -1, 22);
            for (int x = -12; x <= 12; x++)
                for (int z = -6; z <= 22; z++) {
                    double k = r.nextDouble();
                    if (k < 0.16) w.slab(x, 0, z);
                    else if (k < 0.25) w.block(x, 0, z);
                    else if (k < 0.3) w.box(x, 0, z, 0, 0, 0, 1, 1.0 / 16, 1);
                    else if (k < 0.33) { w.block(x, 0, z); w.slab(x, 1, z); }
                }
            Driver d = new Driver(w, 0, 0, -5, 0);
            list.add(new Scene("rubble", w, d, List.of(idle(10), walk(6, 0.075), arc(-40, 5, 0.075), walk(5, 0.075), idle(30)),
                    new Vector3d(-4.6, 3.0, -2.6)));
        }
        {   // along a ledge: its left feet on the high side, its right feet down a block
            BlockWorld w = new BlockWorld().fill(-8, -1, -10, 0, 0, 20).fill(1, -1, -10, 8, -1, 20);
            Driver d = new Driver(w, 0.0, 1, -6, 0);
            list.add(new Scene("ledge", w, d, List.of(idle(10), walk(10, 0.075), idle(30)), new Vector3d(4.6, 2.4, -3.0)));
        }
        {   // it dies where it stands
            BlockWorld w = new BlockWorld().fill(-8, -1, -8, 8, -1, 8);
            Driver d = new Driver(w, 0, 0, 0, 0);
            list.add(new Scene("death", w, d, List.of(idle(70)), new Vector3d(3.8, 2.2, 3.0), 20));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------ running

    static void run(Scene s, MantisRig rig, SoftRaster.Tex tex, File dir, int fps) throws Exception {
        dir.mkdirs();
        for (File f : dir.listFiles()) if (f.getName().endsWith(".png")) f.delete();
        MantisBody body = new MantisBody(rig.spec);
        MantisPose pose = new MantisPose(rig);
        TerrainPatch rock = new TerrainPatch();
        MantisBody.Drive drive = new MantisBody.Drive();
        int[] steps = new int[4];
        body.setFootfalls((ft, running, speed) -> steps[ft.index]++);
        SoftRaster r = new SoftRaster(640, 400, 2);
        MantisPose.Inputs in = new MantisPose.Inputs();
        Vector3d cam = null;
        int frame = 0, ticks = 0, seg = 0, segT = 0;
        double worstMiss = 0;
        int missFrames = 0, kneesLow = 0, feetOff = 0;
        int per = Math.max(1, fps / 20);
        int[] bounds = new int[6];
        while (seg < s.script.size()) {
            if (!s.script.get(seg).tick(s.driver, segT++)) { seg++; segT = 0; }
            s.driver.drive(drive);
            if (s.dieAt >= 0 && ticks >= s.dieAt) drive.mode = MantisBody.Mode.DEAD;
            body.bounds(drive, bounds);
            rock.build(s.world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            body.tick(rock, drive);
            ticks++;
            for (MantisBody.Foot ft : body.feet)
                if (ft.planted && !rock.solid(ft.at.x, ft.at.y - 0.05, ft.at.z)) feetOff++;
            for (int k = 0; k < per; k++) {
                double pt = (double) k / per;
                in.age = ticks + pt;
                in.dying = s.dieAt >= 0 && ticks >= s.dieAt ? Math.min(1.0, (ticks - s.dieAt + pt) / 20.0) : 0.0;
                pose.compute(body, pt, in, 0, 0, 0, null, 0xF000F0);
                for (int i = 0; i < 4; i++) {
                    worstMiss = Math.max(worstMiss, pose.reachMiss[i]);
                    if (pose.reachMiss[i] > 0.02) missFrames++;
                    if (in.dying == 0 && pose.kneeAt[i].y < pose.hipAt[i].y) kneesLow++;
                }
                Vector3d mid = new Vector3d(), f = new Vector3d(), u = new Vector3d();
                body.frame(pt, mid, f, u);
                if (cam == null) cam = new Vector3d(mid);
                else cam.lerp(mid, s.driver.hurry ? 0.16 : 0.06);
                Vector3d eye = new Vector3d(s.camOffset).add(cam);
                Vector3d look = new Vector3d(cam).add(0, -0.4, 0);
                r.camera(eye, look, 50);
                r.clear(0x15131c);
                MossbackClips.drawWorld(r, s.world, cam);
                drawPose(r, pose, tex);
                r.save(new File(dir, String.format("f%05d.png", frame++)));
            }
        }
        System.out.printf("%s: %d ticks, steps ML %d MR %d HL %d HR %d, worst reach miss %.3f, frames short %d, knees under hips %d, planted feet off rock %d%n",
                s.name, ticks, steps[0], steps[1], steps[2], steps[3], worstMiss, missFrames, kneesLow, feetOff);
        File mp4 = new File(dir.getParentFile(), s.name + ".mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
                new File(dir, "f%05d.png").getPath(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", mp4.getPath())
                .inheritIO().start();
        p.waitFor();
    }

    static void drawPose(SoftRaster r, MantisPose pose, SoftRaster.Tex tex) {
        double[][] p = new double[4][3], uv = new double[4][2];
        double[] n = new double[3];
        int[] k = {0};
        pose.emit((x, y, z, u, v, nx, ny, nz, light) -> {
            int i = k[0]++;
            p[i][0] = x; p[i][1] = y; p[i][2] = z; uv[i][0] = u; uv[i][1] = v;
            n[0] = nx; n[1] = ny; n[2] = nz;
            if (k[0] == 4) {
                k[0] = 0;
                r.tri(p[0], p[1], p[2], uv[0], uv[1], uv[2], tex, 0, n[0], n[1], n[2], 0.75);
                r.tri(p[0], p[2], p[3], uv[0], uv[2], uv[3], tex, 0, n[0], n[1], n[2], 0.75);
            }
        }, 0xF000F0, false);
    }
}
