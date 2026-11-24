package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A vent's urchin (0.21.3; 0.21.0's chimney lurker) comes back after it is killed (Taran's card: "A new one moves in
 * days later"): its vent stands empty for {@link #MOVE_IN} ticks (two in-game days), and a little after that a new one sits in its mouth,
 * moved in while no player was near enough to see it come. A held vent with no lurker and no death on record (in a
 * world made before the lurkers, or one whose lurker never found a spot as its chunk was made) fills this way too,
 * without the wait, so the vents round a player in an older world are taken within a few minutes.
 *
 * <p>Every {@link #EVERY} ticks each player in the Deep Gardens is looked round: the held vents ({@link
 * LurkerVents#held}) {@link #NEAR_MIN} to {@link #NEAR_MAX} blocks off whose ground within {@link #REACH} of the vent
 * ticks entities, so a lurker of its own away on a chase would be counted. The vents of the cells round them are worked
 * out off the server thread (a fresh tile of the vent field takes seconds to make) and looked at the next time. As
 * vanilla's own monsters: no refill in Peaceful, or with mob spawning off.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class LurkerRefill {
    private LurkerRefill() {}

    /** How often each player is looked round (ticks). */
    static final int EVERY = 200;
    /** A vent is refilled only between these distances from the nearest player. */
    static final double NEAR_MIN = 48.0, NEAR_MAX = 128.0;
    /** A killed lurker's vent stands empty this long (two in-game days), then gets its new one at one look in {@link #ROLL}. */
    public static final long MOVE_IN = 48000L;
    static final int ROLL = 40;
    /** A held vent with nothing on record gets its lurker at one look in this. */
    static final int FILL_ROLL = 3;
    /**
     * At most this many vents are moved into at each look round a player (each a search of its mouth on the server
     * thread), so the vents round a player in an older world fill over a few minutes without a hitch.
     */
    static final int MOVES_PER_LOOK = 2;
    /** A vent's lurker is counted as its own within this of its axis (across: it chases no further). */
    static final double REACH = 40.0;
    /** The vents are worked out in cells this many blocks across (the vent field's own site tiles). */
    static final int CELL = 128;
    /** For /remix lurker vents: looks at empty vents, lurkers moved in. */
    public static long LOOKS, MOVED_IN;
    /**
     * Vents whose mouths had no side that would do when last looked at (by key: when, game time), not looked at again
     * for a day (a search costs a few trial runs of its crawl, on the server thread).
     */
    private static final Map<Long, Long> NO_SPOT = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 512; }
    };
    static final long NO_SPOT_WAIT = 24000L;
    /** Homes found but seen by a player at the time (by key), kept a while to try again without a new search. */
    private static final Map<Long, Found> FOUND = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Found> e) { return size() > 256; }
    };
    static final long FOUND_KEEP = 2400L;

    private record Found(LurkerVents.Home home, long at) {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 17L + 101, EVERY) == 0) lookRound(level, p, false);
    }

    /** Looks round {@code p} once; with {@code force} every empty held vent near gets its lurker, wait or no wait. */
    public static int lookRound(ServerLevel level, Player p, boolean force) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null || p.isSpectator()) return 0;
        int bottom = t.shape.bottom(Terrain.GARDENS), top = t.shape.top(Terrain.GARDENS);
        if (p.getY() < bottom - 24 || p.getY() > top + 24) return 0;
        if (!force && (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) || !level.getServer().isSpawningMonsters()
                || level.getDifficulty() == Difficulty.PEACEFUL)) return 0;
        Deaths deaths = deaths(level);
        long now = level.getGameTime();
        int pcx = Math.floorDiv(p.getBlockX(), CELL), pcz = Math.floorDiv(p.getBlockZ(), CELL);
        int reach = (int) Math.ceil(NEAR_MAX / CELL);
        int in = 0, moves = 0;
        for (int cx = pcx - reach; cx <= pcx + reach; cx++)
            for (int cz = pcz - reach; cz <= pcz + reach; cz++) {
                List<GardensField.LiveVent> vents = ventsIfPlanned(t, cx, cz);
                if (vents == null) continue;
                for (GardensField.LiveVent v : vents) {
                    if (!LurkerVents.held(v)) continue;
                    double rim = v.topRow() + bottom;
                    double d = nearestPlayer(level, new Vec3(v.x(), rim, v.z()));
                    if (d < NEAR_MIN || d > NEAR_MAX) continue;
                    if (!ventLoaded(level, v, rim)) continue;
                    if (lurkerOf(level, v.key(), v.x(), rim, v.z()) != null) continue;
                    Long died = deaths.at(v.key());
                    if (!force && died != null && now - died < MOVE_IN) continue;
                    LOOKS++;
                    if (!force && (moves >= MOVES_PER_LOOK || level.random.nextInt(died != null ? ROLL : FILL_ROLL) != 0)) continue;
                    moves++;
                    if (moveIn(level, v, bottom)) {
                        deaths.forget(v.key());
                        MOVED_IN++;
                        in++;
                    }
                }
            }
        return in;
    }

    /** A lurker killed at its vent: its vent stands empty a while ({@link #MOVE_IN}). */
    public static void died(ServerLevel level, long key) {
        ServerLevel over = level.getServer().overworld();
        if (level != over) return;
        deaths(over).record(key, over.getGameTime());
    }

    /** The lurker of vent {@code key} near its axis, if one is there (alive, and loaded). */
    @Nullable
    public static ChimneyLurkerEntity lurkerOf(ServerLevel level, long key, double x, double top, double z) {
        AABB box = new AABB(x - REACH, top - 64, z - REACH, x + REACH, top + 16, z + REACH);
        for (ChimneyLurkerEntity e : level.getEntitiesOfClass(ChimneyLurkerEntity.class, box))
            if (e.isAlive() && e.home() != null && e.home().key() == key) return e;
        return null;
    }

    /** When vent {@code key}'s lurker died, if it lies empty since (game time), else null. */
    @Nullable
    public static Long diedAt(ServerLevel level, long key) {
        return deaths(level.getServer().overworld()).at(key);
    }

    /** Whether the ground within {@link #REACH} of the vent ticks entities (a player's loaded ground is square, so its corners tell). */
    private static boolean ventLoaded(ServerLevel level, GardensField.LiveVent v, double top) {
        int x = (int) Math.floor(v.x()), y = (int) Math.floor(top), z = (int) Math.floor(v.z()), r = (int) REACH;
        for (int dx = -r; dx <= r; dx += 2 * r)
            for (int dz = -r; dz <= r; dz += 2 * r)
                if (!level.isPositionEntityTicking(new BlockPos(x + dx, y, z + dz))) return false;
        return level.isPositionEntityTicking(new BlockPos(x, y, z));
    }

    private static double nearestPlayer(ServerLevel level, Vec3 at) {
        double best = Double.MAX_VALUE;
        for (Player p : level.players()) if (!p.isSpectator()) best = Math.min(best, p.position().distanceTo(at));
        return best;
    }

    /** A new lurker into the vent's mouth, at a spot no player sees. */
    private static boolean moveIn(ServerLevel level, GardensField.LiveVent v, int bottom) {
        long now = level.getGameTime();
        Long tried = NO_SPOT.get(v.key());
        if (tried != null && now - tried < NO_SPOT_WAIT) return false;
        Found kept = FOUND.get(v.key());
        LurkerVents.Home h = kept != null && now - kept.at() < FOUND_KEEP ? kept.home()
                : LurkerVents.find(level, v, bottom, (x, z) -> level.isPositionEntityTicking(new BlockPos(x, (int) Math.floor(v.topRow() + bottom), z)));
        if (h == null) {
            NO_SPOT.put(v.key(), now);
            return false;
        }
        NO_SPOT.remove(v.key());
        if (seen(level, h)) {
            if (kept == null || now - kept.at() >= FOUND_KEEP) FOUND.put(v.key(), new Found(h, now));
            return false;
        }
        FOUND.remove(v.key());
        ChimneyLurkerEntity e = EREntities.CHIMNEY_LURKER.get().create(level);
        if (e == null) return false;
        e.settleAt(h);
        BlockPos at = BlockPos.containing(h.waitX(), h.waitY(), h.waitZ());
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
        level.addFreshEntity(e);
        return true;
    }

    /** Whether any player within 96 blocks has a clear line to its spot or to where it looks out from. */
    static boolean seen(ServerLevel level, LurkerVents.Home h) {
        Vec3 spot = new Vec3(h.waitX(), h.waitY(), h.waitZ());
        Vector3d pk = h.peek(new Vector3d());
        Vec3 peek = new Vec3(pk.x, pk.y, pk.z);
        for (Player p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(spot) > 96.0 * 96.0) continue;
            Vec3 eye = p.getEyePosition();
            if (clear(level, eye, spot, p) || clear(level, eye, peek, p)) return true;
        }
        return false;
    }

    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to, @Nullable Player p) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------------------------------------ the vents, by cell

    private static final Map<Long, List<GardensField.LiveVent>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<GardensField.LiveVent>> e) { return size() > 256; }
    };
    private static final Set<Long> PENDING = Collections.synchronizedSet(new HashSet<>());
    private static long cacheSeed;

    private static long cellKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

    /** The live vents whose tops stand in cell (cx, cz), worked out on this thread if they are not yet. */
    public static List<GardensField.LiveVent> vents(Terrain t, int cx, int cz) {
        long key = cellKey(cx, cz);
        synchronized (CACHE) {
            if (cacheSeed != t.seed) { CACHE.clear(); cacheSeed = t.seed; }
            List<GardensField.LiveVent> v = CACHE.get(key);
            if (v != null) return v;
        }
        List<GardensField.LiveVent> v = List.copyOf(t.gardens.liveVents(cx * (double) CELL, cz * (double) CELL, (cx + 1) * (double) CELL, (cz + 1) * (double) CELL));
        synchronized (CACHE) { CACHE.put(key, v); }
        return v;
    }

    /** The cell's live vents if they are worked out already, else null (and they are worked out off this thread). */
    @Nullable
    public static List<GardensField.LiveVent> ventsIfPlanned(Terrain t, int cx, int cz) {
        long key = cellKey(cx, cz);
        synchronized (CACHE) {
            if (cacheSeed == t.seed) {
                List<GardensField.LiveVent> v = CACHE.get(key);
                if (v != null) return v;
            }
        }
        if (PENDING.add(key)) {
            net.minecraft.Util.backgroundExecutor().execute(() -> {
                try { vents(t, cx, cz); } finally { PENDING.remove(key); }
            });
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the deaths

    private static Deaths deaths(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Deaths::load, Deaths::new, "explorationremix_chimney_lurkers");
    }

    /** When each vent's lurker was killed (game time), kept with the world until a new one moves in. */
    static final class Deaths extends SavedData {
        private final Map<Long, Long> at = new HashMap<>();

        @Nullable Long at(long key) { return at.get(key); }

        void record(long key, long time) {
            at.put(key, time);
            setDirty();
        }

        void forget(long key) {
            if (at.remove(key) != null) setDirty();
        }

        static Deaths load(CompoundTag tag) {
            Deaths d = new Deaths();
            ListTag list = tag.getList("Deaths", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                d.at.put(e.getLong("Vent"), e.getLong("At"));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Map.Entry<Long, Long> e : at.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("Vent", e.getKey());
                c.putLong("At", e.getValue());
                list.add(c);
            }
            tag.put("Deaths", list);
            return tag;
        }
    }
}
