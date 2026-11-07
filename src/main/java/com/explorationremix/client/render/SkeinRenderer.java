package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.SkeinEntity;
import com.explorationremix.creature.SkeinSpine;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the Skein along the chain its body lies in ({@link SkeinSpine}, SkeinEntity.spine()), which the head drags
 * after it along the way it has come: the head where the head is, then each of its {@link SkeinEntity#PLATES} plates
 * at its own distance back along the chain, lying on the rock it crosses there and turned along the way the chain runs,
 * so the long body pours over edges and swings round in an easy curve where the head snapped round. The chain moves
 * once a tick, and is drawn part way between where it lay and where it lies, so it moves smoothly. The pieces
 * are the few loose parts of geometry/skein.json (tools/creatures.py): the head, three sizes of plate and the tail,
 * each plate drawn a little narrower than the one before.
 *
 * <p>All of it is procedural: the legs in a fast wave from head to tail while it runs (dead still otherwise, but for a
 * twitch running down a few now and then), the feelers twitching in small jerks, a small fast sway down the body as
 * it runs and a shudder through it when it stops dead, the front plates reared for a strike, the mandibles opening.
 * A glow texture goes over it, drawn as eyes are (so shader packs light it): its eyes, the seam down its back, the
 * glassy rims of its plates and its feeler tips, faint while it lies still and bright once it moves.
 */
public class SkeinRenderer extends EntityRenderer<SkeinEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ExplorationRemix.MOD_ID, "skein"), "main");
    private static final ResourceLocation TEXTURE = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/skein/skein.png");
    private static final ResourceLocation GLOW = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/skein/skein_glow.png");

    private static final int PLATES = SkeinEntity.PLATES, PIECES = PLATES + 1;
    /** Plate widths (px): 16 just behind the head, 7 at the tail. */
    private static final float WIDE = 16.0F, NARROW = 7.0F;
    /** The legs of the last plate are this much smaller than the first's. */
    private static final float LEG_SHRINK = 0.3F;
    /** How far either side of a plate's place the curve is read for the way it runs (blocks; under half a plate step). */
    private static final double TANGENT = 0.1;
    /** The leg wave: radians of the phase between one plate's legs and the next's, and how far a leg swings and lifts. */
    private static final float WAVE_LAG = 0.65F, SWING = 0.5F, LIFT = 0.5F;
    /** The legs' rest angle forward (front plates) to back (tail), radians. */
    private static final float REST_FRONT = 0.55F, REST_BACK = -0.6F;
    /** The sway: how far a piece moves sideways (blocks) and turns (radians), and its lag from one piece to the next. */
    private static final float SWAY_SIDE = 0.035F, SWAY_TURN = 0.12F, SWAY_LAG = 0.55F;
    /** Rearing: the lift (blocks) and pitch (degrees) of the head and the first four plates. */
    private static final float[] REAR_LIFT = {0.4F, 0.28F, 0.18F, 0.09F, 0.03F}, REAR_PITCH = {40, 35, 25, 12, 4};

    private final ModelPart head, mandibleLeft, mandibleRight, feelerLeft, feelerRight, feelerTipLeft, feelerTipRight;
    private final Plate[] kinds;
    private final ModelPart spurLeft, spurRight;

    // per frame (the render thread only): where each piece goes and its light
    private final double[] arcs = new double[1 + 3 * PLATES];
    private final double[] at = new double[6 * (1 + 3 * PLATES)];
    private final Matrix4f[] poses = new Matrix4f[PIECES];
    private final Matrix3f[] normals = new Matrix3f[PIECES];
    private final int[] lights = new int[PIECES];

    /** One size of plate: its root (drawn), its shell (tapered plate by plate) and its legs. */
    private record Plate(ModelPart root, ModelPart shell, ModelPart legLeft, ModelPart shinLeft, ModelPart legRight, ModelPart shinRight,
                         float width) {
        static Plate of(ModelPart model, String name, float width) {
            ModelPart r = model.getChild(name);
            ModelPart ll = r.getChild(name + "_leg_left"), lr = r.getChild(name + "_leg_right");
            return new Plate(r, r.getChild(name + "_shell"), ll, ll.getChild(name + "_shin_left"), lr, lr.getChild(name + "_shin_right"), width);
        }
    }

    public SkeinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.7F;
        ModelPart model = ctx.bakeLayer(LAYER);
        this.head = model.getChild("head");
        this.mandibleLeft = head.getChild("mandible_left");
        this.mandibleRight = head.getChild("mandible_right");
        this.feelerLeft = head.getChild("feeler_left");
        this.feelerRight = head.getChild("feeler_right");
        this.feelerTipLeft = feelerLeft.getChild("feeler_left_tip");
        this.feelerTipRight = feelerRight.getChild("feeler_right_tip");
        this.kinds = new Plate[]{Plate.of(model, "plate_large", 16), Plate.of(model, "plate_mid", 13), Plate.of(model, "plate_small", 10),
                Plate.of(model, "tail", 8)};
        this.spurLeft = kinds[3].root().getChild("tail_spur_left");
        this.spurRight = kinds[3].root().getChild("tail_spur_right");
        for (int i = 0; i < PIECES; i++) { poses[i] = new Matrix4f(); normals[i] = new Matrix3f(); }
    }

    @Override
    public ResourceLocation getTextureLocation(SkeinEntity e) {
        return TEXTURE;
    }

    /** The plate size the k-th plate is drawn with: large behind the head, then mid, then small, then the tail. */
    private Plate kind(int k) {
        return k == PLATES - 1 ? kinds[3] : kinds[Math.min(2, k * 3 / (PLATES - 1))];
    }

    private static float width(int k) {
        return WIDE + (NARROW - WIDE) * k / (PLATES - 1);
    }

    @Override
    public void render(SkeinEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!e.isInvisible() && e.trail().laid()) {
            place(e, pt, pose);
            int overlay = LivingEntityRenderer.getOverlayCoords(e, 0.0F);
            draw(e, pt, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), overlay, 1.0F, false);
            float g = e.glow(pt);
            if (e.isDeadOrDying()) g *= Math.max(0.0F, 1.0F - e.deathTime / 20.0F);
            if (g > 0.02F) draw(e, pt, pose, buffers.getBuffer(RenderType.eyes(GLOW)), OverlayTexture.NO_OVERLAY, g, true);
        }
        super.render(e, yaw, pt, pose, buffers, light);
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * Works out where every piece goes this frame, along the chain its body lies in (SkeinEntity.spine()), drawn part
     * way from where it lay last tick to where it lies now, as the game draws the head: the head at its first joint,
     * each plate its own distance behind along it.
     */
    private void place(SkeinEntity e, float pt, PoseStack pose) {
        SkeinSpine spine = e.spine();
        Quaternionf headTurn = new Quaternionf(e.headOrientO).slerp(e.headOrient, pt);
        Vector3f headUp = headTurn.transform(new Vector3f(0, 1, 0));
        arcs[0] = 0.0;
        for (int k = 0; k < PLATES; k++) {
            double s = SkeinEntity.plateAt(k);
            arcs[1 + 3 * k] = s - TANGENT;
            arcs[2 + 3 * k] = s;
            arcs[3 + 3 * k] = s + TANGENT;
        }
        spine.sampleAll(arcs, pt, at);
        double ox = Mth.lerp(pt, e.xOld, e.getX()), oy = Mth.lerp(pt, e.yOld, e.getY()), oz = Mth.lerp(pt, e.zOld, e.getZ());
        for (int i = 0; i < PIECES; i++) {
            int m = i == 0 ? 0 : 6 * (2 + 3 * (i - 1));               // the piece's own sample (the middle of its three)
            Vec3 p = new Vec3(at[m], at[m + 1], at[m + 2]);
            Vec3 up = i == 0 ? new Vec3(headUp.x, headUp.y, headUp.z) : new Vec3(at[m + 3], at[m + 4], at[m + 5]);
            Quaternionf turn = i == 0 ? headTurn : turnAt(i - 1, up, headTurn);
            lights[i] = LevelRenderer.getLightColor(e.level(), BlockPos.containing(p.add(up.scale(0.3))));
            pose.pushPose();
            pose.translate(p.x - ox, p.y - oy, p.z - oz);
            pose.mulPose(turn);
            move(e, pt, pose, i);
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.501F, 0.0F);
            poses[i].set(pose.last().pose());
            normals[i].set(pose.last().normal());
            pose.popPose();
        }
    }

    /** The k-th plate's turn: its back to the rock's normal there, its front along the way the trail runs to the head. */
    private Quaternionf turnAt(int k, Vec3 up, Quaternionf fallback) {
        int a = 6 * (1 + 3 * k), b = 6 * (3 + 3 * k);
        Vec3 t = new Vec3(at[a] - at[b], at[a + 1] - at[b + 1], at[a + 2] - at[b + 2]);
        Vec3 f = t.subtract(up.scale(t.dot(up)));
        if (f.lengthSqr() < 1.0E-8) {
            Vector3f hf = fallback.transform(new Vector3f(0, 0, -1));
            f = new Vec3(hf.x, hf.y, hf.z);
            f = f.subtract(up.scale(f.dot(up)));
            if (f.lengthSqr() < 1.0E-8) f = Math.abs(up.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        }
        return SkeinEntity.basis(f.normalize(), up);
    }

    /**
     * The piece's own motion on its place (in its frame: x to its right, y up from the rock, z back): reared, swaying,
     * shuddering, and rolled onto its back as it dies.
     */
    private static void move(SkeinEntity e, float pt, PoseStack pose, int i) {
        float age = e.tickCount + pt;
        float rear = e.rear(pt);
        if (i < REAR_LIFT.length && rear > 0.001F) {
            pose.translate(0.0F, REAR_LIFT[i] * rear, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(REAR_PITCH[i] * rear));
        }
        float run = e.run(pt);
        if (run > 0.001F) {
            // a small fast sway that runs down the body, more towards the tail
            float ph = e.sway(pt) - i * SWAY_LAG, reach = 0.3F + 0.7F * i / PIECES;
            pose.translate(Mth.sin(ph) * SWAY_SIDE * run * reach, 0.0F, 0.0F);
            pose.mulPose(Axis.YP.rotation(Mth.cos(ph) * SWAY_TURN * run * reach));
        }
        float shudder = e.shudder(pt);
        if (shudder > 0.01F) {
            // a quick tremor through the whole length as it stops dead
            pose.translate(Mth.sin(age * 9.0F + i * 1.3F) * 0.03F * shudder, -Math.abs(Mth.sin(age * 7.0F + i)) * 0.02F * shudder, 0.0F);
            pose.mulPose(Axis.ZP.rotation(Mth.sin(age * 11.0F + i * 0.9F) * 0.08F * shudder));
        }
        float dead = death(e, pt);
        if (dead > 0.0F) {
            pose.translate(0.0F, 0.25F, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(dead * 180.0F * (i % 2 == 0 ? 1.0F : 0.96F)));
            pose.translate(0.0F, -0.25F, 0.0F);
        }
    }

    /** 0 alive, rising to 1 as it rolls over dead. */
    private static float death(SkeinEntity e, float pt) {
        if (e.deathTime <= 0) return 0.0F;
        return Mth.sqrt(Math.min(1.0F, (e.deathTime + pt - 1.0F) / 20.0F * 1.6F));
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** Draws every piece where {@link #place} put it, posed for this frame: the texture pass, or the glow pass. */
    private void draw(SkeinEntity e, float pt, PoseStack pose, VertexConsumer vc, int overlay, float level, boolean glow) {
        for (int i = 0; i < PIECES; i++) {
            pose.pushPose();
            pose.last().pose().set(poses[i]);
            pose.last().normal().set(normals[i]);
            int light = glow ? 0xF000F0 : lights[i];
            if (i == 0) {
                poseHead(e, pt);
                head.render(pose, vc, light, overlay, level, level, level, 1.0F);
            } else {
                Plate p = kind(i - 1);
                posePlate(e, pt, p, i - 1);
                p.root().render(pose, vc, light, overlay, level, level, level, 1.0F);
            }
            pose.popPose();
        }
    }

    /** The feelers' twitching aim, the mandibles open as much as the jaw says. */
    private void poseHead(SkeinEntity e, float pt) {
        head.getAllParts().forEach(ModelPart::resetPose);
        float rear = e.rear(pt), dead = death(e, pt), jaw = e.jaw(pt);
        feeler(feelerLeft, feelerTipLeft, -1.0F, e.feelerYaw(0, pt), e.feelerPitch(0, pt), rear, dead);
        feeler(feelerRight, feelerTipRight, 1.0F, e.feelerYaw(1, pt), e.feelerPitch(1, pt), rear, dead);
        mandibleLeft.yRot -= jaw * 0.7F;
        mandibleRight.yRot += jaw * 0.7F;
    }

    /** A feeler: {@code out} is the sign of a turn outward (left is -1); raised as it rears, drooping dead. */
    private static void feeler(ModelPart base, ModelPart tip, float out, float yaw, float pitch, float rear, float dead) {
        base.yRot += out * yaw;
        base.xRot += pitch - rear * 0.5F + dead * 0.6F;
        tip.yRot += out * yaw * 0.6F;
        tip.xRot += pitch * 0.5F + dead * 0.5F;
    }

    /** The k-th plate: tapered to its width, its legs in their place in the wave. */
    private void posePlate(SkeinEntity e, float pt, Plate p, int k) {
        p.root().getAllParts().forEach(ModelPart::resetPose);
        float w = width(k), legScale = 1.0F - LEG_SHRINK * k / (PLATES - 1);
        p.shell().xScale = w / p.width();
        p.shell().yScale = 0.75F + 0.25F * w / WIDE;
        float age = e.tickCount + pt, run = e.run(pt), shudder = e.shudder(pt), dead = death(e, pt);
        float rest = REST_FRONT + (REST_BACK - REST_FRONT) * k / (PLATES - 1);
        float ripple = e.ripple(pt);
        float twitch = ripple < 0 ? 0.0F : Math.max(0.0F, 1.0F - Math.abs(k - ripple) / 1.5F);
        for (int side = 0; side < 2; side++) {
            float sgn = side == 0 ? 1.0F : -1.0F;
            ModelPart leg = side == 0 ? p.legLeft() : p.legRight(), shin = side == 0 ? p.shinLeft() : p.shinRight();
            float ph = e.legPhase(pt) - k * WAVE_LAG + (side == 0 ? 0.0F : Mth.PI);
            float lift = Math.max(0.0F, Mth.cos(ph)) * LIFT * run + 0.45F * twitch + 0.15F * shudder * Mth.sin(age * 13.0F + k * 2.0F + side)
                    + 0.6F * dead;
            leg.x = sgn * (w / 2.0F - 2.0F);
            leg.y *= legScale;
            leg.xScale = leg.yScale = leg.zScale = legScale;
            leg.yRot = sgn * (rest + Mth.sin(ph) * SWING * run + 0.25F * twitch * Mth.sin(age * 2.0F));
            leg.zRot -= sgn * lift;
            shin.zRot += sgn * 1.3F * dead;
        }
        if (p == kinds[3]) {
            float sway = Mth.sin(e.sway(pt) - PLATES * SWAY_LAG) * 0.2F * run;
            spurLeft.yRot += sway;
            spurRight.yRot += sway;
        }
    }
}
