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
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 *     <li><b>The burst</b> (eruption effects, round 2: the game's white explosion puffs made it read as TNT, and the
 *     server no longer sends its blast packet, {@code Blast#burst}): a blaze of white-hot light over the strike for
 *     three ticks ({@link CaveParticles.Flash#HOT}, always shown, as the game's explosion was), a burst of spatter, lava
 *     gobs and fire, a dark cloud of ash 4 to 6 blocks across that billows up over the crater and is gone within 4
 *     seconds ({@link Billow#BLAST_ASH}, with caveParticles on), chunks of the ground's own rock thrown out to bounce
 *     and lie round the crater a while ({@link CaveParticles.Rubble}, within 64 blocks) and, within 32 blocks, a jolt
 *     of the view. Within 64 blocks of the player (the packet's reach) it plays the game's own explosion sound as its
 *     packet did; farther out the far blast's sound ({@code lava_bomb.burst}, heard 128 blocks off, as late as sound
 *     travels). All of it where the bomb struck: the client's bomb may have flown on into the rock a tick past the
 *     server's hit, so its path is traced back to the face it went through ({@link #impact}).</li>
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
    /**
     * Within this far of the player a burst plays the game's own explosion sound: the reach of the blast packet
     * ({@code ServerLevel.explode}) that brought it before the server stopped sending one for bombs.
     */
    static final double PACKET_REACH = 64;
    /** Spatter thrown out by a burst, and up by a fizzle. */
    static final int BURST_SPATTER = 30, FIZZLE_SPATTER = 12;
    /** A burst's lava gobs, fireballs and chunks of rock (0.9.0 pack look fixes; fewer, larger chunks in round 2). */
    static final int BURST_GOBS = 14, BURST_FIRE = 8, BURST_RUBBLE = 9;
    /** The blaze over a burst: a flash this many blocks in half-width, this far over the strike, for this many ticks. */
    static final double FLASH_SIZE = 1.9, FLASH_OVER = 0.7;
    static final int FLASH_LIFE = 3;
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
        Vec3 at = impact(level, bomb);
        double x = at.x, y = at.y, z = at.z;
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        double far = Fountain.farShare(d, SmokeBudget.share());
        for (int i = Fountain.count(BURST_SPATTER * far, random.nextDouble()); i > 0; i--) {
            double a = random.nextDouble() * Mth.TWO_PI, out = 0.15 + 0.35 * random.nextDouble();
            EruptionEffects.put(mc, level, ERParticles.LAVA_SPATTER.get(), d, x, y, z, out * Math.cos(a), 0.15 + 0.45 * random.nextDouble(), out * Math.sin(a));
        }
        blast(mc, level, x, y, z, d, far, random);
        // within the server's old 64-block reach of its blast packet, the game's own explosion sound, as that packet's
        // finalizeExplosion played it (the same volume, pitch and source); a jolt from one close by
        double toPlayer = Math.sqrt(player.distanceToSqr(x, y, z));
        if (toPlayer < PACKET_REACH) {
            level.playLocalSound(x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F,
                    (1.0F + (random.nextFloat() - random.nextFloat()) * 0.2F) * 0.7F, false);
            double jolt = Fountain.bombJolt(toPlayer);
            if (jolt > 0) CameraShake.jolt(0, jolt);
            return;
        }
        if (EruptionEffects.spend(level)) {
            level.playLocalSound(x, y, z, ERSounds.LAVA_BOMB_BURST.get(), SoundSource.BLOCKS, BURST_VOLUME, 0.85F + 0.25F * random.nextFloat(), true);
        }
    }

    /**
     * Where the bomb struck, as near as this client can tell: it flies its own copy of the bomb, which may have gone on a
     * tick or two into the rock past the server's hit before the burst reached it, so its path from three ticks back to
     * one tick on is traced to the first face it passes, and the strike lies a tenth of a block out of that face (as the
     * server's blast's centre does). With no face on that path (it struck a living thing), its middle.
     */
    static Vec3 impact(ClientLevel level, LavaBomb bomb) {
        Vec3 p = new Vec3(bomb.getX(), bomb.getY() + MIDDLE, bomb.getZ());
        Vec3 v = bomb.getDeltaMovement();
        if (v.lengthSqr() < 1e-6) return p;
        BlockHitResult hit = level.clip(new ClipContext(p.subtract(v.scale(3)), p.add(v), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bomb));
        if (hit.getType() != HitResult.Type.BLOCK) return p;
        Direction f = hit.getDirection();
        return hit.getLocation().add(f.getStepX() * 0.1, f.getStepY() * 0.1, f.getStepZ() * 0.1);
    }

    /**
     * What a burst throws up besides the spatter: the blaze over the strike, lava gobs and fire, a dark cloud of ash that
     * billows up over the crater and thins away (caveParticles), and chunks of the ground's own rock.
     */
    private static void blast(Minecraft mc, ClientLevel level, double x, double y, double z, double d, double far, RandomSource random) {
        EruptionEffects.flash(mc, d, x, y + FLASH_OVER, z, FLASH_SIZE, FLASH_LIFE, CaveParticles.Flash.HOT, Fountain.BLAST_FORWARD);
        for (int i = Fountain.count(BURST_GOBS * far, random.nextDouble()); i > 0; i--) {
            double a = random.nextDouble() * Mth.TWO_PI, out = 0.15 + 0.3 * random.nextDouble();
            EruptionEffects.put(mc, level, ERParticles.LAVA_GOB.get(), d, x, y, z, out * Math.cos(a), 0.3 + 0.3 * random.nextDouble(), out * Math.sin(a));
        }
        for (int i = Fountain.count(BURST_FIRE * far, random.nextDouble()); i > 0; i--) {
            Particle f = EruptionEffects.put(mc, level, ERParticles.FIREBALL.get(), d, x + jitter(random) * 2, y + 0.3 + jitter(random), z + jitter(random) * 2,
                    jitter(random) * 0.3, 0.04 + 0.08 * random.nextDouble(), jitter(random) * 0.3);
            if (f instanceof CaveParticles.Sized s) s.grow(1.3F);
        }
        if (ERClientConfig.INSTANCE.caveParticles.get()) {
            // the cloud: many small soft puffs thrown out low and up from a ring round the strike, over the crater
            Billow.Kind k = Billow.BLAST_ASH;
            for (int i = Fountain.count(k.puffs() * far, random.nextDouble()); i > 0; i--) {
                double a = random.nextDouble() * Mth.TWO_PI, out = k.out0() + (k.out1() - k.out0()) * random.nextDouble();
                EruptionEffects.put(mc, level, ERParticles.BLAST_ASH.get(), d, x + k.ring() * Math.cos(a), y + 0.3 + 0.6 * random.nextDouble(),
                        z + k.ring() * Math.sin(a), out * Math.cos(a), k.up0() + (k.up1() - k.up0()) * random.nextDouble(), out * Math.sin(a));
            }
        }
        if (d < RUBBLE_REACH) rubble(mc, level, ground(level, x, y, z), x, y + 0.4, z, Fountain.count(BURST_RUBBLE * SmokeBudget.share(), random.nextDouble()),
                0.12, 0.35, 0.3, 0.55, random);
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
     * and up {@code upMin} to {@code upMax}, to bounce and lie where they land a while ({@link CaveParticles.Rubble},
     * tinted as the rock). Shared with the ceiling blocks that crash down (QuakeEffects).
     */
    static void rubble(Minecraft mc, ClientLevel level, BlockState state, double x, double y, double z, int n, double outMin, double outMax,
                       double upMin, double upMax, RandomSource random) {
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) return;
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Mth.TWO_PI, out = outMin + (outMax - outMin) * random.nextDouble();
            Particle p = mc.particleEngine.createParticle(ERParticles.RUBBLE.get(), x + 0.3 * Math.cos(a), y + 0.3 * random.nextDouble(), z + 0.3 * Math.sin(a),
                    out * Math.cos(a), upMin + (upMax - upMin) * random.nextDouble(), out * Math.sin(a));
            if (p instanceof CaveParticles.Rubble r) r.tint(state);
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
