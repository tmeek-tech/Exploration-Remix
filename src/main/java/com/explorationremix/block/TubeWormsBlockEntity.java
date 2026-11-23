package com.explorationremix.block;

import com.explorationremix.registry.EREntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;

/**
 * How a clump of tube worms' plumes move (0.20.6), kept only in the client's world ({@link TubeWormsBlock#newBlockEntity}):
 * when they last came out and went in (by the client's clock), and whether they are drawn moving (client.TubeWormsClient
 * decides, client.render.TubeWormsRenderer draws them) or left still in the chunk's model. Nothing is saved.
 *
 * <p>The chunk's model (client.model.TubeWormsModel) is handed this block entity through {@link #WORMS} as it is built,
 * leaves the plumes out while {@link #live}, and says here what it made and when ({@link #meshed}), so the renderer
 * takes over from it in the frame the new model shows, never drawing the plumes twice or not at all.
 */
public class TubeWormsBlockEntity extends BlockEntity {
    /** The chunk model's key to this block entity. */
    public static final ModelProperty<TubeWormsBlockEntity> WORMS = new ModelProperty<>();
    /** A time long past: worms found out (or in) when their chunk came are settled so. */
    public static final long LONG_AGO = Long.MIN_VALUE / 4;

    /** The client's side of the worms (client.TubeWormsClient sets it): told when one comes, goes and turns. */
    public interface Watcher {
        void loaded(TubeWormsBlockEntity be);

        void removed(TubeWormsBlockEntity be);

        void turned(TubeWormsBlockEntity be, boolean out);
    }

    public static volatile Watcher watcher;

    private boolean out;
    /** The client's game time when the worms last came out and last went in. */
    public long outAt = LONG_AGO, inAt = LONG_AGO;
    /** Until when they move of their own (coming out, going in), near the player or not. */
    public long movingUntil = LONG_AGO;
    /** Near enough to the player to be seen wiggle. */
    public boolean near;
    /** Drawn moving by the renderer, so left out of the chunk's model; read as the chunk's model is built. */
    public volatile boolean live;
    /** When they last became live, and when they began to settle (to still) before leaving it ({@link #LONG_AGO} if not). */
    public long liveAt = LONG_AGO, settleAt = LONG_AGO;
    /** What the chunk's model last made of them (plumes left out or in) and the one before, and in which frame. */
    public volatile boolean meshHides, meshHidBefore;
    public volatile long meshFrame = LONG_AGO;
    /** The renderer's plumes for this block's look, and the model reload they were read from. */
    public Object plumes;
    public int plumesFrom = -1;
    private AABB box;

    public TubeWormsBlockEntity(BlockPos pos, BlockState state) {
        super(EREntities.TUBE_WORMS_TILE.get(), pos, state);
        out = state.getValue(TubeWormsBlock.OUT);
    }

    public boolean out() { return out; }

    @Override
    @SuppressWarnings("deprecation")
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        plumes = null;                                  // read again from the new state's look
        boolean o = state.getValue(TubeWormsBlock.OUT);
        if (o == out) return;
        out = o;
        Watcher w = watcher;
        if (w != null && level != null && level.isClientSide) w.turned(this, o);
    }

    /** The chunk's model was built in frame {@code frame}, with the plumes left out ({@code hides}) or in. */
    public void meshed(boolean hides, long frame) {
        meshHidBefore = meshHides;
        meshHides = hides;
        meshFrame = frame;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        Watcher w = watcher;
        if (w != null && level != null && level.isClientSide) w.loaded(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        Watcher w = watcher;
        if (w != null && level != null && level.isClientSide) w.removed(this);
    }

    @Override
    public ModelData getModelData() { return ModelData.builder().with(WORMS, this).build(); }

    /** The plumes reach a little past the block (a wall clump's lean over its top). */
    @Override
    public AABB getRenderBoundingBox() {
        if (box == null) box = new AABB(worldPosition).inflate(0.3);
        return box;
    }
}
