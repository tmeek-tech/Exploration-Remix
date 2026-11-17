package com.explorationremix.world.compat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.fml.loading.LoadingModList;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps Terrain Slabs' slabs true to the ground they lie on in Taran's pack (Taran, 2026-11-16).
 *
 * <p>Terrain Slabs lays its slabs over every chunk at underground_structures, from the surface down to the world's
 * floor: a bottom slab in the open block at the foot of each one-block step of a floor, and a top slab in place of the
 * lowest block of each step of a ceiling, each made of the block it rests on or hangs from. Three things go wrong in the
 * pack:
 * <ul>
 *     <li>Features that run after it change that block and leave the slab in the old rock. In WWOO's stony peaks the
 *     "volcanic flows" (at strongholds) turn the stone of whole mountains, caves and all, into smooth basalt, so the
 *     caves' dark floors are outlined with light stone steps. Terrain Slabs puts this right only for ores and disks.
 *     Here, whenever any later feature or structure sets the block a laid slab rests on (or hangs from) to a block that
 *     has a slab, the slab becomes that slab, keeping its half and its water.</li>
 *     <li>In WWOO's snowy plains and snowy taiga each step's edge is a bare line of grass, gravel or stone through the
 *     snow. Those biomes are cold enough for vanilla's snowfall only in places (their temperature is "frozen", which
 *     leaves warm patches); vanilla's snowfall does cover slabs (Terrain Slabs lowers the snow half a block onto them),
 *     but WWOO lays the rest of their snow in patches of its own that lie only on full blocks. Here a laid bottom slab
 *     with a snow layer beside it, at its height or one block up, takes a snow layer too (lowered onto it, and a grass
 *     slab under it turns snowy), whichever of the two was laid first. That snow spreads no further, so bare ground
 *     stays bare.</li>
 *     <li>It takes powder snow for open air (powder snow has no collision box), so on snowy slopes, groves and peaks
 *     it lays rock slabs inside the powder snow the surface put down, and the snow over them is left floating half a
 *     block up. Here it lays no slab in place of powder snow, which keeps the snow whole.</li>
 * </ul>
 *
 * <p>All three hang off {@code WorldGenRegion.setBlock} ({@code WorldGenRegionMixin}), the way every feature and
 * structure writes a block. Nothing changes without Terrain Slabs, with {@code compat.terrainSlabs} off in
 * {@code explorationremix-world.toml}, or for slabs a player placed. Safe on any thread.
 */
public final class TerrainSlabsCompat {
    private TerrainSlabsCompat() {}

    /** Terrain Slabs' map from a block to its slab, {@code ModSlabsMap.getSlabForBlock(Block)}; null without the mod. */
    @Nullable
    private static final MethodHandle SLAB_FOR;
    /**
     * Its slabs of soil that lie on another soil and hang from a ceiling as that soil (a grass slab lies on dirt and
     * hangs as a dirt slab): {@code IDuelSlab}, whose {@code getDuelBlock()} is the block it lies on and whose
     * {@code getDuel().getBlock()} is the slab it hangs as.
     */
    @Nullable
    private static final Class<?> DUEL;
    @Nullable
    private static final MethodHandle DUEL_BLOCK, DUEL_COPY, COPY_BLOCK;
    /** Whether it lowers a block onto the slab under it as the block is written, {@code IOffsetState.canGenerateOntop}. */
    @Nullable
    private static final MethodHandle CAN_ONTOP;

    static {
        MethodHandle slabFor = null, duelBlock = null, duelCopy = null, copyBlock = null, canOntop = null;
        Class<?> duel = null;
        if (LoadingModList.get() != null && LoadingModList.get().getModFileById("terrain_slabs") != null) {
            ClassLoader cl = TerrainSlabsCompat.class.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            try {
                Class<?> map = Class.forName("net.countered.terrainslabs.block.ModSlabsMap", false, cl);
                slabFor = lookup.findStatic(map, "getSlabForBlock", MethodType.methodType(Block.class, Block.class));
                try {
                    Class<?> d = Class.forName("net.countered.terrainslabs.block.interfaces.IDuelSlab", false, cl);
                    Class<?> copy = Class.forName("net.countered.terrainslabs.block.interfaces.ISlabCopy", false, cl);
                    duelBlock = lookup.findVirtual(d, "getDuelBlock", MethodType.methodType(Block.class));
                    duelCopy = lookup.findVirtual(d, "getDuel", MethodType.methodType(copy));
                    copyBlock = lookup.findVirtual(copy, "getBlock", MethodType.methodType(Block.class));
                    duel = d;
                } catch (ReflectiveOperationException | LinkageError e) {
                    // Without the duel slabs a soil slab follows the soil under it as any other slab; everything else works.
                    duelBlock = duelCopy = copyBlock = null;
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                ExplorationRemix.LOGGER.warn("Terrain Slabs is installed but its slab map was not found (a newer version?); its slabs stay as it lays them", e);
                slabFor = null;
            }
            try {
                Class<?> offset = Class.forName("net.countered.terrainslabs.block.interfaces.IOffsetState", false, cl);
                canOntop = lookup.findStatic(offset, "canGenerateOntop",
                        MethodType.methodType(boolean.class, BlockGetter.class, BlockPos.class, BlockState.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                ExplorationRemix.LOGGER.warn("Terrain Slabs is installed but its offset check was not found (a newer version?); snow beside its slabs stays off them", e);
            }
        }
        SLAB_FOR = slabFor;
        DUEL = duel;
        DUEL_BLOCK = duelBlock;
        DUEL_COPY = duelCopy;
        COPY_BLOCK = copyBlock;
        CAN_ONTOP = canOntop;
    }

    /** Set when a server loads its level, from the config. */
    private static volatile boolean on;
    /** This thread is changing a slab itself (the change comes back through the same hooks). */
    private static final ThreadLocal<boolean[]> BUSY = ThreadLocal.withInitial(() -> new boolean[1]);
    /** Each slab block's "generated" property (Terrain Slabs marks the slabs it lays), or {@link #NONE}. */
    private static final Map<Block, Object> GENERATED = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /** Called as a server loads its level, before any chunk is generated. */
    public static void onLoadLevel() {
        boolean want = ERConfig.INSTANCE.terrainSlabs.get();
        on = want && SLAB_FOR != null;
        if (on) ExplorationRemix.LOGGER.info("Terrain Slabs compat on: laid slabs follow the ground later features change, take the snow laid beside them, and none are laid in powder snow");
        else if (SLAB_FOR != null) ExplorationRemix.LOGGER.info("Terrain Slabs compat off by config");
    }

    public static boolean on() {
        return on;
    }

    /**
     * Before a block is written during world generation: false refuses the write. Refused: a slab Terrain Slabs lays in
     * place of powder snow.
     */
    public static boolean allow(WorldGenRegion region, BlockPos pos, BlockState state) {
        if (!on || !(state.getBlock() instanceof SlabBlock) || !laid(state) || BUSY.get()[0]) return true;
        if (!region.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) return true;
        return !region.getBlockState(pos).is(Blocks.POWDER_SNOW);
    }

    /**
     * After a block was written during world generation: a slab Terrain Slabs laid on top of it (a bottom slab) or
     * under it (a top slab) becomes that block's slab, if it has one; a snow layer also covers the laid bottom slabs
     * beside it; and a bottom slab Terrain Slabs has just laid takes the snow already lying beside it.
     *
     * @param writeRadius how many chunks from its centre the region may write to
     */
    public static void written(WorldGenRegion region, BlockPos pos, BlockState state, int writeRadius) {
        if (!on) return;
        boolean[] busy = BUSY.get();
        if (busy[0]) return;
        Block block = state.getBlock();
        if (block instanceof SlabBlock) {
            if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM || !laid(state)) return;
            busy[0] = true;
            try {
                snowFromBeside(region, pos, state);
            } finally {
                busy[0] = false;
            }
            return;
        }
        boolean snow = state.is(Blocks.SNOW);
        Block slab = slabFor(block);
        if (slab == null && !snow) return;
        busy[0] = true;
        try {
            if (slab != null) {
                follow(region, pos.above(), block, slab, SlabType.BOTTOM);
                follow(region, pos.below(), block, hanging(slab), SlabType.TOP);
            }
            if (snow) snowBeside(region, pos, state.getValue(SnowLayerBlock.LAYERS), writeRadius);
        } finally {
            busy[0] = false;
        }
    }

    /** Turns the laid slab of the given half at a position into the slab of the block now next to it. */
    private static void follow(WorldGenRegion region, BlockPos at, Block ground, @Nullable Block slab, SlabType half) {
        if (slab == null || region.isOutsideBuildHeight(at)) return;
        BlockState s = region.getBlockState(at);
        Block b = s.getBlock();
        if (b == slab || !(b instanceof SlabBlock) || s.getValue(SlabBlock.TYPE) != half || !laid(s)) return;
        // A grass slab lies on the dirt Terrain Slabs turned the grass under it into: that dirt is its own ground.
        if (half == SlabType.BOTTOM && duelBlock(b) == ground) return;
        region.setBlock(at, slab.withPropertiesOf(s), 2);
    }

    /**
     * A snow layer of the given depth was laid at a position: each laid bottom slab beside it, at its height (the slab
     * lies on the same ground as the snow) or one block lower (the slab is the step down from it), takes a layer too.
     */
    private static void snowBeside(WorldGenRegion region, BlockPos pos, int layers, int writeRadius) {
        if (CAN_ONTOP == null) return;
        ChunkPos center = region.getCenter();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(d);
            if (Math.abs(SectionPos.blockToSectionCoord(side.getX()) - center.x) > writeRadius
                    || Math.abs(SectionPos.blockToSectionCoord(side.getZ()) - center.z) > writeRadius) continue;
            for (int down = 0; down <= 1; down++) {
                BlockPos at = side.below(down);
                if (region.isOutsideBuildHeight(at)) continue;
                BlockState s = region.getBlockState(at);
                if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && laid(s)) {
                    cover(region, at, s, down == 0 ? Math.max(1, layers - 4) : layers);
                }
            }
        }
    }

    /**
     * Terrain Slabs has just laid a bottom slab: if a snow layer already lies beside it, at its height or one block up
     * (a chunk next door can be decorated first, and its snow patches reach into this one before the slabs are laid),
     * the slab takes a layer too.
     */
    private static void snowFromBeside(WorldGenRegion region, BlockPos pos, BlockState slab) {
        if (CAN_ONTOP == null) return;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(d);
            if (!region.hasChunk(SectionPos.blockToSectionCoord(side.getX()), SectionPos.blockToSectionCoord(side.getZ()))) continue;
            for (int up = 0; up <= 1; up++) {
                BlockState s = region.getBlockState(side.above(up));
                if (s.is(Blocks.SNOW)) {
                    int layers = s.getValue(SnowLayerBlock.LAYERS);
                    cover(region, pos, slab, up == 0 ? Math.max(1, layers - 4) : layers);
                    return;
                }
            }
        }
    }

    /**
     * Lays a snow layer of the given depth on a laid bottom slab with open air over it, where Terrain Slabs lowers it
     * onto the slab (so none is left floating); a grass slab under it turns snowy. On the same ground as the snow beside
     * it the slab's snow is half a block thinner, so both tops meet. Snow laid here spreads no further.
     */
    private static void cover(WorldGenRegion region, BlockPos at, BlockState slab, int layers) {
        if (slab.hasProperty(BlockStateProperties.WATERLOGGED) && slab.getValue(BlockStateProperties.WATERLOGGED)) return;
        BlockPos over = at.above();
        if (region.isOutsideBuildHeight(over) || !region.getBlockState(over).isAir()) return;
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
        if (!canOntop(region, over, snow)) return;
        region.setBlock(over, snow, 2);
        if (slab.hasProperty(BlockStateProperties.SNOWY) && !slab.getValue(BlockStateProperties.SNOWY)) {
            region.setBlock(at, slab.setValue(BlockStateProperties.SNOWY, true), 2);
        }
    }

    /** Whether Terrain Slabs lowers this block onto the slab under the position as it is written. */
    private static boolean canOntop(BlockGetter level, BlockPos pos, BlockState state) {
        if (CAN_ONTOP == null) return false;
        try {
            return (boolean) CAN_ONTOP.invokeExact(level, pos, state);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A slab Terrain Slabs laid while generating the world (its own "generated" mark). */
    static boolean laid(BlockState s) {
        Object p = GENERATED.computeIfAbsent(s.getBlock(), TerrainSlabsCompat::generatedProperty);
        return p != NONE && s.getValue((BooleanProperty) p);
    }

    private static Object generatedProperty(Block b) {
        if (!(b instanceof SlabBlock)) return NONE;
        for (Property<?> p : b.getStateDefinition().getProperties()) {
            if (p instanceof BooleanProperty bp && "generated".equals(p.getName())) return bp;
        }
        return NONE;
    }

    /** Terrain Slabs' slab for a block, or null. */
    @Nullable
    static Block slabFor(Block block) {
        if (SLAB_FOR == null) return null;
        try {
            return (Block) SLAB_FOR.invokeExact(block);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The slab Terrain Slabs hangs from a ceiling of the block whose slab this is (a soil slab hangs as its copy). */
    @Nullable
    private static Block hanging(Block slab) {
        if (DUEL == null || DUEL_COPY == null || COPY_BLOCK == null || !DUEL.isInstance(slab)) return slab;
        try {
            return (Block) COPY_BLOCK.invoke(DUEL_COPY.invoke(slab));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The block a soil slab lies on (dirt for a grass slab), or null for any other slab. */
    @Nullable
    private static Block duelBlock(Block slab) {
        if (DUEL == null || DUEL_BLOCK == null || !DUEL.isInstance(slab)) return null;
        try {
            return (Block) DUEL_BLOCK.invoke(slab);
        } catch (Throwable t) {
            return null;
        }
    }
}
