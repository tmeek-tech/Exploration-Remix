package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.choir.ChoirPose;
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
 * draw is a whole arch), the bell five times on its own swing, and the tendril segment every block and a half along
 * each tendril's line with a foot at the bottom. The ring turns slowly on its axis the whole time, which is what tells
 * you at a glance whether the Choir is up and working or down and open.
 *
 * <p>The tendrils move (0.22.3). Each lies along the line {@link ChoirPose} gives it: standing, reared over the mark
 * it is about to strike with its far end pulled out of the ceiling for a head, down on that mark, or anywhere
 * between. A new one grows down out of the ceiling to its root, and a cut one whips back up into it. Its bands of
 * light dim as it is worn through and flare while it rears, and it flashes white where a blow lands on it. The
 * warnings on the floor are {@link ChoirSigns}'.
 *
 * <p>The glow texture goes over the whole thing as eyes do, so shader packs light it: dim while the crown is up,
 * flaring as the core opens, guttering when a slam is spoiled, and flaring once more as it dies and comes apart.
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
     * tendril looks like something that could hold a crown up. Its head, the far end that strikes, is drawn a
     * quarter bigger again than the foot at its root.
     */
    private static final float THICK = 1.5F, HEAD = 1.25F;
    /** The foot's pad lies this far under the root's own middle (6 px of the model), so the pad rests on the floor. */
    private static final double FOOT_PAD = 6 / 16.0;
    /** How far the crown's middle is over the entity's own position (blocks): the middle of its box. */
    private static final float CENTRE = 1.2F;
    /** A bell's idle swing and the jolt a toll or a slam gives it (radians). */
    private static final float SWING = 0.07F, JOLT = 0.38F;
    /** The glow while the crown is up, and once the core is open. */
    private static final float GLOW_UP = 0.35F, GLOW_OPEN = 1.0F;
    /** The glow pass is drawn at full light, as every glow of the mod's is, so shader packs take it for a lamp. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * A tendril's bands of light keep their own strength whatever the crown is doing: how bright on a whole tendril at
     * rest, how much of that is left on one nearly cut through, and how far over it the one in play flares as it
     * rears. They were 0.9 on every tendril (0.22.2), which with a band every block and a half up six cords was the
     * brightest thing in the bowl and said nothing; now the brightest tendril is the one about to strike.
     */
    private static final float BAND_GLOW = 0.5F, BAND_WORN = 0.3F, BAND_FLARE = 0.5F;
    /** A quarter turn that stands the foot's own down (+y) along a cord's way, for the head. */
    private static final Quaternionf ALONG = new Quaternionf().rotationX(-Mth.HALF_PI);

    private final ModelPart plate, rib, bell, core, seg, foot;

    // one tendril's line and the turn carried along it, kept between calls so that drawing makes no garbage
    private final double[][] at = new double[ChoirPose.SEGS_MAX + 1][3];
    private final Quaternionf turn = new Quaternionf(), step = new Quaternionf(), headTurn = new Quaternionf();
    private final Vector3f was = new Vector3f(), now = new Vector3f();
    private double lowX, lowY, lowZ;

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
            float dying = e.deathTime > 0 ? Math.min(1, (e.deathTime + pt) / HollowChoirEntity.DEATH_TICKS) : 0;
            float stun = Mth.lerp(pt, e.stunO, e.stun);
            float glow = GLOW_UP + (GLOW_OPEN - GLOW_UP) * Mth.clamp(Mth.lerp(pt, e.glowO, e.glow), 0, 1);
            // a spoiled slam: its light gutters while it is stalled
            if (stun > 0.01F) glow *= 1 - stun * (0.55F + 0.35F * Mth.sin((e.tickCount + pt) * 2.3F));
            // dying: it flares, then goes out as it comes apart
            if (dying > 0) glow = dying < 0.3F ? Mth.lerp(dying / 0.3F, glow, 1F) : (1 - dying) / 0.7F;
            // a blow that tells on it flashes it white. (The game's own flash for a hurt mob is red, which is a warm
            // colour and not the Choir's.)
            float hurt = e.hurtTime > 0 ? Mth.clamp((e.hurtTime - pt) / 10F, 0, 1) * 0.8F : 0;
            int overlay = OverlayTexture.pack(OverlayTexture.u(hurt), OverlayTexture.v(false));
            double ex = Mth.lerp(pt, e.xOld, e.getX()), ey = Mth.lerp(pt, e.yOld, e.getY()), ez = Mth.lerp(pt, e.zOld, e.getZ());

            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
            crown(e, pt, pose, vc, light, overlay, 1, dying, stun);
            for (int i = 0; i < HollowChoirEntity.PLACES; i++) tendril(e, i, pt, pose, vc, light, false, ex, ey, ez);
            // the glow over it, as eyes are drawn: added to what is under it, so shader packs treat it as a light.
            // Its strength has to ride in the colour: eyes are added one for one, so an alpha does nothing to them
            // (0.21.9 passed it as one, and the glow stood at full the whole fight instead of flaring as it opens)
            vc = buffers.getBuffer(RenderType.eyes(GLOW));
            crown(e, pt, pose, vc, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, glow, dying, stun);
            for (int i = 0; i < HollowChoirEntity.PLACES; i++) tendril(e, i, pt, pose, vc, FULL_BRIGHT, true, ex, ey, ez);
            // and what the fight draws on the floor to warn of its blows
            ChoirSigns.draw(e, pt, pose, buffers, ex, ey, ez);
        }
        super.render(e, yaw, pt, pose, buffers, light);
    }

    /** One pass over the crown: the ring, the ribs, the bells and the core. */
    private void crown(HollowChoirEntity e, float pt, PoseStack pose, VertexConsumer vc, int light, int overlay,
                       float shade, float dying, float stun) {
        float spin = e.spin() * Mth.DEG_TO_RAD;
        float drop = Mth.lerp(pt, e.dropO, e.drop);
        float ring = Mth.lerp(pt, e.ringO, e.ring);
        float bob = Mth.lerp(pt, e.bobO, e.bob);
        // as it dies its pieces fly apart: slowly at first, then all at once
        float apart = dying * dying;

        pose.pushPose();
        // model space: a block is a unit, y grows downward and the parts face -z (as every creature of the mod's is)
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0, -CENTRE, 0);
        pose.mulPose(new Quaternionf().rotateY(spin));
        // once it falls the ring tips over a little, so the core inside it is plainly open to a swing; a spoiled
        // slam knocks it askew for as long as it is stalled
        float tip = drop * 0.22F + stun * 0.10F;
        if (tip > 0) pose.mulPose(new Quaternionf().rotateX(tip));

        for (int i = 0; i < PLATES; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / PLATES));
            if (apart > 0) pose.translate(0, apart * 2.0F, -apart * 3.5F);
            // the plates open outwards as the crown falls, which is what lets the core be reached
            pose.mulPose(new Quaternionf().rotateX(-drop * 0.5F - dying * 2.2F));
            plate.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        for (int i = 0; i < RIBS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / RIBS));
            if (apart > 0) {
                pose.translate(0, -apart * 1.6F, -apart * 1.4F);
                pose.mulPose(new Quaternionf().rotateX(dying * 1.1F));
            }
            rib.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        for (int i = 0; i < BELLS; i++) {
            pose.pushPose();
            pose.mulPose(new Quaternionf().rotateY(i * Mth.TWO_PI / BELLS));
            if (apart > 0) pose.translate(0, apart * 4.0F, 0);
            float phase = bob + i * 1.7F;
            float swing = Mth.sin(phase) * SWING + Mth.sin(phase * 3.1F) * ring * JOLT;
            bell.setRotation(swing, 0, Mth.cos(phase * 0.7F) * SWING);
            bell.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        // the core: still while the crown is up, swelling as it opens, and shrinking to nothing as it dies
        pose.pushPose();
        float swell = (1 + 0.12F * drop + 0.02F * Mth.sin(bob * 1.3F)) * (1 - dying);
        if (swell > 0.01F) {
            pose.scale(swell, swell, swell);
            core.render(pose, vc, light, overlay, shade, shade, shade, 1);
        }
        pose.popPose();

        pose.popPose();
    }

    /**
     * One tendril, in one pass: the cord along its line ({@link ChoirPose#curve}) as a chain of segments, the foot at
     * its root, and its head at the far end once that has pulled free of the ceiling.
     *
     * <p>It is drawn in the world's own terms and outside the crown's turned-over space on purpose: 0.21.9 and 0.22.0
     * drew the tendrils inside it and turned only their heights back, so every tendril stood on the far side of the
     * dais from its own box and a sword swung at one met nothing. Every point here is where a sword finds it
     * ({@link HollowChoirEntity#tendrilFoot}, and the mark for the head that lies on it).
     */
    private void tendril(HollowChoirEntity e, int i, float pt, PoseStack pose, VertexConsumer vc, int light,
                         boolean glowPass, double ex, double ey, double ez) {
        boolean stands = e.standing(i);
        float grow = Mth.lerp(pt, e.growO[i], e.grow[i]), gone = Mth.lerp(pt, e.goneO[i], e.gone[i]);
        // a place with nothing in it: never grown yet, or cut and long since whipped away
        if (stands ? grow <= 0.001F : gone >= 0.999F) return;
        float reach = Mth.lerp(pt, e.reachO[i], e.reach[i]);
        float plunge = Mth.lerp(pt, e.plungeO[i], e.plunge[i]);
        float hit = Mth.lerp(pt, e.hitO[i], e.hit[i]);

        Vec3 root = e.tendrilFoot(i);
        // the floor's surface: the root's own y is the floor block's, which its box is sunk a block into
        double floor = root.y + 1;
        double baseY = floor + FOOT_PAD * THICK - 0.05;
        double topY = Math.max(e.top() + ChoirPose.BURY, baseY + 4);
        // the cord bows in over the crown as it hangs, and does not follow it down when it falls
        double crownY = Math.max(ey, floor + HollowChoirEntity.CROWN_UP - 1.5);
        double ax = root.x - e.getX(), az = root.z - e.getZ();
        double out = Math.max(1e-6, Math.hypot(ax, az));
        double inX = -ax / out * ChoirPose.LEAN, inZ = -az / out * ChoirPose.LEAN;
        int segs = ChoirPose.segments(topY - baseY);

        // its head: hanging over the mark while it rears, and on it as the blow lands (its pad on the floor)
        double tx = e.tipX[i], tz = e.tipZ[i];
        double open = ChoirPose.open(ax, az, tx - e.getX(), tz - e.getZ());
        double ty = e.tipY[i] + FOOT_PAD * THICK * HEAD + ChoirPose.hover(open) * (1 - plunge);
        ChoirPose.curve(at, segs, root.x, baseY, root.z, topY, crownY, inX, inZ, reach, tx, ty, tz, open);

        float shade;
        int overlay = OverlayTexture.NO_OVERLAY;
        if (glowPass) {
            shade = BAND_GLOW * (1 - (1 - BAND_WORN) * e.wear(i)) + BAND_FLARE * reach + 0.4F * hit;
            shade = Mth.clamp(stands ? shade : shade * (1 - gone), 0, 1);
        } else {
            shade = 1;
            if (hit > 0.01F) overlay = OverlayTexture.pack(OverlayTexture.u(hit * 0.85F), OverlayTexture.v(false));
        }

        // how much of the cord is there: a new one comes down out of the ceiling, a cut one whips back up into it
        double from = stands ? (1 - grow) * (1 - grow) * segs : gone * gone * segs;
        chain(pose, vc, light, overlay, shade, segs, from, ex, ey, ez);

        // the foot: on the floor once it is rooted, reaching down for it while it grows. A cut one has left its foot.
        // The model's y grows downward, so it is turned over (a half turn, not a mirror).
        if (stands) {
            pose.pushPose();
            pose.translate(lowX - ex, lowY - ey, lowZ - ez);
            pose.scale(-THICK, -THICK, THICK);
            foot.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        // the head: the cord's far end, which is in the rock until it rears, with the foot's claws for its own
        if (reach > 0.02F) {
            float size = THICK * HEAD * Mth.clamp(reach * 3, 0, 1);
            pose.pushPose();
            pose.translate(at[segs][0] - ex, at[segs][1] - ey, at[segs][2] - ez);
            pose.mulPose(headTurn);
            pose.scale(size, size, size);
            foot.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
    }

    /**
     * A chain of segments through the points of {@link #at}, from the point {@code from} pieces along it (a part of a
     * piece counts) to the last. Each segment is the model's one, which runs 16 px along its own -z, set at a point
     * and turned to face the next. The turn is carried from one segment to the next (each is the last one's, turned
     * only as far as the line turned between them) rather than worked out afresh from each direction, so the cord
     * never twists about itself and no direction is a special case: a tendril can stand straight up, arch right over
     * and point straight down in one chain. Leaves where the chain began in {@link #lowX} and the turn that stands a
     * foot along its last piece in {@link #headTurn}.
     */
    private void chain(PoseStack pose, VertexConsumer vc, int light, int overlay, float shade, int segs, double from,
                       double ex, double ey, double ez) {
        turn.rotationX(Mth.HALF_PI);   // the segment's own -z, stood straight up
        was.set(0, 1, 0);
        int first = Mth.clamp((int) Math.floor(from), 0, segs - 1);
        double part = Mth.clamp(from - first, 0, 1);
        lowX = at[first][0] + (at[first + 1][0] - at[first][0]) * part;
        lowY = at[first][1] + (at[first + 1][1] - at[first][1]) * part;
        lowZ = at[first][2] + (at[first + 1][2] - at[first][2]) * part;
        for (int k = first; k < segs; k++) {
            double[] b = at[k + 1];
            double sx = k == first ? lowX : at[k][0], sy = k == first ? lowY : at[k][1], sz = k == first ? lowZ : at[k][2];
            double dx = b[0] - sx, dy = b[1] - sy, dz = b[2] - sz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-4) continue;
            now.set((float) (dx / len), (float) (dy / len), (float) (dz / len));
            turn.premul(step.rotationTo(was, now));
            was.set(now);
            pose.pushPose();
            pose.translate(sx - ex, sy - ey, sz - ez);
            pose.mulPose(turn);
            // a hair long, so two segments meeting at a bend never show a gap on the outside of it
            pose.scale(THICK, THICK, (float) len * 1.02F);
            seg.render(pose, vc, light, overlay, shade, shade, shade, 1);
            pose.popPose();
        }
        headTurn.set(turn).mul(ALONG);
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
