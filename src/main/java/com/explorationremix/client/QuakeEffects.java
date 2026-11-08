package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.quake.Fall;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A quake in the Basalt Galleries on this client (0.9.0, spec 4.5): its shake, its sound and its dust, and the rock that
 * comes down in it. Reached from {@code LavaNet}'s handlers only through {@code DistExecutor}, so a dedicated server
 * never loads it; everything is timed by the level's game time against the server's, so a slow client stays in step
 * and one that comes late plays the rest.
 * <ul>
 *     <li><b>Quakes</b> ({@link LavaNet.Quake}): a big quake rocks the view ({@link Shake#big}, through
 *     {@link CameraShake}), plays the quake's crack and long dry grinding of stone ({@code quake.rumble}) and shakes grit
 *     out of the rock over the player; a tremor or a climb answer shivers the view, crackles ({@code quake.tremor}) and
 *     drops a little grit, in full when it toppled something within 32 blocks of the player and otherwise by the
 *     client's {@code tremorEffects} (by default one in three faintly). All of it is scaled by the player's height: in
 *     full in the Galleries' rows, fading out over the 48 rows above. A held quake does not shake; its dust goes on at
 *     the held moment's strength.</li>
 *     <li><b>Ceiling debris</b> ({@link LavaNet.Debris}): a block about to drop cracks overhead ({@code quake.crack},
 *     heard from far below with its direction) and trickles grit, and grit patters on the floor where it will land; this
 *     is gameplay, so it shows with caveParticles off and at "Minimal" (forced, thinned by hand). A falling block too far
 *     away for the game to draw leaves a streak of grit; it splashes and hisses where it passes the lava's surface, and
 *     lands with crumbs, grey dust and a thud ({@code quake.debris}), or shatters with them on whoever it hits.</li>
 *     <li><b>Creaks</b>: a build a player stands on creaks ({@code quake.creak}) and sheds crumbs from its sides for a
 *     second before it goes.</li>
 * </ul>
 * The dust in the air is atmosphere and follows caveParticles and the Particles option.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class QuakeEffects {
    private QuakeEffects() {}

    /** The quakes' dust falls from this far round the camera, from this high to this high over it. */
    static final double DUST_RADIUS = 12, DUST_FROM = 6, DUST_TO = 16;
    /** Grit a second in a big quake at full strength, and in a tremor felt in full. */
    static final double BIG_DUST = 20, TREMOR_DUST = 3;
    /** A falling block nearer the camera than this the game draws itself; beyond it, a streak of grit shows where it is. */
    static final double STREAK_FROM = 48;
    /** The crack of a block about to drop is heard this far, fading to a quarter, with its direction. */
    static final double CRACK_REACH = 160;
    /** Lava spatter thrown up where a falling block passes the lava's surface, and its hiss's volume. */
    static final int SPLASH_SPATTER = 8;
    static final float SPLASH_VOLUME = 0.7F;
    /** A creak sheds crumbs for this many ticks. */
    static final int CREAK_TICKS = 20;
    /** At most this many new positioned sounds a tick from these effects (Sound Physics Remastered drops a burst). */
    static final int SOUNDS_PER_TICK = 4;
    /** A block is forgotten this long after its drop time if nothing ended it, ticks (the longest fall is about 110). */
    static final int FORGET = 400;
    /**
     * A block warned of is forgotten when the camera is farther from it than a warning reaches: no message about it comes
     * there (a held one would wait for a release that never came, trickling grit for ever).
     */
    static final double FAR = LavaNet.Debris.WARN_RANGE;

    /** The dust a falling block shakes up: gravel's grey (basalt's own dust is black and does not show here). */
    private static final BlockParticleOption DUST = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState());

    /** The quakes this client plays, by their id. */
    private static final Map<Integer, Quake> QUAKES = new HashMap<>();
    /** The ceiling blocks warned of, by their position. */
    private static final Map<Long, Piece> PIECES = new HashMap<>();
    private static final List<Creak> CREAKS = new ArrayList<>();
    /** The level these belong to (a new one has none of them, and another clock). */
    private static WeakReference<ClientLevel> owner = new WeakReference<>(null);
    /** Positioned sounds left this tick. */
    private static int sounds = SOUNDS_PER_TICK;

    /** One quake as this client knows it. */
    private static final class Quake {
        boolean big;
        long start;
        int duration, seed, held = -1;
        float strength;
        boolean felt, sounded;
        @Nullable SoundInstance sound;
        /** Grit owed to the air, carried from tick to tick. */
        double dust;

        void set(LavaNet.Quake q) {
            big = q.kind() == LavaNet.Quake.BIG;
            start = q.start();
            duration = q.duration();
            strength = q.strength();
            seed = q.seed();
            held = q.held();
            felt = q.felt();
        }

        /** Ticks into it at game time {@code now} and the partial tick: the held moment while it is held. */
        double into(long now, float partial) { return held >= 0 ? held : now - start + partial; }

        /** How this player feels it: a big quake in full, a tremor by {@code tremorEffects} and its {@code felt}. */
        double share() { return big ? 1 : Shake.tremorShare(tremorMode(), felt, seed); }
    }

    /** One ceiling block warned of: waiting to drop, or in the air. */
    private static final class Piece {
        final BlockPos pos;
        BlockState state;
        long dropAt;
        int landY, lavaY;
        /** -1 while it runs; else held, at {@link #heldT} on its timeline. */
        int held = -1, heldT;
        boolean splashed;
        /** Grit owed to the trickle, the patter and the streak. */
        double trickle, patter, streak;

        Piece(BlockPos pos) { this.pos = pos; }

        /** Ticks since its drop time (negative while it is warned of), the held moment while it is held. */
        int t(long now) { return held >= 0 ? heldT : (int) Math.max(-100_000L, Math.min(100_000L, now - dropAt)); }

        /** The tick after its drop it lands on its row. */
        int lands() { return Fall.ticks(pos.getY() - landY); }
    }

    /** A build creaking under a player: crumbs off the sides of its top blocks until {@link #until}. */
    private static final class Creak {
        /** The block under the player's feet, and the lowest of the column's blocks that go. */
        final BlockPos pos;
        final int low;
        final long until;
        /** Crumbs owed, carried from tick to tick. */
        double crumbs;

        Creak(BlockPos pos, int low, long until) {
            this.pos = pos;
            this.low = low;
            this.until = until;
        }
    }

    // ================================================================================================ messages

    /** A quake starts, goes on, is held or released, or ends ({@link LavaNet.Quake#END}), on the main thread. */
    public static void accept(LavaNet.Quake q) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        own(level);
        if (q.kind() == LavaNet.Quake.END) {
            Quake e = QUAKES.remove(q.id());
            if (e != null && e.sound != null) mc.getSoundManager().stop(e.sound);
            return;
        }
        // a quake already known (told again on a hold or a release, or after coming back near) only moves on
        Quake e = QUAKES.computeIfAbsent(q.id(), id -> new Quake());
        e.set(q);
        if (e.held < 0 && !e.sounded) {
            e.sounded = true;
            onset(mc, level, e);
        }
    }

    /** A block of rock is about to come down, stays after all, shatters on someone, or a build creaks (on the main thread). */
    public static void debris(LavaNet.Debris d) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        own(level);
        long now = level.getGameTime();
        BlockPos pos = BlockPos.of(d.pos());
        switch (d.kind()) {
            case LavaNet.Debris.WARN -> warn(mc, level, d, pos, now);
            case LavaNet.Debris.CANCEL -> PIECES.remove(d.pos());
            case LavaNet.Debris.SHATTER -> shatter(level, d, pos);
            case LavaNet.Debris.CREAK -> {
                CREAKS.add(new Creak(pos, Math.min(pos.getY(), d.landY()), now + CREAK_TICKS));
                if (spend()) level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, ERSounds.QUAKE_CREAK.get(), SoundSource.BLOCKS,
                        1.0F, 0.9F + 0.2F * level.random.nextFloat(), false);
            }
            default -> {}
        }
    }

    /** A block warned of, or told again (held, or let go): its timeline starts, freezes or moves on. */
    private static void warn(Minecraft mc, ClientLevel level, LavaNet.Debris d, BlockPos pos, long now) {
        Piece pc = PIECES.get(d.pos());
        boolean fresh = pc == null;
        if (fresh) {
            pc = new Piece(pos);
            PIECES.put(d.pos(), pc);
        }
        pc.state = Block.stateById(d.state());
        pc.dropAt = d.dropAt();
        pc.landY = d.landY();
        pc.lavaY = d.lavaY();
        pc.held = d.held();
        // held: in the air as far as it has fallen, or still waiting to drop
        if (pc.held > 0) pc.heldT = Math.min(Fall.ticks(pc.held), pc.lands() - 1);
        else if (pc.held == 0) pc.heldT = (int) Math.max(-1000, Math.min(0, now - pc.dropAt));
        if (fresh && pc.held < 0 && now <= pc.dropAt && spend()) {
            // heard from far below, and from where it is: a positioned sound from 100 blocks up would be out of range
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double dist = cam.distanceTo(Vec3.atCenterOf(pos));
            float volume = (float) Mth.clamp(1 - dist / CRACK_REACH, 0.25, 1);
            mc.getSoundManager().play(new SimpleSoundInstance(ERSounds.QUAKE_CRACK.get().getLocation(), SoundSource.BLOCKS, volume,
                    0.9F + 0.2F * level.random.nextFloat(), level.random, false, 0, SoundInstance.Attenuation.NONE,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, false));
        }
    }

    /** A block broke on someone: crumbs, dust and the thud where it broke, and its timeline ends. */
    private static void shatter(ClientLevel level, LavaNet.Debris d, BlockPos at) {
        BlockState s = Block.stateById(d.state());
        burst(level, s, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 24, 0.25);
        thud(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        // a ceiling block falls straight down its column
        PIECES.values().removeIf(pc -> pc.pos.getX() == at.getX() && pc.pos.getZ() == at.getZ() && pc.pos.getY() >= at.getY());
    }

    /** A quake's sound, once, when it is first seen running (one that has nearly run out is left silent). */
    private static void onset(Minecraft mc, ClientLevel level, Quake e) {
        Player player = mc.player;
        long left = e.duration - (level.getGameTime() - e.start);
        if (player == null || left < Math.min(40, e.duration / 2)) return;
        double f = e.share() * height(level, player);
        float volume = (float) ((e.big ? 0.6 + 0.4 * e.strength : 0.3 + 0.3 * e.strength) * f);
        if (volume < 0.02F) return;
        SoundEvent sound = e.big ? ERSounds.QUAKE_RUMBLE.get() : ERSounds.QUAKE_TREMOR.get();
        // all round the listener: the whole cavern shakes
        e.sound = new SimpleSoundInstance(sound.getLocation(), SoundSource.AMBIENT, Math.min(1, volume), 0.95F + 0.1F * level.random.nextFloat(),
                level.random, false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        mc.getSoundManager().play(e.sound);
    }

    // ================================================================================================ the shake

    /** Adds the quakes' shake now to {@code out} (CameraShake applies the gates). */
    static void shake(Shake out, ClientLevel level, Player player, float partial) {
        if (QUAKES.isEmpty() || owner.get() != level) return;
        double h = height(level, player);
        if (h <= 0) return;
        long now = level.getGameTime();
        for (Quake e : QUAKES.values()) {
            out.quake(e.big, e.into(now, partial), e.duration, e.strength, h, e.share(), e.seed, e.held >= 0);
        }
    }

    /** True while a quake this client plays is held by command (pictures: nothing shakes). */
    static boolean anyHeld() {
        for (Quake e : QUAKES.values()) if (e.held >= 0) return true;
        return false;
    }

    /**
     * How much of a quake this player feels from their height ({@link Shake#height}) by the Galleries' rows from the last
     * Sync; nothing outside the overworld. With the lava cycle off there is no Sync, and the server tells quakes only to
     * players in or near the Galleries, so they are felt in full.
     */
    private static double height(ClientLevel level, Player player) {
        if (level.dimension() != Level.OVERWORLD) return 0;
        LavaNet.Sync s = LavaWarnings.lastSync();
        return s == null ? 1 : Shake.height(player.getY(), s.bottom(), s.top());
    }

    /** The client's {@code tremorEffects} as {@link Shake}'s choices. */
    private static int tremorMode() {
        return switch (ERClientConfig.INSTANCE.tremorEffects.get()) {
            case ALL -> Shake.ALL;
            case WHERE_BLOCKS_FALL -> Shake.WHERE_BLOCKS_FALL;
            case NONE -> Shake.NONE;
        };
    }

    // ================================================================================================ each tick

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null) {
            clear();
            return;
        }
        own(level);
        if (player == null || mc.isPaused()) return;
        sounds = SOUNDS_PER_TICK;
        long now = level.getGameTime();
        RandomSource random = level.random;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (!QUAKES.isEmpty()) quakes(level, player, cam, now, random);
        if (!PIECES.isEmpty()) pieces(level, cam, now, random);
        if (!CREAKS.isEmpty()) creaks(mc, level, now, random);
    }

    /** The quakes' dust in the air round the camera, and forgetting the quakes that have run out. */
    private static void quakes(ClientLevel level, Player player, Vec3 cam, long now, RandomSource random) {
        boolean particles = ERClientConfig.INSTANCE.caveParticles.get();
        double h = height(level, player);
        for (Iterator<Quake> it = QUAKES.values().iterator(); it.hasNext(); ) {
            Quake e = it.next();
            double t = e.into(now, 0);
            if (e.held < 0 && t > e.duration + 20) {
                it.remove();
                continue;
            }
            if (!particles || h <= 0) continue;
            double rate = e.big ? BIG_DUST * e.strength * Shake.bigEnvelope(t, e.duration)
                    : TREMOR_DUST * e.share() * Shake.tremorEnvelope(t, e.duration);
            e.dust += rate * h / 20;
            for (; e.dust >= 1; e.dust--) dust(level, cam, random);
        }
    }

    /**
     * One grit falling out of the rock over the camera: at the drawn height, or under a lower ceiling just under it (the
     * open space over the camera's own level is climbed, so it never falls in a cave beyond the rock).
     */
    private static void dust(ClientLevel level, Vec3 cam, RandomSource random) {
        double a = random.nextDouble() * Mth.TWO_PI, r = DUST_RADIUS * Math.sqrt(random.nextDouble());
        double x = cam.x + r * Math.cos(a), z = cam.z + r * Math.sin(a);
        double y = cam.y + DUST_FROM + random.nextDouble() * (DUST_TO - DUST_FROM);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z), top = Integer.MIN_VALUE;
        for (int by = Mth.floor(cam.y) + 1; by <= Mth.floor(y); by++) {
            if (!GlowBlock.open(level.getBlockState(p.set(bx, by, bz)))) break;
            top = by;
        }
        if (top == Integer.MIN_VALUE) return;
        level.addAlwaysVisibleParticle(ERParticles.GRIT.get(), x, Math.min(y, top + 0.9), z,
                (random.nextDouble() - 0.5) * 0.02, -0.02, (random.nextDouble() - 0.5) * 0.02);
    }

    /** Each ceiling block's timeline: the warning's grit, its streak in the air, its splash, its landing. */
    private static void pieces(ClientLevel level, Vec3 cam, long now, RandomSource random) {
        double rate = forcedRate();
        for (Iterator<Piece> it = PIECES.values().iterator(); it.hasNext(); ) {
            Piece pc = it.next();
            int t = pc.t(now), lands = pc.lands();
            boolean held = pc.held >= 0;
            if (!held && (t >= lands || t > FORGET)) {
                if (t >= lands && t <= lands + 20 && pc.lavaY == Integer.MIN_VALUE) landed(level, pc);
                it.remove();
                continue;
            }
            // one the player has left far behind is no longer told of (a release would not reach it)
            if (cam.distanceToSqr(Vec3.atCenterOf(pc.pos)) > FAR * FAR) {
                it.remove();
                continue;
            }
            double x = pc.pos.getX() + 0.5, z = pc.pos.getZ() + 0.5;
            if (t < 0) {
                // grit trickling from the block's underside, and a patter of it on the floor where it will land
                for (pc.trickle += rate; pc.trickle >= 1; pc.trickle--) {
                    level.addParticle(ERParticles.GRIT.get(), true, pc.pos.getX() + 0.15 + 0.7 * random.nextDouble(), pc.pos.getY() - 0.05,
                            pc.pos.getZ() + 0.15 + 0.7 * random.nextDouble(), 0, -0.02, 0);
                }
                double floor = pc.lavaY != Integer.MIN_VALUE ? pc.lavaY + 1 : pc.landY;
                for (pc.patter += rate; pc.patter >= 1; pc.patter--) {
                    level.addParticle(ERParticles.GRIT.get(), true, x + random.nextGaussian() * 0.6, floor + 0.6 + random.nextDouble(),
                            z + random.nextGaussian() * 0.6, 0, -0.05, 0);
                }
                continue;
            }
            double y = pc.pos.getY() - Fall.y(t);
            if (!held && !pc.splashed && pc.lavaY != Integer.MIN_VALUE && y <= pc.lavaY + 1) {
                pc.splashed = true;
                splash(level, x, pc.lavaY + 1, z, random);
            }
            if (cam.distanceToSqr(x, y + 0.5, z) > STREAK_FROM * STREAK_FROM) {
                // too far for the game to draw the block: grit left behind as it goes
                double v = held ? 0 : Fall.y(t + 1) - Fall.y(t);
                for (pc.streak += rate * 0.5; pc.streak >= 1; pc.streak--) {
                    level.addParticle(ERParticles.GRIT.get(), true, x + (random.nextDouble() - 0.5) * 0.6, y + random.nextDouble(),
                            z + (random.nextDouble() - 0.5) * 0.6, 0, -0.3 * v, 0);
                }
            }
        }
    }

    /** A falling block landed on the floor: crumbs of it, grey dust and the thud. */
    private static void landed(ClientLevel level, Piece pc) {
        double x = pc.pos.getX() + 0.5, y = pc.landY + 0.2, z = pc.pos.getZ() + 0.5;
        burst(level, pc.state, x, y, z, 16, 0.15);
        thud(level, x, y + 0.3, z);
    }

    /** Crumbs of {@code state} thrown out from (x, y, z), and 8 puffs of grey dust. */
    private static void burst(ClientLevel level, BlockState state, double x, double y, double z, int crumbs, double out) {
        RandomSource random = level.random;
        if (!state.isAir()) {
            BlockParticleOption crumb = new BlockParticleOption(ParticleTypes.BLOCK, state);
            for (int i = 0; i < crumbs; i++) {
                double a = random.nextDouble() * Mth.TWO_PI;
                level.addParticle(crumb, x + (random.nextDouble() - 0.5) * 0.8, y + random.nextDouble() * 0.4, z + (random.nextDouble() - 0.5) * 0.8,
                        out * Math.cos(a), out * random.nextDouble(), out * Math.sin(a));
            }
        }
        for (int i = 0; i < 8; i++) {
            level.addParticle(DUST, x + (random.nextDouble() - 0.5) * 1.2, y + random.nextDouble() * 0.8, z + (random.nextDouble() - 0.5) * 1.2, 0, 0, 0);
        }
    }

    /** The landing's thud: heard 32 blocks off. */
    private static void thud(ClientLevel level, double x, double y, double z) {
        if (spend()) level.playLocalSound(x, y, z, ERSounds.QUAKE_DEBRIS.get(), SoundSource.BLOCKS, 2.0F, 0.85F + 0.25F * level.random.nextFloat(), false);
    }

    /**
     * A block going into the lava where it passes the surface: lava spatter thrown up, the lava's own pops and smoke
     * (the splash a lava bomb makes, {@link BombEffects#splash}), and a hiss a little quieter than a bomb's. It sinks to
     * the bed out of sight.
     */
    private static void splash(ClientLevel level, double x, double y, double z, RandomSource random) {
        BombEffects.splash(Minecraft.getInstance(), level, x, y, z, SPLASH_SPATTER, random);
        if (spend()) level.playLocalSound(x, y, z, ERSounds.LAVA_BOMB_FIZZ.get(), SoundSource.BLOCKS, SPLASH_VOLUME, 0.9F + 0.2F * random.nextFloat(), false);
    }

    /** Each creaking build: crumbs off the sides of its top blocks, until it goes. */
    private static void creaks(Minecraft mc, ClientLevel level, long now, RandomSource random) {
        double rate = forcedRate();
        for (Iterator<Creak> it = CREAKS.iterator(); it.hasNext(); ) {
            Creak c = it.next();
            if (now >= c.until) {
                it.remove();
                continue;
            }
            // its top block and the two under it
            int top = c.pos.getY(), low = Math.max(c.low, top - 2);
            for (c.crumbs += rate; c.crumbs >= 1; c.crumbs--) {
                BlockPos at = new BlockPos(c.pos.getX(), low + random.nextInt(top - low + 1), c.pos.getZ());
                // the game's own crumbs off a block's face (forced: the warning shows whatever the options); crack()
                // takes the bounds of the block's shape, which throws for an empty one (a block drawn with no outline,
                // as some climbing blocks of other mods are, or whatever the column holds by now)
                if (level.getBlockState(at).getShape(level, at).isEmpty()) continue;
                mc.particleEngine.crack(at, Direction.from2DDataValue(random.nextInt(4)));
            }
        }
    }

    // ================================================================================================ helpers

    /**
     * How many of the forced warning particles to spawn a tick for each spawner: 2 at "All", thinned by the Particles
     * option by hand (forced particles skip it), never under one every two ticks.
     */
    private static double forcedRate() { return Math.max(0.5, 2 * SmokeBudget.share()); }

    /** Takes one of this tick's positioned sounds; false when they are spent. */
    private static boolean spend() {
        if (sounds <= 0) return false;
        sounds--;
        return true;
    }

    /** Drops everything when the level changes: its quakes, blocks and creaks are the old level's. */
    private static void own(ClientLevel level) {
        if (owner.get() == level) return;
        clear();
        owner = new WeakReference<>(level);
    }

    private static void clear() {
        QUAKES.clear();
        PIECES.clear();
        CREAKS.clear();
    }
}
