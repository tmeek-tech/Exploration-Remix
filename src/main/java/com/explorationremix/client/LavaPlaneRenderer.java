package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.LavaPlane;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the Basalt Galleries' lava plane (0.21.6, {@link LavaPlane}): one flat square of lava over each loaded chunk
 * round the camera, at the plane's surface between ticks, so it climbs and falls smoothly. It is drawn the way the game
 * draws a lava block's top: the terrain's own solid shader, the still lava's animation ({@link LavaPlaneTexture}, a
 * block's worth of it per block), full block light as lava gives itself, the terrain's fog, and seen from under it too.
 * The rock hides it wherever the Galleries are not open, as it would a sea of blocks.
 *
 * <p>Drawn after the solid world, the entities and the block entities and before anything see-through, so water,
 * glass, particles and smoke show over it and what is under its surface is hidden. Only in the overworld, while the
 * plane is up, with the camera no higher than the reach the cycle's warnings use over the Galleries' top.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class LavaPlaneRenderer {
    private LavaPlaneRenderer() {}

    /** The farthest the plane is drawn, in chunks, whatever the render distance. */
    static final int MAX_CHUNKS = 32;
    /** How far over the Galleries' top (and under their bottom) the camera may be for the plane to be drawn. */
    static final int REACH = 48;
    /** Full block light and no sky light: how the game lights a lava block deep under the ground. */
    private static final int LIGHT = LightTexture.pack(15, 0);

    private static boolean registered;
    private static BufferBuilder builder;

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        LavaPlane plane = LavaPlane.CLIENT;
        LavaNet.Sync sync = LavaWarnings.lastSync();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!plane.on() || sync == null || level == null || level.dimension() != Level.OVERWORLD) return;
        Vec3 cam = event.getCamera().getPosition();
        if (cam.y > sync.top() + REACH || cam.y < sync.bottom() - REACH) return;
        double surface = plane.surface(level.getGameTime(), event.getPartialTick());
        if (Double.isNaN(surface)) return;
        if (!registered) {
            mc.getTextureManager().register(LavaPlaneTexture.LOCATION, new LavaPlaneTexture());
            registered = true;
        }
        BufferBuilder b = builder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int r = Math.min(MAX_CHUNKS, mc.options.getEffectiveRenderDistance());
        int ccx = SectionPos.blockToSectionCoord(Mth.floor(cam.x)), ccz = SectionPos.blockToSectionCoord(Mth.floor(cam.z));
        float y = (float) (surface - cam.y);
        int quads = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > (r + 1) * (r + 1)) continue;
                int cx = ccx + dx, cz = ccz + dz;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                float x0 = (float) ((cx << 4) - cam.x), z0 = (float) ((cz << 4) - cam.z), x1 = x0 + 16, z1 = z0 + 16;
                corner(b, x0, y, z0, 0, 0);
                corner(b, x0, y, z1, 0, 16);
                corner(b, x1, y, z1, 16, 16);
                corner(b, x1, y, z0, 16, 0);
                quads++;
            }
        }
        if (quads == 0) {
            b.end().release();
            return;
        }
        // the terrain's shader takes a chunk's offset, which the game leaves at nothing after the terrain: make sure
        ShaderInstance solid = GameRenderer.getRendertypeSolidShader();
        if (solid != null && solid.CHUNK_OFFSET != null) solid.CHUNK_OFFSET.set(0F, 0F, 0F);
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.mulPoseMatrix(event.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        try {
            Type.PLANE.end(b, RenderSystem.getVertexSorting());
        } finally {
            view.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void corner(BufferBuilder b, float x, float y, float z, float u, float v) {
        b.vertex(x, y, z).color(1F, 1F, 1F, 1F).uv(u, v).uv2(LIGHT).normal(0F, 1F, 0F).endVertex();
    }

    private static BufferBuilder builder() {
        if (builder == null) builder = new BufferBuilder(DefaultVertexFormat.BLOCK.getVertexSize() * 4 * 2048);
        return builder;
    }

    /** The plane's render type: the terrain's solid shader and light, the plane's own repeating texture, both faces. */
    private static final class Type extends RenderType {
        private Type(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }

        static final RenderType PLANE = create("explorationremix_lava_plane", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 1 << 18, false, false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_SOLID_SHADER)
                        .setLightmapState(LIGHTMAP)
                        .setTextureState(new TextureStateShard(LavaPlaneTexture.LOCATION, false, true))
                        .setCullState(NO_CULL)
                        .createCompositeState(false));
    }
}
