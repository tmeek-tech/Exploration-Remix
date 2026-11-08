package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.entity.LavaBomb;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
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
 *     camera; farther out, an ember every other tick, forced and thinned by hand by the Particles option. It stays with
 *     caveParticles off: it shows the danger.</li>
 *     <li><b>The whistle</b> ({@code lava_bomb.whistle}): a loop that follows the bomb, heard 96 blocks off, higher while
 *     it climbs and falling as it comes down, from the moment the bomb comes into this client's world (or into its
 *     hearing) until it is gone.</li>
 *     <li><b>The burst</b>: the server's own blast packet reaches players within 64 blocks of it and plays the game's
 *     explosion there; this adds a burst of spatter and, within 32 blocks, a jolt of the view. Farther out, where no
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
        } else if (even && random.nextFloat() < SmokeBudget.share()) {
            level.addParticle(ERParticles.EMBER.get(), true, x, y, z, 0, 0, 0);
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
