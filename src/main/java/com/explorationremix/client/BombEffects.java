package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.entity.LavaBomb;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A lava bomb on this client (0.9.0, spec 5.5): its trail and its whistle in flight, and what its burst and fizzle look
 * and sound like (entity events {@link LavaBomb#BURST} and {@link LavaBomb#FIZZLE}). Client only: {@link LavaBomb}
 * reaches it only through {@code DistExecutor}, and its own subscribers are registered for the client alone, so a
 * dedicated server never loads it.
 * <ul>
 *     <li><b>The trail</b>, each tick: an ember and a puff of smoke, and a gob of spatter every other tick, near the
 *     camera; farther out a lava gob each tick, drawn larger by the distance and gone within a second, so the bomb's
 *     arc shows across the Caldera as a short orange streak, and an ember every other tick, forced and thinned by hand
 *     by the Particles option. It stays with caveParticles off: it shows the danger.</li>
 *     <li><b>The whistle</b> ({@code lava_bomb.whistle}): a loop that follows the bomb, heard 96 blocks off, higher while
 *     it climbs and falling as it comes down, from the moment the bomb comes into this client's world (or into its
 *     hearing) until it is gone.</li>
 *     <li><b>The burst</b>: the server's own blast packet reaches players within 64 blocks of it and plays the game's
 *     explosion there; this adds a burst of spatter, lava gobs and fire, a dark cloud of ash and rock dust that billows
 *     out and hangs for several seconds (with caveParticles on), chunks of the ground's own rock thrown out to lie round
 *     the crater a while ({@link CaveParticles.Rubble}, within 64 blocks) and, within 32 blocks, a jolt of the view.
 *     Farther out, where no
 *     packet comes, it is this that shows the blast: the game's large explosion, the spatter and the far blast's sound
 *     ({@code lava_bomb.burst}, heard 128 blocks off, as late as sound travels). The same 64-block test the server makes,
 *     on the player's own position, so a client gets one or the other, never both.</li>
 *     <li><b>The fizzle</b>: a splash of spatter, the lava's own pops and smoke, and a hiss ({@code lava_bomb.fizz}).</li>
 * </ul>
 * The new positioned sounds share the eruptions' cap of four a tick ({@link EruptionEffects#spend}); a whistle refused
 * by it starts on a later tick.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class BombEffects {
    private BombEffects() {}

    /** The whistle's volume: heard 96 blocks off. A bomb's whistle starts once it is within that of the camera. */
    static final float WHISTLE_VOLUME = 6;
    static final double WHISTLE_REACH = 96;
    /** The far blast's volume: heard 128 blocks off. */
    static final float BURST_VOLUME = 8;
    /** The server's blast packet reaches players within this far of the blast (ServerLevel.explode). */
    static final double PACKET_REACH = 64;
    /** Spatter thrown out by a burst, and up by a fizzle. */
    static final int BURST_SPATTER = 30, FIZZLE_SPATTER = 12;
    /** A burst's lava gobs, fireballs, puffs of ash and chunks of rock (0.9.0 pack look fixes). */
    static final int BURST_GOBS = 14, BURST_FIRE = 6, BURST_ASH = 20, BURST_RUBBLE = 24;
    /** Rock chunks are thrown only within this far of the camera (smaller than a pixel beyond). */
    static final double RUBBLE_REACH = 64;
    /** A far bomb's trail of gobs lasts this many ticks. */
    static final int TRAIL_LIFE = 16;
    /** The bomb's middle over its feet. */
    static final double MIDDLE = 0.3;

    /** The bombs whose whistle has not started yet (refused by the sound cap, or out of hearing). */
    private static final List<LavaBomb> WAITING = new ArrayList<>();
    /** The level they belong to. */
    private static WeakReference<ClientLevel> owner = new WeakReference<>(null);

    // ================================================================================================ in flight

    /** Every client tick of a bomb in flight, before it moves: its trail. */
    public static void tick(LavaBomb bomb) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || bomb.level() != level || mc.isPaused()) return;
        RandomSource random = level.random;
        double x = bomb.getX(), y = bomb.getY() + MIDDLE, z = bomb.getZ();
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(new Vec3(x, y, z));
        boolean even = (bomb.tickCount & 1) == 0;
        if (d <= Fountain.NEAR) {
            level.addParticle(ERParticles.EMBER.get(), x + jitter(random), y + jitter(random), z + jitter(random), 0, 0, 0);
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + jitter(random), y + jitter(random), z + jitter(random), 0, 0.02, 0);
            if (even) {
                // a gob shaken off as it tumbles: it falls behind and drips away
                Vec3 v = bomb.getDeltaMovement();
                level.addParticle(ERParticles.LAVA_SPATTER.get(), x + jitter(random), y + jitter(random), z + jitter(random),
                        0.3 * v.x + 0.08 * (random.nextDouble() - 0.5), 0.3 * v.y + 0.05 * random.nextDouble(), 0.3 * v.z + 0.08 * (random.nextDouble() - 0.5));
            }
        } else {
            float share = SmokeBudget.share();
            if (random.nextFloat() < share) {
                // a short streak of gobs, drawn larger by the distance: its arc shows from across the Caldera
                Vec3 v = bomb.getDeltaMovement();
                Particle p = EruptionEffects.put(mc, level, ERParticles.LAVA_GOB.get(), d, x, y, z, 0.1 * v.x, 0.1 * v.y, 0.1 * v.z);
                if (p instanceof CaveParticles.LavaGob g) g.life(TRAIL_LIFE);
            }
            if (even && random.nextFloat() < share) level.addParticle(ERParticles.EMBER.get(), true, x, y, z, 0, 0, 0);
        }
    }

    private static double jitter(RandomSource random) { return (random.nextDouble() - 0.5) * 0.4; }

    /** A bomb comes into this client's world: its whistle starts as soon as there is room for it and it is within hearing. */
    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() || !(event.getEntity() instanceof LavaBomb bomb)) return;
        if (event.getLevel() instanceof ClientLevel level) own(level);
        WAITING.add(bomb);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) { WAITING.clear(); }

    /** Each tick: the whistles waiting start, in the order their bombs came, while this tick has room for them. */
    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WAITING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            WAITING.clear();
            return;
        }
        own(level);
        if (mc.isPaused()) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Iterator<LavaBomb> it = WAITING.iterator(); it.hasNext(); ) {
            LavaBomb b = it.next();
            if (b.isRemoved() || b.level() != level) {
                it.remove();
                continue;
            }
            if (cam.distanceToSqr(b.getX(), b.getY() + MIDDLE, b.getZ()) > WHISTLE_REACH * WHISTLE_REACH) continue;
            if (!EruptionEffects.spend(level)) break;
            mc.getSoundManager().play(new Whistle(b));
            it.remove();
        }
    }

    /** Drops the waiting bombs when the level changes: they are the old level's. */
    private static void own(ClientLevel level) {
        if (owner.get() == level) return;
        WAITING.clear();
        owner = new WeakReference<>(level);
    }

    // ================================================================================================ its end

    /** The bomb burst (entity event 3), where the client has it. */
    public static void burst(LavaBomb bomb) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) return;
        RandomSource random = level.random;
        double x = bomb.getX(), y = bomb.getY() + MIDDLE, z = bomb.getZ();
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(new Vec3(x, y, z));
        double far = Fountain.farShare(d, SmokeBudget.share());
        for (int i = Fountain.count(BURST_SPATTER * far, random.nextDouble()); i > 0; i--) {
            double a = random.nextDouble() * Mth.TWO_PI, out = 0.15 + 0.35 * random.nextDouble();
            EruptionEffects.put(mc, level, ERParticles.LAVA_SPATTER.get(), d, x, y, z, out * Math.cos(a), 0.15 + 0.45 * random.nextDouble(), out * Math.sin(a));
        }
        blast(mc, level, x, y, z, d, far, random);
        // the server's own test for who gets its blast packet: the player's position within 64 blocks of it
        double toPlayer = Math.sqrt(player.distanceToSqr(x, y, z));
        if (toPlayer < PACKET_REACH) {
            // the game's own explosion plays here; a jolt from one close by
            double jolt = Fountain.bombJolt(toPlayer);
            if (jolt > 0) CameraShake.jolt(0, jolt);
            return;
        }
        level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1.0, 0, 0);
        if (EruptionEffects.spend(level)) {
            level.playLocalSound(x, y, z, ERSounds.LAVA_BOMB_BURST.get(), SoundSource.BLOCKS, BURST_VOLUME, 0.85F + 0.25F * random.nextFloat(), true);
        }
    }

    /**
     * What a burst throws up besides the game's explosion and the spatter: lava gobs and fire, a dark cloud of ash and
     * rock dust that billows out and hangs (caveParticles), and chunks of the ground's own rock.
     */
    private static void blast(Minecraft mc, ClientLevel level, double x, double y, double z, double d, double far, RandomSource random) {
        for (int i = Fountain.count(BURST_GOBS * far, random.nextDouble()); i > 0; i--) {
            double a = random.nextDouble() * Mth.TWO_PI, out = 0.15 + 0.3 * random.nextDouble();
            EruptionEffects.put(mc, level, ERParticles.LAVA_GOB.get(), d, x, y, z, out * Math.cos(a), 0.3 + 0.3 * random.nextDouble(), out * Math.sin(a));
        }
        for (int i = Fountain.count(BURST_FIRE * far, random.nextDouble()); i > 0; i--) {
            Particle f = EruptionEffects.put(mc, level, ERParticles.FIREBALL.get(), d, x + jitter(random) * 2, y + jitter(random), z + jitter(random) * 2,
                    jitter(random) * 0.3, 0.04 + 0.08 * random.nextDouble(), jitter(random) * 0.3);
            if (f instanceof CaveParticles.Sized s) s.grow(1.3F);
        }
        if (ERClientConfig.INSTANCE.caveParticles.get()) {
            // the cloud: thrown out low and up, then hanging over the crater for several seconds
            for (int i = Fountain.count(BURST_ASH * far, random.nextDouble()); i > 0; i--) {
                double a = random.nextDouble() * Mth.TWO_PI, out = 0.08 + 0.2 * random.nextDouble();
                EruptionEffects.put(mc, level, ERParticles.BLAST_ASH.get(), d, x + 0.8 * Math.cos(a), y + random.nextDouble(), z + 0.8 * Math.sin(a),
                        out * Math.cos(a), 0.04 + 0.12 * random.nextDouble(), out * Math.sin(a));
            }
        }
        if (d < RUBBLE_REACH) rubble(mc, level, ground(level, x, y, z), x, y, z, Fountain.count(BURST_RUBBLE * SmokeBudget.share(), random.nextDouble()),
                0.12, 0.4, 0.25, 0.6, random);
    }

    /** The rock a bomb burst on: the first solid block at or up to three under it, else basalt. */
    private static BlockState ground(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 3; dy++) {
            BlockState s = level.getBlockState(at.set(x, y - dy, z));
            if (s.blocksMotion()) return s;
        }
        return Blocks.BASALT.defaultBlockState();
    }

    /**
     * {@code n} chunks of {@code state} thrown out from (x, y, z): out {@code outMin} to {@code outMax} blocks a tick
     * and up {@code upMin} to {@code upMax}, to lie where they land a while ({@link CaveParticles.Rubble}). Shared with
     * the ceiling blocks that crash down (QuakeEffects).
     */
    static void rubble(Minecraft mc, ClientLevel level, BlockState state, double x, double y, double z, int n, double outMin, double outMax,
                       double upMin, double upMax, RandomSource random) {
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) return;
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Mth.TWO_PI, out = outMin + (outMax - outMin) * random.nextDouble();
            mc.particleEngine.add(new CaveParticles.Rubble(level, x + 0.3 * Math.cos(a), y + 0.3 * random.nextDouble(), z + 0.3 * Math.sin(a),
                    out * Math.cos(a), upMin + (upMax - upMin) * random.nextDouble(), out * Math.sin(a), state));
        }
    }

    /** The bomb fizzled in a fluid or ran out its time (entity event 4), where the client has it: a splash and a hiss. */
    public static void fizzle(LavaBomb bomb) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        double x = bomb.getX(), y = bomb.getY() + MIDDLE, z = bomb.getZ();
        splash(mc, level, x, y, z, FIZZLE_SPATTER, level.random);
        if (EruptionEffects.spend(level)) {
            level.playLocalSound(x, y, z, ERSounds.LAVA_BOMB_FIZZ.get(), SoundSource.BLOCKS, 1.0F, 0.9F + 0.2F * level.random.nextFloat(), false);
        }
    }

    /**
     * A splash where something hot falls into the lava at (x, y, z): {@code spatter} gobs thrown up 0.3 to 0.6 blocks a
     * tick, the lava's own pops and smoke. Shared with the ceiling debris that falls into the lava (QuakeEffects).
     */
    static void splash(Minecraft mc, ClientLevel level, double x, double y, double z, int spatter, RandomSource random) {
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(new Vec3(x, y, z));
        double far = Fountain.farShare(d, SmokeBudget.share());
        for (int i = Fountain.count(spatter * far, random.nextDouble()); i > 0; i--) {
            double a = random.nextDouble() * Mth.TWO_PI, side = 0.08 * random.nextDouble();
            EruptionEffects.put(mc, level, ERParticles.LAVA_SPATTER.get(), d, x + (random.nextDouble() - 0.5) * 0.6, y, z + (random.nextDouble() - 0.5) * 0.6,
                    side * Math.cos(a), 0.3 + 0.3 * random.nextDouble(), side * Math.sin(a));
        }
        for (int i = 0; i < 4; i++) level.addParticle(ParticleTypes.LAVA, x + (random.nextDouble() - 0.5) * 0.8, y, z + (random.nextDouble() - 0.5) * 0.8, 0, 0, 0);
        for (int i = 0; i < 4; i++) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + (random.nextDouble() - 0.5) * 0.8, y + 0.2, z + (random.nextDouble() - 0.5) * 0.8,
                    0, 0.04 + 0.04 * random.nextDouble(), 0);
        }
    }

    /**
     * A bomb's whistle: a loop that follows it, heard 96 blocks off, higher while it climbs and falling as it comes down
     * ({@link Fountain#whistlePitch}), stopped when the bomb is gone.
     */
    private static final class Whistle extends AbstractTickableSoundInstance {
        private final LavaBomb bomb;

        Whistle(LavaBomb bomb) {
            super(ERSounds.LAVA_BOMB_WHISTLE.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.bomb = bomb;
            this.looping = true;
            this.delay = 0;
            this.volume = WHISTLE_VOLUME;
            follow();
        }

        private void follow() {
            this.x = bomb.getX();
            this.y = bomb.getY() + MIDDLE;
            this.z = bomb.getZ();
            this.pitch = (float) Fountain.whistlePitch(bomb.getDeltaMovement().y);
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public void tick() {
            if (bomb.isRemoved()) stop();
            else follow();
        }
    }
}
