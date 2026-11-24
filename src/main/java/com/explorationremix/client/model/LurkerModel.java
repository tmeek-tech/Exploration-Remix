package com.explorationremix.client.model;

import com.explorationremix.creature.ChimneyLurkerEntity;
import com.explorationremix.creature.body.LurkerBody;
import com.explorationremix.creature.body.LurkerPose;
import com.explorationremix.creature.body.LurkerRig;
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
 * The vent urchin as drawn (0.21.3; 0.21.0's chimney lurker): its shape from tools/chimney_lurker.py
 * (geometry/chimney_lurker.json) laid on its body each frame (creature.body.LurkerBody and LurkerPose): its round test,
 * its sixteen walking spines, each from its socket up to a knee close to the test and down a long needle to the point it
 * has set on the blocks under it; its coat of short spines, swaying and pointing at what it attends to; its five teeth
 * under it, opened as it crouches and bites. One pose is worked out per urchin per frame ({@link #setupAnim}) and drawn
 * by the main pass and the glowing layer alike, as the mantis's (MantisModel).
 */
public class LurkerModel extends EntityModel<ChimneyLurkerEntity> {
    private final LurkerPose pose;
    private final LurkerPose.Inputs in = new LurkerPose.Inputs();
    private float partialTick;
    @Nullable private Level level;
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final LurkerPose.Light light = this::lightAt;
    // drawing
    private final Matrix4f matrix = new Matrix4f();
    private final Matrix3f normals = new Matrix3f();
    private final Vector4f p4 = new Vector4f();
    private final Vector3f n3 = new Vector3f();
    private VertexConsumer out;
    private int overlay;
    private float red, green, blue, alpha;
    private boolean burning;
    private final LurkerPose.Sink sink = this::vertex;

    public LurkerModel() {
        super(RenderType::entityCutoutNoCull);
        this.pose = new LurkerPose(LurkerRig.shared());
    }

    @Override
    public void prepareMobModel(ChimneyLurkerEntity e, float limbSwing, float limbSwingAmount, float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public void setupAnim(ChimneyLurkerEntity e, float limbSwing, float amount, float age, float netHeadYaw, float headPitch) {
        float pt = partialTick;
        LurkerBody body = e.drawnBody();
        if (body == null) return;
        in.age = age;
        // dying, it settles onto the rock and its spines splay out flat round it
        in.dying = e.deathTime > 0 ? Math.min(1.0, (e.deathTime + pt - 1.0) / 16.0) : 0.0;
        level = e.level();
        burning = e.isOnFire();
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
        draw(poseStack, consumer, packedOverlay, r, g, b, a, false);
    }

    /** Draws it again lit through and through (its markings, as the vents' glowing beads are lit). */
    public void renderBright(PoseStack poseStack, VertexConsumer consumer, float r, float g, float b, float a) {
        draw(poseStack, consumer, OverlayTexture.NO_OVERLAY, r, g, b, a, true);
    }

    private void draw(PoseStack poseStack, VertexConsumer consumer, int packedOverlay, float r, float g, float b, float a, boolean bright) {
        if (!pose.ready()) return;
        poseStack.pushPose();
        // undo LivingEntityRenderer's flip of a model (a scale of -1, -1, 1 and a drop of 1.501): the pose is the world's
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        matrix.set(poseStack.last().pose());
        normals.set(poseStack.last().normal());
        poseStack.popPose();
        out = consumer;
        overlay = packedOverlay;
        red = r; green = g; blue = b; alpha = a;
        pose.emit(sink, LightTexture.FULL_BRIGHT, bright);
        out = null;
    }

    private void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, int light) {
        matrix.transform(p4.set(x, y, z, 1.0F));
        normals.transform(n3.set(nx, ny, nz));
        int lit = burning ? (light & 0xFFFF0000) | 0xF0 : light;
        out.vertex(p4.x(), p4.y(), p4.z(), red, green, blue, alpha, u, v, overlay, lit, n3.x(), n3.y(), n3.z());
    }
}
