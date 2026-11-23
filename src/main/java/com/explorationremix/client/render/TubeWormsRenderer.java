package com.explorationremix.client.render;

import com.explorationremix.block.TubeWormsBlock;
import com.explorationremix.block.TubeWormsBlockEntity;
import com.explorationremix.client.TubeWormsClient;
import com.explorationremix.client.model.TubeWormsModel;
import com.explorationremix.client.rune.fx.GlowRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.IQuadTransformer;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The tube worms' plumes, moving (0.20.6; Taran: "Tube worms should play a pulling in animation ... Also give them an
 * animation so that they wiggle when out."), drawn for the clumps client.TubeWormsClient keeps live while their
 * chunk's model leaves the plumes out. The plumes are the block model's own (each a crossed pair of faces, and its
 * glow over it), read once per look and moved about the foot of each in its tube's mouth.
 *
 * <p>Taran's card picks. Going in, "snap in, peek out slowly": each plume shrinks down into its tube in
 * {@link #SNAP} ticks, closing as it goes, a plume lagging the next by up to {@link #IN_LAG}. Coming out, each in its
 * own time over {@link #OUT_LAG} ticks: a narrow tuft peeks out of the mouth ({@link #PEEK}), waits ({@link #HOLD}),
 * then rises and opens out, a little past and back ({@link #UNFURL}). Out, "lively, own beat, twitches": each plume
 * sways on two slow beats of its own, turns a little about itself and breathes wider and narrower, and every few
 * seconds twitches, a quick jerk one way that shivers out. The further in a plume is, the less it moves.
 *
 * <p>The plumes are drawn as the chunk would: lit by the world on the block atlas, the glow added over them at full
 * light (on the plume's darkened texels, so it shows as the chunk's does), both seen from either side.
 */
public final class TubeWormsRenderer implements BlockEntityRenderer<TubeWormsBlockEntity> {
    /** Going in: ticks to snap down, and the most a plume lags. */
    static final float SNAP = 3.0F, IN_LAG = 1.5F;
    /** Coming out: the most a plume lags; ticks to peek, to wait peeking, and to unfurl. */
    static final float OUT_LAG = 12.0F, PEEK = 10.0F, HOLD = 8.0F, UNFURL = 20.0F;
    /** Peeking: how far out (of its full height) and how open (of its full width) a plume is. */
    static final float PEEK_E = 0.45F, PEEK_OPEN = 0.3F;
    /** Out: the sway and the turn about itself (radians, about 8 and 10 degrees), a twitch's jerk, the breathing (of its width). */
    static final float SWAY = 0.14F, TWIST = 0.17F, TWITCH = 0.30F, BREATHE = 0.10F;
    /** A twitch: every so many seconds (from the first, plus up to the second), and how long it shivers. */
    static final double TWITCH_EVERY = 3.5, TWITCH_MORE = 4.0, TWITCH_LONG = 0.45;

    /** Bumped as the models are baked again (client.ClientSetup): the plumes are read anew. */
    public static volatile int bakes;
    private static final Map<List<BakedQuad>, Plume[]> LOOKS = new IdentityHashMap<>();
    private static int looksFrom = -1;
    private static final RandomSource RAND = RandomSource.create();
    private static final RenderType BASE = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    private static final RenderType GLOW = GlowRender.glow(InventoryMenu.BLOCK_ATLAS);
    /** This frame's plume: its 3 x 3 (turn, lean and scale, into the camera's frame) and where its foot is. */
    private final float[] a = new float[12];

    public TubeWormsRenderer(BlockEntityRendererProvider.Context ctx) {}

    /** A plume: its foot in its tube's mouth (in the block), and its faces' corners (x, y, z from the foot, u, v). */
    record Plume(float x, float y, float z, float[] base, float[] glow) {}

    @Override
    public boolean shouldRender(TubeWormsBlockEntity be, Vec3 camera) {
        return TubeWormsClient.drawn(be) && BlockEntityRenderer.super.shouldRender(be, camera);
    }

    @Override
    public int getViewDistance() { return 64; }

    @Override
    public void render(TubeWormsBlockEntity be, float pt, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) return;
        long now = level.getGameTime();
        boolean out = be.out();
        if (!out && now - be.inAt > IN_LAG + SNAP + 1.0F) return;        // in: nothing shows
        Plume[] plumes = plumes(be);
        if (plumes.length == 0) return;
        BlockPos pos = be.getBlockPos();
        long seed = pos.asLong() * 0x9E3779B97F4A7C15L;
        float w = TubeWormsClient.wiggle(be, now, pt);
        double secs = (now + (double) pt) / 20.0;
        float sinceOut = since(now, be.outAt, pt), sinceIn = since(now, be.inAt, pt);
        float outFor = since(be.inAt, be.outAt, 0.0F);                    // how long they had been coming out when the scare came
        Vec3 off = be.getBlockState().getOffset(level, pos);
        Matrix4f m = pose.last().pose();
        Matrix3f nm = pose.last().normal();
        float nx = nm.m10(), ny = nm.m11(), nz = nm.m12(), nl = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl > 1.0E-6F) {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        VertexConsumer base = buffers.getBuffer(BASE), glow = buffers.getBuffer(GLOW);
        for (int i = 0; i < plumes.length; i++) {
            Plume p = plumes[i];
            long h = mix(seed + (i + 1) * 0x632BE59BD9B4E019L), h2 = mix(h ^ 0x94D049BB133111EBL);
            float e, open;
            if (out) {
                float s = sinceOut - unit(h, 1) * OUT_LAG;
                e = extension(s);
                open = openness(s);
            } else {
                float s0 = outFor - unit(h, 1) * OUT_LAG;
                float e0 = extension(s0), o0 = openness(s0);
                float u = Mth.clamp((sinceIn - unit(h, 0) * IN_LAG) / SNAP, 0.0F, 1.0F), k = (1.0F - u) * (1.0F - u);
                e = e0 * k;
                open = PEEK_OPEN + (o0 - PEEK_OPEN) * k;
            }
            if (e < 0.03F) continue;
            float swayX = 0.0F, swayZ = 0.0F, twist = 0.0F, breathe = 1.0F;
            float amt = w * (float) Math.pow(Math.min(e, 1.0F), 1.5);
            if (amt > 0.001F) {
                double f1 = 0.45 + 0.3 * unit(h, 2), f2 = 1.1 + 0.6 * unit(h, 3);
                swayX = SWAY * (0.65F * wave(secs, f1, unit(h, 4)) + 0.35F * wave(secs, f2, unit(h, 5)));
                swayZ = SWAY * (0.65F * wave(secs, f1 * 0.83, unit(h, 6)) + 0.35F * wave(secs, f2 * 1.13, unit(h, 7)));
                twist = TWIST * wave(secs, 0.25 + 0.25 * unit(h2, 0), unit(h2, 1));
                breathe = 1.0F + BREATHE * wave(secs, 1.0 / (2.6 + unit(h2, 2)), unit(h2, 3));
                // a twitch now and then: a quick jerk one way, shivering out, the plume flinching narrower
                double every = TWITCH_EVERY + TWITCH_MORE * unit(h2, 4), c = secs / every + unit(h2, 5);
                long n = (long) Math.floor(c);
                double into = (c - n) * every / TWITCH_LONG;
                if (into < 1.0) {
                    float k = (float) into;
                    float jerk = TWITCH * (float) Math.exp(-4.0 * k) * Mth.sin(k * 2.5F * Mth.TWO_PI);
                    float dir = unit(mix(h2 + n * 0x2545F4914F6CDD1DL), 0) * Mth.TWO_PI;
                    swayX += jerk * Mth.cos(dir);
                    swayZ += jerk * Mth.sin(dir);
                    breathe *= 1.0F - 0.2F * (float) Math.exp(-6.0 * k);
                }
                swayX *= amt;
                swayZ *= amt;
                twist *= amt;
                breathe = 1.0F + (breathe - 1.0F) * amt;
            }
            frame(m, p, off, swayX, swayZ, twist, open * breathe, e);
            faces(base, p.base, light, nx, ny, nz);
            faces(glow, p.glow, LightTexture.FULL_BRIGHT, nx, ny, nz);
        }
    }

    /** Ticks from {@code then} to {@code now}, as a float (a time long past stays far past). */
    private static float since(long now, long then, float pt) {
        long d = now - then;
        return d > 1_000_000L ? 1.0E6F : (d < -1_000_000L ? -1.0E6F : d + pt);
    }

    /** How far out a plume is (of its full height), {@code s} ticks after it began coming out. */
    static float extension(float s) {
        if (s <= 0.0F) return 0.0F;
        if (s < PEEK) return PEEK_E * smooth(s / PEEK);
        if (s < PEEK + HOLD) return PEEK_E * (1.0F + 0.06F * Mth.sin((s - PEEK) / HOLD * Mth.TWO_PI));
        if (s < PEEK + HOLD + UNFURL) return PEEK_E + (1.0F - PEEK_E) * backOut((s - PEEK - HOLD) / UNFURL);
        return 1.0F;
    }

    /** How open a plume is (of its full width), {@code s} ticks after it began coming out. */
    static float openness(float s) {
        if (s < PEEK + HOLD) return PEEK_OPEN;
        if (s < PEEK + HOLD + UNFURL) return PEEK_OPEN + (1.0F - PEEK_OPEN) * backOut((s - PEEK - HOLD) / UNFURL);
        return 1.0F;
    }

    private static float smooth(float u) { return u * u * (3.0F - 2.0F * u); }

    /** Eases out a little past 1 and back. */
    private static float backOut(float u) {
        float c = 1.4F, v = u - 1.0F;
        return 1.0F + (c + 1.0F) * v * v * v + c * v * v;
    }

    /** A sine of period 1/freq seconds, starting {@code phase} of a turn in, at {@code secs}. */
    private static float wave(double secs, double freq, double phase) {
        double c = secs * freq + phase;
        return Mth.sin((float) ((c - Math.floor(c)) * Mth.TWO_PI));
    }

    /**
     * This plume's frame into {@link #a}: the camera's {@code m} times the plume's foot (with the block's offset),
     * turned about itself, leant over, and scaled across and up.
     */
    private void frame(Matrix4f m, Plume p, Vec3 off, float swayX, float swayZ, float twist, float across, float up) {
        float ct = Mth.cos(twist), st = Mth.sin(twist), cx = Mth.cos(swayX), sx = Mth.sin(swayX), cz = Mth.cos(swayZ), sz = Mth.sin(swayZ);
        // R = Rz(swayZ) Rx(swayX) Ry(twist), its columns scaled (across, up, across)
        float r00 = (cz * ct - sz * sx * st) * across, r01 = -sz * cx * up, r02 = (cz * st + sz * sx * ct) * across;
        float r10 = (sz * ct + cz * sx * st) * across, r11 = cz * cx * up, r12 = (sz * st - cz * sx * ct) * across;
        float r20 = -cx * st * across, r21 = sx * up, r22 = cx * ct * across;
        float m00 = m.m00(), m01 = m.m10(), m02 = m.m20(), m10 = m.m01(), m11 = m.m11(), m12 = m.m21(), m20 = m.m02(), m21 = m.m12(), m22 = m.m22();
        a[0] = m00 * r00 + m01 * r10 + m02 * r20;
        a[1] = m00 * r01 + m01 * r11 + m02 * r21;
        a[2] = m00 * r02 + m01 * r12 + m02 * r22;
        a[3] = m10 * r00 + m11 * r10 + m12 * r20;
        a[4] = m10 * r01 + m11 * r11 + m12 * r21;
        a[5] = m10 * r02 + m11 * r12 + m12 * r22;
        a[6] = m20 * r00 + m21 * r10 + m22 * r20;
        a[7] = m20 * r01 + m21 * r11 + m22 * r21;
        a[8] = m20 * r02 + m21 * r12 + m22 * r22;
        float fx = (float) (p.x + off.x), fy = (float) (p.y + off.y), fz = (float) (p.z + off.z);
        a[9] = m00 * fx + m01 * fy + m02 * fz + m.m30();
        a[10] = m10 * fx + m11 * fy + m12 * fz + m.m31();
        a[11] = m20 * fx + m21 * fy + m22 * fz + m.m32();
    }

    private void faces(VertexConsumer vc, float[] d, int light, float nx, float ny, float nz) {
        float[] a = this.a;
        for (int i = 0; i < d.length; i += 5) {
            float x = d[i], y = d[i + 1], z = d[i + 2];
            vc.vertex(a[0] * x + a[1] * y + a[2] * z + a[9], a[3] * x + a[4] * y + a[5] * z + a[10], a[6] * x + a[7] * y + a[8] * z + a[11],
                    1.0F, 1.0F, 1.0F, 1.0F, d[i + 3], d[i + 4], OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
        }
    }

    /** The clump's plumes: its look's (the one the chunk shows, out), read once per look. */
    private static Plume[] plumes(TubeWormsBlockEntity be) {
        if (be.plumes instanceof Plume[] ps && be.plumesFrom == bakes) return ps;
        if (looksFrom != bakes) {
            LOOKS.clear();
            looksFrom = bakes;
        }
        BlockState st = be.getBlockState().setValue(TubeWormsBlock.OUT, true);
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
        RAND.setSeed(st.getSeed(be.getBlockPos()));                  // as the chunk picks its look
        List<BakedQuad> quads = model.getQuads(st, null, RAND, ModelData.EMPTY, null);
        Plume[] ps = LOOKS.computeIfAbsent(quads, TubeWormsRenderer::read);
        be.plumes = ps;
        be.plumesFrom = bakes;
        return ps;
    }

    /** A look's plumes from its quads: grouped by their middle across, one of each face (not its back too). */
    static Plume[] read(List<BakedQuad> quads) {
        Map<Long, List<float[]>> bases = new TreeMap<>(), glows = new TreeMap<>();
        Map<Long, float[]> feet = new TreeMap<>();                     // middle x, middle z, lowest y
        Set<String> faces = new HashSet<>();
        for (BakedQuad q : quads) {
            if (!TubeWormsModel.plume(q)) continue;
            boolean isGlow = q.getSprite().contents().name().getPath().endsWith("_glow");
            int[] v = q.getVertices();
            float[] c = new float[20];
            long[] corners = new long[4];
            float mx = 0.0F, mz = 0.0F, lo = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                int o = i * IQuadTransformer.STRIDE;
                float x = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION]), y = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 1]),
                        z = Float.intBitsToFloat(v[o + IQuadTransformer.POSITION + 2]);
                c[i * 5] = x;
                c[i * 5 + 1] = y;
                c[i * 5 + 2] = z;
                c[i * 5 + 3] = Float.intBitsToFloat(v[o + IQuadTransformer.UV0]);
                c[i * 5 + 4] = Float.intBitsToFloat(v[o + IQuadTransformer.UV0 + 1]);
                mx += x / 4.0F;
                mz += z / 4.0F;
                lo = Math.min(lo, y);
                corners[i] = ((Math.round(x * 512.0F) & 0xFFFFFL) << 40) | ((Math.round(y * 512.0F) & 0xFFFFFL) << 20) | (Math.round(z * 512.0F) & 0xFFFFFL);
            }
            Arrays.sort(corners);
            if (!faces.add((isGlow ? "g" : "b") + Arrays.toString(corners))) continue;
            long key = ((long) Math.round(mx * 64.0F) << 32) ^ (Math.round(mz * 64.0F) & 0xFFFFFFFFL);
            (isGlow ? glows : bases).computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            float[] f = feet.computeIfAbsent(key, k -> new float[]{0, 0, Float.MAX_VALUE, 0});
            f[0] += mx;
            f[1] += mz;
            f[3] += 1;
            f[2] = Math.min(f[2], lo);
        }
        List<Plume> out = new ArrayList<>();
        for (Map.Entry<Long, float[]> en : feet.entrySet()) {
            float[] f = en.getValue();
            float fx = f[0] / f[3], fy = f[2], fz = f[1] / f[3];
            out.add(new Plume(fx, fy, fz, relative(bases.get(en.getKey()), fx, fy, fz), relative(glows.get(en.getKey()), fx, fy, fz)));
        }
        return out.toArray(new Plume[0]);
    }

    private static float[] relative(List<float[]> faces, float fx, float fy, float fz) {
        if (faces == null) return new float[0];
        float[] d = new float[faces.size() * 20];
        int n = 0;
        for (float[] c : faces)
            for (int i = 0; i < 4; i++) {
                d[n++] = c[i * 5] - fx;
                d[n++] = c[i * 5 + 1] - fy;
                d[n++] = c[i * 5 + 2] - fz;
                d[n++] = c[i * 5 + 3];
                d[n++] = c[i * 5 + 4];
            }
        return d;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The {@code k}th byte of a hash, as 0 to 1. */
    private static float unit(long h, int k) { return ((h >>> (k * 8)) & 255L) / 255.0F; }
}
