package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * How runes look and sound: each element its own colour, particles and sound, so a glance tells what a cast is doing.
 * The trails are drawn by each client from the entities it is sent; everything else is sent from the server as
 * vanilla particles and sounds, so nothing extra crosses the network.
 */
public final class RuneFx {
    /** How near a client's camera trail particles aren't drawn: a bolt leaving the hand or flying back past your face. */
    static final double TRAIL_CLEAR = 1.6;
    /** Where the camera is, on a client (its setup sets this; null until there is one): see {@link #boltTrail}. */
    public static Supplier<Vec3> camera = () -> null;

    private RuneFx() {}

    /** A rune's colour: its element's, or the rune's own when it has none. */
    public static int colour(RuneId rune, Element e) {
        if (e != Element.NONE) return e.colour;
        return switch (rune) {
            case SPARK -> 0xFFE9A8;
            case SHARD -> 0xBDEBFF;
            case ORB -> 0xD6A8FF;
            case GLOW -> 0xFFF2B0;
            case BURST -> 0xFFD9B0;
            case CLEAVE -> 0xE8F4FF;
            case FIELD -> 0xC8FFD8;
            case BARRIER -> 0x9FE4FF;
            case DASH -> 0xFFFFFF;
            case REEL -> 0xB8FFF0;
            default -> 0xFFFFFF;
        };
    }

    public static DustParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F),
                Mth.clamp(scale, 0.05F, 4.0F));
    }

    /** The element's own particle, or null. */
    public static ParticleOptions elementParticle(Element e) {
        return switch (e) {
            case EMBER -> ParticleTypes.SMALL_FLAME;
            case FROST -> ParticleTypes.SNOWFLAKE;
            case STORM -> ParticleTypes.ELECTRIC_SPARK;
            case TIDE -> ParticleTypes.FALLING_WATER;
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------ client trails

    /** Client, every tick: a flying rune's trail. */
    public static void boltTrail(RuneBolt b) {
        Level l = b.level();
        RandomSource r = l.random;
        Vec3 v = b.getDeltaMovement();
        double x = b.getX(), y = b.getY() + b.getBbHeight() * 0.5, z = b.getZ();
        Vec3 cam = camera.get();
        if (cam != null && cam.distanceToSqr(x, y, z) < (TRAIL_CLEAR + v.length()) * (TRAIL_CLEAR + v.length())) return;
        DustParticleOptions d = dust(colour(b.rune(), b.element()), 0.45F + 0.35F * b.size());
        int n = b.rune() == RuneId.ORB ? 3 : 2;
        for (int i = 0; i < n; i++) {
            double t = r.nextDouble();
            l.addParticle(d, x - v.x * t, y - v.y * t, z - v.z * t, 0, 0, 0);
        }
        ParticleOptions e = elementParticle(b.element());
        if (e != null && r.nextInt(3) == 0) l.addParticle(e, x, y, z, v.x * 0.1, v.y * 0.1, v.z * 0.1);
        if (b.rune() == RuneId.GLOW && r.nextInt(3) == 0) l.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
    }

    /** Client, every tick: motes rising round a Field's edge. */
    public static void fieldAmbient(RuneField f) {
        Level l = f.level();
        RandomSource r = l.random;
        float rad = f.radius();
        int n = Math.max(1, Math.round(rad * 1.5F));
        DustParticleOptions d = dust(colour(RuneId.FIELD, f.element()), 0.8F);
        ParticleOptions e = elementParticle(f.element());
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double rr = rad * (0.85 + r.nextDouble() * 0.15);
            double x = f.getX() + Math.cos(a) * rr, z = f.getZ() + Math.sin(a) * rr;
            l.addParticle(d, x, f.getY() + 0.1, z, 0, 0.02, 0);
            if (e != null && r.nextInt(4) == 0) l.addParticle(e, x, f.getY() + 0.2, z, 0, 0.03, 0);
        }
    }

    /** Client, every tick: a Siphon mote's faint white sparkle. */
    public static void moteTrail(RuneMote m) {
        Level l = m.level();
        if (l.random.nextInt(2) == 0) {
            Vec3 v = m.getDeltaMovement();
            l.addParticle(dust(0xFFFFFF, 0.35F + 0.25F * m.size()), m.getX() - v.x * 0.5, m.getY() + 0.1 - v.y * 0.5, m.getZ() - v.z * 0.5, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------ server

    /** A puff of a rune's colour (and its element) at a point. */
    public static void puff(ServerLevel l, Vec3 p, RuneId rune, Element e, int count, double spread) {
        l.sendParticles(dust(colour(rune, e), 1.0F), p.x, p.y, p.z, count, spread, spread, spread, 0.0);
        ParticleOptions ep = elementParticle(e);
        if (ep != null) l.sendParticles(ep, p.x, p.y, p.z, Math.max(1, count / 2), spread, spread, spread, 0.02);
    }

    /** A ring of a rune's colour round a point, flat on the ground (Burst, Field). */
    public static void ring(ServerLevel l, Vec3 c, double radius, RuneId rune, Element e) {
        int n = Mth.clamp((int) (radius * 10), 8, 48);
        DustParticleOptions d = dust(colour(rune, e), 1.2F);
        ParticleOptions ep = elementParticle(e);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            double x = c.x + Math.cos(a) * radius, z = c.z + Math.sin(a) * radius;
            l.sendParticles(d, x, c.y, z, 1, 0.05, 0.05, 0.05, 0.0);
            if (ep != null && i % 3 == 0) l.sendParticles(ep, x, c.y, z, 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    /** A line of particles from one point to another (a storm's leap, a reel's pull). */
    public static void line(ServerLevel l, Vec3 a, Vec3 b, ParticleOptions p, double step) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        int n = Mth.clamp((int) (len / step), 1, 64);
        for (int i = 0; i <= n; i++) {
            Vec3 q = a.add(d.scale((double) i / n));
            l.sendParticles(p, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /** The sound a rune makes when it goes off. */
    public static SoundEvent sound(RuneId rune, Element e) {
        if (e == Element.EMBER) return SoundEvents.FIRECHARGE_USE;
        if (e == Element.FROST) return SoundEvents.GLASS_BREAK;
        if (e == Element.STORM) return SoundEvents.TRIDENT_RIPTIDE_1;
        if (e == Element.TIDE) return SoundEvents.PLAYER_SPLASH;
        return switch (rune) {
            case BURST -> SoundEvents.FIREWORK_ROCKET_BLAST;
            case CLEAVE -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case BARRIER -> SoundEvents.AMETHYST_BLOCK_PLACE;
            case DASH -> SoundEvents.PHANTOM_FLAP;
            case REEL -> SoundEvents.FISHING_BOBBER_RETRIEVE;
            case FIELD -> SoundEvents.BEACON_ACTIVATE;
            case GLOW -> SoundEvents.AMETHYST_BLOCK_CHIME;
            default -> SoundEvents.AMETHYST_CLUSTER_HIT;
        };
    }

    public static void play(ServerLevel l, Vec3 p, SoundEvent s, float volume, float pitch) {
        l.playSound(null, p.x, p.y, p.z, s, SoundSource.PLAYERS, volume, pitch);
    }
}
