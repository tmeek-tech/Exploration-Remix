package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Reactions;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.net.RuneNet;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * How runes and hits look and sound. Each element has its own colour and sounds, so a glance tells what a cast is
 * doing. The server sends one small {@link Fx} per flash, blast or slam to the players near it, and each of their games
 * draws it: glowing flashes, sparks that fly and fade, shockwave rings, and a shake of the view for big ones close by.
 * Sounds are played by the server, in layers (a body and an edge, the element on top), each a little off its usual
 * pitch so a fight never sounds like a loop. Trails behind bolts, motes and Fields are drawn by each game from the
 * entities themselves ({@link #client}).
 */
public final class RuneFx {
    /** How far an effect is sent: a blast can be seen from a fair way off; a spark's hit, nearer. */
    static final double FAR = 64.0, NEAR = 40.0;

    /** What a client does with effects and with the runes' entities while they fly; nothing on a server. */
    public interface ClientSide {
        void play(Fx fx);

        void boltTick(RuneBolt b);

        void fieldTick(RuneField f);

        void moteTick(RuneMote m);

        ClientSide NONE = new ClientSide() {
            @Override
            public void play(Fx fx) {}

            @Override
            public void boltTick(RuneBolt b) {}

            @Override
            public void fieldTick(RuneField f) {}

            @Override
            public void moteTick(RuneMote m) {}
        };
    }

    /** Set by the client's setup. */
    public static ClientSide client = ClientSide.NONE;

    private RuneFx() {}

    /** A rune's colour: its element's, or the rune's own when it has none. */
    public static int colour(RuneId rune, Element e) {
        if (e != Element.NONE) return e.colour;
        return switch (rune) {
            case SPARK -> 0xFFE9A8;
            case SHARD -> 0xBDEBFF;
            case ORB -> 0xD6A8FF;
            case GLOW -> 0xFFF2B0;
            case BURST -> 0xFFC48A;
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

    // ------------------------------------------------------------------------------------------------ sending

    static void send(ServerLevel l, Fx fx, double range) {
        RuneNet.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(fx.x(), fx.y(), fx.z(), range, l.dimension())),
                new RuneNet.FxMsg(fx));
    }

    private static void send(ServerLevel l, Fx.Kind k, Vec3 at, Vec3 dir, int rgb, Element e, float size, float a, int entity, double range) {
        send(l, Fx.of(k, at, dir, rgb, e, size, a, entity), range);
    }

    // ------------------------------------------------------------------------------------------------ runes

    /** A cast leaves {@code caster}'s {@code hand} at {@code at}, going {@code dir}. */
    public static void cast(ServerLevel l, Vec3 at, Vec3 dir, RuneId rune, Element e, boolean loud, int caster, InteractionHand hand) {
        send(l, Fx.Kind.CAST, at, dir, colour(rune, e), e, 1.0F, hand == InteractionHand.OFF_HAND ? 1.0F : 0.0F, caster, NEAR);
        if (!loud) return;
        play(l, at, SoundEvents.ILLUSIONER_CAST_SPELL, 0.45F, 1.5F + l.random.nextFloat() * 0.3F);
        switch (e) {
            case EMBER -> play(l, at, SoundEvents.BLAZE_SHOOT, 0.3F, 1.5F);
            case FROST -> play(l, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 1.8F);
            case STORM -> play(l, at, SoundEvents.TRIDENT_THROW, 0.5F, 1.7F);
            case TIDE -> play(l, at, SoundEvents.BUCKET_EMPTY, 0.35F, 1.5F);
            default -> play(l, at, SoundEvents.ENDER_EYE_LAUNCH, 0.3F, 1.6F);
        }
    }

    /**
     * A bolt stops at {@code at}: against a wall ({@code out} the way back out of it) or in a creature. A flash, sparks
     * thrown back out, a ring on a wall, its element; and its sound, an Orb's a small blast.
     */
    public static void impact(ServerLevel l, Vec3 at, Vec3 out, RuneId rune, Element e, float size, boolean creature) {
        float s = Mth.clamp(size * (rune == RuneId.ORB ? 1.5F : rune == RuneId.SHARD ? 0.75F : 1.0F), 0.3F, 4.0F);
        send(l, Fx.Kind.IMPACT, at, out, colour(rune, e), e, s, creature ? 1.0F : 0.0F, -1, rune == RuneId.ORB ? FAR : NEAR);
        switch (rune) {
            case ORB -> {
                play(l, at, SoundEvents.GENERIC_EXPLODE, 0.45F, 1.55F);
                play(l, at, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.8F, 0.9F);
            }
            case SHARD -> {
                play(l, at, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.55F, 1.4F);
                play(l, at, SoundEvents.GLASS_HIT, 0.4F, 1.6F);
            }
            case GLOW -> play(l, at, SoundEvents.AMETHYST_BLOCK_CHIME, 0.8F, 1.0F);
            default -> {
                play(l, at, SoundEvents.AMETHYST_CLUSTER_HIT, 0.6F, 1.2F);
                play(l, at, SoundEvents.FIREWORK_ROCKET_BLAST, 0.5F, 1.5F);
            }
        }
        if (creature && rune != RuneId.GLOW) play(l, at, SoundEvents.TRIDENT_HIT, 0.55F, rune == RuneId.ORB ? 0.8F : 1.1F);
        elementSound(l, at, e, 0.55F);
    }

    /** A Burst of radius {@code r} round {@code c}: a flash, a shockwave ring, sparks all round, and a blast you feel. */
    public static void burst(ServerLevel l, Vec3 c, double r, RuneId rune, Element e) {
        send(l, Fx.Kind.BURST, c, Vec3.ZERO, colour(rune, e), e, (float) r, 0.0F, -1, FAR);
        play(l, c, SoundEvents.GENERIC_EXPLODE, 0.75F, 1.35F);
        play(l, c, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 0.75F);
        play(l, c, SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.35F, 1.2F);
        elementSound(l, c, e, 0.9F);
    }

    /** A Cleave from {@code from}, along {@code flat}, reaching {@code range}, its arc {@code half} radians either side. */
    public static void cleave(ServerLevel l, Vec3 from, Vec3 flat, double range, double half, RuneId rune, Element e) {
        send(l, Fx.Kind.CLEAVE, from, flat, colour(rune, e), e, (float) range, (float) half, -1, NEAR);
        play(l, from, SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.85F);
        play(l, from, SoundEvents.TRIDENT_THROW, 0.7F, 1.25F);
        elementSound(l, from, e, 0.6F);
    }

    /** A Field opens ({@code pulse} false) or pulses round {@code c}. */
    public static void field(ServerLevel l, Vec3 c, float radius, Element e, boolean pulse) {
        send(l, Fx.Kind.FIELD, c, Vec3.ZERO, colour(RuneId.FIELD, e), e, radius, pulse ? 1.0F : 0.0F, -1, NEAR);
        if (pulse) {
            play(l, c, SoundEvents.AMETHYST_BLOCK_STEP, 0.5F, 1.6F);
        } else {
            play(l, c, SoundEvents.BEACON_ACTIVATE, 0.5F, 1.7F);
            play(l, c, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6F, 1.1F);
            elementSound(l, c, e, 0.5F);
        }
    }

    /** A Barrier wall stands round {@code mid}: {@code along} the way it runs. */
    public static void barrier(ServerLevel l, Vec3 mid, Vec3 along, int width, int height, Element e) {
        send(l, Fx.Kind.BARRIER, mid, along, colour(RuneId.BARRIER, e), e, width, height, -1, NEAR);
        play(l, mid, SoundEvents.AMETHYST_BLOCK_PLACE, 1.0F, 0.8F);
        play(l, mid, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.6F, 1.3F);
    }

    /** A Dash sets off from {@code at}, going {@code dir}. */
    public static void dash(ServerLevel l, Vec3 at, Vec3 dir, Element e, int caster) {
        send(l, Fx.Kind.DASH, at, dir, colour(RuneId.DASH, e), e, 1.0F, 0.0F, caster, NEAR);
        play(l, at, SoundEvents.TRIDENT_RIPTIDE_1, 0.55F, 1.5F);
        play(l, at, SoundEvents.PHANTOM_FLAP, 0.6F, 1.8F);
    }

    /** A beam of light from {@code a} to {@code b} (Reel's line, Storm's leap). */
    public static void beam(ServerLevel l, Vec3 a, Vec3 b, int rgb, Element e) {
        send(l, Fx.Kind.BEAM, a, b.subtract(a), rgb, e, 1.0F, 0.0F, -1, NEAR);
    }

    /** Reel's rope while it pulls you: sparser and briefer than a beam, as it is sent every other tick. */
    public static void rope(ServerLevel l, Vec3 a, Vec3 b, int rgb) {
        send(l, Fx.Kind.BEAM, a, b.subtract(a), rgb, Element.NONE, 0.6F, 0.5F, -1, NEAR);
    }

    /** A bolt pierces a creature, or a Fan copy passes one it already struck. */
    public static void pierce(ServerLevel l, Vec3 at, Vec3 dir, RuneId rune, Element e) {
        send(l, Fx.Kind.PIERCE, at, dir, colour(rune, e), e, 1.0F, 0.0F, -1, NEAR);
    }

    /** A bolt glances off a wall at {@code at}, flying off {@code dir}. */
    public static void bounce(ServerLevel l, Vec3 at, Vec3 dir, RuneId rune, Element e, int left) {
        send(l, Fx.Kind.BOUNCE, at, dir, colour(rune, e), e, 1.0F, 0.0F, -1, NEAR);
        play(l, at, SoundEvents.AMETHYST_BLOCK_HIT, 0.5F, 1.6F + 0.1F * left);
    }

    /** An element reaction on a creature, round its middle {@code c}. */
    public static void reaction(ServerLevel l, Vec3 c, Reactions.Reaction r, Element e, float width) {
        send(l, Fx.Kind.REACTION, c, Vec3.ZERO, e.colour, e, width, r.ordinal(), -1, NEAR);
        switch (r) {
            case STEAM -> play(l, c, SoundEvents.FIRE_EXTINGUISH, 0.7F, 1.2F);
            case THAW -> play(l, c, SoundEvents.FIRE_EXTINGUISH, 0.6F, 1.6F);
            case SHATTER -> {
                play(l, c, SoundEvents.GLASS_BREAK, 1.0F, 0.75F);
                play(l, c, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.8F, 0.6F);
            }
            case FREEZE -> {
                play(l, c, SoundEvents.PLAYER_HURT_FREEZE, 0.9F, 1.2F);
                play(l, c, SoundEvents.POWDER_SNOW_BREAK, 0.8F, 0.8F);
            }
            case SHOCK -> {
                play(l, c, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5F, 1.7F);
                play(l, c, SoundEvents.TRIDENT_RIPTIDE_1, 0.3F, 2.0F);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------------------------------------ hits

    /** A melee hit lands at {@code at}, coming along {@code dir}: {@code strength} 1 for a full hit, more for a heavy one. */
    public static void hit(ServerLevel l, Vec3 at, Vec3 dir, float strength) {
        send(l, Fx.Kind.HIT, at, dir, 0xFFFFFF, Element.NONE, strength, 0.0F, -1, NEAR);
    }

    /** A momentum hit: the attacker's speed carried into the blow. */
    public static void momentum(ServerLevel l, Vec3 at, Vec3 dir, float amount) {
        send(l, Fx.Kind.MOMENTUM, at, dir, 0xFFF4D6, Element.NONE, amount, 0.0F, -1, NEAR);
        play(l, at, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.9F);
        play(l, at, SoundEvents.TRIDENT_HIT, 0.6F + 0.3F * amount, 0.75F);
    }

    /** A creature's poise breaks: it reels for {@code ticks}. */
    public static void stagger(ServerLevel l, Vec3 c, int entity, float width, int ticks) {
        send(l, Fx.Kind.STAGGER, c, Vec3.ZERO, 0xFFF0B0, Element.NONE, width, ticks, entity, FAR);
        play(l, c, SoundEvents.SHIELD_BREAK, 0.9F, 0.75F);
        play(l, c, SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
        play(l, c, SoundEvents.ANVIL_LAND, 0.2F, 1.7F);
    }

    /** A creature slams into a wall at {@code at}: {@code out} the way back out of the wall, {@code amount} 0 to 1. */
    public static void slam(ServerLevel l, Vec3 at, Vec3 out, float amount, SoundEvent blockSound) {
        send(l, Fx.Kind.SLAM, at, out, 0xFFFFFF, Element.NONE, amount, 0.0F, -1, NEAR);
        play(l, at, SoundEvents.GENERIC_BIG_FALL, 1.0F, 0.85F);
        play(l, at, blockSound, 1.0F, 0.7F);
        play(l, at, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 0.25F + 0.25F * amount, 1.4F);
    }

    /** Motes landed in the gear of the player at {@code at}: {@code full} how full it is now. */
    public static void moteIn(ServerLevel l, Vec3 at, float full) {
        send(l, Fx.Kind.MOTE_IN, at, Vec3.ZERO, 0xFFFFFF, Element.NONE, full, 0.0F, -1, 16.0);
    }

    // ------------------------------------------------------------------------------------------------ sounds

    /** The element's own layer of a sound. */
    static void elementSound(ServerLevel l, Vec3 at, Element e, float volume) {
        switch (e) {
            case EMBER -> play(l, at, SoundEvents.FIRECHARGE_USE, volume, 1.0F);
            case FROST -> play(l, at, SoundEvents.GLASS_BREAK, volume * 0.8F, 1.3F);
            case STORM -> play(l, at, SoundEvents.LIGHTNING_BOLT_IMPACT, volume * 0.45F, 1.9F);
            case TIDE -> play(l, at, SoundEvents.GENERIC_SPLASH, volume, 1.1F);
            default -> {}
        }
    }

    /** The sound a rune makes when it goes off (one layer, for the few places that want just one). */
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

    /** A sound at a point, its pitch moved a little each time (by up to 8% either way). */
    public static void play(ServerLevel l, Vec3 p, SoundEvent s, float volume, float pitch) {
        if (volume <= 0.0F) return;
        float jitter = 1.0F + (l.random.nextFloat() - 0.5F) * 0.16F;
        l.playSound(null, p.x, p.y, p.z, s, SoundSource.PLAYERS, volume, Mth.clamp(pitch * jitter, 0.5F, 2.0F));
    }
}
