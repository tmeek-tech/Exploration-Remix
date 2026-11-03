package com.explorationremix.creature.climb;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
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
 * halves open, so it rounds a ledge but never cuts through a corner of rock.
 */
public class ClimbNodeEvaluator extends NodeEvaluator {
    /** A node's kind; FLOOR to CEILING and WATER can be walked. */
    public static final byte BLOCKED = 0, OPEN = 1, FLOOR = 2, WALL = 3, CEILING = 4, WATER = 5;

    private final Long2ByteMap kinds = new Long2ByteOpenHashMap();
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
    }

    @Override
    public void done() {
        super.done();
        kinds.clear();
    }

    /**
     * The node whose box is centred nearest the mob, or else the walkable one round it whose box is nearest where the
     * mob really is (a box straddling two nodes starts from the one it stands or clings in, not one it would have to
     * squeeze down or round an edge to reach).
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
                    if ((dx | dy | dz) == 0 || !walkable(k)) continue;
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
            if (axes == 2) {
                // round an edge only through an open straight half, never through rock
                boolean viaFirst, viaSecond;
                if (s[0] == 0) { viaFirst = kind(from.x, y, from.z) != BLOCKED; viaSecond = kind(from.x, from.y, z) != BLOCKED; }
                else if (s[1] == 0) { viaFirst = kind(x, from.y, from.z) != BLOCKED; viaSecond = kind(from.x, from.y, z) != BLOCKED; }
                else { viaFirst = kind(x, from.y, from.z) != BLOCKED; viaSecond = kind(from.x, y, from.z) != BLOCKED; }
                if (!viaFirst && !viaSecond) continue;
            }
            Node n = getNode(x, y, z);
            if (n.closed) continue;
            n.type = typeOf(k);
            n.costMalus = malus(k);
            out[count++] = n;
        }
        return count;
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
        if (raised) {
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
     * Rock in {@code p} that reaches the face it shares with the box: its high side along {@code axis} when it lies
     * on the box's low side ({@code high}), else its low side.
     */
    private static boolean face(BlockGetter level, BlockPos p, net.minecraft.core.Direction.Axis axis, boolean high) {
        if (!solid(level, p)) return false;
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
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
