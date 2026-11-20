package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.Neon;
import com.explorationremix.client.model.MantisModel;
import com.explorationremix.creature.StiltMantisEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws the stilt mantis as its body stands on the rock (0.20.0: see MantisModel and creature.body): its colour's
 * texture, then its neon drawn again over it as eyes are (so it glows as the glow mats round it do, and shader packs
 * light it), flaring brighter in its startle display and going out as it dies.
 */
public class StiltMantisRenderer extends MobRenderer<StiltMantisEntity, MantisModel> {
    private static final ResourceLocation[] TEXTURES = textures("");
    private static final ResourceLocation[] GLOW = textures("_glow");

    private static ResourceLocation[] textures(String suffix) {
        ResourceLocation[] out = new ResourceLocation[Neon.values().length];
        for (Neon n : Neon.values())
            out[n.ordinal()] = new ResourceLocation(ExplorationRemix.MOD_ID,
                    "textures/entity/stilt_mantis/stilt_mantis_" + n.getSerializedName() + suffix + ".png");
        return out;
    }

    public StiltMantisRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new MantisModel(), 0.6F);
        addLayer(new Glow(this));
    }

    @Override
    public ResourceLocation getTextureLocation(StiltMantisEntity e) {
        return TEXTURES[e.colour().ordinal()];
    }

    /** Its body stands in the world as it is, turned and (dying) rolled over already: nothing to turn here. */
    @Override
    protected void setupRotations(StiltMantisEntity e, PoseStack pose, float bob, float bodyYaw, float partialTick) {
    }

    private static final class Glow extends RenderLayer<StiltMantisEntity, MantisModel> {
        Glow(RenderLayerParent<StiltMantisEntity, MantisModel> parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, StiltMantisEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            float level = 1.0F;
            if (e.isDeadOrDying()) level *= Math.max(0.0F, 1.0F - e.deathTime / 18.0F);
            if (level < 0.02F || e.isInvisible()) return;
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(GLOW[e.colour().ordinal()]));
            getParentModel().renderBright(pose, vc, level, level, level, 1.0F);
            // the startle display: its neon flares, drawn again over itself (it adds up)
            float flare = e.flare(partialTick) * 0.7F;
            if (flare > 0.02F && !e.isDeadOrDying()) getParentModel().renderBright(pose, vc, flare, flare, flare, 1.0F);
        }
    }
}
