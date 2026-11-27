package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.EmberUrchinModel;
import com.explorationremix.creature.EmberUrchinEntity;
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
 * Draws the ember urchin as its body stands braced in the tunnel (0.23.7; see EmberUrchinModel and creature.body): its
 * black plated texture, then its embers (the seams between its plates, its spines' hot points, the coat's longer
 * spines' tips) drawn again over it as eyes are: the breath rolling slowly over its ball as coals brighten and dim,
 * each point flickering to its own beat, all of it flaring hot (drawn twice over) as someone comes near or it is hurt,
 * and going out as it dies. Eyes add their light to what is under them, so the texture keeps only a dark tint under its
 * glowing pixels (tools/ember_urchin.py), and the two together come out ember orange.
 */
public class EmberUrchinRenderer extends MobRenderer<EmberUrchinEntity, EmberUrchinModel> {
    private static final ResourceLocation TEXTURE = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/ember_urchin/ember_urchin.png");
    private static final ResourceLocation GLOW = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/ember_urchin/ember_urchin_glow.png");

    public EmberUrchinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new EmberUrchinModel(), 0.7F);
        addLayer(new Glow(this));
    }

    @Override
    public ResourceLocation getTextureLocation(EmberUrchinEntity e) { return TEXTURE; }

    /** Its body stands in the world as it is (it never turns, and dying its spines droop on their own): nothing to turn here. */
    @Override
    protected void setupRotations(EmberUrchinEntity e, PoseStack pose, float bob, float bodyYaw, float partialTick) {
    }

    private static final class Glow extends RenderLayer<EmberUrchinEntity, EmberUrchinModel> {
        Glow(RenderLayerParent<EmberUrchinEntity, EmberUrchinModel> parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, EmberUrchinEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            if (e.isInvisible()) return;
            float flare = e.flare(partialTick);
            float level = 0.82F + 0.18F * flare;
            if (e.isDeadOrDying()) level *= Math.max(0.0F, 1.0F - e.deathTime / 16.0F);
            if (level < 0.02F) return;
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(GLOW));
            getParentModel().renderGlow(pose, vc, level, e.embers(partialTick));
            // flared, its embers burn hot: drawn again over themselves (it adds up)
            if (flare > 0.02F && !e.isDeadOrDying()) getParentModel().renderGlow(pose, vc, flare * 0.4F, e.embers(partialTick));
        }
    }
}
