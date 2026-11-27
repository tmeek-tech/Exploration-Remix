package com.explorationremix.creature.body;

import com.explorationremix.creature.EmberSpots;
import com.explorationremix.creature.EmberUrchinEntity;
import com.explorationremix.world.terrain.WastelandBlocks;
import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Clips of the ember urchin (0.23.7) walking made-up tunnels (straight, a junction, a low roof, a cave's floor), held by
 * someone standing in its way, flaring, dying, and walking the Wasteland's own tunnels worked out offline, drawn with its
 * real texture and glow in a dim cave and written as videos (and a few stills), to judge how it looks and moves. Each
 * clip prints how it went: how far it walked, how many of its spines were braced, its steps, any spine through the rock.
 * Runs only when asked: {@code ./gradlew test --tests '*EmberClips*' -PslowTests -Dember.clips=<dir>} (and
 * -Dember.only=tunnel,ahead, -Dember.seeds=8675309,3).
 */
@Tag("slow")
class EmberClips {
    static final double ZOOM = Double.parseDouble(System.getProperty("ember.zoom", "1.0"));
    static final boolean DEBUG = Boolean.getBoolean("ember.debug");
    static final int ROCK = WastelandBlocks.ROCK, LAVA = WastelandBlocks.LAVA;

    /** The blocks of a scene's world: their material ({@link WastelandBlocks#ROCK}, AIR, WATER, LAVA). */
    interface Blocks {
        int mat(int x, int y, int z);
    }

    enum Camera { SIDE, FOLLOW, FIXED }

    static final class Scene {
        final String name;
        final Blocks blocks;
        final Vector3d start;
        final double heading;
        Predicate<Vector3d> allowed;
        int ticks = 400;
        Camera camera = Camera.SIDE;
        Vector3d camOffset = new Vector3d(0, 1.0, 9.5);
        Vector3d fixedEye;
        Predicate<double[]> cut;
        /** Cut away the rock between the camera and it (a cross-section). */
        boolean cutNear;
        /** Someone standing here (their feet): in its way, it holds; its spines turn to them. */
        Vector3d blocker;
        /** What its spines turn to, if no one stands near. */
        Vector3d focus;
        int flareFrom = -1, flareTo = -1, dieAt = -1;
        boolean still;
        double dim = 0.55;

        Scene(String name, Blocks blocks, Vector3d start, double headingDeg) {
            this.name = name;
            this.blocks = blocks;
            this.start = start;
            this.heading = Math.toRadians(headingDeg);
        }
    }

    @Test
    void clips() throws Exception {
        String out = System.getProperty("ember.clips");
        Assumptions.assumeTrue(out != null, "no clip directory given");
        String only = System.getProperty("ember.only", "");
        int fps = Integer.getInteger("ember.fps", 20);
        File dir = new File(out);
        dir.mkdirs();
        String base = "src/main/resources/assets/explorationremix/textures/entity/ember_urchin/ember_urchin";
        SoftRaster.Tex tex = SoftRaster.Tex.load(new File(base + ".png"));
        SoftRaster.Tex glow = SoftRaster.Tex.load(new File(base + "_glow.png"));
        EmberRig rig = EmberRig.load(EmberSpec.GAME_SCALE);
        List<Scene> scenes = new ArrayList<>(scenes());
        for (String seed : System.getProperty("ember.seeds", "8675309").split(",")) {
            if (seed.isBlank()) continue;
            String name = "waste" + seed.trim();
            if (!only.isEmpty() && !List.of(only.split(",")).contains(name)) continue;
            Scene s = wasteland(Long.parseLong(seed.trim()), name);
            if (s != null) scenes.add(s);
        }
        // one of EmberWastelandTest's walks, by its name (waste<seed>-<n>)
        for (String name : only.split(",")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("waste(\\d+)-(\\d+)").matcher(name.trim());
            if (!m.matches()) continue;
            int n = Integer.parseInt(m.group(2));
            List<Scene> walks = wastelandWalks(Long.parseLong(m.group(1)), n + 1, EmberWastelandTest.TICKS, 24);
            if (walks.size() <= n) continue;
            Scene sc = walks.get(n);
            sc.ticks = Integer.getInteger("ember.ticks", sc.ticks);
            sc.camera = Camera.FOLLOW;
            sc.camOffset = new Vector3d(7.5, 1.2, 0);
            sc.cutNear = true;
            scenes.add(sc);
        }
        for (Scene s : scenes) {
            if (!only.isEmpty() && !List.of(only.split(",")).contains(s.name)) continue;
            run(s, rig, tex, glow, Boolean.parseBoolean(System.getProperty("ember.draw", "true")) ? new File(dir, s.name) : null, fps);
        }
    }

    // ------------------------------------------------------------------------------------------------ worlds

    /** Rock round open boxes (each {x0, y0, z0, x1, y1, z1}, inclusive). */
    static Blocks carved(int[]... open) {
        return (x, y, z) -> {
            for (int[] b : open) if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return WastelandBlocks.AIR;
            return ROCK;
        };
    }

    static List<Scene> scenes() {
        List<Scene> out = new ArrayList<>();
        int ticks = Integer.getInteger("ember.ticks", 400);
        // a straight tunnel five wide and six tall, seen from the side through its cut-away wall
        Scene tunnel = new Scene("tunnel", carved(new int[]{-40, 0, -2, 40, 5, 2}), new Vector3d(-12.5, 3.0, 0.5), 0);
        tunnel.ticks = ticks;
        tunnel.cut = b -> b[2] > 2.5;
        out.add(tunnel);
        // the same, seen as a player sees it coming down the tunnel at them: it holds, then turns back
        Scene ahead = new Scene("ahead", carved(new int[]{-40, 0, -2, 40, 5, 2}), new Vector3d(-8.5, 3.0, 0.5), 0);
        ahead.ticks = Math.max(ticks, 420);
        ahead.camera = Camera.FIXED;
        ahead.fixedEye = new Vector3d(1.5, 1.62, 0.9);
        ahead.blocker = new Vector3d(1.5, 0.0, 0.9);
        out.add(ahead);
        // a crossing of two tunnels, from above (its roof cut away)
        Scene cross = new Scene("cross", carved(new int[]{-30, 0, -2, 30, 5, 2}, new int[]{-2, 0, -30, 2, 5, 30}), new Vector3d(-10.5, 3.0, 0.5), 0);
        cross.ticks = Math.max(ticks, 600);
        cross.cut = b -> b[1] > 5.5;
        cross.camOffset = new Vector3d(0.01, 14, 3.0);
        out.add(cross);
        // a low stretch: the roof comes down to four blocks over the floor for eight blocks
        Scene low = new Scene("lowroof", carved(new int[]{-40, 0, -2, -5, 5, 2}, new int[]{-5, 0, -2, 3, 3, 2}, new int[]{3, 0, -2, 40, 5, 2}),
                new Vector3d(-14.5, 3.0, 0.5), 0);
        low.ticks = Math.max(ticks, 500);
        low.cut = b -> b[2] > 2.5;
        out.add(low);
        // a cave's floor, twenty across and ten tall: it walks on its lower spines
        Scene cave = new Scene("cave", carved(new int[]{-12, 0, -12, 12, 9, 12}), new Vector3d(-6.5, 2.6, -3.5), 30);
        cave.ticks = ticks;
        cave.cut = b -> b[2] > 12.5 || b[1] > 9.5;
        cave.camOffset = new Vector3d(0, 4.0, 11);
        out.add(cave);
        // standing in a tunnel as someone comes near: it flares, its coat bristles, its spines turn to them
        Scene flare = new Scene("flare", carved(new int[]{-20, 0, -2, 20, 5, 2}), new Vector3d(0.5, 3.0, 0.5), 0);
        flare.ticks = 260;
        flare.still = true;
        flare.cut = b -> b[2] > 2.5;
        flare.camOffset = new Vector3d(2.0, 0.6, 6.5);
        flare.focus = new Vector3d(6.0, 1.6, 0.5);
        flare.flareFrom = 60;
        flare.flareTo = 180;
        out.add(flare);
        // close up in a tunnel: its ball, plates and seams, its bosses and coat, calm then flared
        Scene portrait = new Scene("portrait", carved(new int[]{-20, 0, -2, 20, 5, 2}), new Vector3d(0.5, 3.0, 0.5), 0);
        portrait.ticks = 200;
        portrait.still = true;
        portrait.cut = b -> b[2] > 2.5;
        portrait.camOffset = new Vector3d(0.6, 0.5, 3.4);
        portrait.flareFrom = 100;
        portrait.flareTo = 200;
        out.add(portrait);
        // dying in a tunnel: its spines droop and it falls, its embers going out
        Scene dying = new Scene("dying", carved(new int[]{-20, 0, -2, 20, 5, 2}), new Vector3d(0.5, 3.0, 0.5), 0);
        dying.ticks = 80;
        dying.still = true;
        dying.dieAt = 30;
        dying.cut = b -> b[2] > 2.5;
        dying.camOffset = new Vector3d(0, 0.6, 7.5);
        out.add(dying);
        return out;
    }

    /** A walk through the Wasteland's own tunnels for {@code seed}, from a tunnel's floor near the middle of a square. */
    static Scene wasteland(long seed, String name) {
        int size = 192;
        int cx = Integer.getInteger("ember.x", 0), cz = Integer.getInteger("ember.z", 0);
        WastelandBlocks w = WastelandBlocks.build(seed, cx, cz, size, true);
        Predicate<Vector3d> allowed = p -> w.inLayer((int) Math.floor(p.y)) && !w.hollows((int) Math.floor(p.x), (int) Math.floor(p.z));
        EmberWalk.Ground g = ground(w::mat, allowed);
        int sheet = Integer.getInteger("ember.sheet", -1);
        for (int r = 0; r < 60; r += 3)
            for (int k = 0; k < Math.max(1, r * 2); k++) {
                double a = Math.PI * 2 * k / Math.max(1, r * 2);
                int x = cx + (int) Math.round(Math.cos(a) * r), z = cz + (int) Math.round(Math.sin(a) * r);
                for (EmberSpots.Spot s : EmberSpots.floors(g, x, z, w.top - 1, w.bottom + 1)) {
                    double width = EmberSpots.width(g, s.x(), s.y(), s.z());
                    if (width > 7.0) continue;
                    if (sheet >= 0 && w.sheet((int) s.x(), (int) s.y(), (int) s.z()) != sheet) continue;
                    Scene sc = new Scene(name, w::mat, new Vector3d(s.x(), s.y(), s.z()), Math.toDegrees(a) + 90);
                    sc.allowed = allowed;
                    sc.ticks = Integer.getInteger("ember.ticks", 1200);
                    sc.camera = Camera.FOLLOW;
                    sc.camOffset = new Vector3d(7.5, 1.2, 0);
                    sc.cutNear = true;
                    System.out.printf("%s: from %.1f %.1f %.1f (sheet %d, way %.1f wide)%n", name, s.x(), s.y(), s.z(),
                            w.sheet((int) s.x(), (int) s.y(), (int) s.z()), width);
                    return sc;
                }
            }
        return null;
    }

    /**
     * Walks of {@code ticks} through the Wasteland's own tunnels for {@code seed}: from up to {@code count} tunnel floors
     * (ways no wider than 7) spread over a square round the origin, each at least {@code apart} from the others, heading
     * round the square.
     */
    static List<Scene> wastelandWalks(long seed, int count, int ticks, int apart) {
        int size = 256;
        WastelandBlocks w = WastelandBlocks.build(seed, 0, 0, size, true);
        Predicate<Vector3d> allowed = p -> w.inLayer((int) Math.floor(p.y)) && !w.hollows((int) Math.floor(p.x), (int) Math.floor(p.z));
        EmberWalk.Ground g = ground(w::mat, allowed);
        List<Scene> out = new ArrayList<>();
        for (int r = 0; r < size / 2 - 40 && out.size() < count; r += 6)
            for (int k = 0; k < Math.max(1, r) && out.size() < count; k++) {
                double a = Math.PI * 2 * k / Math.max(1, r);
                int x = (int) Math.round(Math.cos(a) * r), z = (int) Math.round(Math.sin(a) * r);
                for (EmberSpots.Spot sp : EmberSpots.floors(g, x, z, w.top - 1, w.bottom + 1)) {
                    if (EmberSpots.width(g, sp.x(), sp.y(), sp.z()) > 7.0 || !EmberSpots.wayThrough(g, sp) || !EmberSpots.roams(g, sp)) continue;
                    Vector3d at = new Vector3d(sp.x(), sp.y(), sp.z());
                    if (out.stream().anyMatch(o -> o.start.distance(at) < apart)) continue;
                    Scene sc = new Scene("waste" + seed + "-" + out.size(), w::mat, at, Math.toDegrees(a) + 90);
                    sc.allowed = allowed;
                    sc.ticks = ticks;
                    out.add(sc);
                    break;
                }
            }
        return out;
    }

    static EmberWalk.Ground ground(Blocks b, Predicate<Vector3d> allowed) {
        return new EmberWalk.Ground() {
            @Override
            public boolean solid(int x, int y, int z) { return b.mat(x, y, z) == ROCK; }

            @Override
            public boolean allowed(double x, double y, double z) { return allowed == null || allowed.test(new Vector3d(x, y, z)); }
        };
    }

    // ------------------------------------------------------------------------------------------------ the run

    /** How a walk went. */
    static final class Stats {
        int ticks, live, held, rests, stuck, turnedBack, wayOns, steps, ballIn, outOfGround;
        /** Spine-ticks: all, with some of the bow in rock (planted, stepping, free), and stepping with its point in rock. */
        int legTicks, through, throughPlanted, throughSwinging, throughFree, swingTicks, pointIn;
        double went, lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE, planted;
        /** The furthest its middle got from where it started (blocks): pacing round a pocket keeps this small. */
        double farthest;

        double braced() { return planted / Math.max(1, live); }

        /** The share of spine-ticks with some of the bow in rock, and of stepping ticks with the point in rock. */
        double throughShare() { return through / (double) Math.max(1, legTicks); }

        double pointInShare() { return pointIn / (double) Math.max(1, swingTicks); }

        String line() {
            return String.format(Locale.ROOT, "%d ticks: walked %.1f, at most %.1f from its start (%.0f%% held, %.0f%% resting, stuck %d, turned back %d, wound on %d), height %.2f to %.2f,"
                            + " %.1f of 30 braced, %d steps, bows through rock %.2f%% of spine-ticks (%d planted, %d stepping, %d free),"
                            + " stepping points in rock %.2f%% of stepping ticks (%d), ball in rock %d, out of its ground %d",
                    ticks, went, farthest, 100.0 * held / ticks, 100.0 * rests / ticks, stuck, turnedBack, wayOns, lowest, highest, braced(), steps,
                    100.0 * throughShare(), throughPlanted, throughSwinging, throughFree, 100.0 * pointInShare(), pointIn, ballIn, outOfGround);
        }
    }

    /** Walks a scene, drawing it into {@code dir} (a video and stills) unless that is null. */
    static Stats run(Scene s, EmberRig rig, SoftRaster.Tex tex, SoftRaster.Tex glowTex, File dir, int fps) throws Exception {
        boolean draw = dir != null;
        if (draw) {
            dir.mkdirs();
            for (File f : dir.listFiles()) if (f.getName().endsWith(".png")) f.delete();
        }
        System.out.println(s.name + ":");
        EmberSpec spec = rig.spec;
        EmberBody body = new EmberBody(spec);
        body.setSeed(s.name.hashCode());
        EmberPose pose = new EmberPose(rig);
        EmberRock rock = new EmberRock();
        EmberBody.Drive drive = new EmberBody.Drive();
        EmberWalk walk = new EmberWalk();
        walk.setSeed(s.name.hashCode() * 31L);
        walk.face(Math.cos(s.heading), Math.sin(s.heading));
        EmberWalk.Ground g = ground(s.blocks, s.allowed);
        Terrain terrain = (x, y, z, sink) -> { if (s.blocks.mat(x, y, z) == ROCK) sink.box(x, y, z, x + 1, y + 1, z + 1); };
        int[] steps = {0};
        body.setFootfalls((leg, speed) -> steps[0]++);
        SoftRaster r = draw ? new SoftRaster(640, 400, 2) : null;
        if (r != null) r.dim = s.dim;
        EmberPose.Inputs in = new EmberPose.Inputs();
        EmberUrchinEntity.Embers embers = new EmberUrchinEntity.Embers();
        Vector3d mid = new Vector3d(s.start), cam = null;
        double flare = 0, flareO = 0;
        int frame = 0;
        Stats st = new Stats();
        st.ticks = s.ticks;
        st.live = s.dieAt >= 0 ? s.dieAt : s.ticks;
        int per = Math.max(1, fps / 20);
        EmberBody.Bow bow = new EmberBody.Bow();
        Vector3d p = new Vector3d();
        List<Vector3d> none = List.of();
        List<Integer> keys = List.of(s.ticks / 4, s.ticks / 2, 3 * s.ticks / 4);
        for (int tick = 0; tick < s.ticks; tick++) {
            boolean dead = s.dieAt >= 0 && tick >= s.dieAt;
            // someone in its way ahead (as the entity judges it)
            boolean blocked = false;
            if (s.blocker != null) {
                double dx = s.blocker.x - mid.x, dz = s.blocker.z - mid.z, d = Math.sqrt(dx * dx + dz * dz);
                blocked = d - 0.3 <= spec.span() + 0.6 && (d < 1.0E-3 || (dx * walk.heading.x + dz * walk.heading.z) / d > 0.3);
            }
            if (!dead && !s.still) {
                Vector3d v = walk.tick(g, mid, none, blocked);
                // its box (its ball) never goes into the rock, sliding along it as the game's own collision does (axis by axis)
                double vx = v.x, vy = v.y, vz = v.z;
                if (!EmberWalk.clear(g, mid.x, mid.y + vy, mid.z, spec.radius)) vy = 0;
                if (!EmberWalk.clear(g, mid.x + vx, mid.y + vy, mid.z, spec.radius)) vx = 0;
                if (!EmberWalk.clear(g, mid.x + vx, mid.y + vy, mid.z + vz, spec.radius)) vz = 0;
                mid.add(vx, vy, vz);
                st.went += Math.sqrt(vx * vx + vy * vy + vz * vz);
                if (walk.state == EmberWalk.State.HOLD) st.held++;
                if (walk.state == EmberWalk.State.REST) st.rests++;
                if (s.allowed != null && !s.allowed.test(mid)) st.outOfGround++;
            }
            if (dead && mid.y > s.start.y - 2.0) mid.y -= Math.min(0.3, 0.02 * (tick - s.dieAt + 1));
            st.farthest = Math.max(st.farthest, mid.distance(s.start));
            st.lowest = Math.min(st.lowest, mid.y);
            st.highest = Math.max(st.highest, mid.y);
            boolean flaring = !dead && ((tick >= s.flareFrom && tick < s.flareTo)
                    || (s.blocker != null && s.blocker.distance(mid) < EmberUrchinEntity.FLARE_NEAR));
            flareO = flare;
            flare += ((flaring ? 1.0 : 0.0) - flare) * (flaring ? 0.3 : 0.035);
            if (dead) flare *= 0.9;
            drive.x = mid.x; drive.y = mid.y; drive.z = mid.z;
            drive.mode = dead ? EmberBody.Mode.DEAD : EmberBody.Mode.LIVE;
            drive.flare = flare;
            Vector3d looked = s.blocker != null ? new Vector3d(s.blocker).add(0, 1.6, 0) : s.focus;
            if (looked != null && looked.distance(mid) < EmberUrchinEntity.NOTICE) { drive.focus.set(looked); drive.focusAmount = 1.0; }
            else drive.focusAmount = 0.0;
            TerrainPatch patch = rock.around(body, drive, terrain);
            body.tick(patch, drive);
            if (!dead) {
                st.planted += body.plantedCount();
                if (!EmberWalk.clear(g, mid.x, mid.y, mid.z, spec.radius * 0.95)) st.ballIn++;
                for (EmberBody.Leg lg : body.legs) {
                    st.legTicks++;
                    body.bowOf(lg.index, bow);
                    for (int k = 2; k < 11; k++) {
                        bow.point(bow.length * k / 12.0, p);
                        if (patch.solid(p.x, p.y, p.z)) {
                            st.through++;
                            if (lg.swinging()) st.throughSwinging++;
                            else if (lg.planted) st.throughPlanted++;
                            else st.throughFree++;
                            if (DEBUG && tick % 20 == 0) System.out.printf("      t%d spine %d through rock at %s (%s)%n", tick, lg.index, fmt(p),
                                    lg.swinging() ? "stepping" : lg.planted ? "planted" : "free");
                            break;
                        }
                    }
                    if (lg.swinging()) {
                        st.swingTicks++;
                        if (patch.solid(lg.at.x + lg.nrm.x * 0.05, lg.at.y + lg.nrm.y * 0.05, lg.at.z + lg.nrm.z * 0.05)) st.pointIn++;
                    }
                }
            }
            if (DEBUG && tick % 20 == 0)
                System.out.printf("    t%d %s planted %d swinging %d: %s%n", tick, fmt(mid), body.plantedCount(), body.swingingCount(), walk.describe());
            for (int k = 0; draw && k < per; k++) {
                double pt = (double) k / per;
                in.age = tick + pt;
                in.breath = dead ? -1.0 : Math.sin((tick + pt) / EmberUrchinEntity.BREATH * Math.PI * 2.0);
                pose.compute(body, pt, in, 0, 0, 0, null, 0xF000F0);
                Vector3d c = new Vector3d();
                body.centre(pt, c);
                if (cam == null) cam = new Vector3d(c);
                else cam.lerp(c, 0.08);
                Vector3d eye;
                if (s.camera == Camera.FIXED) eye = new Vector3d(s.fixedEye);
                else if (s.camera == Camera.FOLLOW) {
                    // to the side of its way, a little behind
                    Vector3d h = walk.heading, side = new Vector3d(-h.z, 0, h.x);
                    eye = new Vector3d(cam).fma(s.camOffset.x * ZOOM, side).fma(-2.0 * ZOOM, h).add(0, s.camOffset.y * ZOOM, 0);
                } else eye = new Vector3d(s.camOffset).mul(ZOOM).add(cam);
                r.camera(eye, cam, 60);
                r.clear(0x0d0c0e);
                Vector3d toCam = new Vector3d(eye).sub(cam).normalize();
                Vector3d camAt = new Vector3d(cam);
                Predicate<double[]> cut = s.cut;
                if (s.cutNear) cut = b -> (b[0] - camAt.x) * toCam.x + (b[1] - camAt.y) * toCam.y + (b[2] - camAt.z) * toCam.z > 1.6;
                float fl = (float) (flareO + (flare - flareO) * pt);
                drawWorld(r, s.blocks, cam, cut, c, dead ? 0 : (fl > 0.5 ? EmberUrchinEntity.LIGHT_FLARED : EmberUrchinEntity.LIGHT_CALM));
                embers.time = tick + pt;
                embers.flare = fl;
                double level = 0.82 + 0.18 * fl;
                if (dead) level *= Math.max(0.0, 1.0 - (tick - s.dieAt + pt) / 16.0);
                drawPose(r, pose, tex, glowTex, level, fl, embers);
                if (s.blocker != null) r.dot(new double[]{s.blocker.x, s.blocker.y + 1.0, s.blocker.z}, 0x3070ff, 3);
                File f = new File(dir, String.format("f%05d.png", frame++));
                r.save(f);
                if (k == 0 && keys.contains(tick))
                    Files.copy(f.toPath(), new File(dir.getParentFile(), s.name + "-t" + tick + ".png").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        st.stuck = walk.stuckCount;
        st.turnedBack = walk.turnedBack;
        st.wayOns = walk.wayOns;
        st.steps = steps[0];
        System.out.println("  " + st.line());
        if (draw) {
            File mp4 = new File(dir.getParentFile(), s.name + ".mp4");
            Process proc = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
                    new File(dir, "f%05d.png").getPath(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", mp4.getPath())
                    .inheritIO().start();
            proc.waitFor();
        }
        return st;
    }

    static String fmt(Vector3d v) { return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z); }

    // ------------------------------------------------------------------------------------------------ drawing

    /** The rock's faces open to the air round {@code near} (and lava's top), but what {@code cut} takes away. */
    static void drawWorld(SoftRaster r, Blocks w, Vector3d near, Predicate<double[]> cut) { drawWorld(r, w, near, cut, near, 0); }

    /** And lit by a light of {@code light} (block light's taxicab fall-off) at {@code lit}. */
    static void drawWorld(SoftRaster r, Blocks w, Vector3d near, Predicate<double[]> cut, Vector3d lit, int light) {
        int cx = (int) Math.floor(near.x), cy = (int) Math.floor(near.y), cz = (int) Math.floor(near.z);
        double[] b = new double[6];
        for (int x = cx - 14; x <= cx + 14; x++)
            for (int y = cy - 8; y <= cy + 9; y++)
                for (int z = cz - 14; z <= cz + 14; z++) {
                    int m = w.mat(x, y, z);
                    b[0] = x; b[1] = y; b[2] = z; b[3] = x + 1; b[4] = y + 1; b[5] = z + 1;
                    double[] centre = {x + 0.5, y + 0.5, z + 0.5};
                    if (m == LAVA) {
                        if (w.mat(x, y + 1, z) == LAVA || (cut != null && cut.test(centre))) continue;
                        double top = y + 0.88;
                        double[] a = {x, top, z}, bb = {x + 1, top, z}, c = {x + 1, top, z + 1}, d = {x, top, z + 1};
                        int col = ((x + z) & 1) == 0 ? 0xff6a10 : 0xff8424;
                        r.tri(a, bb, c, null, null, null, null, col, 0, 1, 0, 1.0);
                        r.tri(a, c, d, null, null, null, null, col, 0, 1, 0, 1.0);
                        continue;
                    }
                    if (m != ROCK || (cut != null && cut.test(centre))) continue;
                    for (int f = 0; f < 6; f++) {
                        int axis = f / 2;
                        boolean hi = f % 2 == 1;
                        int nx = x + (axis == 0 ? (hi ? 1 : -1) : 0), ny = y + (axis == 1 ? (hi ? 1 : -1) : 0), nz = z + (axis == 2 ? (hi ? 1 : -1) : 0);
                        if (w.mat(nx, ny, nz) == ROCK) continue;
                        // lit a little by the urchin's own light (as its light block lights the rock in the game)
                        double d = Math.abs(nx + 0.5 - lit.x) + Math.abs(ny + 0.5 - lit.y) + Math.abs(nz + 0.5 - lit.z);
                        face(r, x, y, z, axis, hi, Math.max(0.0, (light - d) / 15.0));
                    }
                }
    }

    private static void face(SoftRaster r, int x, int y, int z, int axis, boolean hi, double lit) {
        double plane = (axis == 0 ? x : axis == 1 ? y : z) + (hi ? 1 : 0);
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        int[] at = {x, y, z};
        double s0 = at[a1], u0 = at[a2];
        boolean dark = ((x * 7 + y * 13 + z * 5) & 3) == 0;
        int base = axis == 1 ? (dark ? 0x3a3c42 : 0x45474e) : (dark ? 0x323439 : 0x3b3d43);
        if (lit > 0) {
            // warm block light over the grey
            int rr = (base >> 16) & 255, gg = (base >> 8) & 255, bb = base & 255;
            rr = (int) Math.min(255, rr * (1 + 2.2 * lit)); gg = (int) Math.min(255, gg * (1 + 1.5 * lit)); bb = (int) Math.min(255, bb * (1 + 0.8 * lit));
            base = (rr << 16) | (gg << 8) | bb;
        }
        double[][] q = new double[4][3];
        double[][] st = {{s0, u0}, {s0 + 1, u0}, {s0 + 1, u0 + 1}, {s0, u0 + 1}};
        for (int i = 0; i < 4; i++) { q[i][axis] = plane; q[i][a1] = st[i][0]; q[i][a2] = st[i][1]; }
        double nx = axis == 0 ? (hi ? 1 : -1) : 0, ny = axis == 1 ? (hi ? 1 : -1) : 0, nz = axis == 2 ? (hi ? 1 : -1) : 0;
        r.tri(q[0], q[1], q[2], null, null, null, null, base, nx, ny, nz, 0);
        r.tri(q[0], q[2], q[3], null, null, null, null, base, nx, ny, nz, 0);
    }

    /** Its quads with its texture, then its embers added over them as the game's eyes layer adds them (and again, flared). */
    static void drawPose(SoftRaster r, EmberPose pose, SoftRaster.Tex tex, SoftRaster.Tex glow, double level, double flare, EmberPose.Shade shade) {
        double[][] q = new double[4][5];
        double[] n = new double[3];
        int[] k = {0};
        pose.emit((x, y, z, u, v, nx, ny, nz, light) -> {
            int i = k[0]++;
            q[i][0] = x; q[i][1] = y; q[i][2] = z; q[i][3] = u; q[i][4] = v;
            n[0] += nx; n[1] += ny; n[2] += nz;
            if (k[0] == 4) {
                k[0] = 0;
                r.tri(q[0], q[1], q[2], uv(q[0]), uv(q[1]), uv(q[2]), tex, 0, n[0], n[1], n[2], 0);
                r.tri(q[0], q[2], q[3], uv(q[0]), uv(q[2]), uv(q[3]), tex, 0, n[0], n[1], n[2], 0);
                n[0] = n[1] = n[2] = 0;
            }
        }, 0xF000F0, false);
        for (int pass = 0; pass < 2; pass++) {
            double lv0 = pass == 0 ? level : flare * 0.4;
            if (lv0 <= 0.01) continue;
            float[] sh = new float[4];
            pose.emitGlow((x, y, z, u, v, nx, ny, nz, s) -> {
                int i = k[0]++;
                q[i][0] = x; q[i][1] = y; q[i][2] = z; q[i][3] = u; q[i][4] = v;
                sh[i] = s;
                if (k[0] == 4) {
                    k[0] = 0;
                    double lv = Math.min(1.0, lv0 * (sh[0] + sh[1] + sh[2] + sh[3]) / 4.0);
                    if (lv <= 0.005) return;
                    r.triAdd(q[0], q[1], q[2], uv(q[0]), uv(q[1]), uv(q[2]), glow, lv);
                    r.triAdd(q[0], q[2], q[3], uv(q[0]), uv(q[2]), uv(q[3]), glow, lv);
                }
            }, shade);
        }
    }

    private static double[] uv(double[] c) { return new double[]{c[3], c[4]}; }
}
