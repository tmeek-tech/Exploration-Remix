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
import org.joml.Vector3f;

/**
 * Draws the Hollow Choir (0.21.9): a ring of eight bone plates with four ribs arching over it and five small bells
 * hanging beneath, the sculk core hanging in the middle of the ring, and the six great tendrils that run from the
 * cavern's ceiling past the ring down to their roots on the bowl's floor.
 *
 * <p>Everything comes out of six loose parts of geometry/hollow_choir.json (tools/hollow_choir.py), drawn again and
 * again: the plate eight times round the ring, the rib four times (it carries its own chain of children, so one
 * draw is a whole arch), the bell five times on its own swing, and the tendril segment every block and a half up a
 * curve per tendril with a foot at the bottom. The ring turns slowly on its axis the whole time, which is what tells you at
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

    /** The ring's copies, the ribs' and the bells'. */
    private static final int PLATES = 8, RIBS = 4, BELLS = 5;
    /**
     * A tendril is drawn half again as thick as its model, so the cord is three quarters of a block through and a
     * tendril looks like something that could hold a crown up; its segments are cut to about that much longer too
     * ({@link #SEG_STEP} blocks), however high the cavern is, so the cord is never a stretched texture.
     */
    private static final float THICK = 1.5F;
    private static final double SEG_STEP = 1.5;
    private static final int SEGS_MAX = 48;
    /** A tendril runs this far up past the cavern's ceiling as the site knows it, so its end is always in the rock. */
    private static final double BURY = 3.0;
    /** The foot's pad lies this far under the root's own middle (6 px of the model), so the pad rests on the floor. */
    private static final double FOOT_PAD = 6 / 16.0;
    /** How far the crown's middle is over the entity's own position (blocks): the middle of its box. */
    private static final float CENTRE = 1.2F;
    /**
     * The tendrils lean in to pass the ring, so they are not straight lines: how far in at the ring's height, and how
     * far above and below it the lean is taken up over.
     */
    private static final double LEAN = 1.6, LEAN_SPAN = 7.0;
    /** A bell's idle swing and the jolt a toll or a slam gives it (radians). */
    private static final float SWING = 0.07F, JOLT = 0.38F;
    /** The glow while the crown is up, and once the core is open. */
    private static final float GLOW_UP = 0.35F, GLOW_OPEN = 1.0F;
    /** The glow pass is drawn at full light, as every glow of the mod's is, so shader packs take it for a lamp. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * The tendrils' bands keep their own glow whatever the crown is doing: they are what shows a tendril at all against
     * a dark cavern, and none stands while the core is open, so the crown's dim-and-flare has nothing to say to them.
     */
    private static final float TENDRIL_GLOW = 0.9F;

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
            // the glow over it, as eyes are drawn: added to what is under it, so shader packs treat it as a light.
            // Its strength has to ride in the colour: eyes are added one for one, so an alpha does nothing to them
            // (0.21.9 passed it as one, and the glow stood at full the whole fight instead of flaring as it opens)
            draw(e, pt, pose, buffers.getBuffer(RenderType.eyes(GLOW)), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, glow, true);
        }
        super.render(e, yaw, pt, pose, buffers, light);
    }

    /** One pass over the whole Choir: the ring, the ribs, the bells, the core and the six tendrils. */
    private void draw(HollowChoirEntity e, float pt, PoseStack pose, VertexConsumer vc, int light, int overlay,
                      float shade, boolean glowPass) {
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
            plate.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        for (int i = 0; i < RIBS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / RIBS));
            rib.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        for (int i = 0; i < BELLS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / BELLS));
            float phase = bob + i * 1.7F;
            float swing = Mth.sin(phase) * SWING + Mth.sin(phase * 3.1F) * ring * JOLT;
            bell.setRotation(swing, 0, Mth.cos(phase * 0.7F) * SWING);
            bell.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        // the core: still while the crown is up, swelling as it opens
        pose.pushPose();
        float swell = 1 + 0.12F * drop + 0.02F * Mth.sin(bob * 1.3F);
        pose.scale(swell, swell, swell);
        core.render(pose, vc, light, overlay, shade, shade, shade, 1);
        pose.popPose();
        pose.popPose();

        pose.popPose();

        // the tendrils: in the world's own terms, not the crown's, since their roots stay on the floor while the crown
        // moves. They are drawn outside the crown's turned-over space on purpose: 0.21.9 and 0.22.0 drew them inside
        // it and turned only their heights back, so every tendril stood on the far side of the dais from its own box
        // (its x the wrong way round) and a sword swung at one met nothing.
        double ex = Mth.lerp(pt, e.xOld, e.getX()), ey = Mth.lerp(pt, e.yOld, e.getY()), ez = Mth.lerp(pt, e.zOld, e.getZ());
        float tendrilShade = glowPass ? TENDRIL_GLOW : shade;
        for (int i = 0; i < HollowChoirEntity.PLACES; i++) {
            if (!e.standing(i)) continue;
            tendril(e, i, pose, vc, light, overlay, tendrilShade, ex, ey, ez);
        }
    }

    /**
     * One tendril: a curve from its root on the floor, up past the ring (leaning in to it) and on into the cavern's
     * ceiling, drawn as a chain of segments with a foot at the bottom. Every point of it is in the world's own terms
     * and is drawn there, so the cord stands in the box a sword finds it by ({@link HollowChoirEntity#tendrilFoot}).
     */
    private void tendril(HollowChoirEntity e, int place, PoseStack pose, VertexConsumer vc, int light, int overlay,
                         float shade, double ex, double ey, double ez) {
        Vec3 root = e.tendrilFoot(place);
        // the floor's surface: the root's own y is the floor block's, which its box is sunk a block into
        double floor = root.y + 1;
        double baseY = floor + FOOT_PAD * THICK - 0.05;
        double topY = Math.max(e.top() + BURY, baseY + 4);
        double ringY = ey;

        // the lean: the tendril bows in towards the ring at the ring's height and straightens again above it
        double ax = root.x - e.getX(), az = root.z - e.getZ();
        double len = Math.max(1e-6, Math.hypot(ax, az));
        double inX = -ax / len * LEAN, inZ = -az / len * LEAN;

        int segs = Mth.clamp((int) Math.round((topY - baseY) / SEG_STEP), 4, SEGS_MAX);
        double[][] at = new double[segs + 1][3];
        for (int k = 0; k <= segs; k++) {
            double y = baseY + (topY - baseY) * k / segs;
            // how near this point is to the ring's height, 0 away from it and 1 at it, eased both ways so the cord
            // bends in a curve and not at a knee
            double near = Mth.clamp(1 - Math.abs(y - ringY) / LEAN_SPAN, 0, 1);
            double bow = near * near * (3 - 2 * near);
            at[k][0] = root.x + inX * bow;
            at[k][1] = y;
            at[k][2] = root.z + inZ * bow;
        }
        chain(pose, vc, light, overlay, shade, at, segs, ex, ey, ez);

        // the root on the floor: the model's y grows downward, so it is turned over (a half turn, not a mirror)
        pose.pushPose();
        pose.translate(root.x - ex, baseY - ey, root.z - ez);
        pose.scale(-THICK, -THICK, THICK);
        foot.render(pose, vc, light, overlay, shade, shade, shade, 1);
        pose.popPose();
    }

    /**
     * A chain of segments through the points of a curve, first to last. Each segment is the model's one, which runs
     * 16 px along its own -z, set at a point and turned to face the next. The turn is carried from one segment to the
     * next (each is the last one's, turned only as far as the curve turned between them) rather than worked out afresh
     * from each direction, so the cord never twists about itself and no direction is a special case: a tendril can
     * stand straight up, arch right over and point straight down in one chain.
     */
    private void chain(PoseStack pose, VertexConsumer vc, int light, int overlay, float shade, double[][] at, int segs,
                       double ex, double ey, double ez) {
        Quaternionf turn = new Quaternionf().rotationX(Mth.HALF_PI);   // the segment's own -z, stood straight up
        Vector3f was = new Vector3f(0, 1, 0), now = new Vector3f();
        Quaternionf step = new Quaternionf();
        for (int k = 0; k < segs; k++) {
            double[] a = at[k], b = at[k + 1];
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-4) continue;
            now.set((float) (dx / len), (float) (dy / len), (float) (dz / len));
            turn.premul(step.rotationTo(was, now));
            was.set(now);
            pose.pushPose();
            pose.translate(a[0] - ex, a[1] - ey, a[2] - ez);
            pose.mulPose(turn);
            // a hair long, so two segments meeting at a bend never show a gap on the outside of it
            pose.scale(THICK, THICK, (float) len * 1.02F);
            seg.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
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
