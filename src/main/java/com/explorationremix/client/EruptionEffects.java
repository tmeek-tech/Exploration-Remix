package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.lava.LavaNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * An eruption of the Caldera on this client (0.9.0, spec 5.5): its smoke, its fountain, its plume and its sounds, played
 * by the level's game time against the eruption's start (or at the moment it is held), so a slow client stays in step
 * with the server and one that comes late plays the rest. Reached from {@code LavaNet}'s handlers only through
 * {@code DistExecutor}, so a dedicated server never loads it. The lava bombs are the server's own entities; their trail,
 * whistle and bursts are {@link BombEffects}'.
 * <ul>
 *     <li><b>The warning</b>: a glow building at the vent's mouth and fire licking over its rim, both growing toward the
 *     burst ({@link CaveParticles.Flash}), lava popping on the crater floor on a cadence that quickens toward the burst
 *     (a spot flings glowing gobs up over the rim with a flare of fire), spatter hopping, dark ash billowing from the
 *     mouth ({@link CaveParticles.EruptionAsh}, never the Caldera's own beige smoke), and the vent's rumble
 *     ({@code eruption.rumble}, placed at the vent, heard 96 blocks off).</li>
 *     <li><b>The burst</b>: a deep boom ({@code eruption.boom}) placed at the vent with no fading, its volume set by the
 *     distance, so it is heard across the Caldera with its direction; it comes as late as sound travels (a block every
 *     half tick), and the view jolts as it arrives ({@link CameraShake}). A blaze over the mouth for its first ticks
 *     and a flash of fire and lava burst from the mouth as it starts ({@link Fountain#blazing}, {@link Fountain#flash}).
 *     Then the fountain's roar ({@code eruption.roar}, the same way, swelling in and dying away with the fountain) and
 *     the fountain itself: a dense jet of lava gobs ({@link CaveParticles.LavaGob}, half a block to a block across)
 *     thrown 10 to 25 blocks up off the crater floor, fanning out and raining back down round the stack, fine spatter
 *     round them, fire roaring at the mouth, embers, and a thick column of ash thrown out of the mouth, lit orange from
 *     below by the fountain and billowing charcoal brown above, that climbs to the vault, spreads under it and goes on
 *     puffing for ten seconds after the fountain. All of it in full at strength 1, in gusts after the burst's first
 *     second, and larger by the eruption's {@code scale} once the lava rises.</li>
 * </ul>
 * Within 32 blocks the particles go through the game's own limits; farther out up to the eruption's range they are
 * forced and thinned by hand by the Particles option and the distance, and the gobs, the spatter and the fire are
 * drawn larger so they still show through the cave fog (the ash, always shown, a little larger). The ash takes at most
 * a quarter of the client's smoke on top of the Caldera's own, which gives way to it ({@link SmokeBudget}). The warning's
 * glow and the burst's blaze are always shown, as the game's own explosion is. With caveParticles off the ash and the
 * embers go; the gobs, the spatter, the fire, the glow and the blaze stay, as they show the danger. A held eruption
 * plays its moment steadily, and nothing shakes the view while it is held.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class EruptionEffects {
    private EruptionEffects() {}

    /** The boom is played to a client that sees the burst start, or comes within this many ticks of its start. */
    static final int BOOM_LATE = 20;
    /** The rumble's volume: heard 96 blocks off. */
    static final float RUMBLE_VOLUME = 6;
    /** At most this many new positioned sounds a tick from the eruptions' and the lava bombs' effects (Sound Physics Remastered drops a burst). */
    static final int SOUNDS_PER_TICK = 4;
    /** The fountain rises from within this share of the crater's radius, and its flames from within this share. */
    static final double FLOOR_SHARE = 0.8, FLAME_SHARE = 0.6;
    /** While it warns: spatter hops on the crater floor a tick. */
    static final int HOPS = 2;
    /**
     * The plume's puffs rise this far after their thrust (to the vault, and under it), and the warning's this far, in
     * blocks (SmokePuff's reach: it sets their lives, 30 to 50 seconds).
     */
    static final double PLUME_REACH_MIN = 60, PLUME_REACH_MAX = 120, WARN_REACH_MIN = 25, WARN_REACH_MAX = 45;
    /** The fire at the start's flash is drawn this much larger than the fire at the mouth, and a pop's flare this much. */
    static final float FLASH_SIZE = 2.2F, FLARE_SIZE = 1.4F;
    /** An eruption is forgotten this long after its plume ends, if no end came for it. */
    static final int FORGET = 20;
    /**
     * How strongly the vent lights its ash's foot from below (0 to 1, EruptionAsh): while it warns from this much to
     * this much more as the burst comes (the crater's lava), while the fountain throws as hard as it throws, and in the
     * plume's tail from this much down to nothing.
     */
    static final double WARN_GLOW = 0.35, WARN_GLOW_MORE = 0.45, TAIL_GLOW = 0.4;
    /** The warning's mouth glow and the burst's blaze stand this far over the mouth, so they show over its rim from below. */
    static final double GLOW_OVER = 3.0, BLAZE_OVER = 3.0;
    /** The warning's fire licks this high over the mouth, from its foot, so it shows over the rim from below. */
    static final double WARN_FIRE_FOOT = 0.8, WARN_FIRE_RISE = 2.5;

    /** The eruptions this client plays, by their id. */
    private static final Map<Integer, Vent> ERUPTIONS = new HashMap<>();
    /** The level they belong to (a new one has none of them, and another clock). */
    private static WeakReference<ClientLevel> owner = new WeakReference<>(null);
    /** The ends of the eruptions' own plume puffs, so they never take more than a quarter of the smoke's room. */
    private static final PriorityQueue<Long> PUFFS = new PriorityQueue<>();
    /** The game tick the positioned sounds are counted for, and how many are left in it. */
    private static long soundTick = Long.MIN_VALUE;
    private static int sounds;
    /** This client's unpaused ticks while an eruption plays: the puffs' clock. */
    private static long ticks;

    /** One eruption as this client knows it. */
    private static final class Vent {
        double x, z;
        float r, strength, scale;
        int floorY, mouthY, pre, burst, held = -1, range;
        long start, seed;
        boolean rumbled, boomed;
        /** The warning's lava pops' clock: a pop each time it passes 1 (it runs on while held, so a held warning pops too). */
        double popClock;
        @Nullable Roar roar;

        void set(LavaNet.Eruption e) {
            x = e.x();
            z = e.z();
            r = e.r();
            floorY = e.floorY();
            mouthY = e.mouthY();
            start = e.start();
            pre = e.pre();
            burst = e.burst();
            strength = e.strength();
            scale = e.scale();
            seed = e.seed();
            held = e.held();
            range = e.range();
        }

        /** Ticks into it at game time {@code now}: the held moment while it is held. */
        double into(long now) { return held >= 0 ? held : now - start; }

        /** Stops its roar. */
        void silence() {
            if (roar != null) roar.end();
            roar = null;
        }
    }

    // ================================================================================================ messages

    /** An eruption starts, is held or released, or ends ({@link LavaNet.Eruption#END}), on the main thread. */
    public static void accept(LavaNet.Eruption e) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        own(level);
        if (e.kind() == LavaNet.Eruption.END) {
            Vent v = ERUPTIONS.remove(e.id());
            if (v != null) v.silence();
            return;
        }
        // one already known (told again on a hold or a release, or after coming back near) only moves on
        ERUPTIONS.computeIfAbsent(e.id(), id -> new Vent()).set(e);
    }

    /** True while an eruption this client plays is held by command (pictures: nothing shakes). */
    static boolean anyHeld() {
        for (Vent v : ERUPTIONS.values()) if (v.held >= 0) return true;
        return false;
    }

    /**
     * Takes one of this tick's new positioned sounds for the eruptions' and the bombs' effects; false when they are
     * spent (a sound refused waits for a later tick, or is left out).
     */
    static boolean spend(ClientLevel level) {
        long now = level.getGameTime();
        if (now != soundTick) {
            soundTick = now;
            sounds = SOUNDS_PER_TICK;
        }
        if (sounds <= 0) return false;
        sounds--;
        return true;
    }

    // ================================================================================================ each tick

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        own(level);
        if (mc.player == null || mc.isPaused() || ERUPTIONS.isEmpty()) return;
        ticks++;
        long now = level.getGameTime();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        RandomSource random = level.random;
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        double share = SmokeBudget.share();
        int budget = CaveAtmosphere.smokeBudget(mc);
        for (Iterator<Vent> it = ERUPTIONS.values().iterator(); it.hasNext(); ) {
            Vent v = it.next();
            double t = v.into(now);
            if (v.held < 0 && t > v.pre + v.burst + Fountain.PLUME_TAIL + FORGET) {
                v.silence();
                it.remove();
                continue;
            }
            double d = cam.distanceTo(new Vec3(v.x, v.mouthY + 0.5, v.z));
            sounds(mc, level, v, t, d);
            // beyond its range it is not told, and nothing of it shows
            if (d > v.range) continue;
            double far = Fountain.farShare(d, share);
            if (t < v.pre) warning(mc, level, v, t, d, far, particles, budget, now, random);
            else if (t < v.pre + v.burst) burst(mc, level, v, t - v.pre, d, far, particles, budget, now, random);
            else if (particles && ticks % Fountain.plumeEvery(v.scale) == 0) {
                // the plume puffs on after the fountain, ever more weakly, its foot's glow dying with it
                double tail = 1 - (t - v.pre - v.burst) / Fountain.PLUME_TAIL;
                if (tail > 0) {
                    ash(mc, v, d, now, budget, random, Fountain.ashThrust(random.nextDouble(), v.strength) * tail, PLUME_REACH_MIN, PLUME_REACH_MAX,
                            TAIL_GLOW * tail);
                }
            }
        }
    }

    /** The eruption's sounds at {@code t} ticks into it, heard {@code d} blocks from the vent: the rumble, the boom, the roar. */
    private static void sounds(Minecraft mc, ClientLevel level, Vent v, double t, double d) {
        RandomSource random = level.random;
        // not yet begun on this clock: the rumble waits for it
        if (!v.rumbled && t >= 0) {
            if (!Fountain.rumbleInTime(t, v.pre)) {
                // too late for it: the warning goes unheard
                v.rumbled = true;
            } else if (v.held < 0 && spend(level)) {
                v.rumbled = true;
                level.playLocalSound(v.x, v.mouthY + 0.5, v.z, ERSounds.ERUPTION_RUMBLE.get(), SoundSource.BLOCKS, RUMBLE_VOLUME,
                        0.9F + 0.2F * random.nextFloat(), false);
            }
        }
        if (!v.boomed && t >= v.pre) {
            v.boomed = true;
            if (v.held < 0 && t < v.pre + BOOM_LATE) {
                // as late as sound travels, and the view jolts as it arrives
                int delay = Shake.boomDelay(d);
                float volume = (float) Fountain.boomVolume(d, v.range);
                mc.getSoundManager().playDelayed(new SimpleSoundInstance(ERSounds.ERUPTION_BOOM.get().getLocation(), SoundSource.BLOCKS, volume,
                        0.9F + 0.15F * random.nextFloat(), random, false, 0, SoundInstance.Attenuation.NONE, v.x, v.mouthY + 0.5, v.z, false), delay);
                CameraShake.jolt(delay, Shake.eruptionJolt(v.strength, d));
            }
        }
        double tb = t - v.pre;
        boolean bursting = tb >= 0 && tb < v.burst;
        if (!bursting) {
            v.silence();
            return;
        }
        float volume = d > v.range ? 0 : (float) (Fountain.roarEnvelope(tb, v.burst) * Fountain.roarVolume(d, v.range));
        // a roar the sound engine dropped (a reload, a device change) is started again; the old one is stopped first, so
        // one the engine still plays under another instance (a mod may swap it as it starts) is never doubled
        if (v.roar != null && (v.roar.isStopped() || ticks % 20 == 0 && !mc.getSoundManager().isActive(v.roar))) v.silence();
        if (v.roar == null) {
            v.roar = new Roar(v.x, v.mouthY + 0.5, v.z, volume);
            mc.getSoundManager().play(v.roar);
        }
        v.roar.target = volume;
    }

    /**
     * The warning {@code t} ticks in: a glow building at the mouth and fire licking over it, both growing toward the
     * burst, lava pops on a cadence that quickens toward it, spatter hopping on the crater floor, and dark ash billowing
     * from the mouth. The glow, the fire and the pops are there in every frame of its last seconds, not by chance.
     */
    private static void warning(Minecraft mc, ClientLevel level, Vent v, double t, double d, double far, boolean particles, int budget, long now,
                                RandomSource random) {
        double w = Fountain.warned(t, v.pre);
        double floor = v.floorY + 1.05;
        for (int i = Fountain.count(HOPS * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            double a = random.nextDouble() * Mth.TWO_PI, side = 0.03 * random.nextDouble();
            put(mc, level, ERParticles.LAVA_SPATTER.get(), d, p[0], floor, p[1], side * Math.cos(a), 0.25 + 0.25 * random.nextDouble(), side * Math.sin(a));
        }
        // the glow at the mouth: always one there (a new one every few ticks outlives the gap), growing and brightening
        if (ticks % Fountain.GLOW_EVERY == 0) {
            flash(mc, d, v.x, v.mouthY + GLOW_OVER, v.z, Fountain.glowSize(w), Fountain.GLOW_LIFE, Fountain.glowStrength(w), Fountain.GLOW_FORWARD);
        }
        // fire licking over the rim, more and bigger as the burst comes
        for (int i = Fountain.count(Fountain.warnFire(w) * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLAME_SHARE, random);
            Particle f = put(mc, level, ERParticles.FIREBALL.get(), d, p[0], v.mouthY + WARN_FIRE_FOOT + WARN_FIRE_RISE * random.nextDouble(), p[1],
                    0, 0.05 + 0.07 * random.nextDouble(), 0);
            if (f instanceof CaveParticles.Sized s) s.grow((float) (1 + 0.6 * w));
        }
        // lava pops on a cadence that quickens toward the burst (each pop shown, its gobs thinned far off)
        v.popClock += 1 / Fountain.popEvery(w);
        if (v.popClock >= 1) {
            v.popClock = Math.min(1, v.popClock - 1);
            pop(mc, level, v, d, floor, far, w, random);
        }
        if (random.nextInt(3) == 0 && Fountain.count(far, random.nextDouble()) > 0) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            put(mc, level, ParticleTypes.LAVA, d, p[0], floor, p[1], 0, 0, 0);
        }
        if (!particles) return;
        for (int i = Fountain.count(Fountain.WARN_ASH * SmokeBudget.share(), random.nextDouble()); i > 0; i--) {
            double thrust = Fountain.WARN_THRUST_MIN + (Fountain.WARN_THRUST_MAX - Fountain.WARN_THRUST_MIN) * random.nextDouble();
            ash(mc, v, d, now, budget, random, thrust, WARN_REACH_MIN, WARN_REACH_MAX, WARN_GLOW + WARN_GLOW_MORE * w);
        }
    }

    /**
     * A lava pop {@code w} through the warning: one spot of the crater floor flings gobs up 5 to 10 blocks, over the rim,
     * more toward the burst ({@link Fountain#popGobs}), with a flare of fire at the mouth.
     */
    private static void pop(Minecraft mc, ClientLevel level, Vent v, double d, double floor, double far, double w, RandomSource random) {
        double[] p = onFloor(v, FLOOR_SHARE, random);
        Particle flare = put(mc, level, ERParticles.FIREBALL.get(), d, p[0], v.mouthY + 0.5, p[1], 0, 0.08 + 0.08 * random.nextDouble(), 0);
        if (flare instanceof CaveParticles.Sized s) s.grow(FLARE_SIZE);
        for (int i = Fountain.count(Fountain.popGobs(w) * Math.max(0.5, far), random.nextDouble()); i > 0; i--) {
            double speed = Fountain.POP_SPEED_MIN + (Fountain.POP_SPEED_MAX - Fountain.POP_SPEED_MIN) * random.nextDouble();
            gob(mc, level, d, p[0] + (random.nextDouble() - 0.5) * 0.6, floor, p[1] + (random.nextDouble() - 0.5) * 0.6, speed,
                    25 * Math.sqrt(random.nextDouble()), random);
        }
    }

    /**
     * The burst {@code tb} ticks in: its start's flash, the fountain's jet of lava gobs and the fine spatter round them,
     * fire roaring at the mouth, embers, and the dark ash of the plume.
     */
    private static void burst(Minecraft mc, ClientLevel level, Vent v, double tb, double d, double far, boolean particles, int budget,
                              long now, RandomSource random) {
        double k = Fountain.intensity(tb, v.burst, v.seed);
        double floor = v.floorY + 1.05;
        double size = v.strength * v.scale;
        // the jet and its fan: gobs climbing 10 to 25 blocks and raining back down round the stack
        for (int i = Fountain.count(Fountain.GOBS * k * size * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            gob(mc, level, d, p[0], floor, p[1], Fountain.speed(random.nextDouble(), v.scale), Fountain.tilt(random.nextDouble(), random.nextDouble()), random);
        }
        double cone = Math.toRadians(Fountain.SPATTER_CONE);
        for (int i = Fountain.count(Fountain.SPATTER * k * size * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            double speed = Fountain.speed(random.nextDouble(), v.scale);
            double tilt = cone * Math.sqrt(random.nextDouble()), a = random.nextDouble() * Mth.TWO_PI;
            double side = speed * Math.sin(tilt);
            put(mc, level, ERParticles.LAVA_SPATTER.get(), d, p[0], floor, p[1], side * Math.cos(a), speed * Math.cos(tilt), side * Math.sin(a));
        }
        double mouth = Math.max(1, v.mouthY + 1 - floor);
        // the start's blaze: one big flash over the mouth, larger than its fireballs, for its first ticks (always shown)
        if (Fountain.blazing(tb)) {
            flash(mc, d, v.x, v.mouthY + BLAZE_OVER, v.z, Fountain.BLAZE_SIZE * Math.sqrt(size), 2, 0, Fountain.BLAZE_FORWARD);
        }
        // the start: a flash of fire and lava thrown wide out of the mouth
        double flash = Fountain.flash(tb);
        if (flash > 0) {
            for (int i = Fountain.count(Fountain.FLASH_FIRE * flash * far, random.nextDouble()); i > 0; i--) {
                double[] p = onFloor(v, FLAME_SHARE, random);
                double a = random.nextDouble() * Mth.TWO_PI, out = 0.1 + 0.15 * random.nextDouble();
                // over the rim, where the flash is seen from below
                Particle f = put(mc, level, ERParticles.FIREBALL.get(), d, p[0], v.mouthY + 3 * random.nextDouble(), p[1],
                        out * Math.cos(a), 0.25 + 0.35 * random.nextDouble(), out * Math.sin(a));
                if (f instanceof CaveParticles.Sized s) s.grow(FLASH_SIZE);
            }
            for (int i = Fountain.count(Fountain.FLASH_GOBS * flash * v.strength * far, random.nextDouble()); i > 0; i--) {
                double[] p = onFloor(v, FLOOR_SHARE, random);
                gob(mc, level, d, p[0], floor, p[1], (0.7 + 0.8 * random.nextDouble()) * Math.sqrt(v.scale),
                        Fountain.FLASH_CONE * Math.sqrt(random.nextDouble()), random);
            }
        }
        // fire roaring at the mouth, from just under the rim to over it
        for (int i = Fountain.count(Fountain.FIRE * k * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLAME_SHARE, random);
            put(mc, level, ERParticles.FIREBALL.get(), d, p[0], v.mouthY - 0.5 + 3 * random.nextDouble(), p[1],
                    (random.nextDouble() - 0.5) * 0.08, 0.15 + 0.25 * random.nextDouble(), (random.nextDouble() - 0.5) * 0.08);
        }
        for (int i = Fountain.count(Fountain.FLAMES * k * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLAME_SHARE, random);
            put(mc, level, ParticleTypes.FLAME, d, p[0], floor + mouth * random.nextDouble(), p[1],
                    (random.nextDouble() - 0.5) * 0.04, 0.05 + 0.1 * random.nextDouble(), (random.nextDouble() - 0.5) * 0.04);
        }
        if (!particles) return;
        for (int i = Fountain.count(Fountain.EMBERS * k * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            put(mc, level, ERParticles.EMBER.get(), d, p[0], floor + (mouth + 8) * random.nextDouble(), p[1], 0, 0, 0);
        }
        // the plume: ash thrown out of the mouth, thick while the fountain throws hardest, its foot lit by the fountain
        double glow = Math.max(k, flash);
        for (int i = Fountain.count(Fountain.ASH * glow * v.scale * SmokeBudget.share(), random.nextDouble()); i > 0; i--) {
            ash(mc, v, d, now, budget, random, Fountain.ashThrust(random.nextDouble(), v.strength), PLUME_REACH_MIN, PLUME_REACH_MAX, glow);
        }
    }

    /** One lava gob from (x, y, z) at {@code speed} blocks a tick, {@code tiltDeg} degrees from straight up in a random direction. */
    private static void gob(Minecraft mc, ClientLevel level, double d, double x, double y, double z, double speed, double tiltDeg, RandomSource random) {
        double tilt = Math.toRadians(tiltDeg), a = random.nextDouble() * Mth.TWO_PI, side = speed * Math.sin(tilt);
        put(mc, level, ERParticles.LAVA_GOB.get(), d, x, y, z, side * Math.cos(a), speed * Math.cos(tilt), side * Math.sin(a));
    }

    /**
     * A puff of ash thrown out of the mouth at {@code thrust} blocks a tick, rising {@code reachMin} to
     * {@code reachMax} blocks more after it, its foot lit by the vent's {@code glow} (0 to 1), when there is room: the
     * eruptions' own puffs never fill more than a quarter of the smoke's count ({@link SmokeBudget}), and never more
     * than that on top of a full count (the Caldera's own plumes are turned away for them). Always shown (forced); seen
     * from {@code d} blocks past {@link Fountain#NEAR} it is drawn larger by {@link Fountain#farAshSize}, so the column
     * stays a column through the haze.
     */
    private static void ash(Minecraft mc, Vent v, double d, long now, int budget, RandomSource random, double thrust, double reachMin, double reachMax,
                            double glow) {
        while (!PUFFS.isEmpty() && PUFFS.peek() <= now) PUFFS.poll();
        if (PUFFS.size() >= budget / 4 || SmokeBudget.alive(now) >= budget + budget / 4) return;
        double reach = reachMin + (reachMax - reachMin) * random.nextDouble();
        PUFFS.add(now + SmokePuff.lifeFor(reach) + 60);
        double[] p = onFloor(v, FLAME_SHARE, random);
        Particle puff = mc.particleEngine.createParticle(ERParticles.ERUPTION_ASH.get(), p[0], v.mouthY + 0.5, p[1], reach, thrust, glow);
        if (d > Fountain.NEAR && puff instanceof CaveParticles.Sized s) s.grow((float) Fountain.farAshSize(d));
    }

    /** A point on the crater floor within {@code share} of its radius: its x and z. */
    private static double[] onFloor(Vent v, double share, RandomSource random) {
        double a = random.nextDouble() * Mth.TWO_PI, rr = share * v.r * Math.sqrt(random.nextDouble());
        return new double[]{v.x + rr * Math.cos(a), v.z + rr * Math.sin(a)};
    }

    /**
     * One particle of an eruption's or a bomb's at (x, y, z) moving (dx, dy, dz), seen from {@code d} blocks, or null when
     * it is not shown: within {@link Fountain#NEAR} kept by the game's own share for the Particles option
     * ({@link SmokeBudget#nearShare}), farther out always (the caller has thinned their count by hand,
     * {@link Fountain#farShare}), and then drawn larger so it keeps more of its size ({@link Fountain#farSize}). Made
     * here rather than through the level, so the caller can size it.
     */
    @Nullable
    static Particle put(Minecraft mc, ClientLevel level, ParticleOptions type, double d, double x, double y, double z, double dx, double dy, double dz) {
        if (d <= Fountain.NEAR && level.random.nextFloat() >= SmokeBudget.nearShare()) return null;
        Particle p = mc.particleEngine.createParticle(type, x, y, z, dx, dy, dz);
        if (d > Fountain.NEAR && p instanceof CaveParticles.Sized s) s.grow((float) Fountain.farSize(d));
        return p;
    }

    /**
     * One particle that shows whatever the Particles option (a sign of danger, as the game's own explosion's is): the
     * warning's glow, the burst's blaze, a bomb's flash. Seen from {@code d} blocks past {@link Fountain#NEAR} it is
     * drawn larger by {@link Fountain#farAshSize} (a big billboard grown as a spatter would swamp the view).
     */
    @Nullable
    static Particle always(Minecraft mc, ParticleOptions type, double d, double x, double y, double z, double dx, double dy, double dz) {
        Particle p = mc.particleEngine.createParticle(type, x, y, z, dx, dy, dz);
        if (d > Fountain.NEAR && p instanceof CaveParticles.Sized s) s.grow((float) Fountain.farAshSize(d));
        return p;
    }

    /**
     * A flash (CaveParticles.Flash) of half-width {@code half} burning at (x, y, z), seen from {@code d} blocks: its
     * life in ticks, its glow (0 for a blast's white flash), drawn up to {@code forward} blocks toward the camera at the
     * size that covers the same part of the view ({@link Fountain#toward}), so the ash and rock right round its spot,
     * drawn before it, do not hide it. Always shown ({@link #always}).
     */
    @Nullable
    static Particle flash(Minecraft mc, double d, double x, double y, double z, double half, double life, double glow, double forward) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double k = Fountain.toward(forward, cam.distanceTo(new Vec3(x, y, z)));
        return always(mc, ERParticles.FLASH.get(), d, x + (cam.x - x) * k, y + (cam.y - y) * k, z + (cam.z - z) * k, half * (1 - k), life, glow);
    }

    /** Drops everything when the level changes: its eruptions are the old level's. */
    private static void own(ClientLevel level) {
        if (owner.get() == level) return;
        clear();
        owner = new WeakReference<>(level);
    }

    private static void clear() {
        for (Vent v : ERUPTIONS.values()) v.silence();
        ERUPTIONS.clear();
        PUFFS.clear();
    }

    /**
     * A fountain's roar: loops at the vent with no fading by distance (its volume is set by the distance instead, so it
     * is heard as far as the boom, with its direction), following the target the eruption gives it each tick.
     */
    private static final class Roar extends AbstractTickableSoundInstance {
        float target;

        Roar(double x, double y, double z, float volume) {
            super(ERSounds.ERUPTION_ROAR.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = Math.max(0.01F, volume);
            this.target = volume;
            this.relative = false;
            this.attenuation = Attenuation.NONE;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() { volume += (target - volume) * 0.5F; }

        void end() { stop(); }
    }
}
