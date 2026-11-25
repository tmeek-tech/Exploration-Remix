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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
     * How far from the dais a sculk shrieker is struck dumb ({@link #hush}): well past where a fighter still counts as
     * fighting ({@link HollowChoirEntity#KEEP}), the eight blocks a sculk sensor hears them over and the eight more a
     * shrieker hears that sensor over, so no noise made in the fight can reach one that still has its call. It is the
     * distance 0.22.0 was measured at and is kept as a number of its own: the fight drew in to the bowl in 0.22.3, and
     * the hush did not need to draw in with it.
     */
    public static final double HUSH_R = 61;
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
        // it starts high over the dais and comes down (HollowChoirEntity.settle places it)
        choir.settle(s, s.ceil());
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

    /**
     * A tendril's slam: it hurts and throws back whoever is standing in the circle it comes down on, which is the
     * circle that was marked on the floor since the tendril began to rear ({@link ChoirActs#slamStrikes}). They are
     * thrown out of the circle and never up: (rootX, rootZ) is the root of the tendril that struck, which is the way
     * a player in the very middle is thrown on from.
     */
    public static void slam(HollowChoirEntity choir, double x, double y, double z, double rootX, double rootZ) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        double ax = x - rootX, az = z - rootZ, al = Math.hypot(ax, az);
        if (al < 0.05) { ax = x - choir.daisX(); az = z - choir.daisZ(); al = Math.hypot(ax, az); }
        if (al < 0.05) { ax = 1; az = 0; al = 1; }
        ax *= ChoirActs.SLAM_ALONG / al;
        az *= ChoirActs.SLAM_ALONG / al;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (!ChoirActs.slamStrikes(Math.hypot(dx, dz), p.getY() - y)) continue;
            Vec3 was = p.getDeltaMovement();
            p.hurt(sl.damageSources().mobAttack(choir), HollowChoirEntity.SLAM_HURT);
            double d = Math.hypot(dx + ax, dz + az), keep = unbraced(p);
            if (d < 1e-3) { dx = 0; dz = 0; d = ChoirActs.SLAM_ALONG; }
            fling(p, was, (dx + ax) / d * ChoirActs.SLAM_THROW * keep, ChoirActs.SLAM_LIFT * keep,
                    (dz + az) / d * ChoirActs.SLAM_THROW * keep);
        }
        motes(sl, x, y + 0.1, z, ChoirActs.SLAM_R, 36);
    }

    /** What a player's armour leaves of a throw: netherite braces its wearer against a tenth of one a piece. */
    private static double unbraced(Player p) {
        return Math.max(0, 1 - p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
    }

    /**
     * Throws a player, at once and to the number: (x, y, z) is what they move at from the next tick, in place of
     * whatever a blow's own knock gave them ({@code was} is how the game had them moving before the blow).
     *
     * <p>A push left for the game to pass on is passed on a tick late. By then the game has run the player a tick on
     * by its own reckoning, through the floor's drag and into whatever stood in the way, and what it sends is what
     * that left: half the push, and none of its lift if there was a brazier to step up on. So this sends the throw
     * itself, the way the game does when one player strikes another, and puts its own reckoning back as it was.
     * A player in the air is given less along the floor ({@link ChoirActs#AIR_SHARE}), so they land where one who
     * stood would have.
     */
    private static void fling(Player p, Vec3 was, double x, double y, double z) {
        p.setDeltaMovement(was);
        p.hurtMarked = false;
        if (!p.isAlive()) return;
        double share = p.onGround() ? 1 : ChoirActs.AIR_SHARE;
        Vec3 v = new Vec3(x * share, y, z * share);
        if (p instanceof ServerPlayer sp && sp.connection != null) {
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), v));
        } else {
            p.setDeltaMovement(v);
            p.hurtMarked = true;
        }
    }

    /**
     * What stands in a toll's way: for each slice of the floor round a dais, how far out the nearest solid thing
     * stands, or more than any ring runs where the slice is clear. Solid is anything standing on the floor with a
     * body at least half a block tall: the bowl's broken pillars and whatever a player builds, but not its braziers,
     * which are low enough for a ring to pass over.
     * Both sides work this out from the blocks themselves as a ring lands, so the gap a player sees in the ring behind
     * a pillar is the gap it leaves.
     */
    public static void tollWalls(Level level, double cx, int floorY, double cz, float[] out) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int s = 0; s < out.length; s++) {
            double a = ChoirActs.sliceAngle(s), cos = Math.cos(a), sin = Math.sin(a);
            float wall = Float.MAX_VALUE;
            for (double r = ChoirActs.TOLL_R0; r <= ChoirActs.TOLL_R1; r += 0.25) {
                p.set(Mth.floor(cx + r * cos), floorY + 1, Mth.floor(cz + r * sin));
                BlockState st = level.getBlockState(p);
                if (st.isAir()) continue;
                VoxelShape shape = st.getCollisionShape(level, p);
                if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.5) continue;
                wall = (float) r;
                break;
            }
            out[s] = wall;
        }
    }

    /**
     * One tick of a toll's ring where it is now: it hurts whoever it passes with their feet on the floor and nothing
     * solid between them and the dais ({@link ChoirActs#tollStrikes}), and each of them once.
     */
    public static void tollRing(HollowChoirEntity choir, double radius, Set<UUID> struck) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        int phase = choir.phase();
        double floor = choir.floorY() + 1;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive() || struck.contains(p.getUUID())) continue;
            double dx = p.getX() - choir.daisX(), dz = p.getZ() - choir.daisZ();
            float wall = choir.tollWall[ChoirActs.tollSlice(dx, dz)];
            if (!ChoirActs.tollStrikes(phase, radius, Math.hypot(dx, dz), p.getY() - floor, wall)) continue;
            struck.add(p.getUUID());
            Vec3 was = p.getDeltaMovement();
            p.hurt(sl.damageSources().mobAttack(choir), HollowChoirEntity.TOLL_HURT);
            double d = Math.max(1e-6, Math.hypot(dx, dz)), keep = unbraced(p);
            fling(p, was, dx / d * ChoirActs.TOLL_THROW * keep, ChoirActs.TOLL_LIFT * keep,
                    dz / d * ChoirActs.TOLL_THROW * keep);
        }
    }

    /**
     * The grab: everyone the fight still holds is drawn in towards the dais, harder the further out they stand
     * ({@link ChoirActs#grabPull}). It never hurts, and that includes the fall: whoever it takes off the seats or the
     * rim comes down slowly.
     */
    public static void grab(HollowChoirEntity choir) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        for (Player p : sl.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double dx = choir.daisX() - p.getX(), dz = choir.daisZ() - p.getZ();
            double d = Math.hypot(dx, dz);
            double pull = ChoirActs.grabPull(d);
            if (d > HollowChoirEntity.KEEP || pull <= 0) continue;
            fling(p, p.getDeltaMovement(), dx / d * pull, ChoirActs.GRAB_LIFT, dz / d * pull);
            if (p.getY() > choir.floorY() + 1.5)
                p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0, false, false, false));
        }
        motes(sl, choir.daisX(), choir.floorY() + 2, choir.daisZ(), 5, 24);
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
            p.hurt(sl.damageSources().mobAttack(choir), HollowChoirEntity.BLOOM_HURT);
        }
    }

    /** A tendril reaching the floor and taking root there: a knock through the stone and a puff of its motes. */
    public static void rootFx(HollowChoirEntity choir, int place) {
        if (!(choir.level() instanceof ServerLevel sl)) return;
        Vec3 f = choir.tendrilFoot(place);
        sl.playSound(null, f.x, f.y + 1, f.z, ERSounds.CHOIR_ROOT.get(), SoundSource.HOSTILE, 0.9F,
                0.9F + sl.random.nextFloat() * 0.2F);
        motes(sl, f.x, f.y + 1.1, f.z, 1.2, 14);
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
