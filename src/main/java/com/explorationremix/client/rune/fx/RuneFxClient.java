package com.explorationremix.client.rune.fx;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.effect.Fx;
import com.explorationremix.rune.effect.RuneBolt;
import com.explorationremix.rune.effect.RuneField;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Reactions;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Each client's half of {@link RuneFx}: it turns every effect the server sends into glowing flashes, sparks, rings and
 * the element's own bits, and shakes the view for big ones close by; and it trails light behind bolts, motes and
 * Fields as they fly. Fewer sparks with the game's particle setting turned down; nothing at all far off.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneFxClient implements RuneFx.ClientSide {
    public static final RuneFxClient INSTANCE = new RuneFxClient();
    private static final int WHITE = 0xFFFFFF;
    /** How near the camera a bolt's sparks aren't shed (a bolt leaving the hand, or flying back past your face). */
    private static final double TRAIL_CLEAR = 1.6;
    /** Staggered creatures (by id) and the client tick their reeling ends. */
    private static final Map<Integer, Long> STAGGERED = new HashMap<>();
    private static long ticks;

    private RuneFxClient() {}

    // ------------------------------------------------------------------------------------------------ helpers

    private static RandomSource rand() { return Minecraft.getInstance().level.random; }

    /** 1 with all particles, less when the game's particle setting is turned down. */
    private static float amount() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return s == ParticleStatus.ALL ? 1.0F : s == ParticleStatus.DECREASED ? 0.5F : 0.2F;
    }

    private static int count(float n) { return Math.max(1, Math.round(n * amount())); }

    static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** A darker shade, for what a colour fades to. */
    static int dim(int rgb, float k) { return mix(0, rgb, k); }

    private static Vec3 sphere(RandomSource r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
        return new Vec3(s * Math.cos(a), z, s * Math.sin(a));
    }

    /** A way within {@code degrees} of {@code dir}. */
    private static Vec3 cone(RandomSource r, Vec3 dir, double degrees) {
        double l = dir.length();
        if (l < 1.0E-6) return sphere(r);
        Vec3 d = dir.scale(1.0 / l);
        Vec3 p = sphere(r);
        Vec3 off = p.subtract(d.scale(p.dot(d)));
        double ol = off.length();
        if (ol < 1.0E-6) return d;
        double ang = Math.toRadians(degrees) * Math.sqrt(r.nextDouble());
        return d.scale(Math.cos(ang)).add(off.scale(Math.sin(ang) / ol));
    }

    /** A way into the half of space {@code n} faces. */
    private static Vec3 hemi(RandomSource r, Vec3 n) {
        Vec3 p = sphere(r);
        return p.dot(n) < 0 ? p.scale(-1) : p;
    }

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        double l = f.length();
        return l < 1.0E-6 ? new Vec3(0, 0, 1) : f.scale(1.0 / l);
    }

    private static Vec3 turn(Vec3 v, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return new Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    private static void vanilla(ClientLevel l, ParticleOptions p, Vec3 at, Vec3 v) { l.addParticle(p, at.x, at.y, at.z, v.x, v.y, v.z); }

    /** Sparks thrown from {@code at}: {@code n} of them, each a streak along {@code way}, of {@code rgb} cooling to its dim. */
    private static void sparks(RandomSource r, Vec3 at, int n, java.util.function.Supplier<Vec3> way, double speed, int rgb, float size, int life,
                               float gravity) {
        for (int i = 0; i < n; i++) {
            Vec3 v = way.get().scale(speed * (0.45 + r.nextDouble() * 0.75));
            int c = mix(rgb, WHITE, r.nextFloat() * 0.6F);
            GlowParticle.streak(at).vel(v).colour(c).fadeTo(dim(rgb, 0.5F)).size(size * (0.7F + r.nextFloat() * 0.6F), size * 0.4F)
                    .life(life + r.nextInt(Math.max(1, life / 2 + 1))).gravity(gravity).drag(0.86F).physics().hot().spawn();
        }
    }

    /** A jagged arc of lightning from {@code a} to {@code b}. */
    private static void arc(RandomSource r, Vec3 a, Vec3 b, int rgb) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1.0E-3) return;
        int segs = Mth.clamp((int) (len / 0.6), 2, 10);
        Vec3 prev = a;
        for (int i = 1; i <= segs; i++) {
            Vec3 p = a.add(d.scale((double) i / segs));
            if (i < segs) p = p.add(sphere(r).scale(0.18 * Math.min(1.0, len / 2)));
            Vec3 s = p.subtract(prev);
            int dots = Math.max(1, (int) (s.length() / 0.12));
            for (int k = 0; k < dots; k++) {
                GlowParticle.dot(prev.add(s.scale((double) k / dots))).colour(mix(rgb, WHITE, 0.55F)).fadeTo(rgb).size(0.045F, 0.02F)
                        .life(3 + r.nextInt(3)).spawn();
            }
            prev = p;
        }
    }

    private static boolean near(Vec3 at, double range) {
        Camera c = Minecraft.getInstance().gameRenderer.getMainCamera();
        return c.isInitialized() && c.getPosition().distanceToSqr(at) < range * range;
    }

    private static double camDist(Vec3 at) {
        Camera c = Minecraft.getInstance().gameRenderer.getMainCamera();
        return c.isInitialized() ? c.getPosition().distanceTo(at) : 1.0E9;
    }

    // ------------------------------------------------------------------------------------------------ effects

    @Override
    public void play(Fx fx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel l = mc.level;
        if (l == null) return;
        Vec3 at = fx.pos();
        double far = switch (fx.kind()) {
            case BURST, STAGGER -> 72.0;
            case MOTE_IN -> 16.0;
            default -> 48.0;
        };
        double dist = camDist(at);
        if (dist > far) return;
        RandomSource r = l.random;
        int rgb = fx.rgb() & 0xFFFFFF;
        Element e = fx.element();
        Vec3 d = fx.dir();
        float s = fx.size();
        switch (fx.kind()) {
            case CAST -> {
                // your own cast: your arm flicks here, as the server doesn't send it (see CastService.castFx)
                if (mc.player != null && fx.entity() == mc.player.getId()) mc.player.swing(fx.a() > 0.5F ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, false);
                cast(l, r, at, d, rgb, e);
            }
            case IMPACT -> impact(l, r, at, d, rgb, e, s, fx.a() > 0.5F, dist);
            case BURST -> burst(l, r, at, rgb, e, Math.max(0.5F, s), dist);
            case CLEAVE -> cleave(l, r, at, d, rgb, e, s, fx.a());
            case FIELD -> field(l, r, at, rgb, e, s, fx.a() > 0.5F);
            case BARRIER -> barrier(r, at, d, rgb, (int) s, (int) fx.a());
            case DASH -> dash(l, r, at, d, rgb, mc.player != null && fx.entity() == mc.player.getId());
            case BEAM -> beam(l, r, at, d, rgb, e, s, fx.a());
            case PIERCE -> pierce(l, r, at, d, rgb);
            case BOUNCE -> bounce(r, at, d, rgb);
            case REACTION -> reaction(l, r, at, rgb, s, (int) fx.a(), dist);
            case HIT -> hit(r, at, d, s);
            case MOMENTUM -> momentum(r, at, d, s, dist);
            case STAGGER -> stagger(r, at, s, (int) fx.a(), fx.entity(), dist);
            case SLAM -> slam(l, r, at, d, s, dist);
            case MOTE_IN -> moteIn(r, at, s, dist);
        }
    }

    private static void cast(ClientLevel l, RandomSource r, Vec3 at, Vec3 d, int rgb, Element e) {
        // small: it sits right by the camera
        GlowParticle.flash(at.add(d.scale(0.25))).colour(mix(rgb, WHITE, 0.5F)).fadeTo(rgb).size(0.07F, 0.2F).alpha(0.9F).life(4).spawn();
        sparks(r, at, count(6), () -> cone(r, d, 28), 0.3, rgb, 0.035F, 7, 0.0F);
        ParticleOptions ep = RuneFx.elementParticle(e);
        if (ep != null) vanilla(l, ep, at, d.scale(0.05));
    }

    private static void impact(ClientLevel l, RandomSource r, Vec3 at, Vec3 out, int rgb, Element e, float s, boolean creature, double dist) {
        GlowParticle.flash(at).colour(mix(rgb, WHITE, 0.65F)).fadeTo(rgb).size(0.25F * s, 0.8F * s).life(5).spawn();
        GlowParticle.flash(at).colour(rgb).fadeTo(dim(rgb, 0.4F)).size(0.4F * s, 1.25F * s).alpha(0.6F).life(10).spawn();
        if (!creature) GlowParticle.ring(at, out).colour(mix(rgb, WHITE, 0.3F)).fadeTo(rgb).size(0.1F, 0.9F * s).alpha(0.8F).life(10).spawn();
        Vec3 way = out.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : out.normalize();
        sparks(r, at, count(9 + 5 * s), () -> creature ? cone(r, way, 70) : hemi(r, way).add(way.scale(0.4)).normalize(), 0.3 * Math.sqrt(s), rgb,
                0.055F, 9, 0.6F);
        // a few embers that hang a moment
        for (int i = 0; i < count(3); i++) {
            GlowParticle.dot(at.add(sphere(r).scale(0.15 * s))).vel(new Vec3(0, 0.01 + r.nextDouble() * 0.02, 0)).colour(mix(rgb, WHITE, 0.4F))
                    .fadeTo(dim(rgb, 0.3F)).size(0.06F, 0.0F).life(18 + r.nextInt(12)).drag(0.95F).spawn();
        }
        element(l, r, at, way, e, s);
        if (s >= 1.3F && dist < 7.0) RuneCamera.shake(0.45F * (float) (1.0 - dist / 7.0));
    }

    /** The element's own bits, thrown out along {@code way}. */
    private static void element(ClientLevel l, RandomSource r, Vec3 at, Vec3 way, Element e, float s) {
        switch (e) {
            case EMBER -> {
                for (int i = 0; i < count(5 * s); i++) vanilla(l, ParticleTypes.FLAME, at, cone(r, way, 70).scale(0.06 + r.nextDouble() * 0.08));
                for (int i = 0; i < count(2 * s); i++) vanilla(l, ParticleTypes.SMOKE, at, way.scale(0.03).add(0, 0.03, 0));
                if (r.nextInt(3) == 0) vanilla(l, ParticleTypes.LAVA, at, Vec3.ZERO);
            }
            case FROST -> {
                for (int i = 0; i < count(6 * s); i++) vanilla(l, ParticleTypes.SNOWFLAKE, at, cone(r, way, 80).scale(0.08 + r.nextDouble() * 0.1));
                BlockParticleOption ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
                for (int i = 0; i < count(4 * s); i++) vanilla(l, ice, at, cone(r, way, 80).scale(0.1));
            }
            case STORM -> {
                for (int i = 0; i < count(7 * s); i++) vanilla(l, ParticleTypes.ELECTRIC_SPARK, at, sphere(r).scale(0.25));
                for (int i = 0; i < Math.max(1, Math.round(2 * s)); i++) arc(r, at, at.add(cone(r, way, 80).scale(0.6 + r.nextDouble() * 0.8 * s)), e.colour);
            }
            case TIDE -> {
                for (int i = 0; i < count(9 * s); i++) vanilla(l, ParticleTypes.SPLASH, at.add(sphere(r).scale(0.2)), cone(r, way, 70).scale(0.15));
                for (int i = 0; i < count(3 * s); i++) vanilla(l, ParticleTypes.BUBBLE_POP, at.add(sphere(r).scale(0.25)), Vec3.ZERO);
            }
            default -> {}
        }
    }

    private static void burst(ClientLevel l, RandomSource r, Vec3 c, int rgb, Element e, float rad, double dist) {
        GlowParticle.flash(c).colour(WHITE).fadeTo(mix(rgb, WHITE, 0.5F)).size(0.6F, 1.5F + rad * 0.3F).life(4).spawn();
        GlowParticle.flash(c).colour(mix(rgb, WHITE, 0.25F)).fadeTo(dim(rgb, 0.5F)).size(1.0F, 2.0F + rad * 0.7F).alpha(0.8F).life(8).spawn();
        Vec3 up = new Vec3(0, 1, 0);
        GlowParticle.ring(c, up).colour(mix(rgb, WHITE, 0.35F)).fadeTo(rgb).size(0.4F, rad * 1.15F).alpha(0.95F).life(10).spawn();
        GlowParticle.ring(c.add(0, 0.05, 0), up).colour(WHITE).fadeTo(rgb).size(0.3F, rad * 0.85F).alpha(0.55F).life(7).spawn();
        sparks(r, c, count(26 + 7 * rad), () -> {
            Vec3 p = sphere(r);
            return new Vec3(p.x, Math.abs(p.y) * 0.8 + 0.12, p.z).normalize();
        }, 0.55 * rad / 2.5, rgb, 0.07F, 12, 0.7F);
        for (int i = 0; i < count(10); i++) {
            Vec3 o = flat(sphere(r));
            vanilla(l, ParticleTypes.POOF, c.add(o.scale(rad * 0.4)), o.scale(0.08 + r.nextDouble() * 0.12));
        }
        switch (e) {
            case EMBER -> {
                for (int i = 0; i < count(18); i++) vanilla(l, ParticleTypes.FLAME, c, sphere(r).scale(0.12 + r.nextDouble() * 0.15 * rad / 2.5));
                for (int i = 0; i < count(4); i++) vanilla(l, ParticleTypes.LAVA, c, Vec3.ZERO);
                for (int i = 0; i < count(6); i++) vanilla(l, ParticleTypes.LARGE_SMOKE, c.add(sphere(r).scale(rad * 0.4)), new Vec3(0, 0.04, 0));
            }
            case FROST -> {
                for (int i = 0; i < count(24); i++) vanilla(l, ParticleTypes.SNOWFLAKE, c, sphere(r).scale(0.15 + r.nextDouble() * 0.2));
                BlockParticleOption ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
                for (int i = 0; i < count(14); i++) vanilla(l, ice, c.add(sphere(r).scale(0.4)), sphere(r).scale(0.2));
                GlowParticle.ring(c.add(0, 0.1, 0), new Vec3(0, 1, 0)).colour(0xE8FAFF).fadeTo(e.colour).size(0.2F, rad * 0.6F).alpha(0.7F).life(14).spawn();
            }
            case STORM -> {
                for (int i = 0; i < count(26); i++) vanilla(l, ParticleTypes.ELECTRIC_SPARK, c, sphere(r).scale(0.4));
                for (int i = 0; i < 5; i++) arc(r, c, c.add(flat(sphere(r)).scale(rad * (0.6 + r.nextDouble() * 0.5))).add(0, r.nextDouble() * 0.6, 0), e.colour);
            }
            case TIDE -> {
                for (int i = 0; i < count(34); i++) vanilla(l, ParticleTypes.SPLASH, c.add(sphere(r).scale(rad * 0.5)), sphere(r).scale(0.25));
                for (int i = 0; i < count(12); i++) vanilla(l, ParticleTypes.BUBBLE_POP, c.add(sphere(r).scale(rad * 0.5)), Vec3.ZERO);
            }
            default -> {}
        }
        double feel = rad * 4.0 + 2.0;
        if (dist < feel) RuneCamera.shake((float) ((1.0 - dist / feel) * (1.0 + 0.25 * rad)));
    }

    private static void cleave(ClientLevel l, RandomSource r, Vec3 from, Vec3 d, int rgb, Element e, float range, float half) {
        Vec3 flat = flat(d);
        int n = count(16 + 3 * range);
        int hot = mix(rgb, WHITE, 0.55F);
        // the crescent the blade of light cuts: brightest at its middle, sweeping across in a tick or two
        int arcDots = Math.max(10, (int) (half * range * 9));
        for (int i = 0; i <= arcDots; i++) {
            double t = (double) i / arcDots;
            double ang = -half + 2 * half * t;
            Vec3 p = from.add(turn(flat, ang).scale(range * 0.62)).add(0, -0.25 + 0.15 * Math.sin(t * Math.PI), 0);
            float mid = (float) Math.sin(t * Math.PI);
            GlowParticle.dot(p).vel(turn(flat, ang + Math.PI / 2).scale(0.06)).colour(WHITE).fadeTo(rgb).size(0.1F + 0.12F * mid, 0.0F)
                    .alpha(0.6F + 0.4F * mid).life(6 + (int) (3 * t)).drag(0.8F).spawn();
        }
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0.5 : (double) i / (n - 1);
            double ang = -half + 2 * half * t;
            Vec3 way = turn(flat, ang);
            Vec3 tangent = turn(flat, ang + Math.PI / 2);
            for (int ring = 0; ring < 2; ring++) {
                double reach = range * (ring == 0 ? 0.72 : 0.48);
                Vec3 p = from.add(way.scale(reach)).add(0, -0.25 + (ring == 0 ? 0 : -0.1), 0);
                GlowParticle.streak(p).vel(tangent.scale(0.22 + 0.1 * ring).add(way.scale(0.08))).colour(ring == 0 ? hot : rgb).fadeTo(dim(rgb, 0.5F))
                        .size(ring == 0 ? 0.09F : 0.06F, 0.02F).life(7 + r.nextInt(4)).drag(0.82F).stretch(3.0F).spawn();
            }
            if (i % 4 == 0) {
                ParticleOptions ep = RuneFx.elementParticle(e);
                if (ep != null) vanilla(l, ep, from.add(way.scale(range * 0.6)).add(0, -0.25, 0), way.scale(0.05));
            }
        }
        for (int i = -1; i <= 1; i++) {
            Vec3 p = from.add(turn(flat, half * 0.7 * i).scale(range * 0.62)).add(0, -0.3, 0);
            vanilla(l, ParticleTypes.SWEEP_ATTACK, p, Vec3.ZERO);
        }
        GlowParticle.flash(from.add(flat.scale(range * 0.55)).add(0, -0.25, 0)).colour(hot).fadeTo(rgb).size(0.3F, 0.9F).alpha(0.6F).life(4).spawn();
    }

    private static void field(ClientLevel l, RandomSource r, Vec3 c, int rgb, Element e, float rad, boolean pulse) {
        Vec3 up = new Vec3(0, 1, 0);
        if (pulse) {
            GlowParticle.ring(c.add(0, 0.08, 0), up).colour(mix(rgb, WHITE, 0.3F)).fadeTo(rgb).size(rad * 0.3F, rad * 0.95F).alpha(0.5F).life(8).spawn();
            return;
        }
        GlowParticle.ring(c.add(0, 0.1, 0), up).colour(mix(rgb, WHITE, 0.4F)).fadeTo(rgb).size(0.3F, rad).alpha(0.95F).life(14).spawn();
        GlowParticle.flash(c.add(0, 0.3, 0)).colour(mix(rgb, WHITE, 0.5F)).fadeTo(rgb).size(0.4F, 1.2F).alpha(0.7F).life(5).spawn();
        for (int i = 0; i < count(18); i++) {
            double a = r.nextDouble() * Math.PI * 2;
            Vec3 p = c.add(Math.cos(a) * rad, 0.1, Math.sin(a) * rad);
            GlowParticle.dot(p).vel(new Vec3(0, 0.06 + r.nextDouble() * 0.06, 0)).colour(mix(rgb, WHITE, 0.4F)).fadeTo(rgb).size(0.07F, 0.02F)
                    .life(14 + r.nextInt(8)).drag(0.92F).spawn();
        }
        ParticleOptions ep = RuneFx.elementParticle(e);
        if (ep != null) for (int i = 0; i < count(8); i++) vanilla(l, ep, c.add(flat(sphere(r)).scale(rad * r.nextDouble())).add(0, 0.2, 0), up.scale(0.05));
    }

    private static void barrier(RandomSource r, Vec3 mid, Vec3 along, int rgb, int width, int height) {
        Vec3 a = flat(along);
        Vec3 n = new Vec3(-a.z, 0, a.x);
        int dots = count(width * height * 3);
        for (int i = 0; i < dots; i++) {
            Vec3 p = mid.add(a.scale((r.nextDouble() - 0.5) * width)).add(0, (r.nextDouble() - 0.5) * height, 0);
            GlowParticle.dot(p).vel(n.scale((r.nextDouble() - 0.5) * 0.06).add(0, 0.02, 0)).colour(mix(rgb, WHITE, 0.5F)).fadeTo(rgb)
                    .size(0.06F, 0.0F).life(10 + r.nextInt(8)).spawn();
        }
        GlowParticle.ring(mid, n).colour(mix(rgb, WHITE, 0.4F)).fadeTo(rgb).size(0.2F, Math.max(width, height) * 0.6F).alpha(0.6F).life(8).spawn();
    }

    private static void dash(ClientLevel l, RandomSource r, Vec3 at, Vec3 d, int rgb, boolean mine) {
        Vec3 way = d.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : d.normalize();
        if (mine) {
            // your own: the view widens and lines of light rush past, as the wake is behind you
            RuneCamera.dash();
            Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 eye = cam.isInitialized() ? cam.getPosition() : at.add(0, 1.6, 0);
            Vec3 side = way.cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : side.normalize();
            Vec3 up = side.cross(way).normalize();
            for (int i = 0; i < count(26); i++) {
                double a = r.nextDouble() * Math.PI * 2, rad = 0.6 + r.nextDouble() * 1.1;
                Vec3 p = eye.add(way.scale(1.5 + r.nextDouble() * 5.0)).add(side.scale(Math.cos(a) * rad)).add(up.scale(Math.sin(a) * rad * 0.8));
                GlowParticle.streak(p).vel(way.scale(-(0.45 + r.nextDouble() * 0.3))).colour(mix(rgb, WHITE, 0.75F)).fadeTo(dim(rgb, 0.5F))
                        .size(0.04F, 0.02F).alpha(0.85F).life(6 + r.nextInt(4)).drag(1.0F).stretch(3.0F).spawn();
            }
        }
        for (int i = 0; i < count(20); i++) {
            Vec3 p = at.add(sphere(r).scale(0.35)).add(0, r.nextDouble() * 1.4, 0);
            GlowParticle.streak(p).vel(way.scale(0.5 + r.nextDouble() * 0.4)).colour(mix(rgb, WHITE, 0.6F)).fadeTo(dim(rgb, 0.4F)).size(0.08F, 0.02F)
                    .life(8 + r.nextInt(5)).drag(0.8F).stretch(3.5F).spawn();
        }
        // a wake of light hanging where they went
        for (int i = 0; i < count(16); i++) {
            Vec3 p = at.add(way.scale(r.nextDouble() * 2.5)).add(sphere(r).scale(0.25)).add(0, 0.2 + r.nextDouble() * 1.3, 0);
            GlowParticle.dot(p).colour(mix(rgb, WHITE, 0.4F)).fadeTo(dim(rgb, 0.3F)).size(0.09F, 0.0F).alpha(0.7F).life(10 + r.nextInt(8)).drag(0.9F).spawn();
        }
        GlowParticle.ring(at.add(0, 0.9, 0), way).colour(WHITE).fadeTo(rgb).size(0.3F, 1.4F).alpha(0.7F).life(8).spawn();
        for (int i = 0; i < count(4); i++) vanilla(l, ParticleTypes.POOF, at.add(0, 0.2, 0), way.scale(-0.05).add(sphere(r).scale(0.03)));
    }

    private static void beam(ClientLevel l, RandomSource r, Vec3 a, Vec3 d, int rgb, Element e, float size, float spacing) {
        Vec3 b = a.add(d);
        boolean rope = spacing > 0.0F;
        if (e == Element.STORM) {
            arc(r, a, b, rgb);
            arc(r, a, b, rgb);
            vanilla(l, ParticleTypes.ELECTRIC_SPARK, b, Vec3.ZERO);
        } else {
            double len = d.length();
            int n = Mth.clamp((int) (len / (rope ? Math.max(0.2F, spacing) : 0.22)), 1, 120);
            float s = 0.06F * Mth.clamp(size, 0.2F, 3.0F);
            for (int i = 0; i <= n; i++) {
                Vec3 p = a.add(d.scale((double) i / n));
                GlowParticle.dot(p).colour(mix(rgb, WHITE, 0.4F)).fadeTo(rgb).size(s, s * 0.3F).life(rope ? 3 : 5 + r.nextInt(4)).spawn();
            }
        }
        if (!rope) GlowParticle.flash(b).colour(mix(rgb, WHITE, 0.5F)).fadeTo(rgb).size(0.2F, 0.5F).alpha(0.8F).life(4).spawn();
    }

    private static void pierce(ClientLevel l, RandomSource r, Vec3 at, Vec3 d, int rgb) {
        GlowParticle.flash(at).colour(mix(rgb, WHITE, 0.6F)).fadeTo(rgb).size(0.2F, 0.55F).life(3).spawn();
        sparks(r, at, count(6), () -> cone(r, d, 35), 0.35, rgb, 0.05F, 6, 0.3F);
        for (int i = 0; i < count(3); i++) vanilla(l, ParticleTypes.CRIT, at, cone(r, d, 50).scale(0.3));
    }

    private static void bounce(RandomSource r, Vec3 at, Vec3 d, int rgb) {
        GlowParticle.flash(at).colour(mix(rgb, WHITE, 0.6F)).fadeTo(rgb).size(0.18F, 0.5F).life(3).spawn();
        sparks(r, at, count(5), () -> cone(r, d, 40), 0.3, rgb, 0.045F, 6, 0.5F);
    }

    private static void reaction(ClientLevel l, RandomSource r, Vec3 c, int rgb, float width, int which, double dist) {
        Reactions.Reaction[] all = Reactions.Reaction.values();
        if (which < 0 || which >= all.length) return;
        float w = Math.max(0.5F, width);
        switch (all[which]) {
            case STEAM -> {
                for (int i = 0; i < count(14); i++) vanilla(l, ParticleTypes.CLOUD, c.add(sphere(r).scale(w * 0.4)), new Vec3(0, 0.05, 0).add(sphere(r).scale(0.03)));
                GlowParticle.ring(c, new Vec3(0, 1, 0)).colour(WHITE).fadeTo(0xB8C8D8).size(0.2F, w * 1.4F).alpha(0.5F).life(9).spawn();
            }
            case THAW -> {
                for (int i = 0; i < count(12); i++) vanilla(l, ParticleTypes.FALLING_WATER, c.add(sphere(r).scale(w * 0.4)), Vec3.ZERO);
                GlowParticle.flash(c).colour(0xCFEFFF).fadeTo(0x5FA8FF).size(0.3F, w).alpha(0.6F).life(5).spawn();
            }
            case SHATTER -> {
                BlockParticleOption ice = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState());
                for (int i = 0; i < count(30); i++) vanilla(l, ice, c.add(sphere(r).scale(w * 0.35)), sphere(r).scale(0.25));
                GlowParticle.flash(c).colour(WHITE).fadeTo(0x9FE4FF).size(0.5F, w * 1.6F).life(5).spawn();
                sparks(r, c, count(16), () -> sphere(r), 0.4, 0xBDEBFF, 0.06F, 10, 0.8F);
                if (dist < 8.0) RuneCamera.shake(0.6F * (float) (1.0 - dist / 8.0));
            }
            case FREEZE -> {
                for (int i = 0; i < count(20); i++) vanilla(l, ParticleTypes.SNOWFLAKE, c.add(sphere(r).scale(w * 0.4)), sphere(r).scale(0.04));
                GlowParticle.ring(c, new Vec3(0, 1, 0)).colour(0xF0FCFF).fadeTo(rgb).size(w * 1.4F, 0.2F).alpha(0.8F).life(10).spawn();
                GlowParticle.flash(c).colour(0xE8FAFF).fadeTo(rgb).size(0.3F, w * 1.1F).alpha(0.7F).life(5).spawn();
            }
            case SHOCK -> {
                for (int i = 0; i < count(18); i++) vanilla(l, ParticleTypes.ELECTRIC_SPARK, c.add(sphere(r).scale(w * 0.4)), sphere(r).scale(0.3));
                for (int i = 0; i < 4; i++) arc(r, c.add(sphere(r).scale(w * 0.3)), c.add(sphere(r).scale(w * 0.9 + 0.4)), rgb);
                GlowParticle.flash(c).colour(0xFFFBD6).fadeTo(rgb).size(0.4F, w * 1.3F).life(4).spawn();
            }
            default -> {}
        }
    }

    private static void hit(RandomSource r, Vec3 at, Vec3 d, float s) {
        float k = Mth.clamp(s, 0.2F, 2.5F);
        GlowParticle.flash(at).colour(WHITE).fadeTo(0xFFE2B0).size(0.18F * k, 0.55F * k).alpha(0.9F).life(4).spawn();
        sparks(r, at, count(4 + 5 * k), () -> cone(r, d, 55), 0.28 * k, 0xFFEBC8, 0.045F, 7, 0.5F);
    }

    private static void momentum(RandomSource r, Vec3 at, Vec3 d, float s, double dist) {
        float k = Mth.clamp(s, 0.0F, 1.0F);
        Vec3 way = d.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : d.normalize();
        GlowParticle.flash(at).colour(WHITE).fadeTo(0xFFD9A0).size(0.3F, 0.9F + 0.5F * k).life(4).spawn();
        GlowParticle.ring(at, way).colour(WHITE).fadeTo(0xFFD9A0).size(0.2F, 1.0F + 0.6F * k).alpha(0.7F).life(6).spawn();
        sparks(r, at, count(8 + 8 * k), () -> cone(r, way, 22), 0.5 + 0.4 * k, 0xFFF0D0, 0.06F, 7, 0.2F);
        if (dist < 6.0) RuneCamera.shake((0.25F + 0.45F * k) * (float) (1.0 - dist / 6.0));
    }

    private static void stagger(RandomSource r, Vec3 c, float width, int reel, int entity, double dist) {
        float w = Math.max(0.5F, width);
        GlowParticle.flash(c).colour(WHITE).fadeTo(0xFFE08A).size(0.5F, 1.2F + w).life(5).spawn();
        GlowParticle.ring(c, new Vec3(0, 1, 0)).colour(0xFFF6D0).fadeTo(0xFFC040).size(0.3F, w * 1.6F + 0.6F).alpha(0.9F).life(9).spawn();
        for (int i = 0; i < count(12); i++) {
            Vec3 o = flat(sphere(r));
            GlowParticle.dot(c).vel(o.scale(0.22 + r.nextDouble() * 0.12).add(0, 0.05, 0)).colour(0xFFF6D0).fadeTo(0xFFB030).size(0.08F, 0.02F)
                    .life(9 + r.nextInt(5)).drag(0.85F).star().spawn();
        }
        if (entity >= 0 && reel > 0) STAGGERED.put(entity, ticks + Math.min(reel, 200));
        if (dist < 9.0) RuneCamera.shake(0.55F * (float) (1.0 - dist / 9.0));
    }

    private static void slam(ClientLevel l, RandomSource r, Vec3 at, Vec3 out, float s, double dist) {
        float k = Mth.clamp(s, 0.0F, 1.0F);
        Vec3 n = out.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : out.normalize();
        GlowParticle.flash(at).colour(WHITE).fadeTo(0xD8D0C0).size(0.4F, 1.0F + k).alpha(0.8F).life(4).spawn();
        GlowParticle.ring(at.add(n.scale(0.05)), n).colour(WHITE).fadeTo(0xA09888).size(0.2F, 1.2F + k).alpha(0.8F).life(8).spawn();
        sparks(r, at, count(8 + 10 * k), () -> hemi(r, n).add(n.scale(0.5)).normalize(), 0.3 + 0.2 * k, 0xE8E0D0, 0.05F, 8, 1.0F);
        BlockState wall = l.getBlockState(BlockPos.containing(at.subtract(n.scale(0.3))));
        if (!wall.isAir()) {
            BlockParticleOption bits = new BlockParticleOption(ParticleTypes.BLOCK, wall);
            for (int i = 0; i < count(14 + 10 * k); i++) vanilla(l, bits, at.add(sphere(r).scale(0.3)), hemi(r, n).scale(0.15 + 0.15 * k));
        }
        for (int i = 0; i < count(4); i++) vanilla(l, ParticleTypes.POOF, at, hemi(r, n).scale(0.05));
        if (dist < 10.0) RuneCamera.shake((0.5F + 0.8F * k) * (float) (1.0 - dist / 10.0));
    }

    private static void moteIn(RandomSource r, Vec3 at, float full, double dist) {
        // not on the player's own camera
        if (dist > 1.5) GlowParticle.flash(at).colour(WHITE).size(0.12F, 0.32F).alpha(0.6F).life(3).spawn();
        for (int i = 0; i < count(3); i++) {
            GlowParticle.dot(at.add(sphere(r).scale(0.3))).vel(new Vec3(0, 0.03, 0)).colour(WHITE).fadeTo(0xC8E8FF).size(0.04F, 0.0F)
                    .life(8 + r.nextInt(5)).spawn();
        }
    }

    // ------------------------------------------------------------------------------------------------ while they fly

    @Override
    public void boltTick(RuneBolt b) {
        RandomSource r = b.level().random;
        Vec3 v = b.getDeltaMovement();
        Vec3 at = b.position().add(0, b.getBbHeight() * 0.5, 0);
        if (near(at, TRAIL_CLEAR + v.length())) return;
        int rgb = RuneFx.colour(b.rune(), b.element());
        float size = b.size();
        int n = b.rune() == RuneId.ORB ? 2 : 1;
        for (int i = 0; i < n; i++) {
            double t = r.nextDouble();
            Vec3 p = at.subtract(v.scale(t)).add(sphere(r).scale(0.06 * size));
            GlowParticle.dot(p).vel(sphere(r).scale(0.015).add(v.scale(0.05))).colour(mix(rgb, WHITE, 0.3F)).fadeTo(dim(rgb, 0.4F))
                    .size(0.05F * (0.8F + 0.4F * size), 0.0F).life(6 + r.nextInt(6)).drag(0.9F).spawn();
        }
        ParticleOptions e = RuneFx.elementParticle(b.element());
        if (e != null && r.nextInt(3) == 0) b.level().addParticle(e, at.x, at.y, at.z, v.x * 0.1, v.y * 0.1, v.z * 0.1);
        if (b.rune() == RuneId.GLOW && r.nextInt(3) == 0) b.level().addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, 0, 0, 0);
    }

    @Override
    public void fieldTick(RuneField f) {
        RandomSource r = f.level().random;
        float rad = f.radius();
        int rgb = RuneFx.colour(RuneId.FIELD, f.element());
        ParticleOptions e = RuneFx.elementParticle(f.element());
        int n = Math.max(1, Math.round(rad * 0.8F * amount()));
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double rr = rad * (0.85 + r.nextDouble() * 0.15);
            Vec3 p = new Vec3(f.getX() + Math.cos(a) * rr, f.getY() + 0.1, f.getZ() + Math.sin(a) * rr);
            GlowParticle.dot(p).vel(new Vec3(0, 0.03 + r.nextDouble() * 0.03, 0)).colour(mix(rgb, WHITE, 0.3F)).fadeTo(dim(rgb, 0.4F)).size(0.05F, 0.01F)
                    .life(14 + r.nextInt(10)).drag(0.96F).spawn();
            if (e != null && r.nextInt(4) == 0) f.level().addParticle(e, p.x, p.y + 0.1, p.z, 0, 0.03, 0);
        }
    }

    @Override
    public void moteTick(RuneMote m) {
        RandomSource r = m.level().random;
        Vec3 v = m.getDeltaMovement();
        GlowParticle.dot(m.position().add(0, 0.12, 0).subtract(v.scale(0.5))).colour(WHITE).fadeTo(0xB8DCFF).size(0.035F + 0.02F * m.size(), 0.0F)
                .life(5 + r.nextInt(3)).spawn();
    }

    // ------------------------------------------------------------------------------------------------ staggered creatures

    /** Stars circle a staggered creature's head until it comes to itself. */
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            if (mc.level == null) STAGGERED.clear();
            return;
        }
        for (Iterator<Map.Entry<Integer, Long>> it = STAGGERED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> s = it.next();
            Entity en = mc.level.getEntity(s.getKey());
            if (en == null || en.isRemoved() || ticks > s.getValue()) {
                it.remove();
                continue;
            }
            float w = en.getBbWidth() * 0.5F + 0.25F;
            Vec3 top = en.position().add(0, en.getBbHeight() + 0.2, 0);
            for (int k = 0; k < 3; k++) {
                double a = ticks * 0.45 + k * Math.PI * 2 / 3;
                Vec3 p = top.add(Math.cos(a) * w, Math.sin(ticks * 0.3 + k) * 0.06, Math.sin(a) * w);
                GlowParticle.dot(p).colour(0xFFF4C8).fadeTo(0xFFB040).size(0.07F, 0.03F).life(3).star().spawn();
            }
        }
    }
}
