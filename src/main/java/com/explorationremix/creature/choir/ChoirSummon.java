package com.explorationremix.creature.choir;

import com.explorationremix.block.ChoirLook;
import com.explorationremix.block.ChoirBrazierBlock;
import com.explorationremix.block.KeySocketBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.ChoirArena;
import com.explorationremix.world.terrain.ChoirSites;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Hollow Choir's arena does: taking the three keys, calling the Choir, dressing the bowl as the fight
 * goes on, the fight's own strikes on the world, and putting the arena back afterwards (0.21.9).
 *
 * <p>None of it is remembered anywhere. The arena is a pure function of its site ({@link ChoirArena}) and the fight's
 * dressing a pure function of the phase, so a reset is only laying phase 0 again, and nothing has to survive a reload
 * but the three sockets' own blockstates and the Choir itself.
 */
public final class ChoirSummon {
    /** How far from the dais the toll of an idle arena is heard (Taran's pick). */
    public static final int HEARD = 150;
    /** How many sprouts a phase raises, and how far out over the floor they come (the dais itself stays clear). */
    private static final int SPROUTS = 8;
    private static final double SPROUT_R = ChoirSites.FLOOR_R * 0.8;
    private static final long SALT_SPROUT = 7001;
    /**
     * How far from the dais a sculk shrieker is struck dumb ({@link #hush}): as far out as a fighter still counts as
     * fighting, plus the eight blocks a sculk sensor hears them over and the eight more a shrieker hears that sensor
     * over, so no noise made in the fight can reach one that still has its call.
     */
    public static final double HUSH_R = HollowChoirEntity.KEEP + 2 * 8 + 1;
    /** The Choir's own pale blue for the lines it puts over the hotbar: never aqua, which is cyan. */
    private static final Style PALE = Style.EMPTY.withColor(ChoirLook.GLOW);

    private ChoirSummon() {}

    // --------------------------------------------------------------------------------------------- finding the arena

    /** The arena a block of it belongs to, or null when that block is not part of one. */
    @Nullable
    public static ChoirSites.Site siteAt(Level level, BlockPos pos) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed() || !level.dimension().equals(Level.OVERWORLD)) return null;
        ChoirSites.Site s = t.network.choirSite(t.plans.cellOf(pos.getX()), t.plans.cellOf(pos.getZ()));
        if (s == null) return null;
        if (Math.hypot(pos.getX() + 0.5 - s.x(), pos.getZ() + 0.5 - s.z()) > ChoirSites.RIM_R + ChoirArena.APRON + 2) return null;
        if (Math.abs(pos.getY() - s.rimY()) > ChoirSites.DEPTH + 8) return null;
        return s;
    }

    /** The Choir already fighting in an arena, or null. */
    @Nullable
    public static HollowChoirEntity fighting(Level level, ChoirSites.Site s) {
        for (HollowChoirEntity e : level.getEntitiesOfClass(HollowChoirEntity.class,
                new net.minecraft.world.phys.AABB(s.x() - 48, s.floorY() - 8, s.z() - 48,
                        s.x() + 48, s.rimY() + 32, s.z() + 48)))
            if (e.isAlive()) return e;
        return null;
    }

    /** How many of an arena's three sockets hold a key. */
    public static int filled(Level level, ChoirSites.Site s) {
        int n = 0;
        for (int[] c : ChoirArena.sockets(s)) {
            BlockState st = level.getBlockState(new BlockPos(c[0], (int) s.rimY() + 1, c[1]));
            if (st.is(ERBlocks.KEY_SOCKET.get()) && st.getValue(KeySocketBlock.FILLED)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------- taking the keys

    /** A key has just gone into a socket: say how it stands, and call the Choir once all three are in. */
    public static void keySet(Level level, BlockPos pos, Player player) {
        level.playSound(null, pos, ERSounds.CHOIR_KEY_SET.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        ChoirSites.Site s = siteAt(level, pos);
        if (s == null || !(level instanceof ServerLevel sl)) return;
        // the first key is already a promise of a fight here, so it is the first key that takes the Warden out of it,
        // and the line over the hotbar says so the once it happens
        boolean hushed = hush(sl, s) > 0;
        int n = filled(level, s);
        if (n < 3) {
            player.displayClientMessage(Component.translatable(hushed ? "message.explorationremix.choir.keys_hush"
                    : "message.explorationremix.choir.keys", n, 3).withStyle(PALE), true);
            return;
        }
        summon(sl, s, player);
    }

    /** Using a filled socket after a lost fight: the Choir comes again and nothing more is spent. */
    public static void relight(Level level, BlockPos pos, Player player) {
        ChoirSites.Site s = siteAt(level, pos);
        if (s == null || !(level instanceof ServerLevel sl)) return;
        if (fighting(level, s) != null) return;
        if (filled(level, s) < 3) {
            player.displayClientMessage(Component.translatable("message.explorationremix.choir.keys",
                    filled(level, s), 3).withStyle(PALE), true);
            return;
        }
        summon(sl, s, player);
    }

    /** Using an empty socket with no key in hand: say what it wants. */
    public static void sayWhatIsWanted(Level level, Player player) {
        if (level.isClientSide) return;
        player.displayClientMessage(Component.translatable("message.explorationremix.choir.need")
                .withStyle(ChatFormatting.GRAY), true);
    }

    /** Calls the Choir into an arena: the braziers blaze, the bowl rings, and the crown comes down from the dark. */
    public static void summon(ServerLevel level, ChoirSites.Site s, @Nullable Player by) {
        if (fighting(level, s) != null) return;
        HollowChoirEntity choir = EREntities.HOLLOW_CHOIR.get().create(level);
        if (choir == null) return;
        hush(level, s);
        dress(level, s, 0);
        choir.settle(s, s.ceil());
        choir.setPos(s.x(), s.floorY() + HollowChoirEntity.CROWN_UP, s.z());
        choir.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(s.x(), s.floorY(), s.z())),
                MobSpawnType.TRIGGERED, null, null);
        level.addFreshEntity(choir);
        level.playSound(null, s.x(), s.floorY() + 3, s.z(), ERSounds.CHOIR_WAKE.get(), SoundSource.HOSTILE, 2.0F, 1.0F);
        // the bowl rings: circles of its motes out over the whole floor, one inside the next
        for (double r = 3; r < ChoirSites.FLOOR_R; r += 3) ring(level, s, r, (int) (r * 5));
        if (by != null) by.displayClientMessage(Component.translatable("message.explorationremix.choir.woken")
                .withStyle(PALE), true);
    }

    // --------------------------------------------------------------------------------------- keeping the Warden out

    /**
     * Strikes dumb every sculk shrieker round an arena (0.22.0), and says how many it found with their call still in
     * them. A shrieker struck dumb still shrieks at a noise, but it warns nobody, darkens nothing and can never call
     * the Warden: it is what vanilla already does to the shriekers a catalyst grows ({@code can_summon=false}).
     *
     * <p>Why it is needed: the arena stands in the Deep Dark, and vanilla's sculk patches grow sensors and shriekers
     * wherever they like, the rim walk included. The test arena had two shriekers four blocks past its rim with a
     * sensor between them and the bowl, so a fight, which is nothing but noise, would have had the Warden climb out
     * of the floor in the middle of it. The Choir is the fight here and nothing of the Warden's belongs in it.
     *
     * <p>It happens when a key goes into a socket and not before (Taran's card), so the walk to an arena is as
     * dangerous as the rest of the Deep Dark, and the hush is something the keys do. It stays done: the blockstate is
     * the whole record. Only chunks that are loaded are looked at, and the Choir asks again as the fight goes on
     * ({@link HollowChoirEntity}), so a chunk that comes in late is hushed too.
     */
    public static void hush(HollowChoirEntity choir) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        ChoirSites.Site s = siteAt(sl, BlockPos.containing(choir.daisX(), choir.floorY(), choir.daisZ()));
        if (s != null) hush(sl, s);
    }

    public static int hush(ServerLevel level, ChoirSites.Site s) {
        double mx = s.x(), my = s.floorY() + 2, mz = s.z();
        int r = (int) Math.ceil(HUSH_R);
        List<BlockPos> found = new ArrayList<>();
        for (int cx = (int) Math.floor(mx - r) >> 4; cx <= (int) Math.floor(mx + r) >> 4; cx++)
            for (int cz = (int) Math.floor(mz - r) >> 4; cz <= (int) Math.floor(mz + r) >> 4; cz++) {
                // never load or generate a chunk for this: what is not there yet cannot hear the fight either
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (java.util.Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (!(e.getValue() instanceof SculkShriekerBlockEntity)) continue;
                    BlockPos p = e.getKey();
                    if (p.distToCenterSqr(mx, my, mz) > HUSH_R * HUSH_R) continue;
                    BlockState st = chunk.getBlockState(p);
                    if (st.is(Blocks.SCULK_SHRIEKER) && st.getValue(SculkShriekerBlock.CAN_SUMMON)) found.add(p.immutable());
                }
            }
        for (BlockPos p : found) {
            level.setBlock(p, level.getBlockState(p).setValue(SculkShriekerBlock.CAN_SUMMON, false), 2);
            level.playSound(null, p, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS, 0.8F, 0.6F);
            motes(level, p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 0.6, 14);
        }
        return found.size();
    }

    // -------------------------------------------------------------------------------------------- dressing the bowl

    /**
     * Lays the arena out for a phase: the sculk comes as far out as that phase reaches, the braziers of the rings it
     * has taken go out, and its sprouts rise. Phase 0 is the arena at rest, so a reset is this with 0.
     */
    public static void dress(HollowChoirEntity choir, int phase) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        ChoirSites.Site s = siteAt(sl, BlockPos.containing(choir.daisX(), choir.floorY(), choir.daisZ()));
        if (s != null) dress(sl, s, phase);
    }

    public static void dress(ServerLevel level, ChoirSites.Site s, int phase) {
        int r = (int) Math.ceil(ChoirSites.RIM_R);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(s.x()) - r; x <= s.x() + r; x++)
            for (int z = (int) Math.floor(s.z()) - r; z <= s.z() + r; z++) {
                int y = ChoirArena.topY(s, x + 0.5, z + 0.5);
                if (y == ChoirArena.NONE) continue;
                if (Math.hypot(x + 0.5 - s.x(), z + 0.5 - s.z()) > ChoirSites.RIM_R) continue;
                p.set(x, y, z);
                BlockState now = level.getBlockState(p);
                // only the arena's own stone is dressed: nothing a player has built is touched
                if (!arenaStone(now)) continue;
                BlockState want = phase <= 0 ? ChoirArena.surface(s, x, z)
                        : ChoirArena.sculkAt(s, x, z, phase) ? Blocks.SCULK.defaultBlockState() : ChoirArena.surface(s, x, z);
                if (!now.is(want.getBlock())) level.setBlock(p, want, 2);
            }
        // the braziers, outwards in: the seats' ring goes out in phase 2, the floor's in phase 3, and the three on the
        // dais burn to the end, so the bowl closes in on a lit dais rather than on the dark
        int[][] br = ChoirArena.braziers(s);
        for (int i = 0; i < br.length; i++) {
            boolean lit = ChoirArena.brazierLit(i, phase);
            p.set(br[i][0], br[i][2], br[i][1]);
            BlockState st = level.getBlockState(p);
            if (st.is(ERBlocks.CHOIR_BRAZIER.get()) && st.getValue(ChoirBrazierBlock.LIT) != lit)
                level.setBlock(p, st.setValue(ChoirBrazierBlock.LIT, lit), 2);
        }
        // the sprouts: raised from phase 2, and cleared away at rest. A sprout brings its own sculk with it, since
        // the phase's own creep has not always reached that far in; the column loop above takes that back at phase 0.
        for (BlockPos sp : sproutSpots(s)) {
            BlockState st = level.getBlockState(sp);
            boolean want = phase >= 2;
            if (want && st.isAir() && arenaStone(level.getBlockState(sp.below()))) {
                level.setBlock(sp.below(), Blocks.SCULK.defaultBlockState(), 2);
                level.setBlock(sp, ERBlocks.CHOIR_SPROUT.get().defaultBlockState(), 2);
                level.playSound(null, sp, ERSounds.CHOIR_SPROUT.get(), SoundSource.HOSTILE, 0.5F, 1.0F);
            } else if (!want && st.is(ERBlocks.CHOIR_SPROUT.get())) {
                level.removeBlock(sp, false);
            }
        }
    }

    /** The arena's own ground, which the fight may dress and undress: its worked deepslate and the sculk over it. */
    private static boolean arenaStone(BlockState st) {
        return st.is(Blocks.SCULK) || st.is(Blocks.DEEPSLATE_TILES) || st.is(Blocks.CRACKED_DEEPSLATE_TILES)
                || st.is(Blocks.DEEPSLATE_BRICKS) || st.is(Blocks.CRACKED_DEEPSLATE_BRICKS)
                || st.is(Blocks.POLISHED_DEEPSLATE) || st.is(Blocks.CHISELED_DEEPSLATE);
    }

    /** Where this arena's sprouts stand: hashed spots over the inner floor, the same every time. */
    public static List<BlockPos> sproutSpots(ChoirSites.Site s) {
        List<BlockPos> out = new ArrayList<>(SPROUTS);
        for (int i = 0; out.size() < SPROUTS && i < SPROUTS * 6; i++) {
            long h = Hashing.mix(s.hash(), SALT_SPROUT, i, 0);
            double a = Hashing.range(h, 0, Math.PI * 2);
            double r = Hashing.range(Hashing.mix(h, 1, 0, 0), ChoirArena.DAIS_RING + 1, SPROUT_R);
            int x = (int) Math.floor(s.x() + r * Math.cos(a)), z = (int) Math.floor(s.z() + r * Math.sin(a));
            int y = ChoirArena.topY(s, x + 0.5, z + 0.5);
            if (y == ChoirArena.NONE || !ChoirArena.onFloor(s, x + 0.5, z + 0.5)) continue;
            BlockPos p = new BlockPos(x, y + 1, z);
            boolean near = false;
            for (BlockPos q : out) if (q.distSqr(p) < 9) near = true;
            if (!near) out.add(p);
        }
        return out;
    }

    public static List<BlockPos> sproutSpots(HollowChoirEntity choir) {
        ChoirSites.Site s = siteAt(choir.level(), BlockPos.containing(choir.daisX(), choir.floorY(), choir.daisZ()));
        return s == null ? List.of() : sproutSpots(s);
    }

    // -------------------------------------------------------------------------------------------------- the strikes

    /** A tendril's slam: it hurts and throws back whoever is standing in the circle it comes down on. */
    public static void slam(HollowChoirEntity choir, double x, double z, double r, float hurt) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = Math.hypot(p.getX() - x, p.getZ() - z);
            if (d > r || Math.abs(p.getY() - choir.floorY()) > 6) continue;
            p.hurt(sl.damageSources().mobAttack(choir), hurt);
            Vec3 away = new Vec3(p.getX() - x, 0.35, p.getZ() - z).normalize().scale(0.75);
            p.push(away.x, away.y, away.z);
            p.hurtMarked = true;
        }
        motes(sl, x, choir.floorY() + 1.1, z, r, 50);
    }

    /**
     * One step of a toll's ring: it hurts whoever the ring passes at floor level. Standing on a broken pillar or up on
     * the seats, or jumping as it goes by, is how it is dodged.
     */
    public static void tollRing(HollowChoirEntity choir, int reach) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        double y = choir.floorY();
        if (reach <= 0) {
            sl.playSound(null, choir.daisX(), y + 2, choir.daisZ(), ERSounds.CHOIR_TOLL.get(), SoundSource.HOSTILE, 1.4F, 1.0F);
            return;
        }
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = Math.hypot(p.getX() - choir.daisX(), p.getZ() - choir.daisZ());
            if (Math.abs(d - reach) > 1.6) continue;
            // over the floor by more than a step: on a pillar, on the seats, or in the air over the ring
            if (p.getY() - y > 1.3) continue;
            p.hurt(sl.damageSources().mobAttack(choir), HollowChoirEntity.TOLL_HURT);
            Vec3 away = new Vec3(p.getX() - choir.daisX(), 0.2, p.getZ() - choir.daisZ()).normalize().scale(0.5);
            p.push(away.x, away.y, away.z);
            p.hurtMarked = true;
        }
        ring(sl, choir.daisX(), y + 0.6, choir.daisZ(), reach, Math.max(12, reach * 3));
    }

    /** The grab: a tendril whips out and draws everyone in the bowl towards the dais, over the sculk. */
    public static void grab(HollowChoirEntity choir) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = Math.hypot(p.getX() - choir.daisX(), p.getZ() - choir.daisZ());
            if (d > ChoirSites.RIM_R || d < 2) continue;
            Vec3 pull = new Vec3(choir.daisX() - p.getX(), 0, choir.daisZ() - p.getZ()).normalize().scale(1.15);
            p.push(pull.x, 0.25, pull.z);
            p.hurtMarked = true;
        }
        motes(sl, choir.daisX(), choir.floorY() + 2, choir.daisZ(), 6, 40);
    }

    /** The bloom of the last phase: the sculk pulses, and only clean stone is safe to stand on. */
    public static void bloom(HollowChoirEntity choir) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = Math.hypot(p.getX() - choir.daisX(), p.getZ() - choir.daisZ());
            if (d > ChoirSites.RIM_R) continue;
            BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
            if (!sl.getBlockState(under).is(Blocks.SCULK) && !sl.getBlockState(under.below()).is(Blocks.SCULK)) continue;
            p.hurt(sl.damageSources().mobAttack(choir), 4);
        }
        for (int i = 2; i < ChoirSites.RIM_R; i += 4) ring(sl, choir.daisX(), choir.floorY() + 0.6, choir.daisZ(), i, i * 2);
    }

    /** A blow on the crown while it is up: the bell rings and the player is told why nothing happened. */
    public static void clang(HollowChoirEntity choir, Player by) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        by.displayClientMessage(Component.translatable("message.explorationremix.choir.held")
                .withStyle(ChatFormatting.GRAY), true);
        motes(sl, choir.getX(), choir.getY(), choir.getZ(), HollowChoirEntity.CROWN_R + 0.6, 16);
    }

    /** A tendril parting: its root bursts and the cut end whips up. */
    public static void tendrilCutFx(HollowChoirEntity choir, int place) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        Vec3 f = choir.tendrilFoot(place);
        motes(sl, f.x, f.y + 1, f.z, 1.6, 40);
        motes(sl, f.x, f.y + 4, f.z, 1.2, 25);
    }

    // ------------------------------------------------------------------------------------------- won, lost, let go

    /** The fight is over: put the arena back, and on a win empty the three sockets so it waits for three new keys. */
    public static void give(HollowChoirEntity choir, boolean won) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        ChoirSites.Site s = siteAt(sl, BlockPos.containing(choir.daisX(), choir.floorY(), choir.daisZ()));
        if (s == null) return;
        dress(sl, s, 0);
        if (!won) return;
        for (int[] c : ChoirArena.sockets(s)) {
            BlockPos p = new BlockPos(c[0], (int) s.rimY() + 1, c[1]);
            BlockState st = sl.getBlockState(p);
            if (st.is(ERBlocks.KEY_SOCKET.get()) && st.getValue(KeySocketBlock.FILLED))
                sl.setBlock(p, st.setValue(KeySocketBlock.FILLED, false), 3);
        }
        sl.playSound(null, s.x(), s.floorY() + 2, s.z(), ERSounds.CHOIR_TOLL_FAR.get(), SoundSource.HOSTILE, 1.6F, 0.9F);
    }

    // ------------------------------------------------------------------------------------------------- the effects

    private static void ring(ServerLevel level, ChoirSites.Site s, double rad, int n) {
        ring(level, s.x(), s.floorY() + 0.6, s.z(), rad, n);
    }

    /** A circle of the Choir's pale motes over the floor. */
    private static void ring(ServerLevel level, double x, double y, double z, double rad, int n) {
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            mote(level, x + rad * Math.cos(a), y, z + rad * Math.sin(a), ChoirLook.GLOW_RGB);
        }
    }

    /** A puff of motes round a point. */
    private static void motes(ServerLevel level, double x, double y, double z, double rad, int n) {
        for (int i = 0; i < n; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, r = rad * level.random.nextDouble();
            mote(level, x + r * Math.cos(a), y + level.random.nextDouble() * 1.2, z + r * Math.sin(a),
                    level.random.nextInt(4) == 0 ? ChoirLook.BONE_RGB : ChoirLook.GLOW_RGB);
        }
    }

    /**
     * One of the Choir's motes, in its own colour. The mote takes its colour from the three numbers of its speed
     * ({@code CaveParticles.Spore}), and a player's game only reads those as they were sent when the count is 0: with
     * a count it rolls speeds of its own and takes the three numbers for a spread instead. Sent with a count of 1 and
     * a speed of 0, as 0.21.9 did, every mote of the fight arrived black, a block or so off its mark, and could not be
     * seen at all. So each goes alone, with count 0 and speed 1, and lands where it was sent in the colour it was sent.
     */
    private static void mote(ServerLevel level, double x, double y, double z, float[] rgb) {
        level.sendParticles(ERParticles.SPORE.get(), x, y, z, 0, rgb[0], rgb[1], rgb[2], 1.0);
    }
}
