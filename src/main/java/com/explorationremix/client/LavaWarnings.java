package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaNet;
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
 *     <li><b>Rise</b>: a continuous low surge, lava popping and smoking all over its surface, a gentle steady
 *     shake.</li>
 *     <li><b>Full</b>: heavy smoke over the lava while the surge dies away.</li>
 *     <li><b>Drain</b>: hissing and steam off the wet basalt the lava has just left.</li>
 * </ul>
 * The server tells the client where the cycle is ({@link LavaNet.Sync}) on joining, on each phase change and every
 * few seconds; in between the client counts on with its own game time. The shake goes to the view through
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
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos ABOVE = new BlockPos.MutableBlockPos();

    /** A sync from the server (on the main thread). */
    public static void accept(LavaNet.Sync s) {
        sync = s.on() ? s : null;
        cycle = s.on() ? new LavaCycle(s.lengths(), s.rise()) : null;
        ClientLevel level = Minecraft.getInstance().level;
        syncedAt = level != null ? level.getGameTime() : Long.MIN_VALUE;
    }

    /** The server's last word on the cycle (its rows: the lava table, the Galleries' top), or null when it does not run here. */
    @Nullable
    public static LavaNet.Sync lastSync() { return sync; }

    /** Where the cycle is now, as far as this client knows, or null when it does not run here. */
    @Nullable
    private static LavaCycle.State now(ClientLevel level) {
        LavaNet.Sync s = sync;
        if (s == null || cycle == null) return null;
        if (syncedAt == Long.MIN_VALUE) syncedAt = level.getGameTime();
        return cycle.at(s.frozen() ? s.tick() : s.tick() + (level.getGameTime() - syncedAt));
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sync = null;
        cycle = null;
        syncedAt = Long.MIN_VALUE;
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
        if (st != null && s != null && player.getY() >= s.bottom() && player.getY() < s.top() + 2) {
            RandomSource random = level.random;
            int surface = s.tableY() + st.level();
            double above = player.getY() - surface;
            float near = (float) Mth.clamp(1 - above / 64.0, 0.3, 1.0);
            double u = st.progress();
            switch (st.phase()) {
                case WARNING -> {
                    tremor = (float) (0.04 + 0.22 * u) * near;
                    if (--nextRumble <= 0) {
                        rumble(random, (float) (0.35 + 0.65 * u) * near, (float) (0.4 + 0.9 * u) * near);
                        nextRumble = (int) (Mth.lerp(u, 300, 50) * (0.75 + 0.5 * random.nextDouble()));
                    }
                    surface(level, player, random, surface, above, (int) (16 + 48 * u), (float) u);
                }
                case RISE -> {
                    tremor = 0.3F * near;
                    surgeVolume = Math.min(1, st.into() / 100F);
                    if (--nextRumble <= 0) {
                        rumble(random, 0.6F * near, 0.6F * near);
                        nextRumble = 160 + random.nextInt(140);
                    }
                    surface(level, player, random, surface, above, 72, 1);
                }
                case FULL -> {
                    float fade = Math.max(0, 1 - st.into() / 300F);
                    tremor = 0.3F * near * fade;
                    surgeVolume = fade;
                    smoke(level, player, random, surface, above);
                }
                case DRAIN -> cooling(level, player, random, surface, s.tableY() + s.rise(), (float) (1 - 0.7 * u));
                default -> nextRumble = 100;
            }
            if (s.frozen()) {
                tremor = 0;
                jolt = 0;
            }
            surgeVolume *= 0.5F + 0.5F * near;
        }
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
     * The lava's surface near the player, sampled at random columns at the height of its top block: bubbling that
     * bursts more and more as {@code u} goes from 0 to 1, with pops, smoke and embers.
     */
    private static void surface(ClientLevel level, Player player, RandomSource random, int surface, double above, int samples, float u) {
        if (above > 56) return;
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        float share = SmokeBudget.share();
        int y = surface - 1;
        for (int k = 0; k < samples; k++) {
            if (!lavaTop(level, player.getBlockX() + random.nextInt(57) - 28, y, player.getBlockZ() + random.nextInt(57) - 28)) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble(), top = y + 0.9;
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

    /** Heavy smoke over the full lava. */
    private static void smoke(ClientLevel level, Player player, RandomSource random, int surface, double above) {
        if (above > 56 || !ERClientConfig.INSTANCE.caveParticles.get()) return;
        float share = SmokeBudget.share();
        int y = surface - 1;
        for (int k = 0; k < 96; k++) {
            if (!lavaTop(level, player.getBlockX() + random.nextInt(65) - 32, y, player.getBlockZ() + random.nextInt(65) - 32)) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble();
            float r = random.nextFloat();
            ParticleOptions p = r < 0.12F ? ParticleTypes.CAMPFIRE_COSY_SMOKE : r < 0.42F ? ParticleTypes.LARGE_SMOKE : null;
            if (p == null || random.nextFloat() >= share) continue;
            level.addAlwaysVisibleParticle(p, true, x, y + 1.1, z, 0, 0.03 + 0.03 * random.nextDouble(), 0);
        }
    }

    /** Hissing and steam off the basalt the draining lava has just left (the band between its surface and its full height). */
    private static void cooling(ClientLevel level, Player player, RandomSource random, int surface, int full, float strength) {
        if (full <= surface) return;
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        for (int k = 0; k < 80; k++) {
            int y = surface + random.nextInt(full - surface);
            POS.set(player.getBlockX() + random.nextInt(49) - 24, y, player.getBlockZ() + random.nextInt(49) - 24);
            BlockState s = level.getBlockState(POS);
            if (GlowBlock.open(s) || !s.getFluidState().isEmpty() || !s.isFaceSturdy(level, POS, Direction.UP)) continue;
            ABOVE.setWithOffset(POS, Direction.UP);
            if (!GlowBlock.open(level.getBlockState(ABOVE))) continue;
            double x = POS.getX() + random.nextDouble(), z = POS.getZ() + random.nextDouble(), top = y + 1.05;
            if (particles && random.nextFloat() < 0.3F * strength) level.addParticle(ERParticles.STEAM.get(), x, top, z, 0, 0, 0);
            if (hisses > 0 && random.nextFloat() < 0.02F * strength) {
                hisses--;
                level.playLocalSound(x, top, z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.25F + 0.25F * strength,
                        1.2F + 0.8F * random.nextFloat(), false);
            }
        }
    }

    /** True when (x, y, z) is lava with air over it; leaves POS there. */
    private static boolean lavaTop(ClientLevel level, int x, int y, int z) {
        POS.set(x, y, z);
        if (!level.getFluidState(POS).is(FluidTags.LAVA)) return false;
        ABOVE.set(x, y + 1, z);
        return GlowBlock.open(level.getBlockState(ABOVE));
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
