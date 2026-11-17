package com.explorationremix.creature.groups;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.groups.MobGroupRules.Home;
import com.explorationremix.creature.groups.MobGroupRules.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Brings vanilla's hostile mobs out in groups, each kind in its home, by the rules of {@link MobGroupRules}, in place of
 * vanilla's scattered spawns (which {@link VanillaMonsterFilter} takes off). Its own spawner run from the server tick,
 * like the Blue Reaches' one, so a group comes out whole, mixed where it is meant to be (zombies with skeletons) and only
 * where its kind lives.
 *
 * <p>Once a second it looks round each player (staggered) at three kinds of place: the ground on a ring
 * {@link MobGroupRules#NEAR} to {@link MobGroupRules#SURFACE_FAR} blocks round the player, a cave floor round it, and the
 * surface water round it. Whatever home the place is (or none), a group of that home's kind comes out there when the
 * home has fewer than its groups round the player and the roll says so: the leader on the spot, the rest round it, each
 * by vanilla's own checks for a natural spawn (dark enough, something to stand on, room, not near a player or the
 * world's spawn). A lone enderman now and then, where the biome has endermen at all.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class MobGroupSpawner {
    private MobGroupSpawner() {}

    /** How often each player is looked round (ticks). */
    static final int EVERY = 20;
    /** Points tried for a cave floor at one look, and how far down from each it looks for the floor. */
    static final int CAVE_TRIES = 4, FLOOR_SCAN = 8;
    /** Spots tried for each member after the leader. */
    static final int MEMBER_TRIES = 8;
    /** Surface water is looked into this deep at most. */
    static final int WATER_SCAN = 24;
    /** Places tried by {@code /remix groups spawn}. */
    static final int COMMAND_TRIES = 300;

    /** The kinds of place looked at each time. */
    enum Look { GROUND, CAVE, WATER }

    /** A place a group may come out, and its home. */
    record Anchor(BlockPos pos, Home home) {}

    // what it has done since the server started, for /remix groups stats
    static long looks, anchors, wanted, spawned, failed, endermen;
    static final long[] ANCHORS_BY_HOME = new long[Home.values().length];
    static final long[] SPAWNED_BY_KIND = new long[Kind.values().length];

    static void resetCounts() {
        looks = anchors = wanted = spawned = failed = endermen = 0;
        Arrays.fill(ANCHORS_BY_HOME, 0);
        Arrays.fill(SPAWNED_BY_KIND, 0);
    }

    // ------------------------------------------------------------------------------------------------ settings

    static boolean enabled() { return ERConfig.INSTANCE.mobGroupsEnabled.get(); }

    static int kept() { return ERConfig.INSTANCE.mobGroupsKept.get(); }

    static int most() { return Math.max(kept(), ERConfig.INSTANCE.mobGroupsMost.get()); }

    static double endermanChance() { return ERConfig.INSTANCE.mobEndermanChance.get(); }

    static double woodsCanopy() { return ERConfig.INSTANCE.mobWoodsCanopy.get(); }

    /** Whether monsters may come out at all: not peaceful, mob spawning on, and the server spawning monsters. */
    static boolean spawningOn(ServerLevel level) {
        return level.getDifficulty() != Difficulty.PEACEFUL && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
                && level.getServer().isSpawningMonsters();
    }

    // ------------------------------------------------------------------------------------------------ looking round

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!enabled()) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 7L, EVERY) == 0) lookRound(level, p);
    }

    private static void lookRound(ServerLevel level, ServerPlayer p) {
        if (p.isSpectator() || !spawningOn(level) || !level.isLoaded(p.blockPosition())) return;
        looks++;
        RandomSource r = level.random;
        int kept = kept(), most = most();
        int[] counts = null;
        for (Look look : Look.values()) {
            double roll = r.nextDouble();
            // a roll this high brings nothing whatever the place is
            if (roll >= MobGroupRules.FILL_CHANCE) continue;
            Anchor a = anchor(level, p, look);
            if (a == null) continue;
            anchors++;
            ANCHORS_BY_HOME[a.home.ordinal()]++;
            if (counts == null) counts = MobGroups.groupsRound(level, p.position());
            if (!MobGroupRules.wantGroup(counts[a.home.ordinal()], kept, most, roll)) continue;
            wanted++;
            if (spawnGroup(level, a.pos, MobGroupRules.kindFor(a.home, r.nextDouble()), p) != null) counts[a.home.ordinal()]++;
        }
        double roll = r.nextDouble(), chance = endermanChance();
        if (roll < chance && MobGroupRules.wantEnderman(endermenRound(level, p.position()), roll, chance)) spawnEnderman(level, p);
    }

    @Nullable
    static Anchor anchor(ServerLevel level, ServerPlayer p, Look look) {
        return switch (look) {
            case GROUND -> groundAnchor(level, p);
            case CAVE -> caveAnchor(level, p);
            case WATER -> waterAnchor(level, p);
        };
    }

    @Nullable
    private static Anchor groundAnchor(ServerLevel level, ServerPlayer p) {
        BlockPos ground = groundOnRing(level, p);
        if (ground == null) return null;
        Home h = MobHomes.surfaceHome(level, ground, woodsCanopy());
        return h == null ? null : new Anchor(ground, h);
    }

    /** The ground of a column on the ring round {@code p}: the first open block over it, under any leaves. */
    @Nullable
    private static BlockPos groundOnRing(ServerLevel level, ServerPlayer p) {
        RandomSource r = level.random;
        double a = r.nextDouble() * Math.PI * 2.0;
        double d = MobGroupRules.NEAR + r.nextDouble() * (MobGroupRules.SURFACE_FAR - MobGroupRules.NEAR);
        int x = Mth.floor(p.getX() + Math.cos(a) * d), z = Mth.floor(p.getZ() + Math.sin(a) * d);
        // the canopy is looked at this far round, and the members come out within the spread
        int m = Math.max(MobHomes.CANOPY_STEP * (MobHomes.CANOPY_SIDE / 2), MobGroupRules.SPREAD);
        if (!level.hasChunksAt(x - m, z - m, x + m, z + m)) return null;
        BlockPos ground = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        BlockState below = level.getBlockState(ground.below());
        // water is the water's own groups' place; the top of a trunk under its leaves is no ground
        if (!below.getFluidState().isEmpty() || below.is(BlockTags.LOGS)) return null;
        return ground;
    }

    @Nullable
    private static Anchor caveAnchor(ServerLevel level, ServerPlayer p) {
        BlockPos floor = caveFloor(level, p);
        if (floor == null) return null;
        Home h = MobHomes.caveHome(level, floor);
        return h == null ? null : new Anchor(floor, h);
    }

    /**
     * A cave floor round {@code p}: open air over something to stand on, under the ground of its column and out of the
     * sky, {@link MobGroupRules#CAVE_NEAR} to {@link MobGroupRules#CAVE_FAR} across and up to
     * {@link MobGroupRules#CAVE_HEIGHT} above or below; or null when the points tried were all in the rock.
     */
    @Nullable
    private static BlockPos caveFloor(ServerLevel level, ServerPlayer p) {
        RandomSource r = level.random;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int s = MobGroupRules.SPREAD;
        for (int t = 0; t < CAVE_TRIES; t++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double d = MobGroupRules.CAVE_NEAR + r.nextDouble() * (MobGroupRules.CAVE_FAR - MobGroupRules.CAVE_NEAR);
            int x = Mth.floor(p.getX() + Math.cos(a) * d), z = Mth.floor(p.getZ() + Math.sin(a) * d);
            int y0 = Mth.clamp(Mth.floor(p.getY() + (r.nextDouble() * 2.0 - 1.0) * MobGroupRules.CAVE_HEIGHT),
                    level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 3);
            if (!level.hasChunksAt(x - s, z - s, x + s, z + s)) continue;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int i = 0; i < FLOOR_SCAN; i++) {
                int y = y0 - i;
                if (y <= level.getMinBuildHeight()) break;
                // at or over the column's ground is the surface, not a cave
                if (y >= surface - 1) continue;
                q.set(x, y, z);
                if (!open(level, q) || !standable(level, q)) continue;
                if (level.canSeeSky(q)) break;
                return q.immutable();
            }
        }
        return null;
    }

    @Nullable
    private static Anchor waterAnchor(ServerLevel level, ServerPlayer p) {
        RandomSource r = level.random;
        double a = r.nextDouble() * Math.PI * 2.0;
        double d = MobGroupRules.NEAR + r.nextDouble() * (MobGroupRules.SURFACE_FAR - MobGroupRules.NEAR);
        int x = Mth.floor(p.getX() + Math.cos(a) * d), z = Mth.floor(p.getZ() + Math.sin(a) * d), s = MobGroupRules.SPREAD;
        if (!level.hasChunksAt(x - s, z - s, x + s, z + s)) return null;
        BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        if (!level.getFluidState(top).is(FluidTags.WATER) || !level.canSeeSky(top.above())) return null;
        int depth = 0;
        while (depth < WATER_SCAN && level.getFluidState(top.below(depth)).is(FluidTags.WATER)) depth++;
        Home h = MobHomes.waterHome(level, top, depth);
        if (h == null) return null;
        // somewhere down the water with water under it
        return new Anchor(top.below(r.nextInt(Math.max(1, depth - 1))), h);
    }

    private static boolean open(ServerLevel level, BlockPos q) {
        BlockState s = level.getBlockState(q);
        return s.getCollisionShape(level, q).isEmpty() && s.getFluidState().isEmpty();
    }

    private static boolean standable(ServerLevel level, BlockPos q) {
        BlockPos b = q.below();
        return !level.getBlockState(b).getCollisionShape(level, b).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ a group

    /**
     * Brings a group of {@code kind} out at {@code at}: its leader there, the rest within {@link MobGroupRules#SPREAD}
     * round it. Nothing comes out when the leader's spot does not take it or fewer than
     * {@link MobGroupRules#fewest} find room. Returns the leader, or null.
     */
    @Nullable
    static Mob spawnGroup(ServerLevel level, BlockPos at, Kind kind, @Nullable ServerPlayer forPlayer) {
        RandomSource r = level.random;
        int size = MobGroupRules.size(kind, ERConfig.INSTANCE.mobGroupMin.get(), ERConfig.INSTANCE.mobGroupMax.get(), r.nextDouble());
        List<EntityType<? extends Mob>> types = memberTypes(level, at, kind, size, r);
        List<Mob> placed = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            EntityType<? extends Mob> type = types.get(i);
            Mob m = null;
            if (i == 0) m = tryAt(level, type, at, forPlayer, placed);
            else
                for (int t = 0; t < MEMBER_TRIES && m == null; t++) {
                    BlockPos q = near(level, at, SpawnPlacements.getPlacementType(type), r);
                    if (q != null) m = tryAt(level, type, q, forPlayer, placed);
                }
            if (m != null) placed.add(m);
            else if (i == 0) { failed++; return null; }
        }
        if (placed.size() < MobGroupRules.fewest(kind, size)) { failed++; return null; }
        UUID id = UUID.randomUUID();
        for (int i = 0; i < placed.size(); i++) {
            Mob m = placed.get(i);
            MobGroups.tag(m, id, kind, i);
            // each its own roll (vanilla's packs share one: a zombie pack is all babies or none)
            ForgeEventFactory.onFinalizeSpawn(m, level, level.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.NATURAL, null, null);
            level.addFreshEntityWithPassengers(m);
        }
        spawned++;
        SPAWNED_BY_KIND[kind.ordinal()]++;
        return placed.get(0);
    }

    /** The members of a new group, leader first. */
    private static List<EntityType<? extends Mob>> memberTypes(ServerLevel level, BlockPos at, Kind kind, int size, RandomSource r) {
        MobHomes.Weights w = MobHomes.weights(level.getBiome(at).value());
        List<EntityType<? extends Mob>> out = new ArrayList<>(size);
        switch (kind) {
            case UNDEAD -> {
                // a skeleton leads: it keeps out of the sun, and the zombies keep with it
                int skeletons = MobGroupRules.skeletons(size, r.nextDouble());
                List<EntityType<? extends Mob>> rest = new ArrayList<>(size);
                for (int i = 1; i < skeletons; i++) rest.add(EntityType.SKELETON);
                for (int i = skeletons; i < size; i++) rest.add(zombie(w, r));
                for (int i = rest.size() - 1; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    EntityType<? extends Mob> t = rest.get(i);
                    rest.set(i, rest.get(j));
                    rest.set(j, t);
                }
                out.add(EntityType.SKELETON);
                out.addAll(rest);
            }
            case SPIDERS -> {
                out.add(EntityType.SPIDER);
                for (int i = 1; i < size; i++) out.add(spider(w, r));
            }
            case WITCHES -> fill(out, EntityType.WITCH, size);
            case CREEPERS -> fill(out, EntityType.CREEPER, size);
            case SLIMES -> fill(out, EntityType.SLIME, size);
            case HUSKS -> fill(out, EntityType.HUSK, size);
            case STRAYS -> fill(out, EntityType.STRAY, size);
            case DROWNED -> fill(out, EntityType.DROWNED, size);
        }
        return out;
    }

    private static void fill(List<EntityType<? extends Mob>> out, EntityType<? extends Mob> type, int n) {
        for (int i = 0; i < n; i++) out.add(type);
    }

    /** A zombie, or now and then a zombie villager, as the biome's own list weighs them. */
    private static EntityType<? extends Mob> zombie(MobHomes.Weights w, RandomSource r) {
        return MobGroupRules.pick(new double[]{w.of(EntityType.ZOMBIE), w.of(EntityType.ZOMBIE_VILLAGER)}, r.nextDouble()) == 1
                ? EntityType.ZOMBIE_VILLAGER : EntityType.ZOMBIE;
    }

    /** A spider, or a cave spider where the biome has them (fewer than its list says). */
    private static EntityType<? extends Mob> spider(MobHomes.Weights w, RandomSource r) {
        double cave = w.of(EntityType.CAVE_SPIDER) * MobGroupRules.CAVE_SPIDER_DAMPING;
        return MobGroupRules.pick(new double[]{w.of(EntityType.SPIDER), cave}, r.nextDouble()) == 1 ? EntityType.CAVE_SPIDER : EntityType.SPIDER;
    }

    /** A spot for a member round {@code at}: on the ground for walkers, in the water for drowned. */
    @Nullable
    private static BlockPos near(ServerLevel level, BlockPos at, SpawnPlacements.Type placement, RandomSource r) {
        int s = MobGroupRules.SPREAD;
        int x = at.getX() + r.nextInt(2 * s + 1) - s, z = at.getZ() + r.nextInt(2 * s + 1) - s;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos(x, at.getY(), z);
        if (!level.isLoaded(q)) return null;
        if (placement == SpawnPlacements.Type.IN_WATER) {
            q.setY(at.getY() + r.nextInt(MobGroupRules.SPREAD_UP + MobGroupRules.SPREAD_DOWN + 1) - MobGroupRules.SPREAD_DOWN);
            return level.getFluidState(q).is(FluidTags.WATER) ? q.immutable() : null;
        }
        // down from a little over the leader's height to the first floor
        for (int y = at.getY() + MobGroupRules.SPREAD_UP; y >= at.getY() - MobGroupRules.SPREAD_DOWN; y--) {
            q.setY(y);
            if (open(level, q) && standable(level, q)) return q.immutable();
        }
        return null;
    }

    /**
     * A new mob of {@code type} standing at {@code pos} when a natural spawn may come out there (not yet in the world),
     * else null: vanilla's distances, the spot's own kind of place, room for its box (and none of the group's already
     * there), and its kind's spawn rules.
     */
    @Nullable
    private static Mob tryAt(ServerLevel level, EntityType<? extends Mob> type, BlockPos pos, @Nullable ServerPlayer forPlayer, List<Mob> placed) {
        if (!level.isNaturalSpawningAllowed(pos)) return null;
        double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
        Vec3 c = new Vec3(x, y, z);
        double nearest = nearestPlayer(level, c);
        double fromPlayer = forPlayer != null ? forPlayer.position().distanceTo(c) : nearest;
        if (!MobGroupRules.spotOk(nearest, fromPlayer, Vec3.atCenterOf(level.getSharedSpawnPos()).distanceTo(c))) return null;
        if (!NaturalSpawner.isSpawnPositionOk(SpawnPlacements.getPlacementType(type), level, pos, type)) return null;
        AABB box = type.getAABB(x, y, z);
        if (!level.noCollision(box)) return null;
        for (Mob o : placed) if (o.getBoundingBox().intersects(box)) return null;
        Mob m = type.create(level);
        if (m == null) return null;
        m.moveTo(x, y, z, level.random.nextFloat() * 360.0F, 0.0F);
        return rulesAllow(level, m, type, pos) ? m : null;
    }

    /** The kind's own spawn rules, through Forge's event so other mods still have their say. */
    private static boolean rulesAllow(ServerLevel level, Mob m, EntityType<?> type, BlockPos pos) {
        if (type != EntityType.SLIME && type != EntityType.DROWNED) return ForgeEventFactory.checkSpawnPosition(m, level, MobSpawnType.NATURAL);
        // vanilla keeps slimes to swamps and slime chunks and lets drowned up only one try in fifteen or forty; in
        // their homes they come out as other monsters do, where it is dark enough
        MobSpawnEvent.PositionCheck ev = new MobSpawnEvent.PositionCheck(m, level, MobSpawnType.NATURAL, null);
        MinecraftForge.EVENT_BUS.post(ev);
        if (ev.getResult() != Event.Result.DEFAULT) return ev.getResult() == Event.Result.ALLOW;
        if (level.getDifficulty() == Difficulty.PEACEFUL || !Monster.isDarkEnoughToSpawn(level, pos, level.random)) return false;
        boolean place = type == EntityType.DROWNED
                ? level.getFluidState(pos).is(FluidTags.WATER) && level.getFluidState(pos.below()).is(FluidTags.WATER)
                : Mob.checkMobSpawnRules(EntityType.SLIME, level, MobSpawnType.NATURAL, pos, level.random);
        return place && m.checkSpawnObstruction(level);
    }

    private static double nearestPlayer(ServerLevel level, Vec3 at) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) best = Math.min(best, p.position().distanceToSqr(at));
        return Math.sqrt(best);
    }

    // ------------------------------------------------------------------------------------------------ a lone enderman

    static int endermenRound(ServerLevel level, Vec3 at) {
        double r = MobGroupRules.DESPAWN;
        return level.getEntitiesOfClass(EnderMan.class, new AABB(at, at).inflate(r), e -> e.isAlive() && e.position().distanceToSqr(at) <= r * r).size();
    }

    /** One enderman on the ground or a cave floor round {@code p}, alone, where the biome has endermen. */
    private static void spawnEnderman(ServerLevel level, ServerPlayer p) {
        BlockPos at = level.random.nextBoolean() ? groundOnRing(level, p) : caveFloor(level, p);
        if (at == null || !MobHomes.endermen(level, at)) return;
        Mob m = tryAt(level, EntityType.ENDERMAN, at, p, List.of());
        if (m == null) return;
        ForgeEventFactory.onFinalizeSpawn(m, level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
        level.addFreshEntityWithPassengers(m);
        endermen++;
    }

    // ------------------------------------------------------------------------------------------------ for the commands

    /** The kind of place a home is looked for in. */
    static Look lookFor(Home home) {
        return switch (home) {
            case GREEN_CAVES -> Look.CAVE;
            case WATER -> Look.WATER;
            default -> Look.GROUND;
        };
    }

    /** A group of {@code kind} near {@code p}, at the first place of its home that takes it, whatever the counts; the leader or null. */
    @Nullable
    static Mob spawnFor(ServerLevel level, ServerPlayer p, Kind kind) {
        Look look = lookFor(kind.home);
        for (int t = 0; t < COMMAND_TRIES; t++) {
            Anchor a = anchor(level, p, look);
            if (a == null || a.home != kind.home) continue;
            Mob leader = spawnGroup(level, a.pos, kind, p);
            if (leader != null) return leader;
        }
        return null;
    }
}
