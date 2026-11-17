package com.explorationremix.creature.body;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves a Mossback's box the way the game's server does and shows it to the body the way the game's client sees it,
 * so a clip or test sees just what a player would.
 *
 * <p>Server: a path of ClimbNodeEvaluator's nodes (the 2 x 1 x 2 cells its box fills, walked as floor, wall or
 * ceiling), found and followed as the game's path finder and ClimbNavigation do; ClimbMoveControl's steering (straight
 * at the next waypoint, straight up while the way across is blocked, a way out when wedged); vanilla collision with its
 * step up of a block; MossbackEntity's surface tracking, its press against a wall, gravity and friction.
 *
 * <p>Client: the entity tracker's updates (every 3 ticks, and at once when the rock it clings to or its heading
 * changes, or it lands or leaves the floor), each eased to over 3 ticks as the client does; its body turned to its
 * heading while it moves; MossbackEntity's own orientation, step rule and body modes.
 */
class GameDriver extends ClipDriver {
    /** MossbackEntity.SPEED (blocks a tick at a speed modifier of 1) and its roaming and hunting modifiers. */
    static final double SPEED = 0.2, ROAM = 0.5, CHASE = 1.2;
    private static final double W = 1.4, H = 0.9;
    /** The entity tracker's update interval for the Mossback (EntityType's default). */
    private static final int UPDATE_INTERVAL = 3;
    /** Direction's 3D data values, and the way into the rock on each side. */
    static final int NONE = -1, DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int[][] NORMAL = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final int[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};
    /** MossbackEntity's: dropping faster than this it falls rather than comes down a step; off the rock longer than this its body lets go. */
    private static final double STEP_FALL = 0.25;
    private static final int BODY_AIR_GRACE = 2;

    // ------------------------------------------------------------------------------------------------ server
    /** Its position (the bottom middle of its box), last tick's, and its motion. */
    double x, y, z, xo, yo, zo, vx, vy, vz;
    boolean onGround = true, noGravity;
    int surface = DOWN, airTicks;
    double airDist;
    float yRot;
    final Vector3d forward = new Vector3d();
    final double modifier;
    private final List<int[]> path = new ArrayList<>();
    private int next;
    private boolean moveTo;
    private double wantX, wantY, wantZ, lastX, lastY, lastZ, lastStep;
    private int wedged;
    private final Map<Long, Byte> kinds = new HashMap<>();

    // ------------------------------------------------------------------------------------------------ tracker
    private int tracked;
    private double baseX, baseY, baseZ;
    private int yRotp;
    private boolean wasOnGround = true, dirty;
    private final Vector3d syncedForward = new Vector3d();

    // ------------------------------------------------------------------------------------------------ client
    /** The client's position of the box, last tick's, and where it eases to. */
    double cx, cy, cz, cxo, cyo, czo;
    private double lerpX, lerpY, lerpZ, lerpYRot;
    private int lerpSteps;
    private float cYRot, cYBodyRot;
    int cSurface = DOWN;
    private final Vector3d cForward = new Vector3d(), cLead = new Vector3d();
    private boolean oriented, atStep;
    private int bodyAir;
    /** Ticks run, and of them the ticks it was airborne (off the rock long enough that it falls, steering only a little). */
    int ticks, airborneTicks;
    /** The farthest it ever fell once airborne: from the highest it got while airborne to where it next touched rock. */
    double maxFall;
    private boolean airborne;
    private double airPeak;

    /** A box standing at (x, y, z) (the bottom middle of its box) heading along (hx, hz), at a speed modifier. */
    GameDriver(BlockWorld world, double x, double y, double z, double hx, double hz, double modifier) {
        super(world, x, y, z, hx, hz);
        this.modifier = modifier;
        this.x = xo = cx = cxo = baseX = x;
        this.y = yo = cy = cyo = baseY = y;
        this.z = zo = cz = czo = baseZ = z;
        yRot = cYRot = cYBodyRot = (float) Math.toDegrees(Math.atan2(-hx, hz));
        yRotp = rotByte(yRot);
        forward.set(heading);
        syncedForward.set(forward);
        cForward.set(forward);
        hurry = modifier > 1.0;
    }

    /** Finds its way to (gx, gy, gz) as the game's path finder does, and sets off along it. */
    GameDriver pathTo(double gx, double gy, double gz) {
        path.clear();
        path.addAll(PathFinder.find(this, start(), new int[]{(int) Math.floor(gx) - 1, (int) Math.floor(gy), (int) Math.floor(gz) - 1}));
        next = 0;
        return this;
    }

    /** Its path is walked (or it has none). */
    boolean arrived() { return next >= path.size(); }

    /** One game tick: the server moves it, the tracker sends it on, the client eases to it. */
    void tick() {
        serverTick();
        track();
        clientTick();
        ticks++;
        if (trace) System.out.printf("t%3d srv (%.3f %.3f %.3f) v (%.3f %.3f %.3f) %s%s  cli (%.3f %.3f %.3f) %s%s%s%n", ticks, x, y, z, vx, vy, vz,
                side(surface), onGround ? " ground" : "", cx, cy, cz, side(cSurface), atStep ? " step" : "", bodyAir > 2 ? " air" : "");
    }

    /** Whether the waypoint of a node lifted by a slab is on top of it, as ClimbNavigation now has it (false: as 0.6 to 0.14 had it). */
    static boolean LIFT = true;
    /** Whether it walks to a wall's foot before climbing it from the floor, as ClimbNavigation now has it (false: as 0.6 to 0.14 had it). */
    static boolean FOOT_FIRST = true;
    /** Whether that also holds while it tops a step or slab at the foot, touching nothing, its path just off a floor. */
    static boolean FROM_FLOOR = true;
    /** Whether a floor node's waypoint stands down on slabs under its cells, as ClimbNavigation now has it. */
    static boolean SINK = true;
    /** Whether a level diagonal step is left out where rock stands at both corners it sweeps past, as ClimbNodeEvaluator now has it. */
    static boolean CORNERS = true;
    /** Prints each tick, server and client (for looking into a scene). */
    static boolean trace = Boolean.getBoolean("mossback.trace");

    private static String side(int s) {
        return switch (s) { case DOWN -> "DOWN"; case UP -> "UP"; case NORTH -> "NORTH"; case SOUTH -> "SOUTH"; case WEST -> "WEST"; case EAST -> "EAST"; default -> "none"; };
    }

    // ------------------------------------------------------------------------------------------------ the server's tick

    private void serverTick() {
        xo = x; yo = y; zo = z;
        // LivingEntity.aiStep: motion too small to matter stops
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        navigate();
        updateSurface();
        steer();
        travel();
        if (airborne) airPeak = Math.max(airPeak, y);
        // MossbackEntity.tick, after its move
        if (airTicks > 0) airDist += Math.sqrt(sq(x - xo) + sq(y - yo) + sq(z - zo));
        updateForward();
    }

    /** ClimbNavigation.tick: on to the next waypoint once close to this one in all three directions, and steer for it. */
    private void navigate() {
        if (arrived()) return;
        double[] w = waypoint(next);
        if (Math.abs(x - w[0]) < W / 2 && Math.abs(z - w[2]) < W / 2 && Math.abs(y - w[1]) < 0.75) next++;
        if (arrived()) return;
        w = waypoint(next);
        // a wall climbed from the floor: to the wall's foot first, then up it
        int[] n = path.get(next);
        boolean leftFloor = FROM_FLOOR && next > 0 && kind(path.get(next - 1)[0], path.get(next - 1)[1], path.get(next - 1)[2]) == PathFinder.FLOOR;
        if (FOOT_FIRST && w[1] > y + 0.5 && (surface == DOWN || leftFloor) && kind(n[0], n[1], n[2]) == PathFinder.WALL
                && sq(w[0] - x) + sq(w[2] - z) > 0.05 * 0.05)
            w[1] = y;
        moveTo = true;
        wantX = w[0]; wantY = w[1]; wantZ = w[2];
    }

    /** The box's position at a path node (node + 1 across), pressed against the rock of a wall or ceiling node. */
    private double[] waypoint(int i) {
        int[] n = path.get(i);
        double ax = n[0] + 1.0, az = n[2] + 1.0;
        byte k = kind(n[0], n[1], n[2]);
        double gap = 1.0 - W / 2;
        if (k == PathFinder.CEILING) return new double[]{ax, n[1] + 1.0 - H, az};
        if (k == PathFinder.FLOOR) {
            double lift = LIFT ? PathFinder.lift(world, n[0], n[1], n[2]) : 0.0;
            if (lift > 0.0) return new double[]{ax, n[1] + lift, az};
            double top = SINK ? PathFinder.support(world, n[0], n[1], n[2]) : 1.0;
            return new double[]{ax, top > 0.0 && top < 1.0 ? n[1] - 1.0 + top : n[1], az};
        }
        if (k != PathFinder.WALL) return new double[]{ax, n[1], az};
        if (solidColumn(n[0] - 1, n[1], n[2], 0, 1)) ax -= gap;
        else if (solidColumn(n[0] + 2, n[1], n[2], 0, 1)) ax += gap;
        if (solidColumn(n[0], n[1], n[2] - 1, 1, 0)) az -= gap;
        else if (solidColumn(n[0], n[1], n[2] + 2, 1, 0)) az += gap;
        return new double[]{ax, n[1], az};
    }

    private boolean solidColumn(int x, int y, int z, int sx, int sz) {
        for (int i = 0; i < 2; i++) if (world.has(x + sx * i, y, z + sz * i)) return true;
        return false;
    }

    /** MossbackEntity.updateSurface: the floor when it touches one, else the wall or ceiling it was on, else any wall, else the ceiling. */
    private void updateSurface() {
        int was = surface, now;
        if (onGround || touching(DOWN)) now = DOWN;
        else if (was != NONE && was != DOWN && touching(was)) now = was;
        else {
            int best = NONE;
            for (int d : HORIZONTAL) {
                if (!touching(d)) continue;
                if (best == NONE || dot(NORMAL[d]) > dot(NORMAL[best])) best = d;
            }
            now = best != NONE ? best : touching(UP) ? UP : NONE;
        }
        if (now != surface) dirty = true;
        surface = now;
        if (now == NONE) airTicks++;
        else { airTicks = 0; airDist = 0; }
        if (now != NONE && airborne) {
            maxFall = Math.max(maxFall, airPeak - y);
            airborne = false;
        }
        noGravity = now != NONE && now != DOWN;
    }

    private double dot(int[] n) { return vx * n[0] + vy * n[1] + vz * n[2]; }

    private boolean touching(int d) {
        double e = 0.08, m = 0.06, x0 = x - W / 2, x1 = x + W / 2, z0 = z - W / 2, z1 = z + W / 2, y0 = y, y1 = y + H;
        return switch (d) {
            case DOWN -> collides(x0 + m, y0 - e, z0 + m, x1 - m, y0, z1 - m);
            case UP -> collides(x0 + m, y1, z0 + m, x1 - m, y1 + e, z1 - m);
            case WEST -> collides(x0 - e, y0 + 0.02, z0 + m, x0, y1 - 0.02, z1 - m);
            case EAST -> collides(x1, y0 + 0.02, z0 + m, x1 + e, y1 - 0.02, z1 - m);
            case NORTH -> collides(x0 + m, y0 + 0.02, z0 - e, x1 - m, y1 - 0.02, z0);
            default -> collides(x0 + m, y0 + 0.02, z1, x1 - m, y1 - 0.02, z1 + e);
        };
    }

    /** ClimbMoveControl.tick. */
    private void steer() {
        if (!moveTo) {
            lastStep = 0;
            wedged = 0;
            // on a wall or ceiling with nowhere to go it holds still
            if (surface != NONE && surface != DOWN) vx = vy = vz = 0;
            return;
        }
        moveTo = false;
        double dx = wantX - x, dy = wantY - y, dz = wantZ - z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double moved = Math.sqrt(sq(x - lastX) + sq(y - lastY) + sq(z - lastZ));
        wedged = lastStep > 0.02 && moved < 0.2 * lastStep ? wedged + 1 : 0;
        lastX = x; lastY = y; lastZ = z; lastStep = 0;
        if (len < 1.0E-3) return;
        double speed = modifier * SPEED;
        boolean floor = surface == DOWN && Math.abs(dy) < 0.6;
        if (!floor) speed *= 0.85;
        double step = Math.min(speed, len);
        lastStep = step;
        double ux = exact(dx, dx / len * step), uy = exact(dy, dy / len * step), uz = exact(dz, dz / len * step);
        if (airDist > 1.5 || airTicks > 40) {
            airborneTicks++;
            if (!airborne) { airborne = true; airPeak = y; }
            vx = ux * 0.5;
            vz = uz * 0.5;
            turn(dx, dz);
            return;
        }
        if (dy > -0.1 && ux * ux + uz * uz > 1.0E-6
                && collides(x - W / 2 + ux + 1.0E-4, y + 1.0E-4, z - W / 2 + uz + 1.0E-4, x + W / 2 + ux - 1.0E-4, y + H - 1.0E-4, z + W / 2 + uz - 1.0E-4)) {
            ux *= 0.1;
            uy = speed;
            uz *= 0.1;
        } else if (floor) uy = vy;
        if (wedged >= 6) {
            double s = Math.max(0.12, step), bestDot = -Double.MAX_VALUE;
            double[] best = null;
            for (double[] t : new double[][]{{0, s, 0}, {s, 0, 0}, {-s, 0, 0}, {0, 0, s}, {0, 0, -s}}) {
                if (collides(x - W / 2 + t[0], y + t[1], z - W / 2 + t[2], x + W / 2 + t[0], y + H + t[1], z + W / 2 + t[2])) continue;
                double d = t[0] * ux + t[1] * uy + t[2] * uz;
                if (d > bestDot) { bestDot = d; best = t; }
            }
            if (best != null) { ux = best[0]; uy = best[1]; uz = best[2]; }
            wedged = 3;
        }
        vx = ux; vy = uy; vz = uz;
        turn(dx, dz);
    }

    private static double exact(double d, double share) {
        return Math.abs(d) < 0.1 && Math.abs(d) > Math.abs(share) ? d : share;
    }

    private void turn(double dx, double dz) {
        if (dx * dx + dz * dz <= 1.0E-4) return;
        float want = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float f = wrap(want - yRot);
        f = Math.max(-25.0F, Math.min(25.0F, f));
        float r = yRot + f;
        if (r < 0) r += 360.0F;
        else if (r > 360.0F) r -= 360.0F;
        yRot = r;
    }

    /** MossbackEntity.travel and LivingEntity.travel: pressed into a wall or ceiling, moved, then gravity and friction. */
    private void travel() {
        if (surface != NONE && surface != DOWN) {
            int[] n = NORMAL[surface];
            vx += n[0] * 0.04; vy += n[1] * 0.04; vz += n[2] * 0.04;
        }
        double friction = onGround ? 0.6F * 0.91F : 0.91F;
        move(vx, vy, vz);
        double fall = noGravity ? vy : vy - 0.08;
        vx *= friction;
        vy = fall * 0.98F;
        vz *= friction;
    }

    /** MossbackEntity.updateForward: the way it moved along its rock. */
    private void updateForward() {
        int[] n = surface == NONE ? NORMAL[DOWN] : NORMAL[surface];
        Vector3d up = new Vector3d(-n[0], -n[1], -n[2]);
        Vector3d mv = new Vector3d(x - xo, y - yo, z - zo);
        Vector3d along = new Vector3d(mv).fma(-mv.dot(up), up);
        if (along.lengthSquared() > 1.0E-4) {
            along.normalize();
            if (forward.dot(along) < -0.99) forward.add(new Vector3d(up).cross(forward).mul(0.3));
            forward.set(along);
        }
        Vector3d f = new Vector3d(forward).fma(-forward.dot(up), up);
        if (f.lengthSquared() > 1.0E-3) forward.set(f).normalize();
        else forward.set(dir(yRot));
        if (syncedForward.dot(forward) < 0.995) {
            syncedForward.set((float) forward.x, (float) forward.y, (float) forward.z);
            dirty = true;
        }
    }

    // ------------------------------------------------------------------------------------------------ collision

    /** Entity.move: collided along y, then the larger of x and z; stepping up as much as a block when it stands on rock. */
    private void move(double mx, double my, double mz) {
        double[] bb = {x - W / 2, y, z - W / 2, x + W / 2, y + H, z + W / 2};
        List<double[]> near = boxes(Math.min(bb[0], bb[0] + mx) - 1, Math.min(bb[1], bb[1] + my) - 1, Math.min(bb[2], bb[2] + mz) - 1,
                Math.max(bb[3], bb[3] + mx) + 1, Math.max(bb[4], bb[4] + my) + 2, Math.max(bb[5], bb[5] + mz) + 1);
        double[] r = collide(near, bb, mx, my, mz);
        boolean bx = r[0] != mx, by = r[1] != my, bz = r[2] != mz;
        if ((onGround || by && my < 0) && (bx || bz)) {
            double up = 1.0;
            double[] a = collide(near, bb, mx, up, mz);
            double[] lifted = collide(near, new double[]{Math.min(bb[0], bb[0] + mx), bb[1], Math.min(bb[2], bb[2] + mz),
                    Math.max(bb[3], bb[3] + mx), bb[4], Math.max(bb[5], bb[5] + mz)}, 0, up, 0);
            if (lifted[1] < up) {
                double[] b = collide(near, shift(bb, 0, lifted[1], 0), mx, 0, mz);
                b[1] += lifted[1];
                if (b[0] * b[0] + b[2] * b[2] > a[0] * a[0] + a[2] * a[2]) a = b;
            }
            if (a[0] * a[0] + a[2] * a[2] > r[0] * r[0] + r[2] * r[2]) {
                double down = collide(near, shift(bb, a[0], a[1], a[2]), 0, -a[1] + my, 0)[1];
                r = new double[]{a[0], a[1] + down, a[2]};
            }
        }
        if (r[0] * r[0] + r[1] * r[1] + r[2] * r[2] > 1.0E-7) { x += r[0]; y += r[1]; z += r[2]; }
        boolean hx = Math.abs(mx - r[0]) >= 1.0E-5F, hz = Math.abs(mz - r[2]) >= 1.0E-5F, vert = my != r[1];
        onGround = vert && my < 0;
        if (hx) vx = 0;
        if (hz) vz = 0;
        if (vert) vy = 0;
    }

    private static double[] shift(double[] bb, double dx, double dy, double dz) {
        return new double[]{bb[0] + dx, bb[1] + dy, bb[2] + dz, bb[3] + dx, bb[4] + dy, bb[5] + dz};
    }

    private static double[] collide(List<double[]> near, double[] bb0, double mx, double my, double mz) {
        double[] bb = bb0.clone();
        double ry = clip(near, bb, 1, my);
        bb[1] += ry; bb[4] += ry;
        double rx, rz;
        if (Math.abs(mx) < Math.abs(mz)) {
            rz = clip(near, bb, 2, mz);
            bb[2] += rz; bb[5] += rz;
            rx = clip(near, bb, 0, mx);
        } else {
            rx = clip(near, bb, 0, mx);
            bb[0] += rx; bb[3] += rx;
            rz = clip(near, bb, 2, mz);
        }
        return new double[]{rx, ry, rz};
    }

    /** Shapes.collide along one axis (0 x, 1 y, 2 z). */
    private static double clip(List<double[]> near, double[] bb, int ax, double d) {
        int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
        for (double[] b : near) {
            if (Math.abs(d) < 1.0E-7) return 0;
            if (!(b[3 + a1] > bb[a1] + 1.0E-7 && b[a1] < bb[3 + a1] - 1.0E-7 && b[3 + a2] > bb[a2] + 1.0E-7 && b[a2] < bb[3 + a2] - 1.0E-7)) continue;
            if (d > 0 && b[ax] >= bb[3 + ax] - 1.0E-7) d = Math.min(d, b[ax] - bb[3 + ax]);
            else if (d < 0 && b[3 + ax] <= bb[ax] + 1.0E-7) d = Math.max(d, b[3 + ax] - bb[ax]);
        }
        return d;
    }

    private List<double[]> boxes(double x0, double y0, double z0, double x1, double y1, double z1) {
        List<double[]> out = new ArrayList<>();
        for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1); bx++)
            for (int by = (int) Math.floor(y0); by <= (int) Math.floor(y1); by++)
                for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1); bz++)
                    world.boxes(bx, by, bz, (a, b, c, d, e, f) -> out.add(new double[]{a, b, c, d, e, f}));
        return out;
    }

    /** Whether any rock overlaps the box (level.noCollision, negated). */
    private boolean collides(double x0, double y0, double z0, double x1, double y1, double z1) {
        for (double[] b : boxes(x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1))
            if (b[3] > x0 + 1.0E-7 && b[0] < x1 - 1.0E-7 && b[4] > y0 + 1.0E-7 && b[1] < y1 - 1.0E-7 && b[5] > z0 + 1.0E-7 && b[2] < z1 - 1.0E-7) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ the tracker

    /** ServerEntity.sendChanges: every few ticks, and at once when its synced data changed; the client eases to each over 3 ticks. */
    private void track() {
        if (tracked % UPDATE_INTERVAL == 0 || dirty) {
            int k = rotByte(yRot);
            double px = Math.round(x * 4096.0) / 4096.0, py = Math.round(y * 4096.0) / 4096.0, pz = Math.round(z * 4096.0) / 4096.0;
            boolean moved = sq(x - baseX) + sq(y - baseY) + sq(z - baseZ) >= 7.6293945E-6F;
            boolean pos = moved || tracked % 60 == 0, rot = Math.abs(k - yRotp) >= 1;
            if (tracked > 0) {
                if (wasOnGround != onGround) {
                    // landing or leaving the floor: a teleport, to the exact spot, still eased to
                    wasOnGround = onGround;
                    lerpTo(x, y, z, k * 360.0F / 256.0F);
                } else if (pos && rot) lerpTo(px, py, pz, k * 360.0F / 256.0F);
                else if (pos) lerpTo(px, py, pz, cYRot);
                else if (rot) lerpTo(cx, cy, cz, k * 360.0F / 256.0F);
            }
            if (dirty) {
                cSurface = surface;
                cForward.set(syncedForward);
                dirty = false;
            }
            if (pos) { baseX = x; baseY = y; baseZ = z; }
            if (rot) yRotp = k;
        }
        tracked++;
    }

    private void lerpTo(double x, double y, double z, double yRot) {
        lerpX = x; lerpY = y; lerpZ = z; lerpYRot = yRot;
        lerpSteps = 3;
    }

    private static int rotByte(float yRot) { return (int) Math.floor(yRot * 256.0F / 360.0F); }

    // ------------------------------------------------------------------------------------------------ the client's tick

    private void clientTick() {
        cxo = cx; cyo = cy; czo = cz;
        if (lerpSteps > 0) {
            cx += (lerpX - cx) / lerpSteps;
            cy += (lerpY - cy) / lerpSteps;
            cz += (lerpZ - cz) / lerpSteps;
            cYRot += wrap((float) (lerpYRot - cYRot)) / lerpSteps;
            lerpSteps--;
        }
        // Mob's body rotation: while it moves its body turns with it
        if (sq(cx - cxo) + sq(cz - czo) > 2.5000003E-7F) cYBodyRot = cYRot;
        orientation();
    }

    /** MossbackEntity.orientation: its belly to its rock, its head the way it goes, both eased. */
    private void orientation() {
        int d = cSurface;
        atStep = stepAt(d);
        Vector3d upT = d == NONE || atStep ? new Vector3d(0, 1, 0) : new Vector3d(-NORMAL[d][0], -NORMAL[d][1], -NORMAL[d][2]);
        Vector3d want;
        if (d == DOWN || d == NONE || atStep) {
            want = dir(cYBodyRot);
            cLead.set(dir(cYRot));
        } else {
            want = new Vector3d(cForward);
            cLead.set(want);
        }
        if (!oriented) {
            oriented = true;
            clientUp.set(upT);
            clientFwd.set(want);
        }
        if (clientUp.dot(upT) < -0.99) clientUp.add(new Vector3d(clientFwd).cross(clientUp).mul(0.3));
        clientUp.lerp(upT, 0.35);
        if (clientUp.lengthSquared() < 1.0E-4) clientUp.set(upT);
        clientUp.normalize();
        if (clientFwd.dot(want) < -0.99) clientFwd.add(new Vector3d(clientUp).cross(clientFwd).mul(0.3));
        clientFwd.lerp(want, 0.3);
        Vector3d f = new Vector3d(clientFwd).fma(-clientFwd.dot(clientUp), clientUp);
        if (f.lengthSquared() < 1.0E-4) f.set(dir(cYBodyRot));
        f.fma(-f.dot(clientUp), clientUp);
        if (f.lengthSquared() < 1.0E-4) f.set(Math.abs(clientUp.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(0, 0, 1));
        clientFwd.set(f.normalize());
    }

    /** MossbackEntity.stepAt: its box, off its floor clinging to a wall or to nothing, is at a step. */
    private boolean stepAt(int d) {
        if (d == DOWN || d == UP) return false;
        if (d == NONE && cyo - cy > STEP_FALL) return false;
        int side = switch (d) {
            case WEST -> Steps.WEST;
            case EAST -> Steps.EAST;
            case NORTH -> Steps.NORTH;
            case SOUTH -> Steps.SOUTH;
            default -> Steps.NONE;
        };
        return Steps.at(world, cx - W / 2, cy, cz - W / 2, cx + W / 2, cy + H, cz + W / 2, side);
    }

    /** MossbackEntity.tickBody's drive, from what the client sees. */
    @Override
    void drive(MossbackBody.Drive d) {
        d.x = cx; d.y = cy + H / 2; d.z = cz;
        d.up.set(clientUp);
        d.forward.set(clientFwd);
        d.lead.set(cLead);
        bodyAir = cSurface == NONE && !atStep ? bodyAir + 1 : 0;
        d.mode = bodyAir > BODY_AIR_GRACE ? MossbackBody.Mode.AIR : MossbackBody.Mode.GROUND;
        d.rear = 0;
        d.hurry = hurry;
        d.step = atStep && d.mode == MossbackBody.Mode.GROUND;
        c.set(d.x, d.y, d.z);
        mode = d.mode;
        step = d.step;
    }

    // ------------------------------------------------------------------------------------------------ odds and ends

    private static Vector3d dir(float yaw) {
        double a = Math.toRadians(yaw);
        return new Vector3d(-Math.sin(a), 0, Math.cos(a));
    }

    private static float wrap(float deg) {
        float f = deg % 360.0F;
        if (f >= 180.0F) f -= 360.0F;
        if (f < -180.0F) f += 360.0F;
        return f;
    }

    private static double sq(double v) { return v * v; }

    /** The node whose box is centred nearest it, or else the walkable one round it nearest where it is (ClimbNodeEvaluator.getStart). */
    private int[] start() {
        int sx = (int) Math.floor(x - 0.5), sy = (int) Math.floor(y + 0.05), sz = (int) Math.floor(z - 0.5);
        if (kind(sx, sy, sz) >= PathFinder.FLOOR) return new int[]{sx, sy, sz};
        int[] best = {sx, sy, sz};
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    byte k = kind(sx + dx, sy + dy, sz + dz);
                    if ((dx | dy | dz) == 0 || k < PathFinder.FLOOR) continue;
                    double d = sq(sx + dx + 1 - x) + sq(sy + dy - y) + sq(sz + dz + 1 - z) + (k == PathFinder.FLOOR ? 0 : 0.05);
                    if (d < bestD) { bestD = d; best = new int[]{sx + dx, sy + dy, sz + dz}; }
                }
        return best;
    }

    /** ClimbNodeEvaluator.classify for the node with low corner (x, y, z), remembered. */
    byte kind(int x, int y, int z) {
        long key = ((long) (x + 2048) << 40) | ((long) (y + 2048) << 20) | (long) (z + 2048);
        Byte k = kinds.get(key);
        if (k == null) kinds.put(key, k = PathFinder.classify(world, x, y, z));
        return k;
    }

    /** The game's path finder over ClimbNodeEvaluator's nodes: A* on distance and each node's malus, its heuristic 1.5 times the distance left. */
    static final class PathFinder {
        static final byte BLOCKED = 0, OPEN = 1, FLOOR = 2, WALL = 3, CEILING = 4;
        private static final double THIN = 0.1, REACH = 0.06;
        private static final int[][] STEPS;

        static {
            List<int[]> steps = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (n == 1 || n == 2) steps.add(new int[]{dx, dy, dz});
                    }
            STEPS = steps.toArray(new int[0][]);
        }

        private PathFinder() {}

        private static final class Node {
            final int x, y, z;
            float g, h, f, walked;
            int heapIdx = -1;
            boolean closed;
            Node from;

            Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

            float distanceTo(Node o) { return distanceTo(o.x, o.y, o.z); }

            float distanceTo(int ox, int oy, int oz) {
                float a = ox - x, b = oy - y, c = oz - z;
                return (float) Math.sqrt(a * a + b * b + c * c);
            }
        }

        /** The nodes from {@code from} to the first within a block (Manhattan) of {@code to}, or to the nearest it got. */
        static List<int[]> find(GameDriver g, int[] from, int[] to) {
            Map<Long, Node> nodes = new HashMap<>();
            Node start = node(nodes, from[0], from[1], from[2]);
            start.g = 0;
            start.h = start.distanceTo(to[0], to[1], to[2]);
            start.f = start.h;
            Heap open = new Heap();
            open.insert(start);
            Node best = start, reached = null;
            float bestLeft = start.h;
            float maxDistance = 64.0F;
            int visits = 0;
            while (!open.isEmpty() && visits++ < 4000) {
                Node n = open.pop();
                n.closed = true;
                if (Math.abs(n.x - to[0]) + Math.abs(n.y - to[1]) + Math.abs(n.z - to[2]) <= 1) { reached = n; break; }
                float left = n.distanceTo(to[0], to[1], to[2]);
                if (left < bestLeft) { bestLeft = left; best = n; }
                if (n.distanceTo(start) >= maxDistance) continue;
                for (int[] s : STEPS) {
                    int x = n.x + s[0], y = n.y + s[1], z = n.z + s[2];
                    byte k = g.kind(x, y, z);
                    if (k < FLOOR) continue;
                    int axes = (s[0] != 0 ? 1 : 0) + (s[1] != 0 ? 1 : 0) + (s[2] != 0 ? 1 : 0);
                    if (axes == 2) {
                        boolean viaFirst, viaSecond;
                        if (s[0] == 0) { viaFirst = g.kind(n.x, y, n.z) != BLOCKED; viaSecond = g.kind(n.x, n.y, z) != BLOCKED; }
                        else if (s[1] == 0) { viaFirst = g.kind(x, n.y, n.z) != BLOCKED; viaSecond = g.kind(n.x, n.y, z) != BLOCKED; }
                        else { viaFirst = g.kind(x, n.y, n.z) != BLOCKED; viaSecond = g.kind(n.x, y, n.z) != BLOCKED; }
                        if (!viaFirst && !viaSecond) continue;
                        // a level one sweeps past a corner cell on each side: not with rock in both
                        if (CORNERS && s[1] == 0 && solid(g.world, n.x + (s[0] > 0 ? 2 : -1), n.y, n.z + (s[2] > 0 ? 0 : 1))
                                && solid(g.world, n.x + (s[0] > 0 ? 0 : 1), n.y, n.z + (s[2] > 0 ? 2 : -1))) continue;
                    }
                    Node m = node(nodes, x, y, z);
                    if (m.closed) continue;
                    float d = n.distanceTo(m);
                    m.walked = n.walked + d;
                    float cost = n.g + d + (k == WALL ? 0.6F : k == CEILING ? 1.2F : 0.0F);
                    if (m.walked < maxDistance && (m.heapIdx < 0 || cost < m.g)) {
                        m.from = n;
                        m.g = cost;
                        m.h = m.distanceTo(to[0], to[1], to[2]) * 1.5F;
                        if (m.heapIdx >= 0) open.changeCost(m, m.g + m.h);
                        else { m.f = m.g + m.h; open.insert(m); }
                    }
                }
            }
            List<int[]> out = new ArrayList<>();
            for (Node n = reached != null ? reached : best; n != null; n = n.from) out.add(0, new int[]{n.x, n.y, n.z});
            return out;
        }

        private static Node node(Map<Long, Node> nodes, int x, int y, int z) {
            long key = ((long) (x + 2048) << 40) | ((long) (y + 2048) << 20) | (long) (z + 2048);
            return nodes.computeIfAbsent(key, k -> new Node(x, y, z));
        }

        /** The game's BinaryHeap, so that ties break as they do in game. */
        private static final class Heap {
            private Node[] heap = new Node[128];
            private int size;

            boolean isEmpty() { return size == 0; }

            void insert(Node n) {
                if (size == heap.length) heap = java.util.Arrays.copyOf(heap, size << 1);
                heap[size] = n;
                n.heapIdx = size;
                up(size++);
            }

            Node pop() {
                Node n = heap[0];
                heap[0] = heap[--size];
                heap[size] = null;
                if (size > 0) down(0);
                n.heapIdx = -1;
                return n;
            }

            void changeCost(Node n, float f) {
                float was = n.f;
                n.f = f;
                if (f < was) up(n.heapIdx);
                else down(n.heapIdx);
            }

            private void up(int i) {
                Node n = heap[i];
                float f = n.f;
                while (i > 0) {
                    int p = i - 1 >> 1;
                    Node q = heap[p];
                    if (!(f < q.f)) break;
                    heap[i] = q;
                    q.heapIdx = i;
                    i = p;
                }
                heap[i] = n;
                n.heapIdx = i;
            }

            private void down(int i) {
                Node n = heap[i];
                float f = n.f;
                while (true) {
                    int a = 1 + (i << 1), b = a + 1;
                    if (a >= size) break;
                    Node na = heap[a], nb = b < size ? heap[b] : null;
                    float fa = na.f, fb = nb != null ? nb.f : Float.POSITIVE_INFINITY;
                    if (fa < fb) {
                        if (!(fa < f)) break;
                        heap[i] = na;
                        na.heapIdx = i;
                        i = a;
                    } else {
                        if (!(fb < f)) break;
                        heap[i] = nb;
                        nb.heapIdx = i;
                        i = b;
                    }
                }
                heap[i] = n;
                n.heapIdx = i;
            }
        }

        // -------------------------------------------------------------------------------------------- classification

        /** ClimbNodeEvaluator.classify for a box filling 2 x 1 x 2 cells with low corner (x, y, z). */
        static byte classify(BlockWorld w, int x, int y, int z) {
            boolean raised = false;
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) {
                    double[] s = shape(w, x + dx, y, z + dz);
                    if (s == null) continue;
                    double top = s[4];
                    if (top <= THIN) continue;
                    if (top <= 0.5) { raised = true; continue; }
                    return BLOCKED;
                }
            if (raised) {
                for (int dx = 0; dx < 2; dx++)
                    for (int dz = 0; dz < 2; dz++) if (shape(w, x + dx, y + 1, z + dz) != null) return BLOCKED;
                return FLOOR;
            }
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) if (solid(w, x + dx, y - 1, z + dz)) return FLOOR;
            for (int dz = 0; dz < 2; dz++) {
                if (face(w, x - 1, y, z + dz, 0, true)) return WALL;
                if (face(w, x + 2, y, z + dz, 0, false)) return WALL;
            }
            for (int dx = 0; dx < 2; dx++) {
                if (face(w, x + dx, y, z - 1, 2, true)) return WALL;
                if (face(w, x + dx, y, z + 2, 2, false)) return WALL;
            }
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) if (face(w, x + dx, y + 1, z + dz, 1, false)) return CEILING;
            return OPEN;
        }

        /** ClimbNodeEvaluator.lift: how far a slab or snow layer in the node's bottom cells lifts a box standing there. */
        static double lift(BlockWorld w, int x, int y, int z) {
            double lift = 0.0;
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) {
                    double[] s = shape(w, x + dx, y, z + dz);
                    if (s != null && s[4] > THIN && s[4] <= 0.5) lift = Math.max(lift, s[4]);
                }
            return lift;
        }

        /** ClimbNodeEvaluator.support: the highest top of the rock in the cells under the node (1 on blocks, 0.5 on slabs). */
        static double support(BlockWorld w, int x, int y, int z) {
            double top = 0.0;
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) {
                    double[] s = shape(w, x + dx, y - 1, z + dz);
                    if (s != null && s[4] > THIN) top = Math.max(top, s[4]);
                }
            return top;
        }

        /** The bounds of a cell's rock in the cell's own coordinates (min x y z, max x y z), or null when it has none. */
        private static double[] shape(BlockWorld w, int x, int y, int z) {
            double[] s = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            boolean[] any = {false};
            w.boxes(x, y, z, (a, b, c, d, e, f) -> {
                any[0] = true;
                s[0] = Math.min(s[0], a - x); s[1] = Math.min(s[1], b - y); s[2] = Math.min(s[2], c - z);
                s[3] = Math.max(s[3], d - x); s[4] = Math.max(s[4], e - y); s[5] = Math.max(s[5], f - z);
            });
            return any[0] ? s : null;
        }

        private static boolean solid(BlockWorld w, int x, int y, int z) {
            double[] s = shape(w, x, y, z);
            return s != null && s[4] > THIN;
        }

        private static boolean face(BlockWorld w, int x, int y, int z, int axis, boolean high) {
            if (!solid(w, x, y, z)) return false;
            double[] s = shape(w, x, y, z);
            return high ? s[3 + axis] >= 1.0 - REACH : s[axis] <= REACH;
        }
    }
}
