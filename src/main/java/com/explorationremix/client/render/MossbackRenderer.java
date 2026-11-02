package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.MossbackModel;
import com.explorationremix.creature.MossbackEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the Mossback against whatever rock it clings to: its belly to the floor, a wall or the ceiling, turned the
 * way it goes, easing from one to the next as it rounds an edge. Two glowing layers go over the texture: its eyes,
 * always faintly lit, and its markings (flank spots, frill rims, throat), dark while it keeps still and bright green
 * (pale blue for the frost cousin) once it moves. Both are drawn as eyes are, so shader packs light them.
 */
public class MossbackRenderer extends MobRenderer<MossbackEntity, MossbackModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ExplorationRemix.MOD_ID, "mossback"), "main");
    private static final ResourceLocation[] TEXTURES = textures("");
    private static final ResourceLocation[] EYES = textures("_eyes");
    private static final ResourceLocation[] MARKINGS = textures("_flare");

    private static ResourceLocation[] textures(String suffix) {
        return new ResourceLocation[]{
                new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/mossback/mossback" + suffix + ".png"),
                new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/mossback/mossback_frost" + suffix + ".png")};
    }

    public MossbackRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new MossbackModel(ctx.bakeLayer(LAYER)), 0.9F);
        addLayer(new Glow(this, EYES, true));
        addLayer(new Glow(this, MARKINGS, false));
    }

    @Override
    public ResourceLocation getTextureLocation(MossbackEntity e) {
        return TEXTURES[Mth.clamp(e.variant(), 0, 1)];
    }

    @Override
    protected void setupRotations(MossbackEntity e, PoseStack pose, float bob, float bodyYaw, float partialTick) {
        Quaternionf q = new Quaternionf(e.orientO).slerp(e.orient, partialTick);
        Vector3f up = q.transform(new Vector3f(0, 1, 0));
        float w = e.getBbWidth() / 2.0F, h = e.getBbHeight() / 2.0F;
        float ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        // from the box's middle out to the rock: the feet go there
        pose.translate(-up.x * ext, h - up.y * ext, -up.z * ext);
        pose.mulPose(q);
        if (e.deathTime > 0) {
            float f = Mth.sqrt(Math.min(1.0F, (e.deathTime + partialTick - 1.0F) / 20.0F * 1.6F));
            pose.mulPose(Axis.ZP.rotationDegrees(f * getFlipDegrees(e)));
        }
    }

    @Override
    protected float getFlipDegrees(MossbackEntity e) {
        return 180.0F;
    }

    /** One glowing layer. Eyes keep a faint light while still; markings go fully dark. */
    private static final class Glow extends RenderLayer<MossbackEntity, MossbackModel> {
        private final ResourceLocation[] textures;
        private final boolean eyes;

        Glow(RenderLayerParent<MossbackEntity, MossbackModel> parent, ResourceLocation[] textures, boolean eyes) {
            super(parent);
            this.textures = textures;
            this.eyes = eyes;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, MossbackEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            float g = e.glow(partialTick);
            float level = eyes ? 0.3F + 0.7F * g : g;
            if (e.isDeadOrDying()) level *= Math.max(0.0F, 1.0F - e.deathTime / 20.0F);
            if (level < 0.02F) return;
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(textures[Mth.clamp(e.variant(), 0, 1)]));
            getParentModel().renderToBuffer(pose, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, level, level, level, 1.0F);
        }
    }
}
