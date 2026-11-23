package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.DeepWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Upper Caves' crickets (0.20.9, Taran: "Change bird noises in the Upper Caves to calm, atmospheric cricket
 * noises"), on each client. The biomes cannot play them, since most of the layer is the pack's own caves, which take the
 * biome of the land above:
 * <ul>
 *     <li><b>The chorus</b> ({@code ambient.upper.crickets}): a stereo bed, a soft far chorus of tree crickets echoing
 *     round the cave (Taran chose it alone, with no nearer chirpers in the loop), played round the listener in an upper
 *     cave. In the mod's own warm upper caves (Root Vaults, Silver Fern Rift, Ivory Root Cavern, sinkholes, oasis
 *     hollows) anywhere; with {@code crickets = EVERY_UPPER_CAVE} also in
 *     the pack's caves above the first band once the player is well underground (no sky light at the eyes, the surface 6
 *     to 14 blocks or more above them). Never in the cold (crickets fall silent there: the frozen caves have their own
 *     draft and ice), in the deep dark, under water or below the first band. It fades in and out over two seconds, as the
 *     biomes' loops do, and is softer in tight tunnels than in big caves: one ray a tick round the player measures how
 *     open the cave is.</li>
 *     <li><b>Crickets close by</b> ({@code ambient.upper.cricket}, mono, so the game places it): while the chorus plays,
 *     a single cricket (two in a big cave) sings from a spot on the rock 7 to 20 blocks off that the player can see, a
 *     short bout of slow chirps, a long rest, another bout, so it stays an accent on the chorus Taran chose for being
 *     soft. Each goes quiet when the player comes within 6.5 blocks, as crickets do, and
 *     sings again once they have been 10 blocks off for three seconds; it is let go past 28 blocks or when the chorus
 *     fades. Out of sight behind rock it is softer.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class UpperCaveSounds {
    private UpperCaveSounds() {}

    /** The mod's own upper caves where crickets sing, by their ids: all but the frozen ones. */
    private static final Set<String> WARM = Set.of("rootvaults", "silver_fern_rift", "ivory_root_cavern", "sinkhole", "oasis_hollow");
    /** The chorus's fade, a share of full volume per tick: two seconds end to end, like the biomes' loops. */
    private static final float FADE = 1 / 40F;
    /** How far a ray looks for rock, in blocks. */
    private static final double REACH = 24;
    private static final double HUSH = 6.5, SING_AGAIN = 10, LET_GO = 28, NEAREST = 7, FARTHEST = 20;

    @Nullable private static Chorus chorus;
    /** The chorus's volume, 0 to 1, as it fades toward the target. */
    private static float level;
    /** How open the cave round the player is: a running mean of the rays' lengths over REACH. */
    private static float open = 0.4F;
    private static final List<Singer> SINGERS = new ArrayList<>();
    private static int nextSearch = 40;
    /** Ticks until the chorus may be started again, should the sound engine have dropped it. */
    private static int retry;

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // leaving the world stops every sound
        chorus = null;
        level = 0;
        SINGERS.clear();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        LocalPlayer player = mc.player;
        if (world == null || player == null) {
            chorus = null;
            level = 0;
            SINGERS.clear();
            return;
        }
        if (mc.isPaused()) return;
        RandomSource random = world.random;
        Vec3 eye = player.getEyePosition();
        // with the ambient or master volume at nothing, play nothing at all: the sound engine drops a sound playing at
        // no volume but keeps ticking it, so starting new ones would only pile them up
        boolean audible = mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0 && mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
        float target = audible ? target(world, player, eye) : 0;
        level = audible ? level + Mth.clamp(target - level, -FADE, FADE) : 0;
        if (target > 0 || level > 0) measureOpenness(world, player, eye, random);
        SoundManager sounds = mc.getSoundManager();
        if (retry > 0) retry--;
        if (level > 0.001F) {
            // the sound engine may have dropped it (a new level, a reload of the sounds): start it again, at most every
            // two seconds
            if ((chorus == null || chorus.isStopped() || !sounds.isActive(chorus)) && retry == 0) {
                if (chorus != null) chorus.end();
                chorus = new Chorus();
                sounds.play(chorus);
                retry = 40;
            }
            if (chorus != null) chorus.set(level * roomGain(open));
        } else if (chorus != null) {
            chorus.end();
            chorus = null;
        }
        singers(world, player, eye, random, sounds);
    }

    /** How loud the chorus should be where the player stands, 0 to 1. */
    private static float target(ClientLevel world, LocalPlayer player, Vec3 eye) {
        ERClientConfig.Crickets mode = ERClientConfig.INSTANCE.crickets.get();
        if (mode == ERClientConfig.Crickets.OFF || world.dimension() != Level.OVERWORLD || !player.isAlive()) return 0;
        if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) return 0;
        BlockPos at = BlockPos.containing(eye);
        if (at.getY() < DeepWorld.packFloor()) return 0;
        // the biome as the game's own ambient sounds find it, so the chorus and the biomes' loops cross at the same place
        Holder<Biome> biome = world.getBiomeManager().getNoiseBiomeAtPosition(player.getX(), player.getY(), player.getZ());
        ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
        if (key != null && key.location().getNamespace().equals(ExplorationRemix.MOD_ID)) return WARM.contains(key.location().getPath()) ? 1 : 0;
        if (mode != ERClientConfig.Crickets.EVERY_UPPER_CAVE) return 0;
        if (biome.is(Biomes.DEEP_DARK) || !biome.value().warmEnoughToRain(at)) return 0;
        return underground(world.getBrightness(LightLayer.SKY, at), world.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()) - at.getY());
    }

    /**
     * How far underground a spot in one of the pack's caves is, for the chorus, 0 to 1: from the sky light at the eyes
     * (sky light, not daylight, so the same by night: none from 2 down, full from 7 up) and how many blocks of ground lie
     * over them (none from 14 up, all from 6 down), so it fades in past a cave's mouth and never plays on the surface or
     * under a tree.
     */
    static float underground(int skyLight, int depth) {
        return smooth((7 - skyLight) / 5F) * smooth((depth - 6) / 8F);
    }

    /** The chorus's volume for how open the cave is: about half in a tight tunnel, full in a big cave. */
    static float roomGain(float open) {
        return 0.55F + 0.45F * smooth((open - 0.12F) / 0.38F);
    }

    /** One ray a tick in a random direction: how far the rock is, averaged over about a second. */
    private static void measureOpenness(ClientLevel world, LocalPlayer player, Vec3 eye, RandomSource random) {
        Vec3 dir = randomDirection(random);
        BlockHitResult hit = world.clip(new ClipContext(eye, eye.add(dir.scale(REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double d = hit.getType() == HitResult.Type.MISS ? REACH : hit.getLocation().distanceTo(eye);
        open += ((float) (d / REACH) - open) * 0.05F;
    }

    // ================================================================================================ crickets close by

    private static void singers(ClientLevel world, LocalPlayer player, Vec3 eye, RandomSource random, SoundManager sounds) {
        boolean wanted = level >= 0.5F && ERClientConfig.INSTANCE.nearCrickets.get();
        for (int i = SINGERS.size() - 1; i >= 0; i--) {
            Singer s = SINGERS.get(i);
            double d = s.pos.distanceTo(eye);
            if (!wanted || d > LET_GO) {
                s.quiet();
                SINGERS.remove(i);
                continue;
            }
            s.tick(world, player, eye, d, random, sounds);
        }
        if (!wanted) {
            nextSearch = 40;
            return;
        }
        // two only in a big cave: two in every cave, with short rests, chirped a quarter of the time in a recording of
        // the game, too busy for the soft chorus
        int most = open > 0.45F ? 2 : 1;
        if (SINGERS.size() < most && --nextSearch <= 0) {
            nextSearch = 20 + random.nextInt(40);
            for (int k = 0; k < 3; k++) {
                Vec3 spot = findSpot(world, player, eye, random);
                if (spot != null) {
                    SINGERS.add(new Singer(spot, 0.92F + 0.16F * random.nextFloat(), 20 + random.nextInt(80)));
                    break;
                }
            }
        }
    }

    /**
     * A spot on the rock in sight of the player for a cricket: where a random ray first meets a block 7 to 20 blocks off,
     * just off its face, in dry air, in the dark (not at a cave's mouth), and not by another cricket. Null if this ray
     * found none.
     */
    @Nullable
    private static Vec3 findSpot(ClientLevel world, LocalPlayer player, Vec3 eye, RandomSource random) {
        Vec3 dir = randomDirection(random);
        BlockHitResult hit = world.clip(new ClipContext(eye, eye.add(dir.scale(FARTHEST)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        double d = hit.getLocation().distanceTo(eye);
        if (d < NEAREST) return null;
        BlockPos air = hit.getBlockPos().relative(hit.getDirection());
        if (!world.getFluidState(hit.getBlockPos()).isEmpty() || !world.getFluidState(air).isEmpty()) return null;
        if (world.getBrightness(LightLayer.SKY, air) > 4) return null;
        Vec3 spot = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.3));
        for (Singer s : SINGERS) if (s.pos.distanceToSqr(spot) < 36) return null;
        return spot;
    }

    private static Vec3 randomDirection(RandomSource random) {
        double y = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - y * y);
        return new Vec3(r * Math.cos(a), y, r * Math.sin(a));
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /**
     * Whether a cricket near the player may sing, tick by tick, from how far off the player is: it goes quiet the moment
     * the player comes within {@link #HUSH} blocks and sings again only once they have kept more than {@link #SING_AGAIN}
     * blocks off for three seconds. In between it stays quiet, so a player at the edge never sets it chirping and
     * stopping.
     */
    static final class Hush {
        /** What a tick changed. */
        enum Change { NONE, FELL_QUIET, MAY_SING }

        /** Ticks the player must keep away before the cricket sings again. */
        static final int AWAY_TICKS = 60;
        private boolean quiet;
        private int away;

        Change tick(double d) {
            if (d < HUSH) {
                away = 0;
                if (quiet) return Change.NONE;
                quiet = true;
                return Change.FELL_QUIET;
            }
            if (!quiet) return Change.NONE;
            away = d > SING_AGAIN ? away + 1 : 0;
            if (away <= AWAY_TICKS) return Change.NONE;
            quiet = false;
            away = 0;
            return Change.MAY_SING;
        }

        boolean quiet() { return quiet; }
    }

    /** One cricket on the rock near the player: sings in bouts, goes quiet as the player comes close. */
    private static final class Singer {
        final Vec3 pos;
        final float pitch;
        final Hush hush = new Hush();
        @Nullable Voice voice;
        int rest, sightCheck;
        float muffle = 1;

        Singer(Vec3 pos, float pitch, int rest) {
            this.pos = pos;
            this.pitch = pitch;
            this.rest = rest;
        }

        void tick(ClientLevel world, LocalPlayer player, Vec3 eye, double d, RandomSource random, SoundManager sounds) {
            switch (hush.tick(d)) {
                case FELL_QUIET -> quiet();
                // a moment's wait before it starts again, as if it were making sure
                case MAY_SING -> rest = 40 + random.nextInt(80);
                case NONE -> {}
            }
            // behind rock it is muffled (blocks' and ambient sounds pass through rock in the game unless a mod muffles them)
            if (--sightCheck <= 0) {
                sightCheck = 20;
                BlockHitResult hit = world.clip(new ClipContext(eye, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                muffle = hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(pos) > 1.0 ? 0.35F : 1;
            }
            if (voice != null) {
                voice.muffle = muffle;
                if (voice.isStopped() || !sounds.isActive(voice)) {
                    // a bout ended: rest a while before the next, four to sixteen seconds, so it never drowns the chorus
                    voice = null;
                    rest = 80 + random.nextInt(240);
                }
            } else if (!hush.quiet() && --rest <= 0) {
                voice = new Voice(pos, pitch, muffle);
                sounds.play(voice);
            }
        }

        /** Stops singing at once, as a cricket does, with a moment's fade so it never clicks. */
        void quiet() {
            if (voice != null) voice.fadeOut();
            voice = null;
        }
    }

    /** The chorus round the listener: a looping stereo bed whose volume this class sets each tick. */
    private static final class Chorus extends AbstractTickableSoundInstance {
        Chorus() {
            super(ERSounds.UPPER_CRICKETS.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        void set(float v) { volume = v; }

        void end() { stop(); }

        @Override
        public void tick() {}

        @Override
        public boolean canStartSilent() { return true; }
    }

    /** A bout of a nearby cricket, at its spot on the rock. */
    private static final class Voice extends AbstractTickableSoundInstance {
        float muffle;
        private float gain;
        private boolean fading;

        Voice(Vec3 at, float pitch, float muffle) {
            super(ERSounds.UPPER_CRICKET.get(), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            x = at.x;
            y = at.y;
            z = at.z;
            this.pitch = pitch;
            this.muffle = muffle;
            gain = muffle;
            volume = gain;
            looping = false;
            delay = 0;
            relative = false;
            attenuation = Attenuation.LINEAR;
        }

        void fadeOut() { fading = true; }

        @Override
        public void tick() {
            if (fading) {
                gain -= 0.25F;
                if (gain <= 0) {
                    volume = 0;
                    stop();
                    return;
                }
            } else {
                gain += Mth.clamp(muffle - gain, -0.08F, 0.08F);
            }
            volume = gain * Math.min(1, level);
        }

        @Override
        public boolean canStartSilent() { return true; }
    }
}
