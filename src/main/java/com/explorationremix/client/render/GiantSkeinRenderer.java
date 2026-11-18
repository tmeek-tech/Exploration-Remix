package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.GiantSkeinEntity;
import com.explorationremix.creature.GiantStrike;
import com.explorationremix.creature.SkeinEntity;
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
 * Draws the giant Skein (0.18.8) as the Skein is drawn, at four times the size: its head, then each of its {@link
 * GiantSkeinEntity#PLATES} plates at its own distance back along its body, lying on the rock it crosses there and
 * turned along the way its body runs, so the thirty blocks of it pour over the rift's terraces in one long curve. The
 * body moves once a tick and is drawn part way between where it lay and where it lies. While it strikes, its front is
 * drawn up off the rock along the strike's curve (GiantSkeinEntity#bodyAt), each piece's back kept turned the same
 * way along it from the rock, so the reared neck never twists. The pieces are the loose parts of
 * geometry/giant_skein.json (tools/giant_skein.py): the head, three sizes of plate and the tail, each plate a little
 * narrower than the one before.
 *
 * <p>All of it moves: its legs in slow waves down its length as it crawls (dead still at rest, but for a ripple running
 * down them now and then), a slow sway along it as it goes, its head and front rising a little as it notices you, its
 * spines and tail fan raised and spread as it hunts and rears, a tremor through its coiled front, its mandibles
 * opening, its feelers sweeping. The glow texture goes over it drawn as eyes are (so shader packs light it): the tips
 * of its spines and its eyes, a slow wave of light running down its length, brighter as it hunts and flaring as it
 * rears. Dying, its legs curl in and its lights go out.
 */
public class GiantSkeinRenderer extends EntityRenderer<GiantSkeinEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation(ExplorationRemix.MOD_ID, "giant_skein"), "main");
    private static final ResourceLocation TEXTURE = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/giant_skein/giant_skein.png");
    private static final ResourceLocation GLOW = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/giant_skein/giant_skein_glow.png");

    private static final int PLATES = GiantSkeinEntity.PLATES, PIECES = PLATES + 1;
    /** Plate widths (px): 40 just behind the head, 18 at the tail (tools/giant_skein.py). */
    private static final float WIDE = 40.0F, NARROW = 18.0F;
    /** The legs of the last plate are this much smaller than the first's. */
    private static final float LEG_SHRINK = 0.25F;
    /** How far either side of a piece's place its body is read for the way it runs (blocks; under half a plate step). */
    private static final double TANGENT = 0.4;
    /** The leg wave: radians of the phase between one plate's legs and the next's, and how far a leg swings and lifts. */
    private static final float WAVE_LAG = 0.55F, SWING = 0.35F, LIFT = 0.35F;
    /** The legs' rest angle forward (front plates) to back (tail), radians. */
    private static final float REST_FRONT = 0.55F, REST_BACK = -0.6F;
    /** The slow sway as it crawls: how far a piece moves sideways (blocks) and turns (radians), and the lag between pieces. */
    private static final float SWAY_SIDE = 0.07F, SWAY_TURN = 0.04F, SWAY_LAG = 0.45F, SWAY_RATE = 0.35F;
    /** Noticing you: the rise (blocks) and pitch (degrees) of its head and first four plates. */
    private static final float[] RISE = {0.55F, 0.4F, 0.26F, 0.13F, 0.05F}, RISE_PITCH = {14, 10, 6, 3, 1};

    private final ModelPart head, mandibleLeft, mandibleRight, mandibleTipLeft, mandibleTipRight,
            feelerLeft, feelerRight, feelerTipLeft, feelerTipRight;
    private final Plate[] kinds;

    // per frame (the render thread only): where each piece goes and its light
    private final double[] arcs = new double[2 + 3 * PLATES];
    private final double[] at = new double[6 * (2 + 3 * PLATES)];
    private final Vec3[] pos = new Vec3[PIECES], nrm = new Vec3[PIECES], fwd = new Vec3[PIECES], ups = new Vec3[PIECES];
    private final Matrix4f[] poses = new Matrix4f[PIECES];
    private final Matrix3f[] normals = new Matrix3f[PIECES];
    private final int[] lights = new int[PIECES];

    /**
     * One size of plate: its root (drawn), its shell (tapered plate by plate), the roots of its two back spines (left,
     * right) and four rim thorns (left front, left back, right front, right back), its tail fan's five spikes (the tail
     * only), and its legs.
     */
    private record Plate(ModelPart root, ModelPart shell, ModelPart[] spines, ModelPart[] thorns, ModelPart[] fan,
                         ModelPart legLeft, ModelPart shinLeft, ModelPart legRight, ModelPart shinRight, float width) {
        static Plate of(ModelPart model, String name, float width) {
            ModelPart r = model.getChild(name);
            ModelPart ll = r.getChild(name + "_leg_left"), lr = r.getChild(name + "_leg_right");
            boolean tail = name.equals("tail");
            ModelPart[] spines = tail ? new ModelPart[0] : new ModelPart[]{r.getChild(name + "_spine_left_0"), r.getChild(name + "_spine_right_0")};
            ModelPart[] thorns = tail ? new ModelPart[]{r.getChild("tail_thorn_left_0"), r.getChild("tail_thorn_right_0")}
                    : new ModelPart[]{r.getChild(name + "_thorn_left0_0"), r.getChild(name + "_thorn_left1_0"),
                    r.getChild(name + "_thorn_right0_0"), r.getChild(name + "_thorn_right1_0")};
            ModelPart[] fan = new ModelPart[tail ? 5 : 0];
            for (int k = 0; k < fan.length; k++) fan[k] = r.getChild("tail_fan" + k + "_0");
            return new Plate(r, r.getChild(name + "_shell"), spines, thorns, fan, ll, ll.getChild(name + "_shin_left"), lr,
                    lr.getChild(name + "_shin_right"), width);
        }
    }

    public GiantSkeinRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        // no round shadow: under a thirty-block body clinging to a wall it would only mark its head
        this.shadowRadius = 0.0F;
        ModelPart model = ctx.bakeLayer(LAYER);
        this.head = model.getChild("head");
        this.mandibleLeft = head.getChild("mandible_left");
        this.mandibleRight = head.getChild("mandible_right");
        this.mandibleTipLeft = mandibleLeft.getChild("mandible_left_tip");
        this.mandibleTipRight = mandibleRight.getChild("mandible_right_tip");
        this.feelerLeft = head.getChild("feeler_left");
        this.feelerRight = head.getChild("feeler_right");
        this.feelerTipLeft = feelerLeft.getChild("feeler_left_tip");
        this.feelerTipRight = feelerRight.getChild("feeler_right_tip");
        this.kinds = new Plate[]{Plate.of(model, "plate_large", 40), Plate.of(model, "plate_mid", 33), Plate.of(model, "plate_small", 26),
                Plate.of(model, "tail", 20)};
        for (int i = 0; i < PIECES; i++) { poses[i] = new Matrix4f(); normals[i] = new Matrix3f(); }
    }

    @Override
    public ResourceLocation getTextureLocation(GiantSkeinEntity e) {
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
    public void render(GiantSkeinEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!e.isInvisible() && e.body().laid()) {
            place(e, pt, pose);
            int overlay = LivingEntityRenderer.getOverlayCoords(e, 0.0F);
            float dead = death(e, pt), flare = flare(e, pt);
            draw(e, pt, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), overlay, 1.0F, false, flare, dead);
            float g = e.glow(pt) * (1.0F - dead);
            if (g > 0.02F) draw(e, pt, pose, buffers.getBuffer(RenderType.eyes(GLOW)), OverlayTexture.NO_OVERLAY, g, true, flare, dead);
            // coiled to strike, and striking: its spine tips and eyes flare, drawn again over themselves (it adds up)
            int phase = e.strikePhase();
            float wind = phase == GiantStrike.COIL || phase == GiantStrike.OUT ? (float) GiantStrike.weight(phase, e.strikeProgress(pt)) : 0.0F;
            if (wind > 0.02F && dead <= 0.0F)
                draw(e, pt, pose, buffers.getBuffer(RenderType.eyes(GLOW)), OverlayTexture.NO_OVERLAY, 0.7F * wind, true, flare, dead);
        }
        super.render(e, yaw, pt, pose, buffers, light);
    }

    /** How far its spines and tail fan are raised and spread: none at rest or crawling, half hunting, all rearing. */
    private static float flare(GiantSkeinEntity e, float pt) {
        return Mth.clamp((e.glow(pt) - 0.8F) / 0.2F, 0.0F, 1.0F);
    }

    /** 0 alive, rising to 1 as its legs curl and its lights go out. */
    private static float death(GiantSkeinEntity e, float pt) {
        if (e.deathTime <= 0) return 0.0F;
        return Mth.sqrt(Math.min(1.0F, (e.deathTime + pt - 1.0F) / 30.0F));
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * Works out where every piece goes this frame, along its body as GiantSkeinEntity#bodyAt draws it (with its front up
     * off the rock while it strikes), part way from where it lay last tick to where it lies now: the head at the front,
     * each plate its own distance behind.
     */
    private void place(GiantSkeinEntity e, float pt, PoseStack pose) {
        arcs[0] = 0.0;
        arcs[1] = TANGENT;
        for (int k = 0; k < PLATES; k++) {
            double s = GiantSkeinEntity.plateAt(k);
            arcs[2 + 3 * k] = s - TANGENT;
            arcs[3 + 3 * k] = s;
            arcs[4 + 3 * k] = s + TANGENT;
        }
        e.bodyAt(arcs, pt, at);
        double w = GiantStrike.weight(e.strikePhase(), e.strikeProgress(pt));
        Quaternionf headTurn = new Quaternionf(e.headOrientO).slerp(e.headOrient, pt);
        for (int i = 0; i < PIECES; i++) {
            int m = i == 0 ? 0 : 6 * (3 + 3 * (i - 1));                 // the piece's own sample
            int a = i == 0 ? 0 : 6 * (2 + 3 * (i - 1)), b = i == 0 ? 6 : 6 * (4 + 3 * (i - 1));   // ahead of it, behind it
            pos[i] = new Vec3(at[m], at[m + 1], at[m + 2]);
            nrm[i] = new Vec3(at[m + 3], at[m + 4], at[m + 5]);
            fwd[i] = new Vec3(at[a] - at[b], at[a + 1] - at[b + 1], at[a + 2] - at[b + 2]);
            ups[i] = nrm[i];
        }
        if (w > 1.0E-4) {
            // the reared front: each piece's back turned as the one behind it was, square to the way the curve runs there,
            // from the rock's normal where the curve leaves the rock (so the neck never twists, even pointing straight out)
            Vec3 carry = null;
            for (int i = PIECES - 1; i >= 0; i--) {
                double arc = i == 0 ? 0.0 : GiantSkeinEntity.plateAt(i - 1);
                if (arc >= GiantStrike.NECK || fwd[i].lengthSqr() < 1.0E-10) { carry = nrm[i]; continue; }
                Vec3 f = fwd[i].normalize(), u = carry == null ? nrm[i] : carry;
                Vec3 up = u.subtract(f.scale(u.dot(f)));
                ups[i] = up.lengthSqr() < 1.0E-6 ? nrm[i] : up.normalize();
                carry = ups[i];
            }
        }
        double ox = Mth.lerp(pt, e.xOld, e.getX()), oy = Mth.lerp(pt, e.yOld, e.getY()), oz = Mth.lerp(pt, e.zOld, e.getZ());
        for (int i = 0; i < PIECES; i++) {
            Quaternionf turn;
            if (i == 0) {
                turn = headTurn;
                if (w > 1.0E-4) turn = new Quaternionf(headTurn).slerp(turnAlong(fwd[0], ups[0], headTurn), (float) w);
            } else turn = turnAlong(fwd[i], ups[i], headTurn);
            Vector3f lu = turn.transform(new Vector3f(0, 1, 0));
            lights[i] = LevelRenderer.getLightColor(e.level(), BlockPos.containing(pos[i].add(lu.x * 0.4, lu.y * 0.4, lu.z * 0.4)));
            pose.pushPose();
            pose.translate(pos[i].x - ox, pos[i].y - oy, pos[i].z - oz);
            pose.mulPose(turn);
            move(e, pt, pose, i);
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.501F, 0.0F);
            poses[i].set(pose.last().pose());
            normals[i].set(pose.last().normal());
            pose.popPose();
        }
    }

    /** A piece's turn: its back to {@code up}, its front along {@code f} (the way its body runs there, towards the head). */
    private static Quaternionf turnAlong(Vec3 t, Vec3 up, Quaternionf fallback) {
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
     * The piece's own motion on its place (in its frame: x to its right, y up from the rock, z back): its front risen
     * as it notices you, swaying slowly as it crawls, trembling as it coils to strike, sinking as it dies.
     */
    private static void move(GiantSkeinEntity e, float pt, PoseStack pose, int i) {
        float age = e.tickCount + pt;
        float lift = e.lift(pt);
        if (i < RISE.length && lift > 0.001F) {
            pose.translate(0.0F, RISE[i] * lift, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(RISE_PITCH[i] * lift));
        }
        float run = e.run(pt);
        if (run > 0.001F) {
            // a slow sway down its length, more towards the tail
            float ph = e.legPhase(pt) * SWAY_RATE - i * SWAY_LAG, reach = 0.3F + 0.7F * i / PIECES;
            pose.translate(Mth.sin(ph) * SWAY_SIDE * run * reach, 0.0F, 0.0F);
            pose.mulPose(Axis.YP.rotation(Mth.cos(ph) * SWAY_TURN * run * reach));
        }
        float shudder = e.shudder(pt);
        if (shudder > 0.01F && i < 10) {
            // a tremor through its coiled front, dying away down its body
            float k = shudder * (1.0F - i / 10.0F);
            pose.translate(Mth.sin(age * 7.0F + i * 1.3F) * 0.05F * k, Math.abs(Mth.sin(age * 5.0F + i)) * 0.03F * k, 0.0F);
            pose.mulPose(Axis.ZP.rotation(Mth.sin(age * 9.0F + i * 0.9F) * 0.05F * k));
        }
        float dead = death(e, pt);
        if (dead > 0.0F) {
            pose.translate(0.0F, -0.15F * dead, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(dead * 8.0F * (i % 2 == 0 ? 1.0F : -1.0F)));
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** Draws every piece where {@link #place} put it, posed for this frame: the texture pass, or the glow pass. */
    private void draw(GiantSkeinEntity e, float pt, PoseStack pose, VertexConsumer vc, int overlay, float level, boolean glow, float flare, float dead) {
        float age = e.tickCount + pt;
        for (int i = 0; i < PIECES; i++) {
            pose.pushPose();
            pose.last().pose().set(poses[i]);
            pose.last().normal().set(normals[i]);
            int light = glow ? 0xF000F0 : lights[i];
            // the glow: a slow wave of light running down its length
            float l = glow ? level * (0.85F + 0.15F * Mth.sin(age * 0.07F - i * 0.5F)) : level;
            if (i == 0) {
                poseHead(e, pt, flare, dead);
                head.render(pose, vc, light, overlay, l, l, l, 1.0F);
            } else {
                Plate p = kind(i - 1);
                posePlate(e, pt, p, i - 1, flare, dead);
                p.root().render(pose, vc, light, overlay, l, l, l, 1.0F);
            }
            pose.popPose();
        }
    }

    /** The feelers' sweep, raised as it flares; the mandibles open as much as the jaw says. */
    private void poseHead(GiantSkeinEntity e, float pt, float flare, float dead) {
        head.getAllParts().forEach(ModelPart::resetPose);
        float jaw = e.jaw(pt);
        feeler(feelerLeft, feelerTipLeft, -1.0F, e.feelerYaw(0, pt), e.feelerPitch(0, pt), flare, dead);
        feeler(feelerRight, feelerTipRight, 1.0F, e.feelerYaw(1, pt), e.feelerPitch(1, pt), flare, dead);
        mandibleLeft.yRot -= jaw * 0.6F;
        mandibleRight.yRot += jaw * 0.6F;
        mandibleTipLeft.yRot -= jaw * 0.2F;
        mandibleTipRight.yRot += jaw * 0.2F;
    }

    /** A feeler: {@code out} is the sign of a turn outward (left is -1); raised as it flares, drooping dead. */
    private static void feeler(ModelPart base, ModelPart tip, float out, float yaw, float pitch, float flare, float dead) {
        base.yRot += out * yaw;
        base.xRot += pitch - flare * 0.35F + dead * 0.6F;
        tip.yRot += out * yaw * 0.6F;
        tip.xRot += pitch * 0.5F + dead * 0.5F;
    }

    /**
     * The k-th plate: tapered to its width and a little flatter towards the tail, its spines and thorns moved out with
     * its edge (every other plate's spines a little shorter, so its back is not a comb), raised and spread as it flares,
     * its legs in their place in the wave.
     */
    private void posePlate(GiantSkeinEntity e, float pt, Plate p, int k, float flare, float dead) {
        p.root().getAllParts().forEach(ModelPart::resetPose);
        float w = width(k), wr = w / p.width(), ys = 0.82F + 0.18F * w / WIDE;
        float legScale = 1.0F - LEG_SHRINK * k / (PLATES - 1);
        p.shell().xScale = wr;
        p.shell().yScale = ys;
        float run = e.run(pt), age = e.tickCount + pt, shudder = e.shudder(pt);
        for (int i = 0; i < p.spines().length; i++) {
            ModelPart sp = p.spines()[i];
            float sgn = i == 0 ? 1.0F : -1.0F, size = k % 2 == 1 ? 0.84F : 1.0F;
            sp.x *= wr;
            sp.y *= ys;
            sp.xScale = sp.yScale = sp.zScale = size;
            sp.xRot += 0.35F * flare + jitter(k, i) * 0.06F;
            sp.zRot += sgn * (0.2F * flare + jitter(k, i + 2) * 0.05F);
        }
        for (int i = 0; i < p.thorns().length; i++) {
            ModelPart th = p.thorns()[i];
            float sgn = i < p.thorns().length / 2 ? 1.0F : -1.0F;
            th.x = sgn * (w / 2.0F - 1.0F);
            th.y *= ys;
            th.xRot += 0.15F * flare;
        }
        for (int i = 0; i < p.fan().length; i++) {
            // the tail fan spreads and rises as it flares, and trails its sway
            ModelPart f = p.fan()[i];
            float spread = (i - 2) / 2.0F;
            f.zRot += spread * 0.3F * flare;
            f.xRot += 0.25F * flare + Mth.sin(e.legPhase(pt) * SWAY_RATE - PIECES * SWAY_LAG) * 0.08F * run;
        }
        float rest = REST_FRONT + (REST_BACK - REST_FRONT) * k / (PLATES - 1);
        float ripple = e.ripple(pt);
        float twitch = ripple < 0 ? 0.0F : Math.max(0.0F, 1.0F - Math.abs(k - ripple) / 2.0F);
        for (int side = 0; side < 2; side++) {
            float sgn = side == 0 ? 1.0F : -1.0F;
            ModelPart leg = side == 0 ? p.legLeft() : p.legRight(), shin = side == 0 ? p.shinLeft() : p.shinRight();
            float ph = e.legPhase(pt) - k * WAVE_LAG + (side == 0 ? 0.0F : Mth.PI);
            float lift = Math.max(0.0F, Mth.cos(ph)) * LIFT * run + 0.4F * twitch + 0.1F * shudder * Mth.sin(age * 9.0F + k * 2.0F + side);
            leg.x = sgn * (w / 2.0F - 4.0F);
            leg.y *= legScale;
            leg.xScale = leg.yScale = leg.zScale = legScale;
            leg.yRot = sgn * (rest + Mth.sin(ph) * SWING * run + 0.2F * twitch * Mth.sin(age * 1.5F) + jitter(k, side + 4) * 0.05F);
            // dying, its legs fold in under it
            leg.zRot -= sgn * (lift - 0.5F * dead);
            shin.zRot += sgn * 1.2F * dead;
        }
    }

    /** A fixed small number (-1 to 1) for plate k and slot i, so its spines and legs are not all set alike. */
    private static float jitter(int k, int i) {
        int h = (k * 73856093) ^ (i * 19349663);
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return ((h & 0xffff) / 32767.5F) - 1.0F;
    }
}
