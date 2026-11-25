package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.Geometry;
import com.explorationremix.creature.choir.HollowChoirEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws the Hollow Choir (0.21.9): a ring of eight bone plates with four ribs arching over it and five small bells
 * hanging beneath, the sculk core hanging in the middle of the ring, and the six great tendrils that run from the
 * cavern's ceiling past the ring down to their roots on the bowl's floor.
 *
 * <p>Everything comes out of six loose parts of geometry/hollow_choir.json (tools/hollow_choir.py), drawn again and
 * again: the plate eight times round the ring, the rib four times (it carries its own chain of children, so one
 * draw is a whole arch), the bell five times on its own swing, and the tendril segment fourteen times down a curve
 * per tendril with a foot at the bottom. The ring turns slowly on its axis the whole time, which is what tells you at
 * a glance whether the Choir is up and working or down and open.
 *
 * <p>A cut tendril is not drawn at all: the ring is visibly hanging on fewer and fewer threads as the fight goes on,
 * and when the last one parts it has nothing left and falls. The glow texture goes over the whole thing as eyes do,
 * so shader packs light it: dim while the crown is up, flaring as the core opens.
 */
public class HollowChoirRenderer extends EntityRenderer<HollowChoirEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(new ResourceLocation(ExplorationRemix.MOD_ID, "hollow_choir"), "main");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/hollow_choir/hollow_choir.png");
    private static final ResourceLocation GLOW =
            new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/hollow_choir/hollow_choir_glow.png");

    /** The ring's copies, the ribs', the bells', and how many segments a tendril is drawn from. */
    private static final int PLATES = 8, RIBS = 4, BELLS = 5, SEGS = 14;
    /** A segment is one block long in the model (16 px along -z), so a tendril's curve is stepped in blocks. */
    private static final float SEG_LEN = 1.0F;
    /** How far the crown's middle is over the entity's own position (blocks): the middle of its box. */
    private static final float CENTRE = 1.2F;
    /** The tendrils lean in to pass the ring, so they are not straight lines: how far in, at the ring's height. */
    private static final double LEAN = 1.6;
    /** A bell's idle swing and the jolt a toll or a slam gives it (radians). */
    private static final float SWING = 0.07F, JOLT = 0.38F;
    /** The glow while the crown is up, and once the core is open. */
    private static final float GLOW_UP = 0.35F, GLOW_OPEN = 1.0F;

    private final ModelPart plate, rib, bell, core, seg, foot;

    public HollowChoirRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0;
        ModelPart model = ctx.bakeLayer(LAYER);
        this.plate = model.getChild("plate");
        this.rib = model.getChild("rib");
        this.bell = model.getChild("bell");
        this.core = model.getChild("core");
        this.seg = model.getChild("seg");
        this.foot = model.getChild("foot");
    }

    @Override
    public ResourceLocation getTextureLocation(HollowChoirEntity e) {
        return TEXTURE;
    }

    @Override
    public void render(HollowChoirEntity e, float yaw, float pt, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!e.isInvisible()) {
            float glow = Mth.lerp(e.open() ? 0.12F : 0.06F, e.glowO, e.glow);
            glow = GLOW_UP + (GLOW_OPEN - GLOW_UP) * Mth.clamp(glow, 0, 1);
            draw(e, pt, pose, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                    light, OverlayTexture.NO_OVERLAY, 1, false);
            // the glow over it, as eyes are drawn: added to what is under it, so shader packs treat it as a light
            draw(e, pt, pose, buffers.getBuffer(RenderType.eyes(GLOW)), light, OverlayTexture.NO_OVERLAY, glow, true);
        }
        super.render(e, yaw, pt, pose, buffers, light);
    }

    /** One pass over the whole Choir: the ring, the ribs, the bells, the core and the six tendrils. */
    private void draw(HollowChoirEntity e, float pt, PoseStack pose, VertexConsumer vc, int light, int overlay,
                      float alpha, boolean glowPass) {
        float spin = e.spin() * Mth.DEG_TO_RAD;
        float drop = Mth.lerp(pt, e.dropO, e.drop);
        float ring = Mth.lerp(pt, e.ringO, e.ring);
        float bob = Mth.lerp(pt, e.bobO, e.bob);

        pose.pushPose();
        // model space: a block is a unit, y grows downward and the parts face -z (as every creature of the mod's is)
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0, -CENTRE, 0);
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotateY(spin));
        // once it falls the ring tips over a little, so the core inside it is plainly open to a swing
        if (drop > 0) pose.mulPose(new Quaternionf().rotateX(drop * 0.22F));

        for (int i = 0; i < PLATES; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / PLATES));
            // the plates open outwards as the crown falls, which is what lets the core be reached
            pose.mulPose(new Quaternionf().rotateX(-drop * 0.5F));
            plate.render(pose, vc, light, overlay, 1, 1, 1, alpha);
            pose.popPose();
        }
        for (int i = 0; i < RIBS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / RIBS));
            rib.render(pose, vc, light, overlay, 1, 1, 1, alpha);
            pose.popPose();
        }
        for (int i = 0; i < BELLS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / BELLS));
            float phase = bob + i * 1.7F;
            float swing = Mth.sin(phase) * SWING + Mth.sin(phase * 3.1F) * ring * JOLT;
            bell.setRotation(swing, 0, Mth.cos(phase * 0.7F) * SWING);
            bell.render(pose, vc, light, overlay, 1, 1, 1, alpha);
            pose.popPose();
        }
        // the core: still while the crown is up, swelling as it opens
        pose.pushPose();
        float swell = 1 + 0.12F * drop + 0.02F * Mth.sin(bob * 1.3F);
        pose.scale(swell, swell, swell);
        core.render(pose, vc, light, overlay, 1, 1, 1, glowPass ? alpha : 1);
        pose.popPose();
        pose.popPose();

        // the tendrils: in world terms, not the crown's, since their roots stay on the floor while the crown moves
        double ex = Mth.lerp(pt, e.xOld, e.getX()), ey = Mth.lerp(pt, e.yOld, e.getY()), ez = Mth.lerp(pt, e.zOld, e.getZ());
        for (int i = 0; i < HollowChoirEntity.PLACES; i++) {
            if (!e.standing(i)) continue;
            tendril(e, i, pose, vc, light, overlay, alpha, ex, ey, ez);
        }
        pose.popPose();
    }

    /**
     * One tendril: a curve from its root on the floor, up past the ring (leaning in to it) and on to the cavern's
     * ceiling, drawn as a chain of segments with a foot at the bottom. The segments are stepped a block at a time, so
     * a tendril keeps the same look however high the cavern is.
     */
    private void tendril(HollowChoirEntity e, int place, PoseStack pose, VertexConsumer vc, int light, int overlay,
                         float alpha, double ex, double ey, double ez) {
        Vec3 root = e.tendrilFoot(place);
        double topY = e.top();
        double floorY = e.floorY();
        double span = Math.max(4, topY - floorY);
        double ringY = ey;

        // the lean: the tendril bows in towards the ring at the ring's height and straightens again above it
        double ax = root.x - e.getX(), az = root.z - e.getZ();
        double len = Math.max(1e-6, Math.hypot(ax, az));
        double inX = -ax / len * LEAN, inZ = -az / len * LEAN;

        double[] prev = null;
        for (int k = 0; k <= SEGS; k++) {
            double t = k / (double) SEGS;
            double y = floorY + span * t;
            // how near this point is to the ring's height, 0 away from it and 1 at it
            double near = Math.max(0, 1 - Math.abs(y - ringY) / 5.0);
            double bow = near * near;
            double[] at = {root.x + inX * bow, y, root.z + inZ * bow};
            if (prev != null) drawSeg(pose, vc, light, overlay, alpha, prev, at, ex, ey, ez, e);
            prev = at;
        }
        // the root on the floor
        pose.pushPose();
        pose.translate(root.x - ex, -(root.y - ey), root.z - ez);
        foot.render(pose, vc, light, overlay, 1, 1, 1, alpha);
        pose.popPose();
    }

    /** One segment of a tendril, stretched between two points of its curve (the part runs 16 px along -z). */
    private void drawSeg(PoseStack pose, VertexConsumer vc, int light, int overlay, float alpha,
                         double[] a, double[] b, double ex, double ey, double ez, HollowChoirEntity e) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5) return;
        pose.pushPose();
        // this space has y growing downwards, so the world's up is -y here
        pose.translate(a[0] - ex, -(a[1] - ey), a[2] - ez);
        float yawTo = (float) Math.atan2(dx, dz);
        float pitchTo = (float) Math.atan2(-dy, Math.hypot(dx, dz));
        pose.mulPose(new Quaternionf().rotateY(yawTo + Mth.PI));
        pose.mulPose(new Quaternionf().rotateX(pitchTo));
        float stretch = (float) (len / SEG_LEN);
        pose.scale(1, 1, stretch);
        seg.render(pose, vc, light, overlay, 1, 1, 1, alpha);
        pose.popPose();
    }

    /** The light at the crown, which the tendrils and the ring are all drawn by. */
    @Override
    protected int getBlockLightLevel(HollowChoirEntity e, BlockPos pos) {
        return Math.max(super.getBlockLightLevel(e, pos), 6);
    }

    /** The whole thing reaches from the floor to the ceiling, so it is drawn whenever any of it could be on screen. */
    @Override
    public boolean shouldRender(HollowChoirEntity e, net.minecraft.client.renderer.culling.Frustum frustum,
                                double x, double y, double z) {
        return frustum.isVisible(e.getBoundingBoxForCulling());
    }
}
