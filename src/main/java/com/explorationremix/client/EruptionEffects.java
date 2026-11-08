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
 *     <li><b>The warning</b>: heavy smoke over the vent's mouth with a puff of the volcanic smoke each second, lava
 *     popping and spatter hopping on the crater floor, and the vent's rumble ({@code eruption.rumble}, placed at the vent,
 *     heard 96 blocks off).</li>
 *     <li><b>The burst</b>: a deep boom ({@code eruption.boom}) placed at the vent with no fading, its volume set by the
 *     distance, so it is heard across the Caldera with its direction; it comes as late as sound travels (a block every
 *     half tick), and the view jolts as it arrives ({@link CameraShake}). Then the fountain's roar
 *     ({@code eruption.roar}, the same way, swelling in and dying away with the fountain) and the fountain itself: lava
 *     spatter thrown up off the crater floor, flames and embers at the mouth, smoke, and a plume of the volcanic smoke
 *     that climbs to the vault and goes on puffing for ten seconds after it. All of it in full at strength 1, in gusts
 *     after the burst's first second, and larger by the eruption's {@code scale} once the lava rises.</li>
 * </ul>
 * Within 32 blocks the particles go through the game's own limits; farther out up to the eruption's range they are
 * forced and thinned by hand by the Particles option and the distance, and the spatter is drawn larger so it still
 * shows through the cave fog. With caveParticles off the plume, the embers and the smoke go; the spatter and the flames
 * stay, as they show the danger. A held eruption plays its moment steadily, and nothing shakes the view while it is
 * held.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class EruptionEffects {
    private EruptionEffects() {}

    /** The vent's rumble is played to a client that has at least this many ticks of the warning still to come. */
    static final int RUMBLE_LEFT = 20;
    /** The boom is played to a client that sees the burst start, or comes within this many ticks of its start. */
    static final int BOOM_LATE = 20;
    /** The rumble's volume: heard 96 blocks off. */
    static final float RUMBLE_VOLUME = 6;
    /** At most this many new positioned sounds a tick from the eruptions' and the lava bombs' effects (Sound Physics Remastered drops a burst). */
    static final int SOUNDS_PER_TICK = 4;
    /** The fountain rises from within this share of the crater's radius, and its flames from within this share. */
    static final double FLOOR_SHARE = 0.8, FLAME_SHARE = 0.6;
    /** While it warns: spatter hops on the crater floor a tick, and large smoke over the mouth a tick. */
    static final int HOPS = 2, WARN_SMOKE = 3;
    /** The plume's puffs rise this far (to the vault), and the warning's this far, in blocks. */
    static final double PLUME_REACH_MIN = 100, PLUME_REACH_MAX = 170, WARN_REACH_MIN = 50, WARN_REACH_MAX = 90;
    /** An eruption is forgotten this long after its plume ends, if no end came for it. */
    static final int FORGET = 20;

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
            if (t < v.pre) warning(mc, level, v, d, far, particles, budget, now, random);
            else if (t < v.pre + v.burst) burst(mc, level, v, t - v.pre, d, far, particles, budget, now, random);
            else if (particles && ticks % Fountain.plumeEvery(v.scale) == 0) plume(level, v, now, budget, random, PLUME_REACH_MIN, PLUME_REACH_MAX);
        }
    }

    /** The eruption's sounds at {@code t} ticks into it, heard {@code d} blocks from the vent: the rumble, the boom, the roar. */
    private static void sounds(Minecraft mc, ClientLevel level, Vent v, double t, double d) {
        RandomSource random = level.random;
        if (!v.rumbled) {
            if (t < 0 || v.pre - t < RUMBLE_LEFT) {
                // too late for it (or not yet begun on this clock): the warning goes unheard
                if (t >= 0) v.rumbled = true;
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
        // a roar the sound engine dropped (a reload, a device change) is started again
        if (v.roar != null && (v.roar.isStopped() || ticks % 20 == 0 && !mc.getSoundManager().isActive(v.roar))) v.roar = null;
        if (v.roar == null) {
            v.roar = new Roar(v.x, v.mouthY + 0.5, v.z, volume);
            mc.getSoundManager().play(v.roar);
        }
        v.roar.target = volume;
    }

    /** The warning: smoke over the mouth and a puff each second, lava popping and spatter hopping on the crater floor. */
    private static void warning(Minecraft mc, ClientLevel level, Vent v, double d, double far, boolean particles, int budget, long now,
                                RandomSource random) {
        double floor = v.floorY + 1.05;
        for (int i = Fountain.count(HOPS * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            double a = random.nextDouble() * Mth.TWO_PI, side = 0.03 * random.nextDouble();
            put(mc, level, ERParticles.LAVA_SPATTER.get(), d, p[0], floor, p[1], side * Math.cos(a), 0.25 + 0.25 * random.nextDouble(), side * Math.sin(a));
        }
        if (random.nextInt(3) == 0 && Fountain.count(far, random.nextDouble()) > 0) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            put(mc, level, ParticleTypes.LAVA, d, p[0], floor, p[1], 0, 0, 0);
        }
        if (!particles) return;
        for (int i = Fountain.count(WARN_SMOKE * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLAME_SHARE, random);
            put(mc, level, ParticleTypes.LARGE_SMOKE, d, p[0], v.mouthY + random.nextDouble(), p[1], 0, 0.06 + 0.08 * random.nextDouble(), 0);
        }
        if (ticks % 20 == 0) plume(level, v, now, budget, random, WARN_REACH_MIN, WARN_REACH_MAX);
    }

    /**
     * The burst {@code tb} ticks in: spatter thrown up off the crater floor, flames and embers at the mouth, smoke, and
     * the plume's puffs.
     */
    private static void burst(Minecraft mc, ClientLevel level, Vent v, double tb, double d, double far, boolean particles, int budget,
                              long now, RandomSource random) {
        double k = Fountain.intensity(tb, v.burst, v.seed);
        double floor = v.floorY + 1.05;
        double cone = Math.toRadians(Fountain.SPATTER_CONE);
        for (int i = Fountain.count(Fountain.SPATTER * k * v.strength * v.scale * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLOOR_SHARE, random);
            double speed = Fountain.speed(random.nextDouble(), v.scale);
            double tilt = cone * Math.sqrt(random.nextDouble()), a = random.nextDouble() * Mth.TWO_PI;
            double side = speed * Math.sin(tilt);
            put(mc, level, ERParticles.LAVA_SPATTER.get(), d, p[0], floor, p[1], side * Math.cos(a), speed * Math.cos(tilt), side * Math.sin(a));
        }
        double mouth = Math.max(1, v.mouthY + 1 - floor);
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
        for (int i = Fountain.count(Fountain.SMOKE * far, random.nextDouble()); i > 0; i--) {
            double[] p = onFloor(v, FLAME_SHARE, random);
            put(mc, level, ParticleTypes.LARGE_SMOKE, d, p[0], v.mouthY + random.nextDouble(), p[1], 0, 0.1 + 0.1 * random.nextDouble(), 0);
        }
        if (ticks % Fountain.plumeEvery(v.scale) == 0) plume(level, v, now, budget, random, PLUME_REACH_MIN, PLUME_REACH_MAX);
    }

    /**
     * A puff of the volcanic smoke over the mouth, rising {@code reachMin} to {@code reachMax} blocks, when there is room:
     * the eruptions' own puffs never fill more than a quarter of the smoke's count ({@link SmokeBudget}), nor any puff
     * a full count.
     */
    private static void plume(ClientLevel level, Vent v, long now, int budget, RandomSource random, double reachMin, double reachMax) {
        while (!PUFFS.isEmpty() && PUFFS.peek() <= now) PUFFS.poll();
        if (PUFFS.size() >= budget / 4 || SmokeBudget.alive(now) >= budget) return;
        double reach = reachMin + (reachMax - reachMin) * random.nextDouble();
        PUFFS.add(now + SmokePuff.lifeFor(reach) + 60);
        double[] p = onFloor(v, FLAME_SHARE, random);
        level.addAlwaysVisibleParticle(ERParticles.VOLCANIC_SMOKE.get(), true, p[0], v.mouthY + 0.5, p[1], reach, 0.5, 0);
    }

    /** A point on the crater floor within {@code share} of its radius: its x and z. */
    private static double[] onFloor(Vent v, double share, RandomSource random) {
        double a = random.nextDouble() * Mth.TWO_PI, rr = share * v.r * Math.sqrt(random.nextDouble());
        return new double[]{v.x + rr * Math.cos(a), v.z + rr * Math.sin(a)};
    }

    /**
     * One particle of an eruption's or a bomb's at (x, y, z) moving (dx, dy, dz), seen from {@code d} blocks: within
     * {@link Fountain#NEAR} through the game's own limits (always shown, thinned by the Particles option), farther out
     * forced (the caller has thinned their count by hand, {@link Fountain#farShare}), a spatter drawn larger so it keeps
     * more of its size ({@link Fountain#farSize}).
     */
    static void put(Minecraft mc, ClientLevel level, ParticleOptions type, double d, double x, double y, double z, double dx, double dy, double dz) {
        if (d <= Fountain.NEAR) {
            level.addAlwaysVisibleParticle(type, x, y, z, dx, dy, dz);
            return;
        }
        Particle p = mc.particleEngine.createParticle(type, x, y, z, dx, dy, dz);
        if (p instanceof CaveParticles.Spatter s) s.grow((float) Fountain.farSize(d));
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
