package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One giant Skein to each Deep Blue Rift, for good (0.18.8, Taran's picks: one per rift; tough, and gone for good): the
 * record of which rifts have had theirs, and the setting down of each.
 *
 * <p>A rift gets its giant the first time a player in the Blue Reaches comes within {@link #NEAR} blocks of it, in a
 * world made before this build as much as in a new one: it is set down at the first of the rift's home spots that will
 * do ({@link #homeSpot}, the same spots for a world's seed every time), high on a wall, out of the ledge path's way and
 * clear of the bridges, where its body has room to lie along the wall, no player is within {@link #CLEAR} blocks and no
 * player can see it come. From then on it is an ordinary creature of the world, kept with its chunk, and the rift is
 * marked as having had its giant: once it is killed nothing moves in after it, and the rift stays empty.
 *
 * <p>Kept per level (the overworld, where the Blue Reaches are), so it lasts through restarts.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class GiantSkeins extends SavedData {
    private static final String NAME = "explorationremix_giant_skeins";

    /** How often each player is looked round (ticks). */
    static final int EVERY = 100;
    /** A rift whose centre line passes this near a player in the Blue Reaches gets its giant. */
    static final double NEAR = 160.0;
    /** It comes no nearer a player than this, and out of the sight of every player within {@link #SIGHT}. */
    static final double CLEAR = 40.0, SIGHT = 160.0;
    /** Home spots tried, in order, for a rift. */
    static final int SPOTS = 16;
    /** Its body must have this much room to lie along the wall from its head (blocks; it is 29 long). */
    static final double ROOM = 20.0;
    /** Its home spots keep this far from the rift's ends (blocks along it). */
    static final double END = 32.0;
    /** The chunks this far round a spot must be loaded (its body is laid over the rock there). */
    static final int LOADED = 40;
    private static final long SALT = 0x4769616E74486F6DL;     // "GiantHom"

    /** One rift's record: the giant set down in it, where, and whether it has been killed. */
    public static final class Home {
        public final int cellX, cellZ;
        @Nullable public UUID id;
        /** Where it was set down, or (once killed) where it died. */
        public BlockPos at = BlockPos.ZERO;
        public long placedAt = -1, killedAt = -1;

        Home(int cellX, int cellZ) {
            this.cellX = cellX;
            this.cellZ = cellZ;
        }

        public boolean killed() { return killedAt >= 0; }
    }

    private final Map<Long, Home> homes = new HashMap<>();

    public static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xffffffffL);
    }

    public static GiantSkeins get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(GiantSkeins::load, GiantSkeins::new, NAME);
    }

    /** The rift's record, or null when it has not had its giant yet. */
    @Nullable
    public Home home(Route r) {
        return homes.get(key(r.cellX, r.cellZ));
    }

    public List<Home> homes() {
        return new ArrayList<>(homes.values());
    }

    /** For /remix giant forget: the rift has had no giant (it gets one again); false when it had none. */
    public boolean forget(Route r) {
        if (homes.remove(key(r.cellX, r.cellZ)) == null) return false;
        setDirty();
        return true;
    }

    private void placed(Route r, GiantSkeinEntity g, long time) {
        Home h = new Home(r.cellX, r.cellZ);
        h.id = g.getUUID();
        h.at = g.blockPosition();
        h.placedAt = time;
        homes.put(key(r.cellX, r.cellZ), h);
        setDirty();
    }

    /** A giant died: if it was a rift's own, that rift stays empty for good. */
    public static void killed(GiantSkeinEntity g) {
        if (!(g.level() instanceof ServerLevel l) || l.dimension() != Level.OVERWORLD) return;
        GiantSkeins rec = get(l);
        for (Home h : rec.homes.values()) {
            if (h.killed() || !g.getUUID().equals(h.id)) continue;
            h.killedAt = l.getGameTime();
            h.at = g.blockPosition();
            rec.setDirty();
            ExplorationRemix.LOGGER.info("The giant Skein of the rift of cell {} {} was killed at {}; nothing moves in after it",
                    h.cellX, h.cellZ, h.at.toShortString());
        }
    }

    // ------------------------------------------------------------------------------------------------ saving

    static GiantSkeins load(CompoundTag tag) {
        GiantSkeins rec = new GiantSkeins();
        for (Tag t : tag.getList("Homes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Home h = new Home(c.getInt("CellX"), c.getInt("CellZ"));
            if (c.hasUUID("Id")) h.id = c.getUUID("Id");
            h.at = new BlockPos(c.getInt("X"), c.getInt("Y"), c.getInt("Z"));
            h.placedAt = c.getLong("PlacedAt");
            h.killedAt = c.contains("KilledAt") ? c.getLong("KilledAt") : -1;
            rec.homes.put(key(h.cellX, h.cellZ), h);
        }
        return rec;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Home h : homes.values()) {
            CompoundTag c = new CompoundTag();
            c.putInt("CellX", h.cellX);
            c.putInt("CellZ", h.cellZ);
            if (h.id != null) c.putUUID("Id", h.id);
            c.putInt("X", h.at.getX());
            c.putInt("Y", h.at.getY());
            c.putInt("Z", h.at.getZ());
            c.putLong("PlacedAt", h.placedAt);
            if (h.killed()) c.putLong("KilledAt", h.killedAt);
            list.add(c);
        }
        tag.put("Homes", list);
        return tag;
    }

    // ------------------------------------------------------------------------------------------------ setting them down

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!DeepWorld.installed()) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 13L, EVERY) == 0) lookRound(level, p);
    }

    /** The rifts near a player in the Blue Reaches that have had no giant yet get theirs, where a spot will do now. */
    private static void lookRound(ServerLevel level, ServerPlayer p) {
        Terrain t = DeepWorld.terrain();
        if (t == null || p.isSpectator() || !level.isLoaded(p.blockPosition())) return;
        if (p.getY() > t.shape.top(Terrain.BLUE) + 40 || p.getY() < t.shape.bottom(Terrain.BLUE) - 10) return;
        GiantSkeins rec = get(level);
        for (Route r : t.network.riftsNear(p.getX(), p.getZ(), NEAR)) {
            if (!r.isRift() || rec.homes.containsKey(key(r.cellX, r.cellZ))) continue;
            double[] loc = r.locate(p.getX(), p.getZ());
            if (loc == null || loc[0] > NEAR) continue;
            setDown(level, r, false);
        }
    }

    /**
     * Sets down rift {@code r}'s giant at the first of its home spots that will do now (with {@code anyway}, whether or
     * not a player is near or sees it: /remix giant place), and marks the rift as having had it. Null when none will.
     */
    @Nullable
    public static GiantSkeinEntity setDown(ServerLevel level, Route r, boolean anyway) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || !r.isRift() || r.length < 2 * END + 16.0) return null;
        for (int k = 0; k < SPOTS; k++) {
            Spot spot = spot(level, r, homeSpot(t.seed, r, k), anyway);
            if (spot == null) continue;
            GiantSkeinEntity g = EREntities.GIANT_SKEIN.get().create(level);
            if (g == null) return null;
            g.setRift(r);
            g.moveTo(spot.p().pos().x, spot.p().pos().y, spot.p().pos().z, 0.0F, 0.0F);
            g.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot.p().pos())), MobSpawnType.NATURAL, null, null);
            g.placeOn(level, spot.p(), spot.back().scale(-1.0), spot.back());
            if (!level.addFreshEntity(g)) return null;
            get(level).placed(r, g, level.getGameTime());
            ExplorationRemix.LOGGER.info("The giant Skein of the rift of cell {} {} is set down at {} (home spot {})",
                    r.cellX, r.cellZ, g.blockPosition().toShortString(), k);
            return g;
        }
        return null;
    }

    /**
     * The k-th of rift {@code r}'s home spots, the same for a world's seed every time: {arc length along the rift, the
     * side (1 or -1), height}. Along the middle of the rift, away from its bridges, high on one wall or the other (from
     * two fifths to four fifths of the way up from the stream), above or below the ledge path on the path's side.
     */
    static double[] homeSpot(long seed, Route r, int k) {
        long h = Hashing.mix(seed ^ SALT, r.cellX, r.cellZ, k);
        double u = Hashing.unit(h), v = Hashing.unit(Hashing.mix(h, 1, 0, 0)), w = Hashing.unit(Hashing.mix(h, 2, 0, 0));
        double s = END + (r.length - 2 * END) * (0.1 + 0.8 * u);
        for (Route.Bridge b : r.bridges) {
            double gap = b.halfAlong() + 14.0;
            if (Math.abs(s - b.s()) < gap) s = b.s() + (s >= b.s() ? gap : -gap);
        }
        s = Mth.clamp(s, END, r.length - END);
        double side = v < 0.5 ? 1.0 : -1.0;
        double bed = r.W, top = r.riftTopAt(s), depth = top - bed;
        double y = bed + depth * (0.4 + 0.4 * w);
        double pathY = Route.feetAt(r.floorAt(s));
        if (r.pathSideAt(s) * side > 0.3 && Math.abs(y - pathY) < 6.0) y = pathY + (y >= pathY ? 6.0 : -6.0);
        y = Mth.clamp(y, bed + Math.max(8.0, depth * 0.3), top - 6.0);
        return new double[]{s, side, y};
    }

    /** A home spot that will do: where it sits on the wall, and the way its body lies back from its head. */
    record Spot(GiantSkeinEntity.Placement p, Vec3 back) {}

    /** Whether the home spot {@code h} will do now, and how it lies there; null when not. */
    @Nullable
    static Spot spot(ServerLevel level, Route r, double[] h, boolean anyway) {
        Vec3 w = GiantSkeinEntity.wallAt(level, r, h[0], h[1], h[2]);
        if (w == null) return null;
        BlockPos at = BlockPos.containing(w);
        if (!level.isPositionEntityTicking(at)
                || !level.hasChunksAt(at.getX() - LOADED, at.getZ() - LOADED, at.getX() + LOADED, at.getZ() + LOADED)) return null;
        if (level.getBiome(at).is(SkeinEntity.REFUGE)) return null;
        if (!anyway && nearestPlayer(level, w) < CLEAR) return null;
        GiantSkeinEntity.Placement p = wallNode(level, w);
        if (p == null) return null;
        Vec3 up = GiantSkeinEntity.normal(p.rock()), feet = GiantSkeinEntity.placedFeet(EREntities.GIANT_SKEIN.get(), p);
        double[] d = r.directionAt(h[0]);
        Vec3 along = new Vec3(d[0], 0.0, d[1]);
        double ahead = GiantSkeinEntity.drapeRoom(level, feet, up, along), behind = GiantSkeinEntity.drapeRoom(level, feet, up, along.scale(-1.0));
        if (Math.max(ahead, behind) < ROOM) return null;
        Vec3 back = ahead >= behind ? along : along.scale(-1.0);
        if (!anyway && seen(level, feet.add(up.scale(0.8)), back)) return null;
        return new Spot(p, back);
    }

    /** The node on the wall nearest {@code w} where it can sit, pressed to the wall; null when there is none near. */
    @Nullable
    static GiantSkeinEntity.Placement wallNode(ServerLevel level, Vec3 w) {
        int bx = Mth.floor(w.x - 1.5), by = Mth.floor(w.y - 0.6), bz = Mth.floor(w.z - 1.5);
        GiantSkeinEntity.Placement best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -2; dz <= 2; dz++) {
                    GiantSkeinEntity.Placement p = GiantSkeinEntity.placement(level, EREntities.GIANT_SKEIN.get(), new BlockPos(bx + dx, by + dy, bz + dz));
                    if (p == null || p.rock().getAxis() == Direction.Axis.Y) continue;
                    double d = p.pos().add(0.0, 0.6, 0.0).distanceToSqr(w);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
        return best;
    }

    private static double nearestPlayer(ServerLevel level, Vec3 at) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) best = Math.min(best, p.position().distanceToSqr(at));
        return Math.sqrt(best);
    }

    /** Whether any player near enough could see its head or its body lying back from it along {@code back}. */
    private static boolean seen(ServerLevel level, Vec3 head, Vec3 back) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            for (double s = 0.0; s <= 27.0; s += 9.0) {
                Vec3 q = head.add(back.scale(s));
                if (p.position().distanceToSqr(q) > SIGHT * SIGHT) continue;
                HitResult hit = level.clip(new ClipContext(p.getEyePosition(), q, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (hit.getType() == HitResult.Type.MISS) return true;
            }
        }
        return false;
    }
}
