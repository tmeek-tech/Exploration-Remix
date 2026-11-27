package com.explorationremix.client.model;

import com.explorationremix.creature.EmberUrchinEntity;
import com.explorationremix.creature.body.EmberBody;
import com.explorationremix.creature.body.EmberPose;
import com.explorationremix.creature.body.EmberRig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * The ember urchin as drawn (0.23.7): its shape from tools/ember_urchin.py (geometry/ember_urchin.json) laid on its
 * body each frame (creature.body.EmberBody and EmberPose): its plated ball, round and breathing a little; a boss at
 * each walking spine's socket; each of its thirty walking spines bent along its bow from its socket to the point it has
 * braced on the rock; its coat of short spines lying back, bristling as it flares. One pose is worked out per urchin
 * per frame ({@link #setupAnim}) and drawn by the main pass and the glowing layer alike, as the Chimney Spider's
 * (LurkerModel).
 */
public class EmberUrchinModel extends EntityModel<EmberUrchinEntity> {
    private final EmberPose pose;
    private final EmberPose.Inputs in = new EmberPose.Inputs();
    private float partialTick;
    @Nullable private Level level;
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final EmberPose.Light light = this::lightAt;
    // drawing
    private final Matrix4f matrix = new Matrix4f();
    private final Matrix3f normals = new Matrix3f();
    private final Vector4f p4 = new Vector4f();
    private final Vector3f n3 = new Vector3f();
    private VertexConsumer out;
    private int overlay;
    private float red, green, blue, alpha;
    private final EmberPose.Sink sink = this::vertex;
    private float glowLevel;
    private final EmberPose.GlowSink glowSink = this::glowVertex;

    public EmberUrchinModel() {
        super(RenderType::entityCutoutNoCull);
        this.pose = new EmberPose(EmberRig.shared());
    }

    @Override
    public void prepareMobModel(EmberUrchinEntity e, float limbSwing, float limbSwingAmount, float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public void setupAnim(EmberUrchinEntity e, float limbSwing, float amount, float age, float netHeadYaw, float headPitch) {
        float pt = partialTick;
        EmberBody body = e.drawnBody();
        if (body == null) return;
        in.age = age;
        in.breath = e.isDeadOrDying() ? -1.0 : e.breath(pt);
        level = e.level();
        double ox = Mth.lerp(pt, e.xOld, e.getX()), oy = Mth.lerp(pt, e.yOld, e.getY()), oz = Mth.lerp(pt, e.zOld, e.getZ());
        int fallback = LevelRenderer.getLightColor(level, e.blockPosition());
        pose.compute(body, pt, in, ox, oy, oz, light, fallback);
        level = null;
    }

    /** The light where a part of it is (the brightest open block beside it, should that part lie in rock). */
    private int lightAt(double x, double y, double z) {
        Level l = level;
        if (l == null) return LightTexture.FULL_BRIGHT;
        lightPos.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState state = l.getBlockState(lightPos);
        if (!state.isSolidRender(l, lightPos)) return LevelRenderer.getLightColor(l, state, lightPos);
        int best = 0, bestSum = -1;
        int cx = lightPos.getX(), cy = lightPos.getY(), cz = lightPos.getZ();
        for (Direction d : Direction.values()) {
            lightPos.set(cx + d.getStepX(), cy + d.getStepY(), cz + d.getStepZ());
            BlockState s = l.getBlockState(lightPos);
            if (s.isSolidRender(l, lightPos)) continue;
            int c = LevelRenderer.getLightColor(l, s, lightPos);
            int sum = LightTexture.block(c) + LightTexture.sky(c);
            if (sum > bestSum) { bestSum = sum; best = c; }
        }
        return bestSum < 0 ? LevelRenderer.getLightColor(l, state, lightPos.set(cx, cy, cz)) : best;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay,
                               float r, float g, float b, float a) {
        if (!pose.ready()) return;
        place(poseStack);
        out = consumer;
        overlay = packedOverlay;
        red = r; green = g; blue = b; alpha = a;
        pose.emit(sink, LightTexture.FULL_BRIGHT, false);
        out = null;
    }

    /**
     * Draws its embers again lit through and through (its seams, its spines' points), at {@code level}, each point at
     * the share {@code shade} gives it (the breath rolling over its ball, its points' flicker).
     */
    public void renderGlow(PoseStack poseStack, VertexConsumer consumer, float level, EmberPose.Shade shade) {
        if (!pose.ready()) return;
        place(poseStack);
        out = consumer;
        glowLevel = level;
        pose.emitGlow(glowSink, shade);
        out = null;
    }

    /** The pose is the world's: undoes LivingEntityRenderer's flip of a model (a scale of -1, -1, 1 and a drop of 1.501). */
    private void place(PoseStack poseStack) {
        poseStack.pushPose();
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        matrix.set(poseStack.last().pose());
        normals.set(poseStack.last().normal());
        poseStack.popPose();
    }

    private void glowVertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade) {
        matrix.transform(p4.set(x, y, z, 1.0F));
        normals.transform(n3.set(nx, ny, nz));
        float c = Math.max(0.0F, Math.min(1.0F, glowLevel * shade));
        out.vertex(p4.x(), p4.y(), p4.z(), c, c, c, 1.0F, u, v, OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, n3.x(), n3.y(), n3.z());
    }

    private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int light) {
        matrix.transform(p4.set(x, y, z, 1.0F));
        normals.transform(n3.set(nx, ny, nz));
        out.vertex(p4.x(), p4.y(), p4.z(), red, green, blue, alpha, u, v, overlay, light, n3.x(), n3.y(), n3.z());
    }
}
