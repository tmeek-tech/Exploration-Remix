package com.explorationremix.creature.climb;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Target;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Paths for a creature that walks on floors, walls and ceilings. A node is the low corner of the cells the creature's
 * box fills (for a box 1.4 wide and 0.9 tall: 2 x 1 x 2 cells; vanilla puts a mob of that width at node + 1). A node
 * can be walked when those cells are open and the box touches rock on some side: below (floor), beside (wall) or
 * above (ceiling). Water can be swum at its top, never under it. Floors cost least, then walls, then ceilings, so it walks normally where it can
 * and climbs where it must. Moves go to the 18 face and edge neighbours; an edge move needs one of its two straight
 * halves open, so it rounds a ledge but never cuts through a corner of rock, and a level one is left out where rock
 * stands at both corners it would sweep past, or where it would step up onto one and meet rock over its back. A move
 * onto or off a slab (or snow) needs room for the lifted box over the cells it crosses, and a move the creature tried
 * and failed to make is left out for a while ({@link #avoid}).
 */
public class ClimbNodeEvaluator extends NodeEvaluator {
    /** A node's kind; FLOOR to CEILING and WATER can be walked. */
    public static final byte BLOCKED = 0, OPEN = 1, FLOOR = 2, WALL = 3, CEILING = 4, WATER = 5;

    private final Long2ByteMap kinds = new Long2ByteOpenHashMap();
    private final Long2FloatMap lifts = new Long2FloatOpenHashMap();
    /** Moves it failed to make: from node, to node, and the game time they are left out until. */
    private final java.util.List<long[]> avoided = new java.util.ArrayList<>();
    private long now;
    private static final int[][] STEPS;

    static {
        java.util.List<int[]> steps = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int n = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (n == 1 || n == 2) steps.add(new int[]{dx, dy, dz});
                }
        STEPS = steps.toArray(new int[0][]);
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        kinds.clear();
        lifts.clear();
        now = mob.level().getGameTime();
        avoided.removeIf(a -> a[2] <= now);
    }

    @Override
    public void done() {
        super.done();
        kinds.clear();
        lifts.clear();
    }

    /**
     * Leaves the move from node {@code from} to node {@code to} out of this creature's paths until game time
     * {@code until}: it tried it and could not make it (some shape the nodes misjudge).
     */
    public void avoid(BlockPos from, BlockPos to, long until) {
        long f = from.asLong(), t = to.asLong();
        for (long[] a : avoided)
            if (a[0] == f && a[1] == t) { a[2] = Math.max(a[2], until); return; }
        avoided.add(new long[]{f, t, until});
    }

    private boolean avoided(Node from, int x, int y, int z) {
        return avoided(from.x, from.y, from.z, x, y, z);
    }

    private boolean avoided(int fx, int fy, int fz, int x, int y, int z) {
        if (avoided.isEmpty()) return false;
        long f = BlockPos.asLong(fx, fy, fz), t = BlockPos.asLong(x, y, z);
        for (long[] a : avoided)
            if (a[0] == f && a[1] == t && a[2] > now) return true;
        return false;
    }

    /**
     * The node whose box is centred nearest the mob, or else the walkable one round it whose box is nearest where the
     * mob really is (a box straddling two nodes starts from the one it stands or clings in, not one it would have to
     * squeeze down or round an edge to reach), leaving out one it tried and failed to get to from where it is (it
     * stood on a slab under a lip, its own node no node, and was sent at the one it could not reach for over a minute).
     */
    @Override
    public Node getStart() {
        int x = Mth.floor(mob.getX() - entityWidth * 0.5 + 0.5);
        int y = Mth.floor(mob.getY() + 0.05);
        int z = Mth.floor(mob.getZ() - entityDepth * 0.5 + 0.5);
        if (walkable(kind(x, y, z))) return start(x, y, z);
        int bx = x, by = y, bz = z;
        double best = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    byte k = kind(nx, ny, nz);
                    if ((dx | dy | dz) == 0 || !walkable(k) || avoided(x, y, z, nx, ny, nz)) continue;
                    // a floor wins a tie: it is where it walks most easily
                    double d = Mth.square(nx + entityWidth * 0.5 - mob.getX()) + Mth.square(ny - mob.getY())
                            + Mth.square(nz + entityDepth * 0.5 - mob.getZ()) + (k == FLOOR ? 0.0 : 0.05);
                    if (d < best) { best = d; bx = nx; by = ny; bz = nz; }
                }
        return start(bx, by, bz);
    }

    private Node start(int x, int y, int z) {
        Node n = getNode(x, y, z);
        n.type = typeOf(kind(x, y, z));
        n.costMalus = 0;
        return n;
    }

    @Override
    public Target getGoal(double x, double y, double z) {
        return getTargetFromNode(getNode(Mth.floor(x - entityWidth * 0.5 + 0.5), Mth.floor(y), Mth.floor(z - entityDepth * 0.5 + 0.5)));
    }

    @Override
    public int getNeighbors(Node[] out, Node from) {
        int count = 0;
        for (int[] s : STEPS) {
            int x = from.x + s[0], y = from.y + s[1], z = from.z + s[2];
            byte k = kind(x, y, z);
            if (!walkable(k)) continue;
            int axes = (s[0] != 0 ? 1 : 0) + (s[1] != 0 ? 1 : 0) + (s[2] != 0 ? 1 : 0);
            // never straight down through a slab it stands on, or straight up through one it would stand on
            boolean offSlab = s[1] < 0 && bottom(from.x, from.y, from.z) > 0.0, ontoSlab = s[1] > 0 && bottom(x, y, z) > 0.0;
            if (axes == 1 && (offSlab || ontoSlab)) continue;
            if (axes == 2) {
                // round an edge only through an open straight half, never through rock
                boolean viaFirst, viaSecond;
                if (s[1] == 0) { viaFirst = kind(x, from.y, from.z) != BLOCKED; viaSecond = kind(from.x, from.y, z) != BLOCKED; }
                else {
                    // up or down first, or across first (off a slab it goes across first, onto one up first); up more
                    // than a block (off a slab onto a block, or onto a slab on one), it cannot step: straight up first
                    // only with rock beside it to climb (it floated up through the air and fell)
                    byte up = kind(from.x, y, from.z);
                    boolean high = s[1] > 0 && y + bottom(x, y, z) - from.y - bottom(from.x, from.y, from.z) > 1.0 + 1.0E-4;
                    viaFirst = !offSlab && up != BLOCKED && (!high || up == WALL || up == CEILING);
                    viaSecond = !ontoSlab && kind(x, from.y, z) != BLOCKED;
                }
                if (!viaFirst && !viaSecond) continue;
                // across the floor, the box cuts past a corner cell on each side that neither node holds: with rock in
                // both (two slab corners where slab lines meet, or a slab and a block) it wedged between them and
                // quivered there, so it goes round by a straight half instead
                if (s[1] == 0 && rockAtBothCorners(from, s[0], s[2], y + bottom(x, y, z))) continue;
                // or it would step up onto the corner it cuts past, and lifted meet rock over the cells it sweeps (a lip
                // over the far node, which its own rule never looked at): it stood on a slab's corner under a lip of
                // silt, the way on blocked and the way down under its feet, for over a minute
                if (s[1] == 0 && liftedAtCorner(from, s[0], s[2])) continue;
            }
            if (!headroom(from, x, y, z) || avoided(from, x, y, z)) continue;
            Node n = getNode(x, y, z);
            if (n.closed) continue;
            n.type = typeOf(k);
            n.costMalus = malus(k);
            out[count++] = n;
        }
        return count;
    }

    /**
     * Whether rock stands in both cells a level diagonal step from {@code from} (by {@code sx}, {@code sz}) sweeps past:
     * the two corners of the cells the two boxes fill together that neither box fills. Rock counts between the lower of
     * the box's two bottoms and the higher one's top ({@code to}: where its bottom stands at the far node): up on slabs
     * a block in the row over a corner is in its way and a slab beside it is not.
     */
    private boolean rockAtBothCorners(Node from, int sx, int sz, double to) {
        double at = from.y + bottom(from.x, from.y, from.z);
        double lo = Math.min(at, to), top = Math.max(at, to) + mob.getBbHeight();
        int ax = sx > 0 ? from.x + entityWidth : from.x - 1, az = sz > 0 ? from.z : from.z + entityDepth - 1;
        int bx = sx > 0 ? from.x : from.x + entityWidth - 1, bz = sz > 0 ? from.z + entityDepth : from.z - 1;
        return rockIn(ax, az, lo, top) && rockIn(bx, bz, lo, top);
    }

    /**
     * Whether a level diagonal step across a floor from {@code from} (by {@code sx}, {@code sz}) cuts past a corner cell
     * it steps up onto (see {@link #stepAt}) where the box, lifted onto it, meets rock over the cells the two boxes fill
     * together. It steps up only with room for the lifted box over the cells it stands in, as the game lifts it from
     * there (under rock there it slides past the corner instead), and only standing on a floor (clinging to a wall or
     * ceiling it slides past).
     */
    private boolean liftedAtCorner(Node from, int sx, int sz) {
        if (kind(from.x, from.y, from.z) != FLOOR) return false;
        double at = from.y + bottom(from.x, from.y, from.z);
        int ax = sx > 0 ? from.x + entityWidth : from.x - 1, az = sz > 0 ? from.z : from.z + entityDepth - 1;
        int bx = sx > 0 ? from.x : from.x + entityWidth - 1, bz = sz > 0 ? from.z + entityDepth : from.z - 1;
        double step = Math.max(stepAt(ax, az, at), stepAt(bx, bz, at));
        if (step <= at || rockOver(from.x, from.z, from.x + entityWidth - 1, from.z + entityDepth - 1, step)) return false;
        return rockOver(Math.min(from.x, from.x + sx), Math.min(from.z, from.z + sz),
                Math.max(from.x, from.x + sx) + entityWidth - 1, Math.max(from.z, from.z + sz) + entityDepth - 1, step);
    }

    /**
     * Where a box standing at {@code at} stands once it steps up onto the rock in the column (x, z) as it cuts past it:
     * on rock in its way no higher than it steps, with room over it for the box. {@code at} where it would not step
     * (nothing in its way, or rock it slides along: too high to step onto, or with rock over it).
     */
    private double stepAt(int x, int z, double at) {
        double height = mob.getBbHeight(), limit = at + mob.maxUpStep(), step = at;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = Mth.floor(at); r < at + height - 1.0E-6; r++) {
            p.set(x, r, z);
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.isEmpty()) continue;
            double lo = r + shape.min(net.minecraft.core.Direction.Axis.Y), hi = r + shape.max(net.minecraft.core.Direction.Axis.Y);
            if (lo >= at + height - 1.0E-6 || hi <= at + THIN) continue;
            if (hi > limit + 1.0E-6) return at;
            step = Math.max(step, hi);
        }
        return step > at && !rockOver(x, z, x, z, step) ? step : at;
    }

    /** Rock in the cells from (x0, z0) to (x1, z1) that a box standing at {@code at} would hit (a slab it stands level with is none). */
    private boolean rockOver(int x0, int z0, int x1, int z1, double at) {
        double top = at + mob.getBbHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = Mth.floor(at); r < top - 1.0E-6; r++)
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++) {
                    p.set(cx, r, cz);
                    VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
                    if (!shape.isEmpty() && r + shape.min(net.minecraft.core.Direction.Axis.Y) < top - 1.0E-6
                            && r + shape.max(net.minecraft.core.Direction.Axis.Y) > at + THIN) return true;
                }
        return false;
    }

    /** Rock in the column (x, z) that a box from {@code lo} up to {@code top} would hit (a carpet or a slab it stands level with it walks over). */
    private boolean rockIn(int x, int z, double lo, double top) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = Mth.floor(lo); r < top - 1.0E-6; r++) {
            p.set(x, r, z);
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (!shape.isEmpty() && r + shape.min(net.minecraft.core.Direction.Axis.Y) < top - 1.0E-6
                    && r + shape.max(net.minecraft.core.Direction.Axis.Y) > lo + THIN) return true;
        }
        return false;
    }

    /**
     * Whether the box has room to get from {@code from} to the node at (x, y, z) when a slab or snow layer lifts it in
     * one of them, or slabs under the cells let it down (and it stands higher in one). It crosses at the higher of the two
     * heights over the cells of both (onto a slab it steps up while it is still over the cells it leaves; off one it is
     * still on it when it is most of the way over the next cells), so at that height its back reaches into the row over
     * the lower node's cells, which that node's own rule never looked at: that row must be clear there. (Under a block of
     * rock or a leaf there, the path led it to push at the step for ever.)
     */
    private boolean headroom(Node from, int x, int y, int z) {
        double a = bottom(from.x, from.y, from.z), b = bottom(x, y, z);
        if (a == 0.0 && b == 0.0 || Math.abs(from.y + a - (y + b)) < 1.0E-4) return true;
        double hi = Math.max(from.y + a, y + b), top = hi + mob.getBbHeight();
        int row = (from.y + a < y + b ? from.y : y) + entityHeight;
        int x0 = Math.min(from.x, x), x1 = Math.max(from.x, x) + entityWidth - 1, z0 = Math.min(from.z, z), z1 = Math.max(from.z, z) + entityDepth - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = row; r < top - 1.0E-6; r++)
            for (int cx = x0; cx <= x1; cx++)
                for (int cz = z0; cz <= z1; cz++) {
                    p.set(cx, r, cz);
                    VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
                    // rock between the lifted box's bottom (a carpet, a slab it stands on, lies below it) and its top
                    if (!shape.isEmpty() && r + shape.min(net.minecraft.core.Direction.Axis.Y) < top - 1.0E-6
                            && r + shape.max(net.minecraft.core.Direction.Axis.Y) > hi + THIN) return false;
                }
        return true;
    }

    /**
     * Where the box stands at a floor node, above the node's low corner: up on a slab or snow layer in its cells, or
     * down on slabs under them (0 at every other node, and on a floor of whole blocks). Cached for this search.
     */
    private double bottom(int x, int y, int z) {
        if (kind(x, y, z) != FLOOR) return 0.0;
        long key = BlockPos.asLong(x, y, z);
        float b = lifts.getOrDefault(key, Float.NaN);
        if (Float.isNaN(b)) {
            double lift = lift(level, x, y, z, entityWidth, entityDepth);
            double under = lift > 0.0 ? 1.0 : support(level, x, y, z, entityWidth, entityDepth);
            b = (float) (lift > 0.0 ? lift : under > 0.0 && under < 1.0 ? under - 1.0 : 0.0);
            lifts.put(key, b);
        }
        return b;
    }

    private static boolean walkable(byte k) {
        return k >= FLOOR;
    }

    private static float malus(byte k) {
        return switch (k) {
            case WALL -> 0.6F;
            case CEILING -> 1.2F;
            case WATER -> 1.0F;
            default -> 0.0F;
        };
    }

    private static BlockPathTypes typeOf(byte k) {
        return switch (k) {
            case BLOCKED -> BlockPathTypes.BLOCKED;
            case OPEN -> BlockPathTypes.OPEN;
            case WATER -> BlockPathTypes.WATER;
            default -> BlockPathTypes.WALKABLE;
        };
    }

    /** The kind of a node, cached for this search. */
    public byte kind(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte k = kinds.getOrDefault(key, (byte) -1);
        if (k < 0) {
            k = classify(level, x, y, z, entityWidth, entityHeight, entityDepth);
            kinds.put(key, k);
        }
        return k;
    }

    // ------------------------------------------------------------------------------------------------ classification

    /** Classifies the node with low corner (x, y, z) for a creature filling w x h x d cells. Usable on a live level. */
    public static byte classify(BlockGetter level, int x, int y, int z, int w, int h, int d) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean raised = false, water = false;
        for (int dx = 0; dx < w; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = 0; dz < d; dz++) {
                    p.set(x + dx, y + dy, z + dz);
                    BlockState s = level.getBlockState(p);
                    if (harmful(s)) return BLOCKED;
                    if (s.getFluidState().is(FluidTags.LAVA)) return BLOCKED;
                    if (s.getFluidState().is(FluidTags.WATER)) water = true;
                    VoxelShape shape = s.getCollisionShape(level, p);
                    if (shape.isEmpty()) continue;
                    double top = shape.max(net.minecraft.core.Direction.Axis.Y);
                    // a carpet (or a den) under its feet: the box still fits in the cell on top of it
                    if (dy == 0 && top <= THIN) continue;
                    // a snow layer or slab on the floor raises it a little; anything taller blocks
                    if (dy == 0 && top <= 0.5) { raised = true; continue; }
                    return BLOCKED;
                }
        // a slab under water (a pond's edge, a waterlogged slab) is no floor to walk: there it swims, at the top
        if (raised && !water) {
            // standing on the low block lifts the box half a block: the cells above must be open
            for (int dx = 0; dx < w; dx++)
                for (int dz = 0; dz < d; dz++) {
                    p.set(x + dx, y + h, z + dz);
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return BLOCKED;
                }
            return FLOOR;
        }
        if (water) {
            // it swims at the top of the water, where it can breathe, floating with its back out of it: only where
            // the water's top lies in the box's own cells, with open air over every one of them (deeper, it would
            // float up out of the node, or into rock over it)
            for (int dx = 0; dx < w; dx++)
                for (int dz = 0; dz < d; dz++) {
                    BlockState over = level.getBlockState(p.set(x + dx, y + h, z + dz));
                    if (!over.getCollisionShape(level, p).isEmpty() || !over.getFluidState().isEmpty()) return BLOCKED;
                }
            return WATER;
        }
        // floor: rock under any of the cells
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++)
                if (solid(level, p.set(x + dx, y - 1, z + dz))) return FLOOR;
        // walls: rock beside the box at its own height, reaching the side it would cling to (a pane, bars or a
        // fence post stand in the middle of their cell, out of its reach)
        for (int dy = 0; dy < h; dy++) {
            for (int dz = 0; dz < d; dz++) {
                if (face(level, p.set(x - 1, y + dy, z + dz), net.minecraft.core.Direction.Axis.X, true)) return WALL;
                if (face(level, p.set(x + w, y + dy, z + dz), net.minecraft.core.Direction.Axis.X, false)) return WALL;
            }
            for (int dx = 0; dx < w; dx++) {
                if (face(level, p.set(x + dx, y + dy, z - 1), net.minecraft.core.Direction.Axis.Z, true)) return WALL;
                if (face(level, p.set(x + dx, y + dy, z + d), net.minecraft.core.Direction.Axis.Z, false)) return WALL;
            }
        }
        // ceiling: rock over any of the cells, coming down to the top of the box (not a top slab's half way up)
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++)
                if (face(level, p.set(x + dx, y + h, z + dz), net.minecraft.core.Direction.Axis.Y, false)) return CEILING;
        return OPEN;
    }

    /**
     * How far a slab or snow layer in the bottom cells of the node with low corner (x, y, z) lifts a box standing there:
     * the highest such top (half a block at most), or 0 when nothing does (a carpet lies under the box without lifting it).
     */
    public static double lift(BlockGetter level, int x, int y, int z, int w, int d) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double lift = 0.0;
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                p.set(x + dx, y, z + dz);
                VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
                if (shape.isEmpty()) continue;
                double top = shape.max(net.minecraft.core.Direction.Axis.Y);
                if (top > THIN && top <= 0.5) lift = Math.max(lift, top);
            }
        return lift;
    }

    /**
     * How high the rock under the floor node with low corner (x, y, z) holds a box standing there, when nothing in its
     * own cells lifts it: the highest top of the shapes in the cells under it (1 on a block, 0.5 on a slab, less on a
     * snow layer), or 0 when none of them has a real shape.
     */
    public static double support(BlockGetter level, int x, int y, int z, int w, int d) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double top = 0.0;
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                p.set(x + dx, y - 1, z + dz);
                VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
                if (shape.isEmpty()) continue;
                double t = shape.max(net.minecraft.core.Direction.Axis.Y);
                if (t > THIN) top = Math.max(top, t);
            }
        return top;
    }

    /**
     * Rock in {@code p} that reaches the face it shares with the box: its high side along {@code axis} when it lies
     * on the box's low side ({@code high}), else its low side.
     */
    private static boolean face(BlockGetter level, BlockPos p, net.minecraft.core.Direction.Axis axis, boolean high) {
        if (!solid(level, p)) return false;
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
        // a side to cling to is at least half a block high: the edge of a big dripleaf's leaf, a quarter of a block
        // thick in the air, is none (clinging to one, it ended up pressed up under the leaf, or wedged between two)
        if (axis != net.minecraft.core.Direction.Axis.Y
                && shape.max(net.minecraft.core.Direction.Axis.Y) - shape.min(net.minecraft.core.Direction.Axis.Y) < 0.5) return false;
        return high ? shape.max(axis) >= 1.0 - REACH : shape.min(axis) <= REACH;
    }

    /** How far short of a cell's side rock may stop and still be clung to (the box reaches 0.08 past its own sides). */
    private static final double REACH = 0.06;

    /** Rock to cling to: something with a real collision shape (not a carpet), neither harmful nor wet. */
    private static boolean solid(BlockGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (harmful(s) || !s.getFluidState().isEmpty()) return false;
        VoxelShape shape = s.getCollisionShape(level, p);
        return !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > THIN;
    }

    /** A collision shape no taller than this (carpets, the den) lies under the box without lifting it. */
    private static final double THIN = 0.1;

    private static boolean harmful(BlockState s) {
        return s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                || s.is(BlockTags.FIRE) || s.is(Blocks.WITHER_ROSE) || s.is(BlockTags.CAMPFIRES);
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        return typeOf(classify(level, x, y, z, entityWidth, entityHeight, entityDepth));
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        return typeOf(classify(level, x, y, z, 2, 1, 2));
    }
}
