package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.creature.choir.ChoirActs;
import com.explorationremix.creature.choir.HollowChoirEntity;
import com.explorationremix.world.terrain.ChoirSites;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * The warnings the Hollow Choir's fight draws in the world (0.22.3): where each blow will land, and a count down to
 * when. The mod has no dodge roll, no parry and no stamina, so a blow that could not be read could only be taken;
 * these are what make each one a thing to act on.
 * <ul>
 * <li><b>Slam.</b> A circle on the floor from the tick the tendril begins to rear, which says where, with a low rim
 * of light standing on it so that it shows to someone inside it who is looking up at the crown; and a disc that fills
 * the circle from the middle and meets it the tick the blow lands, which says when.
 * <li><b>Toll.</b> A ring of light that drops out of the crown to the dais's edge, then runs out over the floor as a
 * low wall with the band that strikes drawn under it. It is cut off behind anything solid, as the blow is.
 * <li><b>Grab.</b> Three ripples that run in from the rim to the dais, over the seats and down the ramps.
 * <li><b>Bloom.</b> Every block of sculk in the bowl, lit and brightening.
 * </ul>
 * Each is drawn from the same clock the server strikes by ({@link ChoirActs}), so a sign and its blow cannot drift
 * apart. They are light added onto what is under them, in the Choir's own light blue, flat on the floor or low over
 * it: nothing here stands in the way of the view.
 *
 * <p>The light is in the corners, not in the sheet. Every shape is drawn in a few strips with a strength at each
 * corner, and the game fades one into the next, so a ring's glow is smooth however wide it is drawn. (A first cut
 * kept the fade in the sheet's pixels; stretched over three blocks of floor, each pixel was a hard-edged band.)
 */
final class ChoirSigns {
    private ChoirSigns() {}

    static final ResourceLocation SHEET =
            new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/hollow_choir/hollow_choir_signs.png");

    // the sheet's pieces (tools/hollow_choir.py, SIGN_*), in shares of its 64 pixels: the middle of its patch of plain
    // light, and its four squares of sculk veins, a hair inside each square's edge
    private static final float PX = 1 / 64F, IN = 0.02F / 64F;
    private static final float WHITE_U = 60 * PX, WHITE_V = 4 * PX;
    private static final float TILE_V0 = 24 * PX, TILE = 16 * PX;
    /** Which corner of a tile's piece each corner of its square takes, before it is turned. */
    private static final int[] CORNER_U = {0, 0, 1, 1}, CORNER_V = {0, 1, 1, 0};

    /**
     * The colour of the light, for its red and green (its blue is all of its strength): the Choir's own light blue,
     * a shade deeper than its bands since light added onto lit stone pales, running to a paler blue only where it is
     * at its very brightest, from {@link #HOT_FROM} of full strength up.
     */
    private static final float RED = 0.50F, GREEN = 0.74F, RED_HOT = 0.80F, GREEN_HOT = 0.92F, HOT_FROM = 0.72F;

    /** Everything round is cut into the toll's own slices, so a ring is broken exactly where the blow is. */
    private static final int N = ChoirActs.TOLL_SLICES;
    private static final float[] COS = new float[N + 1], SIN = new float[N + 1];

    static {
        for (int i = 0; i <= N; i++) {
            double a = i * Math.PI * 2 / N;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    /** Across a ring lying on the floor, edge to edge: a bright line down its middle and a soft skirt either side. */
    private static final float[] ACROSS = {-1F, -0.36F, 0F, 0.36F, 1F}, ACROSS_LIGHT = {0F, 0.36F, 1F, 0.36F, 0F};
    /** Out along a disc, middle to edge: a faint wash and a bright leading edge, which dies away just past it. */
    private static final float[] OUT = {0F, 0.62F, 0.90F, 1F}, OUT_LIGHT = {0.10F, 0.16F, 0.42F, 1F};
    private static final float OUT_SKIRT = 0.14F;
    /** Up a wall of light, foot to top: brightest at the floor, gone at its top. */
    private static final float[] UP = {0F, 0.3F, 1F}, UP_LIGHT = {0.8F, 0.3F, 0F};

    /**
     * A sign lies this far over the stone it is drawn on; a toll's wall of light stands this tall, and the rim of a
     * slam's mark this tall.
     */
    private static final double LIFT = 0.03, WALL_H = 0.6, RIM_H = 0.9;
    /** A sprout's thread to the crown: this wide, drawn in this many pieces. */
    private static final double THREAD_W = 0.07;
    private static final int THREAD_PIECES = 10;

    /** Draws whatever signs the fight is showing now. (ex, ey, ez) is where the crown is drawn, which all is measured from. */
    static void draw(HollowChoirEntity e, float pt, PoseStack pose, MultiBufferSource buffers, double ex, double ey, double ez) {
        int act = e.act(), phase = e.phase();
        float clock = e.clock(pt);
        float flash = Mth.lerp(pt, e.flashO, e.flash);
        boolean bloom = act == HollowChoirEntity.ACT_BLOOM && !e.bloomTiles.isEmpty();
        if (act != HollowChoirEntity.ACT_WINDUP && act != HollowChoirEntity.ACT_SLAM && act != HollowChoirEntity.ACT_TOLL
                && act != HollowChoirEntity.ACT_GRAB && !bloom && flash <= 0.01F && e.sproutsSeen.isEmpty()) return;

        VertexConsumer vc = buffers.getBuffer(GlowRender.glow(SHEET));
        Matrix4f m = pose.last().pose();
        double x = e.getX() - ex, z = e.getZ() - ez;
        double floor = e.floorY() + 1 - ey;

        // ---- the slam's mark: the circle says where from the first tick, the disc filling it says when
        if (act == HollowChoirEntity.ACT_WINDUP || (act == HollowChoirEntity.ACT_SLAM && clock <= ChoirActs.SLAM_AT)) {
            Vec3 mark = e.mark();
            float fill = ChoirActs.slamFill(phase, act == HollowChoirEntity.ACT_SLAM, clock);
            double mx = mark.x - ex, my = mark.y + LIFT - ey, mz = mark.z - ez;
            // the circle throbs, and the throb quickens as the disc closes on it
            float throb = 0.84F + 0.16F * Mth.sin(fill * fill * 32F);
            ring(m, vc, mx, my, mz, ChoirActs.SLAM_R, 0.5, (0.6F + 0.4F * fill) * throb, null);
            // its rim: low, and faint until the blow is near, but it is what someone standing inside the circle
            // sees of it while they look up at the crown
            wall(m, vc, mx, my, mz, ChoirActs.SLAM_R, RIM_H, (0.25F + 0.55F * fill) * throb, null);
            disc(m, vc, mx, my + 0.008, mz, ChoirActs.SLAM_R * fill, 0.5F + 0.5F * fill);
        }
        if (flash > 0.01F) {
            double fx = e.flashX - ex, fy = e.flashY + LIFT - ey, fz = e.flashZ - ez;
            ring(m, vc, fx, fy, fz, ChoirActs.SLAM_R * (1 + 0.3 * (1 - flash)), 0.6, flash, null);
            disc(m, vc, fx, fy + 0.008, fz, ChoirActs.SLAM_R, flash * 0.7F);
        }

        // ---- the toll: each ring drops out of the crown to the dais's edge, then runs out over the floor
        if (act == HollowChoirEntity.ACT_TOLL) {
            double from = 0.7, to = floor + LIFT;
            for (int k = 0; k < ChoirActs.tollRings(phase); k++) {
                double drop = ChoirActs.tollDrop(k, clock);
                if (!Double.isNaN(drop)) {
                    // falling faster as it goes, and widening from the crown's own rim to where it will land
                    double y = from + (to - from) * drop * drop;
                    double r = ChoirActs.TOLL_CROWN_R + (ChoirActs.TOLL_R0 - ChoirActs.TOLL_CROWN_R) * drop;
                    ring(m, vc, x, y, z, r, 0.45, (float) (0.5 + 0.5 * drop), null);
                    // and the line on the floor it is falling to, brightening as it comes
                    ring(m, vc, x, to, z, ChoirActs.TOLL_R0, 0.4, (float) (0.12 + 0.5 * drop * drop), null);
                }
                double r = ChoirActs.tollRadius(phase, k, clock);
                if (!Double.isNaN(r)) {
                    // it dies away over its last block, at the foot of the seats
                    float light = (float) Mth.clamp((ChoirActs.TOLL_R1 - r) / 1.2 + 0.2, 0, 1);
                    ring(m, vc, x, to, z, r, ChoirActs.tollHalf(phase), light, e.tollWall);
                    wall(m, vc, x, floor, z, r, WALL_H, light, e.tollWall);
                }
            }
        }

        // ---- the grab: three ripples in from the rim, over the seats and down the ramps, then the dais flares
        if (act == HollowChoirEntity.ACT_GRAB) {
            double turn = e.turn();
            for (int k = 0; k < ChoirActs.GRAB_RIPPLES; k++) {
                double r = ChoirActs.grabRipple(k, clock);
                if (Double.isNaN(r)) continue;
                float near = (float) (1 - (r - ChoirActs.GRAB_TO) / (ChoirSites.RIM_R - ChoirActs.GRAB_TO));
                ripple(m, vc, x, floor + LIFT, z, r, 0.7, 0.5F + 0.5F * near, turn);
            }
            float since = clock - ChoirActs.GRAB_TELL;
            if (since >= 0 && since < 8) disc(m, vc, x, floor + LIFT, z, ChoirActs.GRAB_TO + 2.0, 1 - since / 8F);
        }

        // ---- the bloom: every block of sculk in the bowl lit, brightening to the pulse
        if (bloom) {
            float light = Math.min(1, ChoirActs.bloomLight(clock));
            if (light > 0.004F)
                for (BlockPos t : e.bloomTiles) {
                    int h = Mth.murmurHash3Mixer(t.getX() * 73856093 ^ t.getZ() * 19349663);
                    tile(m, vc, t.getX() - ex, t.getY() + 1 + 0.012 - ey, t.getZ() - ez, h & 3, (h >>> 2) & 3, light);
                }
        }

        // ---- the sprouts: a thread of light from each up to the crown, a beat running up it, since each mends it
        if (!e.sproutsSeen.isEmpty()) {
            float beat = ((e.tickCount + pt) % HollowChoirEntity.SPROUT_MEND_EVERY) / HollowChoirEntity.SPROUT_MEND_EVERY;
            for (BlockPos s : e.sproutsSeen)
                thread(m, vc, s.getX() + 0.5 - ex, s.getY() + 0.85 - ey, s.getZ() + 0.5 - ez, x, 1.0, z, beat);
        }
    }

    // ------------------------------------------------------------------------------------------------- the shapes

    /**
     * A ring lying flat: {@code half} wide either side of {@code r}, its bright line on {@code r} itself. With
     * {@code wall}, only the slices it has not been stopped in are drawn.
     */
    private static void ring(Matrix4f m, VertexConsumer vc, double x, double y, double z, double r, double half,
                             float light, @Nullable float[] wall) {
        if (light <= 0.004F || r + half <= 0) return;
        for (int b = 0; b + 1 < ACROSS.length; b++) {
            float r0 = (float) Math.max(0, r + half * ACROSS[b]), r1 = (float) Math.max(0, r + half * ACROSS[b + 1]);
            if (r1 <= 0) continue;
            float l0 = light * ACROSS_LIGHT[b], l1 = light * ACROSS_LIGHT[b + 1];
            for (int s = 0; s < N; s++) {
                if (wall != null && r > wall[s]) continue;
                put(m, vc, x + r0 * COS[s], y, z + r0 * SIN[s], l0);
                put(m, vc, x + r1 * COS[s], y, z + r1 * SIN[s], l1);
                put(m, vc, x + r1 * COS[s + 1], y, z + r1 * SIN[s + 1], l1);
                put(m, vc, x + r0 * COS[s + 1], y, z + r0 * SIN[s + 1], l0);
            }
        }
    }

    /** A ring that lies on the arena's own ground slice by slice: up the steps of the seats and along the ramps. */
    private static void ripple(Matrix4f m, VertexConsumer vc, double x, double y, double z, double r, double half,
                               float light, double turn) {
        if (light <= 0.004F) return;
        for (int s = 0; s < N; s++) {
            double a = ChoirActs.sliceAngle(s);
            double up = y + ChoirActs.groundUp(turn, r * Math.cos(a), r * Math.sin(a));
            for (int b = 0; b + 1 < ACROSS.length; b++) {
                float r0 = (float) Math.max(0, r + half * ACROSS[b]), r1 = (float) Math.max(0, r + half * ACROSS[b + 1]);
                float l0 = light * ACROSS_LIGHT[b], l1 = light * ACROSS_LIGHT[b + 1];
                put(m, vc, x + r0 * COS[s], up, z + r0 * SIN[s], l0);
                put(m, vc, x + r1 * COS[s], up, z + r1 * SIN[s], l1);
                put(m, vc, x + r1 * COS[s + 1], up, z + r1 * SIN[s + 1], l1);
                put(m, vc, x + r0 * COS[s + 1], up, z + r0 * SIN[s + 1], l0);
            }
        }
    }

    /**
     * A wall of light standing on the floor at {@code r}, {@code tall} high and brightest at its foot. With
     * {@code wall}, only the slices it has not been stopped in are drawn.
     */
    private static void wall(Matrix4f m, VertexConsumer vc, double x, double y, double z, double r, double tall,
                             float light, @Nullable float[] wall) {
        if (light <= 0.004F) return;
        float rr = (float) r;
        for (int b = 0; b + 1 < UP.length; b++) {
            double y0 = y + tall * UP[b], y1 = y + tall * UP[b + 1];
            float l0 = light * UP_LIGHT[b], l1 = light * UP_LIGHT[b + 1];
            for (int s = 0; s < N; s++) {
                if (wall != null && r > wall[s]) continue;
                put(m, vc, x + rr * COS[s], y0, z + rr * SIN[s], l0);
                put(m, vc, x + rr * COS[s + 1], y0, z + rr * SIN[s + 1], l0);
                put(m, vc, x + rr * COS[s + 1], y1, z + rr * SIN[s + 1], l1);
                put(m, vc, x + rr * COS[s], y1, z + rr * SIN[s], l1);
            }
        }
    }

    /** A disc lying flat: a faint wash with a bright edge. */
    private static void disc(Matrix4f m, VertexConsumer vc, double x, double y, double z, double r, float light) {
        if (light <= 0.004F || r <= 0.02) return;
        for (int b = 0; b < OUT.length; b++) {
            // the last strip is the skirt past the edge
            boolean skirt = b + 1 == OUT.length;
            float r0 = (float) (r * OUT[b]), r1 = skirt ? (float) r + OUT_SKIRT : (float) (r * OUT[b + 1]);
            float l0 = light * OUT_LIGHT[b], l1 = skirt ? 0 : light * OUT_LIGHT[b + 1];
            for (int s = 0; s < N; s += 2) {
                put(m, vc, x + r0 * COS[s], y, z + r0 * SIN[s], l0);
                put(m, vc, x + r1 * COS[s], y, z + r1 * SIN[s], l1);
                put(m, vc, x + r1 * COS[s + 2], y, z + r1 * SIN[s + 2], l1);
                put(m, vc, x + r0 * COS[s + 2], y, z + r0 * SIN[s + 2], l0);
            }
        }
    }

    /** One block's worth of lit sculk: one of the sheet's four squares of veins, turned one of four ways. */
    private static void tile(Matrix4f m, VertexConsumer vc, double x, double y, double z, int which, int turned, float light) {
        float u0 = which * TILE + IN, du = TILE - 2 * IN, v0 = TILE_V0 + IN, dv = TILE - 2 * IN;
        for (int c = 0; c < 4; c++) {
            int k = (c + turned) & 3;
            put(m, vc, x + CORNER_U[c], y, z + CORNER_V[c], u0 + CORNER_U[k] * du, v0 + CORNER_V[k] * dv, light);
        }
    }

    /** A thin thread of light from one point up to another, with a bright beat {@code beat} of the way along it. */
    private static void thread(Matrix4f m, VertexConsumer vc, double x0, double y0, double z0, double x1, double y1,
                               double z1, float beat) {
        for (int k = 0; k < THREAD_PIECES; k++) {
            float t0 = k / (float) THREAD_PIECES, t1 = (k + 1) / (float) THREAD_PIECES;
            float l0 = threadLight(t0, beat), l1 = threadLight(t1, beat);
            double ax = x0 + (x1 - x0) * t0, ay = y0 + (y1 - y0) * t0, az = z0 + (z1 - z0) * t0;
            double bx = x0 + (x1 - x0) * t1, by = y0 + (y1 - y0) * t1, bz = z0 + (z1 - z0) * t1;
            // two flat strips crossed, so it shows from every side
            put(m, vc, ax - THREAD_W, ay, az, l0);
            put(m, vc, ax + THREAD_W, ay, az, l0);
            put(m, vc, bx + THREAD_W, by, bz, l1);
            put(m, vc, bx - THREAD_W, by, bz, l1);
            put(m, vc, ax, ay, az - THREAD_W, l0);
            put(m, vc, ax, ay, az + THREAD_W, l0);
            put(m, vc, bx, by, bz + THREAD_W, l1);
            put(m, vc, bx, by, bz - THREAD_W, l1);
        }
    }

    private static float threadLight(float t, float beat) {
        return 0.16F + 0.6F * Math.max(0, 1 - Math.abs(t - beat) * 5);
    }

    /** One corner of plain light at a strength. */
    private static void put(Matrix4f m, VertexConsumer vc, double x, double y, double z, float light) {
        put(m, vc, x, y, z, WHITE_U, WHITE_V, light);
    }

    /**
     * One corner. The light rides in the colour: this is added onto what is behind it, and an alpha does nothing. It
     * is the Choir's light blue at every strength but the very brightest, where it pales.
     */
    private static void put(Matrix4f m, VertexConsumer vc, double x, double y, double z, float u, float v, float light) {
        float l = Mth.clamp(light, 0, 1);
        float hot = l <= HOT_FROM ? 0 : (l - HOT_FROM) / (1 - HOT_FROM);
        hot *= hot;
        vc.vertex(m, (float) x, (float) y, (float) z)
                .color((RED + (RED_HOT - RED) * hot) * l, (GREEN + (GREEN_HOT - GREEN) * hot) * l, l, 1F).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(0, 1, 0).endVertex();
    }
}
