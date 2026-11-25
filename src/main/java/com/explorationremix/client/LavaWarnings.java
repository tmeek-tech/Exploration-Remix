package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.lava.LavaPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * The lava cycle's warning signs, on each client: no clock on the screen, only the layer itself telling you, as the
 * rain does in Rain World. Played only in the Basalt Galleries, and stronger the nearer you are to the lava's surface.
 * <ul>
 *     <li><b>Warning</b> (the lava still at its table): deep rumbles through the rock, far apart at first and closer
 *     and louder toward the rise, each with a jolt of the view over a slight tremor that grows; the lava's surface
 *     bubbling and bursting more and more, with pops, smoke and embers.</li>
 *     <li><b>Rise</b>, in climbs with breaks between them (0.21.6): while it climbs, a continuous low surge, lava popping
 *     and smoking all over its surface and a gentle steady shake; in a break the lava holds, the surge sinks and the
 *     surface settles, then stirs again, and a rumble with a jolt of the view comes just before the next climb.</li>
 *     <li><b>Full</b>: heavy smoke over the lava while the surge dies away.</li>
 *     <li><b>Fall</b> (the drain, now one quick drop): a deep rumble and a jolt as it goes, the surge with it, and
 *     hissing and steam off the wet basalt the lava has just left, which go on cooling a while into the calm.</li>
 * </ul>
 * The server tells the client where the cycle is ({@link LavaNet.Sync}) on joining, on each phase change and every
 * few seconds; in between the client counts on with its own game time, and so does the client's lava plane
 * ({@link LavaPlane#CLIENT}, set here from each sync), which the warnings follow. The shake goes to the view through
 * {@link CameraShake}, with the quakes' and eruptions' (the client's screenShake and shakeStrength and the game's
 * Distortion Effects, none while the clock is held, for pictures); particles follow caveParticles.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class LavaWarnings {
    private LavaWarnings() {}

    @Nullable private static LavaNet.Sync sync;
    @Nullable private static LavaCycle cycle;
    /** The client's game time when the last sync came, or MIN_VALUE until a level is there to read it from. */
    private static long syncedAt = Long.MIN_VALUE;
    private static int ticks;
    /** The view's shake in degrees, now and a tick ago (CameraShake draws between them), and the jolt decaying in it. */
    private static float shake, shakeBefore, jolt;
    private static int nextRumble = 100;
    /** Sounds left this second, so a busy surface does not pile up pops and hisses. */
    private static int pops, hisses;
    @Nullable private static Surge surge;
    /** The break (by its climb) whose warning before the next climb has played, or -1; the phase a tick ago. */
    private static int warnedClimb = -1;
    @Nullable private static LavaCycle.Phase lastPhase;
    /** A break stirs again over its last this many ticks, and the rumble before the next climb comes this many before it. */
    static final int STIR = 120, WARN_AHEAD = 40;
    /** The wet basalt goes on steaming this many ticks into the calm after the fall. */
    static final int COOL_AFTER = 400;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos ABOVE = new BlockPos.MutableBlockPos();

    /** A sync from the server (on the main thread). */
    public static void accept(LavaNet.Sync s) {
        sync = s.on() ? s : null;
        cycle = s.on() ? s.cycle() : null;
        ClientLevel level = Minecraft.getInstance().level;
        syncedAt = level != null ? level.getGameTime() : Long.MIN_VALUE;
        plane();
    }

    /** The client's lava plane follows the last sync from the moment it came (off without one, or before a level is there). */
    private static void plane() {
        LavaNet.Sync s = sync;
        LavaCycle c = cycle;
        if (s == null || c == null) {
            LavaPlane.CLIENT.off();
            return;
        }
        if (syncedAt == Long.MIN_VALUE) return;
        LavaPlane.CLIENT.set(c, s.tableY(), s.top(), s.bottom(), s.tick(), syncedAt, s.frozen());
    }

    /** The server's last word on the cycle (its rows: the lava table, the Galleries' top), or null when it does not run here. */
    @Nullable
    public static LavaNet.Sync lastSync() { return sync; }

    /** Where the cycle is now, as far as this client knows, or null when it does not run here. */
    @Nullable
    private static LavaCycle.State now(ClientLevel level) {
        LavaNet.Sync s = sync;
        if (s == null || cycle == null) return null;
        if (syncedAt == Long.MIN_VALUE) {
            syncedAt = level.getGameTime();
            plane();
        }
        return cycle.at(s.frozen() ? s.tick() : s.tick() + (level.getGameTime() - syncedAt));
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sync = null;
        cycle = null;
        syncedAt = Long.MIN_VALUE;
        LavaPlane.CLIENT.off();
        warnedClimb = -1;
        lastPhase = null;
        shake = shakeBefore = jolt = 0;
        // leaving the world stops every sound
        surge = null;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || mc.isPaused()) return;
        ticks++;
        shakeBefore = shake;
        if (ticks % 20 == 0) {
            pops = 4;
            hisses = 3;
        }
        LavaNet.Sync s = sync;
        LavaCycle.State st = level.dimension() == Level.OVERWORLD ? now(level) : null;
        float tremor = 0, surgeVolume = 0;
        if (st != null && s != null && cycle != null && player.getY() >= s.bottom() && player.getY() < s.top() + 2) {
            RandomSource random = level.random;
            // the lava's surface: the plane's while it is up (0.21.6), else the top of the table's own lava blocks
            double plane = LavaPlane.CLIENT.surface(level.getGameTime(), 0);
            boolean up = !Double.isNaN(plane);
            double surface = up ? plane : s.tableY() - LavaCycle.SURFACE_DROP;
            double above = player.getY() - surface;
            float near = (float) Mth.clamp(1 - above / 64.0, 0.3, 1.0);
            double u = st.progress();
            int full = s.tableY() + s.rise();
            switch (st.phase()) {
                case WARNING -> {
                    tremor = (float) (0.04 + 0.22 * u) * near;
                    if (--nextRumble <= 0) {
                        rumble(random, (float) (0.35 + 0.65 * u) * near, (float) (0.4 + 0.9 * u) * near);
                        nextRumble = (int) (Mth.lerp(u, 300, 50) * (0.75 + 0.5 * random.nextDouble()));
                    }
                    surface(level, player, random, surface, up, above, (int) (16 + 48 * u), (float) u);
                    warnedClimb = -1;
                }
                case RISE -> {
                    LavaCycle.Stage sg = cycle.stage(st);
                    if (sg == null || sg.climbing()) {
                        // a climb: the surge, a steady shake, the surface popping all over
                        tremor = 0.3F * near;
                        // it swells in over two seconds, from where the break left it after the first climb
                        surgeVolume = sg == null ? 1 : (float) Math.min(1, (sg.index() > 0 ? 0.5 : 0) + sg.into() / 40);
                        if (--nextRumble <= 0) {
                            rumble(random, 0.6F * near, 0.6F * near);
                            nextRumble = 160 + random.nextInt(140);
                        }
                        surface(level, player, random, surface, up, above, 72, 1);
                    } else {
                        // a break: the lava holds, the surge sinks and the surface settles; over the break's last
                        // seconds it stirs again, and a rumble with a jolt comes just before the next climb
                        double left = sg.left();
                        float stir = (float) Mth.clamp(1 - left / STIR, 0, 1);
                        tremor = (0.06F + 0.16F * stir) * near;
                        surgeVolume = 0.12F + 0.4F * stir * stir;
                        surface(level, player, random, surface, up, above, (int) (12 + 52 * stir), 0.15F + 0.85F * stir);
                        if (warnedClimb != sg.index() && left <= WARN_AHEAD && !s.frozen()) {
                            rumble(random, 0.9F * near, 1.2F * near);
                            warnedClimb = sg.index();
                        }
                        nextRumble = 120 + random.nextInt(80);
                    }
                }
                case FULL -> {
                    float fade = Math.max(0, 1 - st.into() / 300F);
                    tremor = 0.3F * near * fade;
                    surgeVolume = fade;
                    smoke(level, player, random, surface, up, above);
                }
                case DRAIN -> {
                    // the fall: the sea drops away at once, with one deep rumble and a jolt as it goes
                    if (lastPhase == LavaCycle.Phase.FULL && !s.frozen()) rumble(random, 1.0F * near, 1.4F * near);
                    tremor = (float) (0.28 * (1 - u)) * near;
                    surgeVolume = (float) (0.8 * (1 - u));
                    cooling(level, player, random, surface, full, (float) (1 - 0.5 * u));
                }
                default -> {
                    nextRumble = 100;
                    warnedClimb = -1;
                    // the basalt the fall left goes on steaming a while into the calm
                    if (st.into() < COOL_AFTER) cooling(level, player, random, surface, full, 0.5F * (1 - st.into() / (float) COOL_AFTER));
                }
            }
            if (s.frozen()) {
                tremor = 0;
                jolt = 0;
            }
            surgeVolume *= 0.5F + 0.5F * near;
        }
        lastPhase = st == null ? null : st.phase();
        jolt *= 0.93F;
        shake += (tremor + jolt - shake) * 0.15F;
        surge(mc, surgeVolume);
    }

    /**
     * The lava's shake now, in degrees, between the last two ticks: CameraShake adds it to the view with the lava's own
     * waveform ({@link Shake#lava}) on the lava's clock ({@link #clock}), after its gates (screenShake, shakeStrength,
     * Distortion Effects, the held clock).
     */
    static float amplitude(float partialTick) { return Mth.lerp(partialTick, shakeBefore, shake); }

    /** The lava's waveform's clock: this client's unpaused ticks and the partial tick. */
    static double clock(float partialTick) { return ticks + partialTick; }

    // ================================================================================================ the signs

    /** A rumble through the rock, all round the listener, and a jolt of the view. */
    private static void rumble(RandomSource random, float volume, float joltDegrees) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ERSounds.LAVA_RUMBLE.get().getLocation(), SoundSource.AMBIENT,
                Math.min(1, volume), 0.8F + 0.25F * random.nextFloat(), random, false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        jolt += joltDegrees;
    }

    /**
     * The lava's surface near the player, sampled at random columns at its height ({@code surface}: the plane's while
     * it is {@code up}, else the table's lava): bubbling that bursts more and more as {@code u} goes from 0 to 1, with
     * pops, smoke and embers.
     */
    private static void surface(ClientLevel level, Player player, RandomSource random, double surface, boolean up, double above, int samples, float u) {
        if (above > 56) return;
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        float share = SmokeBudget.share();
        for (int k = 0; k < samples; k++) {
            if (!surfaceAt(level, player.getBlockX() + random.nextInt(57) - 28, player.getBlockZ() + random.nextInt(57) - 28, surface, up)) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble(), top = surface;
            if (random.nextFloat() < 0.06F + 0.25F * u) {
                // a burst: a bubble breaking, throwing up lava and smoke
                if (particles) {
                    // small particles follow the player's Particles option and distance limit; the smoke, seen from the
                    // ledges far above, is forced and thinned by the option by hand
                    for (int j = 1 + random.nextInt(2); j > 0; j--) level.addParticle(ParticleTypes.LAVA, x, top, z, 0, 0, 0);
                    for (int j = 0; j < 2; j++) if (random.nextFloat() < share) level.addParticle(ParticleTypes.LARGE_SMOKE, true, x, top + 0.2, z, 0, 0.04 + 0.03 * random.nextDouble(), 0);
                    for (int j = 0; j < 3; j++) level.addParticle(ERParticles.EMBER.get(), x, top + 0.1, z, 0, 0, 0);
                }
                if (pops > 0 && random.nextFloat() < 0.5F) {
                    pops--;
                    level.playLocalSound(x, top, z, random.nextFloat() < 0.8F ? SoundEvents.LAVA_POP : SoundEvents.LAVA_AMBIENT, SoundSource.BLOCKS,
                            0.5F + 0.4F * u, 0.7F + 0.4F * random.nextFloat(), false);
                }
            } else if (particles && random.nextFloat() < 0.2F) {
                level.addParticle(ParticleTypes.SMOKE, x, top + 0.1, z, 0, 0.02, 0);
            }
        }
    }

    /** Heavy smoke over the full lava, and now and then a pop off its surface (the plane has no lava blocks to pop). */
    private static void smoke(ClientLevel level, Player player, RandomSource random, double surface, boolean up, double above) {
        if (above > 56 || !ERClientConfig.INSTANCE.caveParticles.get()) return;
        float share = SmokeBudget.share();
        for (int k = 0; k < 96; k++) {
            if (!surfaceAt(level, player.getBlockX() + random.nextInt(65) - 32, player.getBlockZ() + random.nextInt(65) - 32, surface, up)) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble();
            if (random.nextFloat() < 0.03F) {
                level.addParticle(ParticleTypes.LAVA, x, surface, z, 0, 0, 0);
                if (pops > 0 && random.nextFloat() < 0.3F) {
                    pops--;
                    level.playLocalSound(x, surface, z, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.3F, 0.8F + 0.3F * random.nextFloat(), false);
                }
            }
            float r = random.nextFloat();
            ParticleOptions p = r < 0.12F ? ParticleTypes.CAMPFIRE_COSY_SMOKE : r < 0.42F ? ParticleTypes.LARGE_SMOKE : null;
            if (p == null || random.nextFloat() >= share) continue;
            level.addAlwaysVisibleParticle(p, true, x, surface + 0.2, z, 0, 0.03 + 0.03 * random.nextDouble(), 0);
        }
    }

    /** Hissing and steam off the basalt the draining lava has just left (the band between its surface and its full height). */
    private static void cooling(ClientLevel level, Player player, RandomSource random, double surface, int full, float strength) {
        int from = Mth.ceil(surface);
        if (full <= from) return;
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        for (int k = 0; k < 80; k++) {
            int y = from + random.nextInt(full - from);
            POS.set(player.getBlockX() + random.nextInt(49) - 24, y, player.getBlockZ() + random.nextInt(49) - 24);
            BlockState s = level.getBlockState(POS);
            if (s.isAir() || !s.getFluidState().isEmpty() || !s.isFaceSturdy(level, POS, Direction.UP)) continue;
            ABOVE.setWithOffset(POS, Direction.UP);
            if (!level.getBlockState(ABOVE).isAir()) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble(), top = y + 1.05;
            if (particles && random.nextFloat() < 0.3F * strength) level.addParticle(ERParticles.STEAM.get(), x, top, z, 0, 0, 0);
            if (hisses > 0 && random.nextFloat() < 0.02F * strength) {
                hisses--;
                level.playLocalSound(x, top, z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.25F + 0.25F * strength,
                        1.2F + 0.8F * random.nextFloat(), false);
            }
        }
    }

    /**
     * True when the lava's surface shows in column (x, z): while the plane is {@code up}, the cell at its {@code surface}
     * is open (air or the glow, not inside rock); else the table's top lava block has air over it. Leaves POS there.
     */
    private static boolean surfaceAt(ClientLevel level, int x, int z, double surface, boolean up) {
        if (up) {
            POS.set(x, Mth.floor(surface), z);
            return level.getBlockState(POS).isAir();
        }
        return lavaTop(level, x, Mth.floor(surface), z);
    }

    /** True when (x, y, z) is lava with air over it; leaves POS there. */
    private static boolean lavaTop(ClientLevel level, int x, int y, int z) {
        POS.set(x, y, z);
        if (!level.getFluidState(POS).is(FluidTags.LAVA)) return false;
        ABOVE.set(x, y + 1, z);
        return level.getBlockState(ABOVE).isAir();
    }

    /**
     * Keeps the rise's surge sound playing at {@code volume}, starting it when needed; it fades out by itself. A surge
     * the sound engine dropped (a resource reload, a sound device change) is started again.
     */
    private static void surge(Minecraft mc, float volume) {
        if (surge != null && (surge.isStopped() || ticks % 20 == 0 && !mc.getSoundManager().isActive(surge))) surge = null;
        if (volume <= 0.01F) {
            if (surge != null) surge.target = 0;
            return;
        }
        if (surge == null) {
            surge = new Surge();
            mc.getSoundManager().play(surge);
        }
        surge.target = Math.min(1, volume);
    }

    /** The rise's continuous low surge: loops all round the listener, following its target volume. */
    private static final class Surge extends AbstractTickableSoundInstance {
        float target;

        Surge() {
            super(ERSounds.LAVA_SURGE.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            volume += (target - volume) * 0.04F;
            if (target <= 0 && volume < 0.01F) stop();
        }
    }
}
