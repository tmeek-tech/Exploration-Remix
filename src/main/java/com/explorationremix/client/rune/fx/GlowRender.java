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
     * A rune sword's ready light on its blade, seen from outside without a shader pack (RuneGlow): untextured squares on
     * the blade's own texels, added by their alpha, nudged towards the eye so they win against the blade they lie on, and
     * drawn after the held items (it is one of the buffers the game draws last, see RenderBuffersMixin).
     */
    public static final RenderType BLADE = create("explorationremix_blade_light", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 8192,
            false, false, CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** A swing's sweep through the air: additive, soft, seen from both sides, drawn last as {@link #BLADE} is. */
    public static final RenderType SWEEP = create("explorationremix_swing_sweep", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(new ResourceLocation("explorationremix", "textures/misc/swing_sweep.png"), true, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * A rune stone's carving lit from inside (0.23.1, client.rune.stone.RuneStoneClient): its light texture from the block
     * atlas laid again over the faces of the stone, the colour added as it is (the texture holds the light to add, black
     * where there is none), nudged towards the eye so it wins against the face it lies on, hidden by whatever stands in
     * front, and fading into the dark with the fog as the eyes of a spider do.
     */
    public static final RenderType STONE = create("explorationremix_rune_stone_light", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, true))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    private static final Function<ResourceLocation, RenderType> THROUGH = Util.memoize(tex -> create("explorationremix_rune_glow_through",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1024, false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false)));

    /**
     * As {@link #glow}, but seen through rock: what a broken rune stone's answer is drawn with as it shoots to another
     * stone, and that stone's flare, so that the stones round a broken one show where they lie (0.23.1).
     */
    public static RenderType through(ResourceLocation texture) { return THROUGH.apply(texture); }

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
