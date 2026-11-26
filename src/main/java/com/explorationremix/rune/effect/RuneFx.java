package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Reactions;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.net.RuneNet;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

        /** A rune freed from a rune stone, while it rises and flies (0.23.1). */
        default void freedTick(com.explorationremix.rune.stone.FreedRune r) {}

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
            // the bow's runes: a cold white flare, and light blues and lilacs
            case GLOW -> 0xE4EEFF;
            case ROPE -> 0xA9CCFF;
            case SNARE -> 0xB4B9FF;
            case VOLLEY -> 0xC3D6FF;
            case MINE -> 0x9AAEFF;
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

    /** A cast leaves {@code caster}'s {@code hand} at {@code at}, going {@code dir}; a second phase's is brighter, and rings. */
    public static void cast(ServerLevel l, Vec3 at, Vec3 dir, RuneId rune, Element e, boolean loud, int caster, InteractionHand hand,
                            boolean empowered) {
        send(l, Fx.Kind.CAST, at, dir, colour(rune, e), e, 1.0F, (hand == InteractionHand.OFF_HAND ? 1.0F : 0.0F) + (empowered ? 2.0F : 0.0F),
                caster, NEAR);
        if (empowered) play(l, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.4F, 1.9F);
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
        float s = Mth.clamp(size * (rune == RuneId.ORB ? 1.5F : rune == RuneId.SHARD || rune == RuneId.VOLLEY ? 0.75F : 1.0F), 0.3F, 4.0F);
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
            case GLOW -> {
                play(l, at, SoundEvents.AMETHYST_BLOCK_CHIME, 0.9F, 1.0F);
                play(l, at, SoundEvents.FIRECHARGE_USE, 0.2F, 1.9F);
            }
            case ROPE, SNARE -> play(l, at, SoundEvents.AMETHYST_BLOCK_HIT, 0.6F, 1.1F);
            case VOLLEY -> play(l, at, SoundEvents.ARROW_HIT, 0.4F, 1.3F);
            default -> {
                play(l, at, SoundEvents.AMETHYST_CLUSTER_HIT, 0.6F, 1.2F);
                play(l, at, SoundEvents.FIREWORK_ROCKET_BLAST, 0.5F, 1.5F);
            }
        }
        if (creature && rune != RuneId.GLOW && rune != RuneId.VOLLEY) play(l, at, SoundEvents.TRIDENT_HIT, 0.55F, rune == RuneId.ORB ? 0.8F : 1.1F);
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

    /** Burst's shockwave: a ring of force rolling out along the ground round {@code c}, from {@code from} to {@code to} blocks. */
    public static void shockwave(ServerLevel l, Vec3 c, float from, float to, int rgb, Element e) {
        send(l, Fx.Kind.SHOCKWAVE, c, Vec3.ZERO, rgb, e, from, to, -1, FAR);
        play(l, c, SoundEvents.GENERIC_EXPLODE, 0.35F, 0.7F);
    }

    /** Snare's roots take hold round {@code c}, {@code radius} across. */
    public static void snare(ServerLevel l, Vec3 c, float radius, int rgb, Element e) {
        send(l, Fx.Kind.SNARE, c, Vec3.ZERO, rgb, e, radius, 0.0F, -1, NEAR);
    }

    /** What {@link #armor} shows: a Leap's push off the air, Second Wind, a glide, a pull up onto a ledge. */
    public static final int ARMOR_LEAP = 0, ARMOR_WIND = 1, ARMOR_GLIDE = 2, ARMOR_CLIMB = 3;

    /** Armor at work on {@code player} at {@code at} ({@code what}: {@link #ARMOR_LEAP} and the rest), for everyone near. */
    public static void armor(ServerLevel l, Vec3 at, int rgb, int what, int player) {
        send(l, Fx.Kind.ARMOR, at, Vec3.ZERO, rgb, Element.NONE, 1.0F, what, player, NEAR);
    }

    /** The same, for everyone near but {@code p}, whose own game drew it as it moved them (a leap, a pull). */
    public static void armorFrom(ServerPlayer p, Vec3 at, int rgb, int what) {
        Fx fx = Fx.of(Fx.Kind.ARMOR, at, Vec3.ZERO, rgb, Element.NONE, 1.0F, what, p.getId());
        RuneNet.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(p, at.x, at.y, at.z, NEAR, p.serverLevel().dimension())),
                new RuneNet.FxMsg(fx));
    }

    /** A second phase drags creatures toward {@code c} from {@code reach} blocks round (Orb's gravity, Field's pull). */
    public static void well(ServerLevel l, Vec3 c, float reach, int rgb, Element e) {
        send(l, Fx.Kind.WELL, c, Vec3.ZERO, rgb, e, reach, 0.0F, -1, NEAR);
    }

    /** One stretch of Cleave's second-phase wave, at {@code at} going {@code flat}, {@code halfWidth} either side. */
    public static void wave(ServerLevel l, Vec3 at, Vec3 flat, float halfWidth, int rgb, Element e) {
        send(l, Fx.Kind.WAVE, at, flat, rgb, e, halfWidth, 0.0F, -1, NEAR);
    }

    /**
     * The second phase of the weapon in {@code player}'s hand wakes ({@code awake}) as they reach its active rune's level,
     * or sleeps as they drop below it: a ring of light round them that only they and those near see, and a chime.
     */
    public static void phase(ServerLevel l, Vec3 feet, int rgb, boolean awake, int player) {
        send(l, Fx.Kind.PHASE, feet, Vec3.ZERO, rgb, Element.NONE, 1.0F, awake ? 1.0F : 0.0F, player, NEAR);
        if (awake) {
            play(l, feet, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9F, 1.2F);
            play(l, feet, SoundEvents.BEACON_POWER_SELECT, 0.4F, 1.8F);
        } else {
            play(l, feet, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5F, 0.6F);
        }
    }

    /**
     * Overflow charged: energy came into the weapon past full, so its next cast is a third stronger. Rings of light rise
     * round the player, in light blue (the caller plays the sound).
     */
    public static void overflow(ServerLevel l, ServerPlayer p) {
        send(l, Fx.Kind.PHASE, p.position(), Vec3.ZERO, OVERFLOW_BLUE, Element.NONE, 1.0F, 1.0F, p.getId(), NEAR);
    }

    /** Overflow's light blue. */
    public static final int OVERFLOW_BLUE = 0xBFD9FF;

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

    /**
     * A hunter starts winding up a strike: a cold glint at its eyes ({@code eyes}, facing {@code dir}), so the half
     * second before it strikes can be read from across a cave as well as heard. Its own wind-up sound plays with it.
     */
    public static void windup(ServerLevel l, Vec3 eyes, Vec3 dir, float size) {
        send(l, Fx.Kind.WINDUP, eyes, dir, 0xFFFFFF, Element.NONE, size, 0.0F, -1, NEAR);
    }

    /** A creature slams into a wall at {@code at}: {@code out} the way back out of the wall, {@code amount} 0 to 1. */
    public static void slam(ServerLevel l, Vec3 at, Vec3 out, float amount, SoundEvent blockSound) {
        send(l, Fx.Kind.SLAM, at, out, 0xFFFFFF, Element.NONE, amount, 0.0F, -1, NEAR);
        play(l, at, SoundEvents.GENERIC_BIG_FALL, 1.0F, 0.85F);
        play(l, at, blockSound, 1.0F, 0.7F);
        play(l, at, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 0.25F + 0.25F * amount, 1.4F);
    }

    /**
     * A sword's jump attack comes down (0.22.7): dust and a crack off the ground at {@code at} ({@code ground} its block's
     * state id), {@code flat} the way {@code player} faced, {@code amount} 1 when it struck a creature, less when it
     * struck only the ground.
     */
    public static void smash(ServerLevel l, Vec3 at, Vec3 flat, float amount, SoundEvent groundSound, int ground, int player) {
        // the block's id goes as the colour: a whole int, where the extra number is bounded for drawing
        send(l, Fx.Kind.SMASH, at, flat, ground, Element.NONE, amount, 0.0F, player, NEAR);
        play(l, at, SoundEvents.GENERIC_BIG_FALL, 0.9F, 0.75F);
        play(l, at, groundSound, 1.0F, 0.6F);
    }

    /** A sword's parry (0.22.7) at {@code at} on {@code player}'s blade, turning back what came along {@code -toward}. */
    public static void parry(ServerLevel l, Vec3 at, Vec3 toward, int player) {
        send(l, Fx.Kind.PARRY, at, toward, 0xFFFFFF, Element.NONE, 1.0F, 0.0F, player, FAR);
    }

    /** A blow taken on {@code player}'s raised sword (0.22.7) at {@code at}; {@code amount} 0 to 1 for how hard. */
    public static void block(ServerLevel l, Vec3 at, Vec3 toward, float amount, int player) {
        send(l, Fx.Kind.BLOCK, at, toward, 0xFFFFFF, Element.NONE, amount, 0.0F, player, NEAR);
    }

    /** Motes landed in the gear of the player at {@code at}: {@code full} how full it is now. */
    public static void moteIn(ServerLevel l, Vec3 at, float full) {
        send(l, Fx.Kind.MOTE_IN, at, Vec3.ZERO, 0xFFFFFF, Element.NONE, full, 0.0F, -1, 16.0);
    }

    // ------------------------------------------------------------------------------------------------ plants (0.18.7)

    /** The glowbells' light blue, the colour of their ring. */
    public static final int BELL_BLUE = 0x8EC2FF;
    /** A frostberry's cold: a pale light blue. */
    public static final int FROST_BLUE = 0xBFE4FF;

    /** A rune rang the glowbell at {@code c}: a ring of light going out {@code reach} blocks, and its bell, heard far. */
    public static void bell(ServerLevel l, Vec3 c, double reach) {
        send(l, Fx.Kind.BELL, c, Vec3.ZERO, BELL_BLUE, Element.NONE, (float) reach, 0.0F, -1, FAR);
        play(l, c, SoundEvents.NOTE_BLOCK_BELL.get(), 1.6F, 1.25F);
        play(l, c, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4F, 1.5F);
        play(l, c, SoundEvents.BELL_RESONATE, 0.5F, 1.9F);
    }

    /** A glowbell's ring reached the creature round {@code c}, {@code width} wide: it lights up. */
    public static void ping(ServerLevel l, Vec3 c, float width) {
        send(l, Fx.Kind.PING, c, Vec3.ZERO, BELL_BLUE, Element.NONE, width, 0.0F, -1, FAR);
    }

    /** A struck fern's fronds grow a block further at {@code at}, going {@code dir}; {@code step} counts the blocks. */
    public static void unroll(ServerLevel l, Vec3 at, Vec3 dir, int step) {
        send(l, Fx.Kind.UNROLL, at, dir, 0xDDE7F0, Element.NONE, 1.0F, 0.0F, -1, NEAR);
        if (step % 2 == 0) play(l, at, step == 0 ? SoundEvents.BIG_DRIPLEAF_TILT_DOWN : SoundEvents.AZALEA_LEAVES_PLACE, 0.7F,
                Math.min(1.9F, 0.9F + 0.06F * step));
    }

    /** The fronds roll back up: a rustle. */
    public static void rollUp(ServerLevel l, Vec3 at) {
        play(l, at, SoundEvents.BIG_DRIPLEAF_TILT_UP, 0.6F, 1.3F);
        play(l, at, SoundEvents.AZALEA_LEAVES_BREAK, 0.5F, 1.1F);
    }

    /** The fronds shiver: they roll up in a moment. */
    public static void shiver(ServerLevel l, Vec3 at) {
        play(l, at, SoundEvents.AZALEA_LEAVES_STEP, 0.8F, 0.7F);
        play(l, at, SoundEvents.BIG_DRIPLEAF_TILT_UP, 0.4F, 0.8F);
    }

    /** A struck frostberry vine bursts at {@code c}: a puff of cold {@code reach} blocks round, falling {@code fall} blocks to the floor. */
    public static void frostPuff(ServerLevel l, Vec3 c, double reach, double fall) {
        send(l, Fx.Kind.FROST_PUFF, c, Vec3.ZERO, FROST_BLUE, Element.FROST, (float) reach, (float) fall, -1, NEAR);
        play(l, c, SoundEvents.POWDER_SNOW_BREAK, 1.0F, 1.2F);
        play(l, c, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6F, 1.7F);
        play(l, c, SoundEvents.CAVE_VINES_PICK_BERRIES, 0.8F, 1.3F);
    }

    // ------------------------------------------------------------------------------------------------ rune stones (0.23.1)

    /** The rune stones' light: their carving's light blue. */
    public static final int STONE_BLUE = 0x8EC2FF;

    /** A rune stone breaks at {@code c}: {@code toward} its breaker (zero for a blast), {@code host} its stone's state id. */
    public static void stoneBurst(ServerLevel l, Vec3 c, Vec3 toward, int host) {
        send(l, Fx.Kind.STONE_BURST, c, toward, host, Element.NONE, 1.0F, 0.0F, -1, FAR);
    }

    /** The rune stone at {@code at} answers one broken {@code from} away: the spark reaches it in {@code delay} ticks. */
    public static void stoneFlare(ServerLevel l, Vec3 at, Vec3 from, int delay) {
        send(l, Fx.Kind.STONE_FLARE, at, from, STONE_BLUE, Element.NONE, 1.0F, delay, -1, FAR);
    }

    /** A freed rune reaches {@code player} at {@code at}: {@code index} 0 a stone's first rune, 1 its second. */
    public static void stoneCatch(ServerLevel l, Vec3 at, int index, int player) {
        send(l, Fx.Kind.STONE_CATCH, at, Vec3.ZERO, STONE_BLUE, Element.NONE, index, 0.0F, player, NEAR);
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
            case ROPE, SNARE -> SoundEvents.CHAIN_PLACE;
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
