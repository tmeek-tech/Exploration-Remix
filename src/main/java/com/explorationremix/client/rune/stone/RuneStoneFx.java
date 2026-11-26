package com.explorationremix.client.rune.stone;

import com.explorationremix.client.rune.fx.GlowParticle;
import com.explorationremix.client.rune.fx.RuneCamera;
import com.explorationremix.rune.stone.FreedRune;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

import static com.explorationremix.client.rune.stone.RuneStoneClient.CORE;
import static com.explorationremix.client.rune.stone.RuneStoneClient.DIM;
import static com.explorationremix.client.rune.stone.RuneStoneClient.MAIN;
import static com.explorationremix.client.rune.stone.RuneStoneClient.WHITE;

/**
 * What a rune stone throws off, on each client (0.23.1), all in the carving's light blue: the light leaking out of its
 * strokes while it is dug ({@link #leak}); its burst as it breaks ({@link #burst}); the trail of a freed rune rising out
 * of the hole, hanging and flying to its player ({@link #freedTick}) and its catch ({@link #caught}); and the flare of a
 * stone answering a broken one ({@link #flare}).
 */
public final class RuneStoneFx {
    private RuneStoneFx() {}

    // ------------------------------------------------------------------------------------------------ helpers

    private static int count(float n) { return Math.max(1, Math.round(n * RuneStoneClient.amount())); }

    static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

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

    private static Vec3 normal(Direction d) { return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()); }

    // ------------------------------------------------------------------------------------------------ while it is dug

    /**
     * One tick of light leaking out of a stone being dug, {@code k} of the way through: a few motes drifting off its
     * strokes, more the further the pick has got, and near the end sparks spitting from them.
     */
    static void leak(ClientLevel l, RandomSource r, RuneStoneClient.Lit lit, float k) {
        float rate = (0.15F + 1.4F * k * k) * RuneStoneClient.amount();
        int n = (int) rate + (r.nextFloat() < rate - (int) rate ? 1 : 0);
        if (n == 0) return;
        List<Map.Entry<Direction, RuneStoneClient.Face>> open = RuneStoneClient.open(l, lit);
        if (open.isEmpty()) return;
        Vec3 base = Vec3.atLowerCornerOf(lit.pos);
        for (int i = 0; i < n; i++) {
            Map.Entry<Direction, RuneStoneClient.Face> f = open.get(r.nextInt(open.size()));
            List<Vec3> points = f.getValue().points();
            if (points.isEmpty()) continue;
            Vec3 out = normal(f.getKey());
            Vec3 p = base.add(points.get(r.nextInt(points.size()))).add(out.scale(0.02));
            if (k > 0.65F && r.nextFloat() < 0.3F) {
                GlowParticle.streak(p).vel(cone(r, out, 45).scale(0.09 + 0.1 * r.nextDouble())).colour(CORE).fadeTo(MAIN).size(0.028F, 0.01F)
                        .life(5 + r.nextInt(4)).gravity(0.35F).drag(0.85F).hot().spawn();
            } else {
                GlowParticle.dot(p).vel(out.scale(0.008 + 0.02 * r.nextDouble()).add(sphere(r).scale(0.004)).add(0, 0.004, 0))
                        .colour(mix(MAIN, WHITE, 0.2F + 0.5F * k)).fadeTo(DIM).size(0.025F + 0.025F * k, 0.0F).life(10 + r.nextInt(10)).drag(0.95F)
                        .spawn();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ as it breaks

    /**
     * A stone breaks at {@code c} ({@code toward}: to whoever broke it, nothing for a blast): a white flash cooling to
     * light blue and a softer light left a moment in the hole, a bright ring and a wide faint one spreading over the face
     * round the hole, shards of light flung out (most towards the open side), glints hanging in the air, motes
     * rising out of the hole after it, chips of the stone it hid in ({@code host}, a state id) thrown with it, and a jolt
     * close by.
     */
    public static void burst(ClientLevel l, RandomSource r, Vec3 c, Vec3 toward, int host, double dist) {
        boolean blast = toward.lengthSqr() < 1.0E-6;
        Vec3 n = blast ? new Vec3(0, 1, 0) : toward.normalize();
        GlowParticle.flash(c).colour(WHITE).fadeTo(0xBFD9FF).size(0.5F, 1.7F).alpha(0.85F).life(5).spawn();
        GlowParticle.flash(c).colour(MAIN).fadeTo(DIM).size(0.6F, 1.1F).alpha(0.45F).life(13).ease(1.5F).spawn();
        // the rings lie on the face the breaker dug in from, just out from it, washing over the stone round the hole (a
        // ring inside the hole was hidden by the rock round it, and one lying flat at the stone's middle swept through
        // the eyes of whoever dug it from close by, a grey band across half the view)
        Vec3 face = Vec3.atLowerCornerOf(Direction.getNearest(n.x, n.y, n.z).getNormal());
        GlowParticle.ring(c.add(face.scale(0.53)), face).colour(MAIN).fadeTo(DIM).size(0.3F, 2.3F).alpha(0.85F).life(10).spawn();
        GlowParticle.ring(c.add(face.scale(0.57)), face).colour(MAIN).fadeTo(DIM).size(0.6F, 3.0F).alpha(0.32F).life(14).spawn();
        for (int i = 0; i < count(24); i++) {
            Vec3 way = blast || r.nextFloat() < 0.35F ? sphere(r) : cone(r, n, 75);
            Vec3 v = way.scale(0.22 + r.nextDouble() * 0.38).add(0, 0.04, 0);
            GlowParticle.streak(c.add(way.scale(0.3))).vel(v).colour(mix(MAIN, WHITE, 0.3F + 0.6F * r.nextFloat())).fadeTo(DIM)
                    .size(0.05F + 0.03F * r.nextFloat(), 0.015F).life(9 + r.nextInt(8)).gravity(0.5F).drag(0.88F).physics().hot().spawn();
        }
        for (int i = 0; i < count(10); i++) {
            Vec3 p = c.add(sphere(r).scale(0.4 + 0.6 * r.nextDouble()));
            GlowParticle.dot(p).vel(sphere(r).scale(0.02).add(0, 0.012, 0)).colour(WHITE).fadeTo(MAIN).size(0.09F + 0.05F * r.nextFloat(), 0.0F)
                    .life(16 + r.nextInt(18)).drag(0.9F).star().spawn();
        }
        for (int i = 0; i < count(12); i++) {
            Vec3 p = c.add((r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.7);
            GlowParticle.dot(p).vel(new Vec3((r.nextDouble() - 0.5) * 0.01, 0.015 + r.nextDouble() * 0.03, (r.nextDouble() - 0.5) * 0.01))
                    .colour(mix(MAIN, WHITE, 0.4F * r.nextFloat())).fadeTo(DIM).size(0.045F, 0.0F).life(28 + r.nextInt(24)).drag(0.97F).spawn();
        }
        BlockState stone = Block.stateById(host);
        if (!stone.isAir()) {
            BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, stone);
            for (int i = 0; i < count(20); i++) {
                Vec3 way = blast || r.nextFloat() < 0.4F ? sphere(r) : cone(r, n, 70);
                Vec3 p = c.add(way.scale(0.35));
                Particle chip = Minecraft.getInstance().particleEngine.createParticle(chips, p.x, p.y, p.z, 0, 0, 0);
                if (chip == null) continue;
                Vec3 v = way.scale(0.14 + r.nextDouble() * 0.2).add(0, 0.08 + r.nextDouble() * 0.1, 0);
                chip.setParticleSpeed(v.x, v.y, v.z);
                chip.scale(0.8F + r.nextFloat() * 0.7F);
            }
        }
        if (dist < 7.0) RuneCamera.shake(0.55F * (float) (1.0 - dist / 7.0));
    }

    // ------------------------------------------------------------------------------------------------ its runes

    /**
     * A freed rune's light as it goes: motes falling off it as it rises out of the stone, glints round it as it hangs,
     * a flash as it sets off, and a trail behind it as it flies. Nothing once it is close to the camera.
     */
    public static void freedTick(FreedRune rune) {
        Minecraft mc = Minecraft.getInstance();
        RandomSource r = rune.level().random;
        int age = rune.age();
        int wait = FreedRune.RISE + FreedRune.HANG + FreedRune.SECOND_WAIT * rune.index();
        Vec3 at = rune.position().add(0, rune.getBbHeight() * 0.5, 0);
        Camera cam = mc.gameRenderer.getMainCamera();
        if (cam.isInitialized() && cam.getPosition().distanceToSqr(at) < 1.2) return;
        if (age < FreedRune.RISE) {
            if (r.nextInt(2) == 0) {
                GlowParticle.dot(at.add(sphere(r).scale(0.12))).vel(new Vec3(0, -0.012, 0)).colour(MAIN).fadeTo(DIM).size(0.035F, 0.0F)
                        .life(10 + r.nextInt(6)).spawn();
            }
        } else if (age < wait) {
            if (r.nextInt(3) == 0) {
                GlowParticle.dot(at.add(sphere(r).scale(0.3 + 0.1 * r.nextDouble()))).vel(sphere(r).scale(0.004)).colour(WHITE).fadeTo(MAIN)
                        .size(0.06F, 0.0F).life(8 + r.nextInt(6)).star().spawn();
            }
            if (age == wait - 1) GlowParticle.flash(at).colour(WHITE).fadeTo(MAIN).size(0.15F, 0.55F).alpha(0.6F).life(4).spawn();
        } else {
            Vec3 v = rune.getDeltaMovement();
            for (int i = 0; i < 2; i++) {
                Vec3 p = at.subtract(v.scale(r.nextDouble())).add(sphere(r).scale(0.04));
                GlowParticle.dot(p).vel(sphere(r).scale(0.01)).colour(mix(MAIN, WHITE, 0.45F)).fadeTo(DIM).size(0.06F, 0.0F).life(7 + r.nextInt(6))
                        .drag(0.9F).spawn();
            }
            if (r.nextInt(3) == 0) GlowParticle.dot(at.add(sphere(r).scale(0.1))).colour(WHITE).fadeTo(MAIN).size(0.07F, 0.0F).life(6).star().spawn();
        }
    }

    /**
     * A freed rune reaches its player, at their chest: a ring closing in round them and stars rising off it, and a flash
     * as well when it is someone else's (one's own would sit under the eye). The second rune's is a little brighter.
     */
    public static void caught(RandomSource r, Vec3 at, int index, boolean own) {
        Vec3 up = new Vec3(0, 1, 0);
        float k = (own ? 0.6F : 1.0F) * (index > 0 ? 1.15F : 1.0F);
        if (!own) GlowParticle.flash(at).colour(WHITE).fadeTo(MAIN).size(0.2F, 0.75F).alpha(0.7F).life(4).spawn();
        GlowParticle.ring(at, up).colour(CORE).fadeTo(MAIN).size(0.95F, 0.2F).alpha(Math.min(1.0F, 0.6F * k)).life(7).ease(1.0F).spawn();
        for (int i = 0; i < count(own ? 6 : 10); i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 0.35 + r.nextDouble() * 0.25;
            Vec3 p = at.add(Math.cos(a) * d, (r.nextDouble() - 0.5) * 0.6, Math.sin(a) * d);
            GlowParticle.dot(p).vel(new Vec3(-Math.sin(a) * 0.03, 0.03 + r.nextDouble() * 0.03, Math.cos(a) * 0.03)).colour(mix(MAIN, WHITE, 0.6F))
                    .fadeTo(MAIN).size(0.06F * k + 0.02F, 0.0F).life(10 + r.nextInt(8)).drag(0.92F).star().spawn();
        }
    }

    // ------------------------------------------------------------------------------------------------ an answer

    /** A stone answering a broken one flares: a flash on each face that shows and motes off its strokes. */
    static void flare(ClientLevel l, RandomSource r, RuneStoneClient.Lit lit) {
        Vec3 base = Vec3.atLowerCornerOf(lit.pos);
        for (Map.Entry<Direction, RuneStoneClient.Face> f : RuneStoneClient.open(l, lit)) {
            Vec3 out = normal(f.getKey());
            GlowParticle.flash(Vec3.atCenterOf(lit.pos).add(out.scale(0.52))).colour(MAIN).fadeTo(DIM).size(0.2F, 0.8F).alpha(0.6F).life(5).spawn();
            List<Vec3> points = f.getValue().points();
            if (points.isEmpty()) continue;
            for (int i = 0; i < count(5); i++) {
                Vec3 p = base.add(points.get(r.nextInt(points.size()))).add(out.scale(0.03));
                GlowParticle.dot(p).vel(out.scale(0.02 + r.nextDouble() * 0.03).add(sphere(r).scale(0.008))).colour(mix(MAIN, WHITE, 0.5F)).fadeTo(DIM)
                        .size(0.04F, 0.0F).life(14 + r.nextInt(10)).drag(0.95F).spawn();
            }
        }
    }
}
