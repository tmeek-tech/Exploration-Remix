package com.explorationremix.client.rune.stone;

import com.explorationremix.client.rune.GlowSprite;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.rune.stone.FreedRune;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * A rune freed from a broken rune stone (0.23.1): the rune itself, turning, at full brightness, in a soft light-blue
 * halo. It swells out of the stone as it rises, turns slowly as it hangs, and spins as it flies to its player.
 */
public final class FreedRuneRenderer extends EntityRenderer<FreedRune> {
    private static final ResourceLocation HALO = GlowSprite.texture("halo");
    private final ItemRenderer items;

    public FreedRuneRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.items = ctx.getItemRenderer();
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(FreedRune r) { return TextureAtlas.LOCATION_BLOCKS; }

    @Override
    public void render(FreedRune r, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        ItemStack stack = r.item();
        if (stack.isEmpty()) return;
        float age = r.age() + pt;
        int wait = FreedRune.RISE + FreedRune.HANG + FreedRune.SECOND_WAIT * r.index();
        float grow = Mth.clamp(age / FreedRune.RISE, 0.0F, 1.0F);
        grow = 1.0F - (1.0F - grow) * (1.0F - grow);
        float size = 0.3F + 0.7F * grow;
        boolean flying = age >= wait;
        // turning: slowly as it rises and hangs, then fast once it flies
        float spin = r.getYRot() + (flying ? wait * 5.0F + (age - wait) * 22.0F : age * 5.0F);
        float pulse = 0.85F + 0.15F * Mth.sin(age * 0.45F + r.getId());
        pose.pushPose();
        pose.translate(0.0F, r.getBbHeight() * 0.5F, 0.0F);
        GlowSprite.facing(pose, buffers.getBuffer(GlowRender.glow(HALO)), entityRenderDispatcher.cameraOrientation(), 0.5F * size * pulse, 0.0F, 0.06F,
                RuneStoneClient.scale(0xBFD9FF, flying ? 0.6F : 0.5F), 1.0F);
        GlowSprite.facing(pose, buffers.getBuffer(GlowRender.glow(HALO)), entityRenderDispatcher.cameraOrientation(), 0.2F * size, 0.0F, 0.04F,
                RuneStoneClient.scale(0xE6F2FF, 0.45F * pulse), 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        float s = 1.15F * size;
        pose.scale(s, s, s);
        // the ground pose lifts an item an eighth of a block: down again, so it turns round its middle
        pose.translate(0.0F, -0.125F, 0.0F);
        items.renderStatic(stack, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, r.level(), r.getId());
        pose.popPose();
        super.render(r, yaw, pt, pose, buffers, light);
    }
}
