package com.explorationremix.client.rune.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * How the runes' light is drawn: added onto what is behind it (so it glows, and black in a texture is nothing), at full
 * brightness, seen from both sides, and never hiding what is behind it. Two ways: {@link #SHEET} for the glow particles,
 * and {@link #glow} for entities (the ribbons behind bolts, the halos round bolts and motes, a Field's glow).
 *
 * <p>Textures drawn with {@link #glow} are premultiplied (their colour already darkened by their alpha), since the
 * blend there adds the colour as it is; the particle sheet's sprites are plain white with alpha.
 */
public final class GlowRender extends RenderType {
    private GlowRender(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, Runnable setup,
                       Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
    }

    private static final Function<ResourceLocation, RenderType> GLOW = Util.memoize(tex -> create("explorationremix_rune_glow",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)));

    /** Additive light for entities, with a premultiplied texture: the colour given at each corner is added as it is. */
    public static RenderType glow(ResourceLocation texture) { return GLOW.apply(texture); }

    /**
     * The light on a sword's blade (its runes ready to cast): as {@link #glow}, its texture smoothed, drawn after the
     * held items it lies on (it is one of the buffers the game draws last, see RenderBuffersMixin), so it adds onto the
     * blade rather than being covered by it.
     */
    public static final RenderType BLADE = create("explorationremix_blade_glow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(new ResourceLocation("explorationremix", "textures/misc/blade_glow.png"), true, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** A swing's sweep through the air: additive, soft, seen from both sides, drawn as {@link #BLADE} is. */
    public static final RenderType SWEEP = create("explorationremix_swing_sweep", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(new ResourceLocation("explorationremix", "textures/misc/swing_sweep.png"), true, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** The glow particles: added by their alpha, full bright, both sides, not written to depth. */
    public static final ParticleRenderType SHEET = new ParticleRenderType() {
        @Override
        public void begin(BufferBuilder b, TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public void end(Tesselator t) {
            t.end();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        }

        @Override
        public String toString() { return "EXPLORATIONREMIX_RUNE_GLOW"; }
    };
}
