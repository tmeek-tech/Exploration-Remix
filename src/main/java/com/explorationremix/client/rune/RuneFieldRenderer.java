package com.explorationremix.client.rune;

import com.explorationremix.rune.effect.RuneField;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.RuneId;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * A Field: a glowing ring flat on the ground, as wide as the Field reaches, in its element's colour. It breathes gently
 * and turns slowly, comes up over a few ticks, and fades away over the last fifth of its life.
 */
final class RuneFieldRenderer extends EntityRenderer<RuneField> {
    private static final ResourceLocation TEXTURE = GlowSprite.texture("field");
    /** Just above the ground under it, so the ground does not flicker through. */
    private static final float LIFT = 0.02F;
    /** Ticks it takes to come up. */
    private static final float RISE = 5.0F;
    /** The share of its life over which it fades at the end. */
    private static final float FADE = 0.2F;

    RuneFieldRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(RuneField f) { return TEXTURE; }

    @Override
    public boolean shouldRender(RuneField f, Frustum frustum, double camX, double camY, double camZ) {
        // the ring is far wider than the Field's box
        if (!f.shouldRender(camX, camY, camZ)) return false;
        double r = Math.max(0.5F, f.radius()) + 0.25;
        return frustum.isVisible(new AABB(f.getX() - r, f.getY() - 0.25, f.getZ() - r, f.getX() + r, f.getY() + 0.5, f.getZ() + r));
    }

    @Override
    public void render(RuneField f, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float radius = f.radius();
        if (!f.isInvisible() && radius > 0.0F) {
            float t = f.tickCount + partialTick;
            float left = 1.0F - f.progress(partialTick);
            float fade = Mth.clamp(left / FADE, 0.0F, 1.0F) * Mth.clamp(t / RISE, 0.0F, 1.0F);
            float breath = Mth.sin(t * 0.15F);
            float alpha = fade * (0.8F + 0.2F * breath);
            if (alpha > 0.004F) {
                GlowSprite.flat(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE)), LIFT, radius * (1.0F + 0.02F * breath),
                        t * 0.6F, RuneFx.colour(RuneId.FIELD, f.element()), alpha);
            }
        }
        super.render(f, yaw, partialTick, pose, buffers, light);
    }
}
