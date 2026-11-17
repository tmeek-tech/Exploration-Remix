package com.explorationremix.client.model;

import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.creature.body.MossbackBody;
import com.explorationremix.creature.body.MossbackPose;
import com.explorationremix.creature.body.MossbackRig;
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
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;

/**
 * The Mossback as drawn (0.15.0): its shape from tools/creatures.py (geometry/mossback.json) laid on its body each
 * frame (see creature.body). Its trunk and tail bend as one skin along its spine, its legs reach from their shoulders
 * to the feet it has set on the rock, and its head turns to what it watches; the moment's touches (breathing, the
 * hunt's flare and gape, the tremble before a strike, the thrash with a catch, the death roll) come from the lizard's
 * own state. One pose is worked out per lizard per frame ({@link #setupAnim}) and drawn by the main pass and the
 * glowing layers alike.
 */
public class MossbackModel extends EntityModel<MossbackEntity> {
    private final MossbackPose pose;
    private final MossbackPose.Inputs in = new MossbackPose.Inputs();
    private float partialTick;
    @Nullable private Level level;
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    private final MossbackPose.Light light = this::lightAt;
    // drawing
    private final Matrix4f matrix = new Matrix4f();
    private final Matrix3f normals = new Matrix3f();
    private final Vector4f p4 = new Vector4f();
    private final Vector3f n3 = new Vector3f();
    private VertexConsumer out;
    private int overlay;
    private float red, green, blue, alpha;
    /** On fire: lit through by its own flames (block light 15, as vanilla lights a burning mob). */
    private boolean burning;
    private final MossbackPose.Sink sink = this::vertex;

    public MossbackModel() {
        super(RenderType::entityCutoutNoCull);
        this.pose = new MossbackPose(MossbackRig.shared());
    }

    @Override
    public void prepareMobModel(MossbackEntity e, float limbSwing, float limbSwingAmount, float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public void setupAnim(MossbackEntity e, float limbSwing, float amount, float age, float netHeadYaw, float headPitch) {
        float pt = partialTick;
        MossbackBody body = e.drawnBody();
        in.jaw = e.jaw(pt);
        in.rear = e.rear(pt);
        in.frill = e.frill(pt);
        in.windup = e.windup(pt);
        in.age = age;
        // on a floor it turns its head to what it watches; on walls and ceilings it looks ahead
        in.look = e.onFloor();
        in.lookYaw = Mth.wrapDegrees(netHeadYaw) * Mth.DEG_TO_RAD;
        in.lookPitch = headPitch * Mth.DEG_TO_RAD;
        byte s = e.state();
        in.thrash = s == MossbackEntity.GRAB;
        in.eat = s == MossbackEntity.EAT;
        // dying, it rolls onto its back
        in.dying = e.deathTime > 0 ? Math.sqrt(Math.min(1.0, (e.deathTime + pt - 1.0) / 20.0 * 1.6)) : 0.0;
        level = e.level();
        burning = e.isOnFire();
        // from where the lizard is drawn (as LevelRenderer places it), so every part lands where its body has it in the world
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

    /** Draws it again lit through and through (its glowing layers: eyes and markings). */
    public void renderBright(PoseStack poseStack, VertexConsumer consumer, float r, float g, float b, float a) {
        draw(poseStack, consumer, OverlayTexture.NO_OVERLAY, r, g, b, a, true);
    }

    private void draw(PoseStack poseStack, VertexConsumer consumer, int packedOverlay, float r, float g, float b, float a, boolean bright) {
        if (!pose.ready()) return;
        poseStack.pushPose();
        unflip(poseStack);
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

    /**
     * Undoes LivingEntityRenderer's flip of a model (a scale of -1, -1, 1 and a drop of 1.501): the pose is the world's
     * again, from the lizard's place as drawn (MossbackRenderer turns nothing: the body lies in the world as it is).
     */
    private static void unflip(PoseStack poseStack) {
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
    }

    /**
     * Moves the pose from the model's root to the gap between its jaws, as posed for this frame (for what it carries
     * there: see MossbackRenderer). The pose is then the jaw's own, as the model's parts have it: y down, the snout
     * towards -z.
     */
    public void translateToJaw(PoseStack poseStack) {
        unflip(poseStack);
        Vector3d gap = pose.jawGap();
        poseStack.translate(gap.x, gap.y, gap.z);
        poseStack.mulPose(new Quaternionf().setFromNormalized(pose.jawTurn()));
    }
}
