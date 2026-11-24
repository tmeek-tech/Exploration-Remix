package com.explorationremix.client.model;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.TubeWormsBlockEntity;
import com.explorationremix.client.TubeWormsClient;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tube worms' chunk model (0.20.6): their blockstate models, which leave the plumes out for a clump drawn moving
 * (block.TubeWormsBlockEntity's live, client.TubeWormsClient) and note on it, as the chunk is built, what they made
 * and in which frame, so client.render.TubeWormsRenderer takes the plumes over from them without a gap or a double.
 * Without the block entity (an item, a picture of the block, a renderer that hands no model data) they are the plain
 * models, plumes and all.
 */
public final class TubeWormsModel extends BakedModelWrapper<BakedModel> {
    /** The plumes' textures (the plumes and their glow): only those leave the model. */
    public static final String PLUMES = "block/tube_worm_plumes_";
    /** Each look's quads without its plumes, by the look's own list. */
    private final Map<List<BakedQuad>, List<BakedQuad>> tubesOnly = Collections.synchronizedMap(new IdentityHashMap<>());

    private TubeWormsModel(BakedModel original) { super(original); }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data,
                                             @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        if (side != null) return quads;                       // the plumes are all unculled
        TubeWormsBlockEntity be = data.get(TubeWormsBlockEntity.WORMS);
        if (be == null) return quads;
        boolean hide = be.live;
        // a chunk being built asks with its layer; a block being broken (its cracks) asks with none
        if (renderType != null) be.meshed(hide, TubeWormsClient.frame);
        return hide ? tubesOnly.computeIfAbsent(quads, TubeWormsModel::withoutPlumes) : quads;
    }

    /** Whether a quad is a plume's (or its glow's). */
    public static boolean plume(BakedQuad q) { return q.getSprite().contents().name().getPath().startsWith(PLUMES); }

    private static List<BakedQuad> withoutPlumes(List<BakedQuad> quads) {
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) if (!plume(q)) out.add(q);
        return out;
    }

    /** Wraps every blockstate model of the floor's and the wall's tube worms. */
    public static void wrap(ModelEvent.ModifyBakingResult e) {
        Map<ResourceLocation, BakedModel> models = e.getModels();
        for (Map.Entry<ResourceLocation, BakedModel> en : models.entrySet()) {
            if (!(en.getKey() instanceof ModelResourceLocation mrl) || !mrl.getNamespace().equals(ExplorationRemix.MOD_ID)) continue;
            if (!mrl.getPath().equals("tube_worms") && !mrl.getPath().equals("wall_tube_worms")) continue;
            if (mrl.getVariant().equals("inventory") || en.getValue() instanceof TubeWormsModel) continue;
            en.setValue(new TubeWormsModel(en.getValue()));
        }
    }
}
