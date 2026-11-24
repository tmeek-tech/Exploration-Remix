package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.Neon;
import com.explorationremix.client.model.LurkerModel;
import com.explorationremix.creature.ChimneyLurkerEntity;
import com.explorationremix.creature.body.LurkerPose;
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
 * Draws the vent urchin as its body stands on the rock (0.21.3; see LurkerModel and creature.body): its colour's
 * texture (black), then its neon (the bands down every walking spine, Taran's 0.21.3 card: "Bands down every spine")
 * drawn again over it as eyes are, at the brightness it has them: dark while it sits in its vent's mouth (Taran: "mostly
 * black while it's waiting in ambush"), flashing on as it sees someone, pulses running down its body while it chases
 * (0.21.4, quicker the nearer its quarry: see ChimneyLurkerEntity.Pulses), surging as it crouches to leap, fading as it
 * climbs home and going out as it dies. Eyes add their light to what is under them, so
 * the texture keeps only a dark tint under its glowing pixels (tools/chimney_lurker.py), and the two together come out
 * its vent's colour.
 */
public class ChimneyLurkerRenderer extends MobRenderer<ChimneyLurkerEntity, LurkerModel> {
    private static final ResourceLocation[] TEXTURES = textures("");
    private static final ResourceLocation[] GLOW = textures("_glow");
    private static final float SHADOW = 0.9F;

    private static ResourceLocation[] textures(String suffix) {
        ResourceLocation[] out = new ResourceLocation[Neon.values().length];
        for (Neon n : Neon.values())
            out[n.ordinal()] = new ResourceLocation(ExplorationRemix.MOD_ID,
                    "textures/entity/chimney_lurker/chimney_lurker_" + n.getSerializedName() + suffix + ".png");
        return out;
    }

    public ChimneyLurkerRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new LurkerModel(), SHADOW);
        addLayer(new Glow(this));
    }

    @Override
    public ResourceLocation getTextureLocation(ChimneyLurkerEntity e) {
        return TEXTURES[e.colour().ordinal()];
    }

    /** Its body stands in the world as it is, turned and (dying) rolled over already: nothing to turn here. */
    @Override
    protected void setupRotations(ChimneyLurkerEntity e, PoseStack pose, float bob, float bodyYaw, float partialTick) {
    }

    /** No shadow under it while it sits in its vent's mouth or clings to its rock (it would fall across the flue below it). */
    @Override
    public void render(ChimneyLurkerEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        shadowRadius = e.clingingForDraw() ? 0.0F : SHADOW;
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    private static final class Glow extends RenderLayer<ChimneyLurkerEntity, LurkerModel> {
        Glow(RenderLayerParent<ChimneyLurkerEntity, LurkerModel> parent) { super(parent); }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, ChimneyLurkerEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            float level = e.glow(partialTick);
            if (e.isDeadOrDying()) level *= Math.max(0.0F, 1.0F - e.deathTime / 18.0F);
            if (e.isInvisible()) return;
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(GLOW[e.colour().ordinal()]));
            // chasing, pulses run down it (0.21.4): its glow shaded down its body
            if (level >= 0.02F) getParentModel().renderGlow(pose, vc, level, e.pulseShade(partialTick));
            // the flash as it sees someone, and the surge as it crouches: its markings drawn again over themselves (it adds up)
            float flash = e.flash(partialTick) * 0.8F;
            if (flash > 0.02F && !e.isDeadOrDying()) getParentModel().renderGlow(pose, vc, flash, LurkerPose.STEADY);
        }
    }
}
