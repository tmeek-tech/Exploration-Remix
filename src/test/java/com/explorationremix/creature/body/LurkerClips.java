package com.explorationremix.creature.body;

import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Clips of the chimney lurker's body in made-up places (standing, walking, its skitter, steps and rubble, waiting in
 * its chimney's mouth, out over the rim and down its vent, home again up the outside and in, the crouch and the leap,
 * dying), drawn with its real texture and glow and written as videos, to judge how it moves. Runs only when asked:
 * {@code ./gradlew test --tests '*LurkerClips*' -PslowTests -Dlurker.clips=<dir>} (and -Dlurker.only=walk,brace).
 */
@Tag("slow")
class LurkerClips {
    /**
     * Moves the lurker as the game will: its box over a floor by the game's ground movement (up a block at a step, down
     * by falling, through the air in a leap), its body over its vent by the crawler ({@link LurkerCrawl}).
     */
    static final class Driver {
        final BlockWorld world;
        final LurkerSpec spec;
        /** On the floor: its box's feet. */
        final Vector3d at = new Vector3d();
        double yaw, vy, speed;
        boolean hurry, airborne, dead;
        double crouch, brace, fangs, tense, glow;
        /** On its vent: the crawler holding it to the rock (null on the floor). */
        LurkerCrawl crawl;
        /** Leaping: its speed through the air. */
        final Vector3d flight = new Vector3d();
        boolean leaping;
        static final double HW = 0.95;

        Driver(BlockWorld world, LurkerSpec spec, double x, double y, double z, double yawDeg) {
            this.world = world;
            this.spec = spec;
            at.set(x, y, z);
            yaw = Math.toRadians(yawDeg);
        }

        double floorTop(double below) {
            return world.top(at.x - HW, at.z - HW, at.x + HW, at.z + HW, below);
        }

        void move(double step) {
            double ox = at.x, oz = at.z;
            at.x -= Math.sin(yaw) * step;
            at.z += Math.cos(yaw) * step;
            double top = floorTop(at.y + 1.0 + 1.0E-6);
            if (Steps.any(world, at.x - HW + 1.0E-6, Math.max(top, at.y) + 1.0E-6, at.z - HW + 1.0E-6, at.x + HW - 1.0E-6,
                    Math.max(top, at.y) + 1.2, at.z + HW - 1.0E-6)) { at.x = ox; at.z = oz; return; }
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

        /** Off its vent onto the floor where the crawler left it, facing the way it went. */
        void toFloor() {
            at.set(crawl.pos).sub(0, spec.ride, 0);
            double hx = crawl.heading.x, hz = crawl.heading.z;
            if (hx * hx + hz * hz > 1.0E-6) yaw = Math.atan2(-hx, hz);
            crawl = null;
            vy = 0;
            settle(floorTop(at.y + 0.6));
        }

        void drive(LurkerBody.Drive d) {
            if (crawl != null) {
                d.x = crawl.pos.x; d.y = crawl.pos.y; d.z = crawl.pos.z;
                d.heading.set(crawl.heading);
                d.up.set(crawl.up);
                d.mode = LurkerBody.Mode.SURFACE;
                d.velocity.set(0, 0, 0);
            } else {
                if (!leaping && vy == 0) settle(floorTop(at.y + 1.0E-6));
                d.x = at.x; d.y = at.y + spec.ride; d.z = at.z;
                d.heading.set(-Math.sin(yaw), 0, Math.cos(yaw));
                d.up.set(0, 0, 0);
                d.mode = leaping || airborne ? LurkerBody.Mode.AIR : LurkerBody.Mode.SURFACE;
                d.velocity.set(flight);
            }
            if (dead) d.mode = LurkerBody.Mode.DEAD;
            d.crouch = crouch; d.brace = brace; d.fangs = fangs; d.tense = tense; d.hurry = hurry;
        }
    }

    static final double ZOOM = Double.parseDouble(System.getProperty("lurker.zoom", "1.0"));
    static final boolean DEBUG = Boolean.getBoolean("lurker.debug");

    interface Seg { boolean tick(Driver d, int t); }

    static final double EASE = 0.454;

    static Seg walk(double dist, double speed) {
        double[] left = {dist};
        return (d, t) -> {
            d.speed += (speed - d.speed) * EASE;
            double s = Math.min(d.speed, left[0]);
            d.move(s);
            left[0] -= s;
            return left[0] > 1.0E-9;
        };
    }

    static Seg arc(double deg, double radius, double speed) {
        double[] left = {Math.toRadians(Math.abs(deg)) * radius};
        return (d, t) -> {
            d.speed += (speed - d.speed) * EASE;
            double s = Math.min(d.speed, left[0]);
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

    static Seg idle(int ticks) {
        return (d, t) -> {
            if (d.crawl == null) {
                if (d.speed > 0.003) { d.speed *= 1.0 - EASE; d.move(d.speed); } else d.speed = 0.0;
            }
            return t < ticks - 1;
        };
    }

    static Seg set(java.util.function.Consumer<Driver> r) { return (d, t) -> { r.accept(d); return false; }; }

    /** Waiting in its chimney's mouth: on the flue's inner wall at {@code azimuth}, head up, {@code depth} under the rim. */
    static Seg waitIn(double ax, double az, double azimuth, double rFlue, double top, double depth) {
        return set(d -> {
            d.crawl = new LurkerCrawl(d.spec.ride);
            Vector3d from = new Vector3d(ax + Math.cos(azimuth) * (rFlue - d.spec.ride), top - depth, az + Math.sin(azimuth) * (rFlue - d.spec.ride));
            d.crawl.start(from, ax, az, LurkerCrawl.Sense.OUT);
            if (d.crawl.read(d.world)) d.crawl.settle();
            d.crawl.heading.set(0, 1, 0);
            d.brace = 1;
        });
    }

    /** Over its vent's rock about the axis (ax, az) going {@code sense}, at {@code speed}, until {@code done} (or a limit). */
    static Seg crawl(LurkerCrawl.Sense sense, double ax, double az, double speed, Predicate<Driver> done, int limit) {
        return (d, t) -> {
            if (t == 0) {
                Vector3d from = d.crawl != null ? new Vector3d(d.crawl.pos) : new Vector3d(d.at).add(0, d.spec.ride, 0);
                if (d.crawl == null) d.crawl = new LurkerCrawl(d.spec.ride);
                d.crawl.start(from, ax, az, sense);
            }
            if (!d.crawl.step(d.world, speed)) System.out.println("  crawl lost its hold at tick " + t);
            Vector3d at = new Vector3d(d.crawl.pos);
            if (done.test(d) || t >= limit) {
                if (t >= limit) System.out.println("  crawl " + sense + " gave up after " + t + " ticks at " + fmt(at));
                else System.out.println("  crawl " + sense + " done in " + t + " ticks at " + fmt(at));
                return false;
            }
            return true;
        };
    }

    /** Out from its waiting spot: done on the floor (facing up, well under its rim), then off the vent onto the floor. */
    static Seg out(double ax, double az, double speed, double top) {
        int[] flat = {0};
        return crawl(LurkerCrawl.Sense.OUT, ax, az, speed, d -> {
            flat[0] = d.crawl.facingUp() > 0.85 && d.crawl.pos.y < top - 4 ? flat[0] + 1 : 0;
            if (flat[0] < 2) return false;
            d.toFloor();
            return true;
        }, 400);
    }

    /** Home from the floor: up the outside, over the rim and down into the mouth, where it turns head up and braces. */
    static Seg in(double ax, double az, double speed) {
        return crawl(LurkerCrawl.Sense.IN, ax, az, speed, d -> {
            if (d.crawl.facingOut() < -0.6 && d.crawl.pos.y <= d.crawl.highest - (1.2 + d.spec.ride)) {
                d.crawl.heading.set(0, 1, 0);
                d.brace = 1;
                return true;
            }
            return false;
        }, 400);
    }

    /** A leap: crouched, it springs forward and up, its speed through the air taken down by the game's gravity and drag. */
    static Seg leap(double ahead, double rise) {
        return (d, t) -> {
            if (t == 0) {
                d.leaping = true;
                d.crouch = 0;
                d.fangs = 1;
                d.flight.set(-Math.sin(d.yaw) * ahead, rise, Math.cos(d.yaw) * ahead);
            }
            d.at.add(d.flight);
            d.flight.y -= 0.08;
            d.flight.mul(0.98, 0.98, 0.98);
            double top = d.floorTop(d.at.y + 0.6);
            if (d.flight.y < 0 && d.at.y <= top) {
                d.at.y = top;
                d.leaping = false;
                d.flight.set(0, 0, 0);
                d.fangs = 0.3;
                return false;
            }
            return true;
        };
    }

    record Scene(String name, BlockWorld world, Driver driver, List<Seg> script, Vector3d camOffset, int dieAt, Vector3d aim,
                 Predicate<double[]> cut, boolean fixedCam) {
        Scene(String name, BlockWorld world, Driver driver, List<Seg> script, Vector3d camOffset) {
            this(name, world, driver, script, camOffset, -1, new Vector3d(0, 0, 0), null, false);
        }
    }

    @Test
    void clips() throws Exception {
        String out = System.getProperty("lurker.clips");
        Assumptions.assumeTrue(out != null, "no clip directory given");
        String only = System.getProperty("lurker.only", "");
        String colour = System.getProperty("lurker.colour", "violet");
        int fps = Integer.getInteger("lurker.fps", 20);
        File dir = new File(out);
        dir.mkdirs();
        String base = "src/main/resources/assets/explorationremix/textures/entity/chimney_lurker/chimney_lurker_" + colour;
        SoftRaster.Tex tex = SoftRaster.Tex.load(new File(base + ".png"));
        SoftRaster.Tex glow = SoftRaster.Tex.load(new File(base + "_glow.png"));
        LurkerRig rig = LurkerRig.load(LurkerSpec.GAME_SCALE);
        for (Scene s : scenes(rig.spec)) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(s.name)) continue;
            run(s, rig, tex, glow, new File(dir, s.name), fps);
        }
    }

    // ------------------------------------------------------------------------------------------------ worlds

    /**
     * A vent like the Deep Gardens' live chimneys round the axis (0, 0): a column tapering from a flared foot to its rim,
     * a flange part way up, and a flue down its middle from its rim to a block over the floor.
     */
    static BlockWorld vent(double rFoot, double rTop, double rFlue, int height, int flangeY, double flange) {
        int r = (int) Math.ceil(rFoot + flange + 1);
        BlockWorld w = new BlockWorld().fill(-18, -1, -18, 18, -1, 18);
        for (int y = 0; y < height; y++) {
            double t = (y + 0.5) / height;
            double rOut = rTop + (rFoot - rTop) * Math.pow(1.0 - t, 2.5);
            if (flangeY >= 0 && (y == flangeY || y == flangeY + 1)) rOut += flange;
            for (int x = -r; x < r; x++)
                for (int z = -r; z < r; z++) {
                    double d = Math.hypot(x + 0.5, z + 0.5);
                    if (d > rOut || (y >= 1 && d < rFlue)) continue;
                    w.block(x, y, z);
                }
        }
        return w;
    }

    // ------------------------------------------------------------------------------------------------ scenes

    static List<Scene> scenes(LurkerSpec spec) {
        List<Scene> list = new ArrayList<>();
        {   // standing still, turned three quarters to the camera
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 10);
            Driver d = new Driver(w, spec, 0, 0, 0, 30);
            list.add(new Scene("still", w, d, List.of(set(dd -> dd.glow = 1), idle(40)), new Vector3d(4.6, 2.6, 3.6)));
        }
        {   // the camera going round it
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 10);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("orbit", w, d, List.of(set(dd -> dd.glow = 1), idle(120)), new Vector3d(0, 2.0, 5.6), -1,
                    new Vector3d(0, 0.2, 0), null, false));
        }
        {   // walking on a flat floor: straight, a turn, a stop, a turn on the spot, on again
            BlockWorld w = new BlockWorld().fill(-16, -1, -16, 16, -1, 16);
            Driver d = new Driver(w, spec, -3, 0, -7, 0);
            list.add(new Scene("walk", w, d, List.of(idle(10), walk(5.0, 0.09), arc(-90, 4.0, 0.09), walk(2.0, 0.09), idle(30), spin(140, 6),
                    idle(20), walk(3.0, 0.09), idle(30)), new Vector3d(-6.2, 4.2, -4.4)));
        }
        {   // from the side: a walk, then its skitter
            BlockWorld w = new BlockWorld().fill(-8, -1, -16, 8, -1, 44);
            Driver d = new Driver(w, spec, 0, 0, -12, 0);
            list.add(new Scene("run", w, d, List.of(idle(10), walk(4, 0.09), set(dd -> { dd.hurry = true; dd.glow = 1; }), walk(18, 0.26),
                    set(dd -> dd.hurry = false), walk(1.0, 0.09), idle(30)), new Vector3d(8.0, 2.4, 0.0)));
        }
        {   // steps, slabs and a drop
            BlockWorld w = MossbackClips.stepsWorld();
            Driver d = new Driver(w, spec, 0, 0, -5, 0);
            list.add(new Scene("steps", w, d, List.of(idle(10), walk(23, 0.09), idle(30)), new Vector3d(-7.0, 3.4, -2.0)));
        }
        {   // rubble: blocks, slabs and carpets at random
            Random r = new Random(7);
            BlockWorld w = new BlockWorld().fill(-14, -1, -14, 14, -1, 24);
            for (int x = -14; x <= 14; x++)
                for (int z = -6; z <= 24; z++) {
                    double k = r.nextDouble();
                    if (k < 0.16) w.slab(x, 0, z);
                    else if (k < 0.25) w.block(x, 0, z);
                    else if (k < 0.3) w.box(x, 0, z, 0, 0, 0, 1, 1.0 / 16, 1);
                    else if (k < 0.33) { w.block(x, 0, z); w.slab(x, 1, z); }
                }
            Driver d = new Driver(w, spec, 0, 0, -5, 0);
            list.add(new Scene("rubble", w, d, List.of(idle(10), walk(7, 0.09), arc(-40, 6, 0.09), walk(5, 0.09), idle(30)),
                    new Vector3d(-6.4, 4.0, -3.4)));
        }
        {   // a sheer wall six blocks high: up its face from the floor, over its top edge and on across the top, seen from the side
            BlockWorld w = new BlockWorld().fill(-16, -1, -10, 16, -1, 10).fill(-8, 0, -10, -1, 5, 10);
            Driver d = new Driver(w, spec, 6, 0, 0.3, 90);
            list.add(new Scene("wall", w, d, List.of(idle(10), walk(2, 0.09), in(-60, 0.3, 0.12), idle(20)),
                    new Vector3d(0.0, 1.5, 10.0), -1, new Vector3d(0, 0, 0), b -> b[1] >= 0 && (b[2] + b[5]) / 2 > 1.6, false));
        }
        // its vent: ten blocks high, its flue six across the middle (as most live vents' are), a flange part way up
        int height = 10;
        double rFlue = 3.0, az = 0.12, depth = 2.0;
        Predicate<double[]> cutNear = b -> b[1] >= 0 && (b[2] + b[5]) / 2 > 0.6;
        Predicate<double[]> cutAxisSide = b -> b[1] >= 0 && (b[0] + b[3]) / 2 < 0.8;
        {   // waiting in its chimney's mouth, a cut through the vent: braced, stirring, then out and down to the floor
            BlockWorld w = vent(6.6, 4.6, rFlue, height, 5, 1.1);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("brace", w, d, List.of(waitIn(0, 0, az, rFlue, height, depth), idle(50), set(dd -> dd.tense = 1), idle(40),
                    set(dd -> { dd.tense = 0; dd.brace = 0; dd.glow = 1; dd.hurry = true; }), out(0, 0, 0.24, height),
                    walk(6, 0.26), set(dd -> dd.hurry = false), idle(30)),
                    new Vector3d(0.0, 1.0, 12.0), -1, new Vector3d(0, 0, 0), cutNear, false));
        }
        {   // waiting, seen from across its chimney (the near half cut away): its back, its legs braced round the flue
            BlockWorld w = vent(6.6, 4.6, rFlue, height, 5, 1.1);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("brace-back", w, d, List.of(waitIn(0, 0, az, rFlue, height, depth), idle(40), set(dd -> dd.tense = 1), idle(40)),
                    new Vector3d(-6.5, 1.2, 0.8), -1, new Vector3d(0, 0.3, 0), cutAxisSide, false));
        }
        {   // the same, seen from over the rim (no cut): only its front feet's tips show over the rim
            BlockWorld w = vent(6.6, 4.6, rFlue, height, 5, 1.1);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("brace-top", w, d, List.of(waitIn(0, 0, az, rFlue, height, depth), idle(50), set(dd -> dd.tense = 1), idle(40),
                    set(dd -> { dd.tense = 0; dd.brace = 0; dd.glow = 1; dd.hurry = true; }), out(0, 0, 0.24, height), idle(20)),
                    new Vector3d(-5.5, 6.0, 5.0), -1, new Vector3d(0, 0, 0), null, false));
        }
        {   // home: from the floor up the outside, over the rim and down into the mouth, where it turns and braces
            BlockWorld w = vent(6.6, 4.6, rFlue, height, 5, 1.1);
            Driver d = new Driver(w, spec, 10.0, 0, 1.2, 90);
            list.add(new Scene("home", w, d, List.of(set(dd -> dd.glow = 0.6), idle(10), in(0, 0, 0.14), idle(60)),
                    new Vector3d(0.0, 1.0, 12.0), -1, new Vector3d(0, 0, 0), cutNear, false));
        }
        {   // a wide vent (its flue nine across): waiting, then out
            BlockWorld w = vent(8.0, 6.4, 4.5, 9, 4, 1.0);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("wide", w, d, List.of(waitIn(0, 0, az, 4.5, 9, depth), idle(40), set(dd -> { dd.brace = 0; dd.glow = 1; dd.hurry = true; }),
                    out(0, 0, 0.24, 9), walk(4, 0.26), idle(20)),
                    new Vector3d(0.0, 2.0, 13.0), -1, new Vector3d(0, 0, 0), cutNear, false));
        }
        {   // the crouch and the leap
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 24);
            Driver d = new Driver(w, spec, 0, 0, -4, 0);
            list.add(new Scene("leap", w, d, List.of(set(dd -> dd.glow = 1), idle(10), set(dd -> { dd.crouch = 1; dd.fangs = 0.7; }), idle(10),
                    leap(0.48, 0.42), idle(20), set(dd -> dd.fangs = 0), idle(20)), new Vector3d(7.5, 2.0, 1.0)));
        }
        {   // it dies where it stands
            BlockWorld w = new BlockWorld().fill(-10, -1, -10, 10, -1, 10);
            Driver d = new Driver(w, spec, 0, 0, 0, 0);
            list.add(new Scene("death", w, d, List.of(set(dd -> dd.glow = 1), idle(20), set(dd -> dd.dead = true), idle(50)),
                    new Vector3d(5.4, 3.0, 4.2)));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------ running

    static void run(Scene s, LurkerRig rig, SoftRaster.Tex tex, SoftRaster.Tex glowTex, File dir, int fps) throws Exception {
        dir.mkdirs();
        for (File f : dir.listFiles()) if (f.getName().endsWith(".png")) f.delete();
        System.out.println(s.name + ":");
        LurkerBody body = new LurkerBody(rig.spec);
        LurkerPose pose = new LurkerPose(rig);
        TerrainPatch rock = new TerrainPatch();
        LurkerBody.Drive drive = new LurkerBody.Drive();
        int[] steps = new int[LurkerSpec.LEGS];
        body.setFootfalls((ft, running, speed) -> steps[ft.index]++);
        SoftRaster r = new SoftRaster(640, 400, 2);
        LurkerPose.Inputs in = new LurkerPose.Inputs();
        Vector3d cam = null;
        int frame = 0, ticks = 0, seg = 0, segT = 0, deadAt = -1;
        double worstMiss = 0;
        int missFrames = 0, kneesLow = 0, feetOff = 0, feetIn = 0, bodyNear = 0, planted = 0, plantedTicks = 0;
        double glow = 0;
        int per = Math.max(1, fps / 20);
        int[] bounds = new int[6];
        double[] fld = new double[4];
        while (seg < s.script.size()) {
            if (!s.script.get(seg).tick(s.driver, segT++)) { seg++; segT = 0; }
            s.driver.drive(drive);
            if (s.driver.dead && deadAt < 0) deadAt = ticks;
            body.bounds(drive, bounds);
            rock.build(s.world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            body.tick(rock, drive);
            ticks++;
            if (DEBUG && ticks % 10 == 0) {
                Vector3d c = body.centre(), uu = body.up(), ff = body.forward();
                System.out.printf("    t%d centre %s up %s fwd %s%s%n", ticks, fmt(c), fmt(uu), fmt(ff),
                        s.driver.crawl != null ? " crawl " + fmt(s.driver.crawl.pos) + " out " + String.format("%.2f", s.driver.crawl.facingOut()) : "");
            }
            glow += (s.driver.glow - glow) * 0.25;
            if (deadAt < 0) {
                for (LurkerBody.Foot ft : body.feet) {
                    if (ft.planted) {
                        planted++;
                        if (!rock.solid(ft.at.x - ft.nrm.x * 0.06, ft.at.y - ft.nrm.y * 0.06, ft.at.z - ft.nrm.z * 0.06)) feetOff++;
                    }
                    if (ft.swinging() && rock.solid(ft.at.x + ft.nrm.x * 0.05, ft.at.y + ft.nrm.y * 0.05, ft.at.z + ft.nrm.z * 0.05)) feetIn++;
                }
                plantedTicks++;
                Vector3d c = body.centre();
                if (rock.field(c.x, c.y, c.z, 2.0, 0.0, fld) && fld[0] < 0.35) bodyNear++;
            }
            for (int k = 0; k < per; k++) {
                double pt = (double) k / per;
                in.age = ticks + pt;
                in.dying = deadAt >= 0 ? Math.min(1.0, (ticks - deadAt + pt) / 20.0) : 0.0;
                pose.compute(body, pt, in, 0, 0, 0, null, 0xF000F0);
                if (in.dying == 0) {
                    for (int i = 0; i < LurkerSpec.LEGS; i++) {
                        LurkerBody.Foot ft = body.feet[i];
                        if (!ft.planted) continue;
                        worstMiss = Math.max(worstMiss, pose.reachMiss[i]);
                        if (pose.reachMiss[i] > 0.02) missFrames++;
                        if (pose.kneeAt[i].distanceSquared(pose.hipAt[i]) > 0 && new Vector3d(pose.kneeAt[i]).sub(pose.hipAt[i]).dot(pose.upNow) < 0) kneesLow++;
                    }
                }
                Vector3d mid = new Vector3d(), f = new Vector3d(), u = new Vector3d();
                body.frame(pt, mid, f, u);
                if (cam == null) cam = new Vector3d(mid);
                else cam.lerp(mid, s.driver.hurry ? 0.14 : 0.07);
                Vector3d off = new Vector3d(s.camOffset).mul(ZOOM);
                if (s.name.startsWith("orbit")) {
                    double a = Math.toRadians(ticks + pt) * 3.0;
                    off.set(off.x * Math.cos(a) - off.z * Math.sin(a), off.y, off.x * Math.sin(a) + off.z * Math.cos(a));
                }
                Vector3d eye = off.add(cam).add(s.aim);
                Vector3d look = new Vector3d(cam).add(s.aim);
                r.camera(eye, look, 55);
                r.clear(0x15131c);
                drawWorld(r, s.world, cam, s.cut);
                double level = in.dying > 0 ? glow * (1.0 - in.dying) : glow;
                drawPose(r, pose, tex, glowTex, level);
                r.save(new File(dir, String.format("f%05d.png", frame++)));
            }
        }
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < LurkerSpec.LEGS; i++) st.append(i == 0 ? "" : " ").append(steps[i]);
        System.out.printf("  %d ticks, steps [%s], planted %.1f of 12, worst reach miss %.3f, frames short %d, knees under hips %d,"
                        + " planted feet off rock %d, stepping feet in rock %d, body against rock %d%n",
                ticks, st, plantedTicks == 0 ? 0 : (double) planted / plantedTicks, worstMiss, missFrames, kneesLow, feetOff, feetIn, bodyNear);
        File mp4 = new File(dir.getParentFile(), s.name + ".mp4");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
                new File(dir, "f%05d.png").getPath(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", mp4.getPath())
                .inheritIO().start();
        p.waitFor();
    }

    static String fmt(Vector3d v) { return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z); }

    static void drawWorld(SoftRaster r, BlockWorld w, Vector3d near, Predicate<double[]> cut) {
        w.forEach(b -> {
            if (Math.abs(b[0] - near.x) > 12 || Math.abs(b[2] - near.z) > 12 || Math.abs(b[1] - near.y) > 10) return;
            if (cut != null && cut.test(b)) return;
            for (int f = 0; f < 6; f++) MossbackClips.face(r, w, b, f);
        });
    }

    static void drawPose(SoftRaster r, LurkerPose pose, SoftRaster.Tex tex, SoftRaster.Tex glow, double level) {
        List<double[][]> quads = new ArrayList<>();
        double[][] q = new double[4][5];
        double[] n = new double[3];
        int[] k = {0};
        pose.emit((x, y, z, u, v, nx, ny, nz, light) -> {
            int i = k[0]++;
            q[i][0] = x; q[i][1] = y; q[i][2] = z; q[i][3] = u; q[i][4] = v;
            n[0] = nx; n[1] = ny; n[2] = nz;
            if (k[0] == 4) {
                k[0] = 0;
                double[][] c = new double[4][];
                for (int j = 0; j < 4; j++) c[j] = q[j].clone();
                r.tri(c[0], c[1], c[2], uv(c[0]), uv(c[1]), uv(c[2]), tex, 0, n[0], n[1], n[2], 0);
                r.tri(c[0], c[2], c[3], uv(c[0]), uv(c[2]), uv(c[3]), tex, 0, n[0], n[1], n[2], 0);
                quads.add(c);
            }
        }, 0xF000F0, false);
        if (level <= 0.01) return;
        for (double[][] c : quads) {
            r.triAdd(c[0], c[1], c[2], uv(c[0]), uv(c[1]), uv(c[2]), glow, level);
            r.triAdd(c[0], c[2], c[3], uv(c[0]), uv(c[2]), uv(c[3]), glow, level);
        }
    }

    private static double[] uv(double[] c) { return new double[]{c[3], c[4]}; }
}
