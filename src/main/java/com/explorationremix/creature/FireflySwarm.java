package com.explorationremix.creature;

import com.explorationremix.creature.firefly.Flock;
import com.explorationremix.creature.firefly.Perches;
import com.explorationremix.registry.EREntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Blue fireflies: one entity is a whole swarm of {@link #MIN_COUNT} to {@link #MAX_COUNT} of them, so a cave full of
 * them costs a few entities, not hundreds. The Blue Reaches' small lights, and what the Skein hunts.
 *
 * <p>The server moves only the swarm's centre. It drifts slowly through the open air of its cave, never more than
 * {@link #LEASH} blocks from where it came into the world nor into another biome, and keeps out of rock and water.
 * Now and then, after at least fifteen seconds in the air, it picks a patch of floor, wall or ceiling a few blocks off,
 * flies in and settles there for ten to forty seconds, then takes off again. It takes off at once when a player comes
 * within {@link #SHY} blocks or something strikes at it; a strike (a hit, an explosion, a hunter's jaws) also
 * scatters it, and it flees for a few seconds and keeps off the rock a while. {@link #bite} takes fireflies away (the
 * Skein eating); a swarm with none left is gone. Left alone for a couple of minutes, a swarm slowly grows back
 * towards the number it began with, a firefly a minute.
 *
 * <p>Each client simulates the fireflies themselves round the centre ({@link Flock}): a loose, looping flock in flight
 * that bursts apart when the swarm scatters and gathers again; landed, each firefly flies to a point of its own on
 * the real block faces round the landing anchor ({@link Perches}) and sits there, its light breathing slowly, out of
 * step with the rest ({@link com.explorationremix.creature.firefly.Pulse}). The server syncs what the flock needs:
 * flying or landed, the anchor and the side its rock is on, the count, a seed (so every client sees the same
 * fireflies) and each scatter with where it came from.
 *
 * <p>It is no mob: it has no health, never blocks anyone, is not pushed by water, pistons or other entities, sets off
 * no pressure plates, and an arrow flies through it. A swing at it only scatters it. It makes no sound. Being no mob,
 * its type is of the MISC category, not AMBIENT: it counts against no mob cap (an ambient one would crowd the bats out
 * of its own caves) and wants no attributes. All the same it goes away as ambient mobs do, once no player has been near
 * it a while ({@link #checkDespawn}, with distances of its own).
 */
public class FireflySwarm extends Entity {
    // ------------------------------------------------------------------------------------------------ tuning
    public static final int MIN_COUNT = 8, MAX_COUNT = 24;
    /** It keeps within this many blocks of where it came into the world, sideways, and up or down. */
    static final double LEASH = 12.0, LEASH_UP = 6.0;
    /** The centre's drifting and fleeing speeds (blocks a tick), and how much of the way to its wanted motion it turns a tick. */
    static final double DRIFT = 0.05, FLEE = 0.14, STEER = 0.08;
    /** A player this near (blocks): a landed swarm takes off and a flying one drifts away from them. */
    public static final double SHY = 3.0;
    /** It picks its patch to land on within this of its centre, and never this near a player. */
    static final int PERCH_REACH = 4;
    static final double PERCH_SHY = 5.0;
    /**
     * Landed this long (ticks: 10 to 40 s); in the air at least {@link #FLY_MIN} first (15 s), then a chance a tick
     * (another 20 s on average), so a swarm spends more of its time flying than sitting.
     */
    static final int LAND_MIN = 200, LAND_MAX = 800, FLY_MIN = 300, LAND_CHANCE = 400;
    /** Flying in to its patch takes no longer than this, or it gives the patch up. */
    static final int APPROACH_TICKS = 300;
    /** After a scare it flees this long, and keeps off the rock at least {@link #SCARE_REST} (to twice that). */
    static final int FLEE_TICKS = 60, SCARE_REST = 300;
    /** Left alone (no bite, no strike) this long, it gains a firefly, and another each {@link #REGROW_EVERY}, up to its full count. */
    static final int REGROW_AFTER = 2400, REGROW_EVERY = 1200;
    /** Half the box's height: the box sits round the centre. */
    static final double HALF = 0.3;
    /** Drawn while within this many blocks (times the view-distance scale): a light in the dark carries far. */
    static final double SEEN_FROM = 96.0;
    /** Random points it tries for its next one, at each pick. */
    static final int PICK_TRIES = 12;
    /** After a pick that found nowhere to go, it hovers this long (ticks) before it tries again, not every tick. */
    static final int REPICK = 20;
    /**
     * An ambient mob's despawn distances, kept here rather than read from its type's category (MISC, which vanilla
     * despawns nothing by): gone at once with no player within {@link #DESPAWN_FAR} blocks, and in time with none
     * within {@link #DESPAWN_NEAR}.
     */
    static final double DESPAWN_FAR = 128.0, DESPAWN_NEAR = 32.0;

    // ------------------------------------------------------------------------------------------------ synced
    private static final EntityDataAccessor<Boolean> DATA_LANDED = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.BOOLEAN);
    /** The open block it landed in, and the side of it its rock is on (a Direction's 3D value). */
    private static final EntityDataAccessor<BlockPos> DATA_ANCHOR = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Byte> DATA_ROCK = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_COUNT = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEED = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.INT);
    /** How many times it has been scattered, and where the last strike came from (the clients burst the flock on each). */
    private static final EntityDataAccessor<Integer> DATA_SCATTERS = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_STRIKE = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.VECTOR3);

    // ------------------------------------------------------------------------------------------------ server
    @Nullable private Vec3 home;              // where it came into the world: it keeps near it
    @Nullable private ResourceKey<Biome> homeBiome;
    private int full = MIN_COUNT;             // the count it grows back to
    @Nullable private Vec3 target;            // the point it drifts towards
    private int retarget;                     // ticks until it picks a new one anyway
    private Vec3 drift = Vec3.ZERO;           // the centre's motion (blocks a tick)
    private int flightTicks, landTicks, restTicks, fleeTicks, aloneTicks, farTicks;
    @Nullable private BlockPos perch;         // flying in to land: the open block of its patch, its rock, the point it settles at
    @Nullable private Direction perchRock;
    @Nullable private Vec3 landAt;
    private int approachTicks;                // how long it has been flying in to it

    // ------------------------------------------------------------------------------------------------ client
    @Nullable private Flock flock;
    private int flockSeed, seenScatters;
    private boolean wasLanded, firstSight;
    @Nullable private BlockPos perchedAt;     // the anchor its fireflies were given points round (null while they fly)
    private int settleWait;
    private List<Perches.Spot> spots = List.of();
    private int[] spotOrder = new int[0];
    @Nullable private Vec3 lastStrike;
    private int strikeTicks;
    private double lerpX, lerpY, lerpZ;
    private int lerpSteps;

    public FireflySwarm(EntityType<? extends FireflySwarm> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
        if (!level.isClientSide) {
            // a swarm summoned without spawn() is a swarm all the same
            entityData.set(DATA_SEED, random.nextInt());
            setCount(MIN_COUNT + random.nextInt(MAX_COUNT - MIN_COUNT + 1));
            full = count();
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_LANDED, false);
        entityData.define(DATA_ANCHOR, BlockPos.ZERO);
        entityData.define(DATA_ROCK, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_COUNT, MIN_COUNT);
        entityData.define(DATA_SEED, 0);
        entityData.define(DATA_SCATTERS, 0);
        entityData.define(DATA_STRIKE, new Vector3f());
    }

    /**
     * Makes one swarm of {@code count} fireflies (at most {@link #MAX_COUNT}) with its centre at {@code at}, or in the
     * nearest open air within two blocks of it, and adds it to the level. Returns it, or null when there is no open
     * air there or the level would not take it.
     */
    @Nullable
    public static FireflySwarm spawn(ServerLevel level, Vec3 at, int count) {
        if (count <= 0) return null;
        Vec3 c = at;
        BlockPos p = BlockPos.containing(at);
        if (!open(level, p)) {
            BlockPos q = nearestOpen(level, p, 2);
            if (q == null) return null;
            c = Vec3.atCenterOf(q);
        }
        FireflySwarm s = EREntities.FIREFLY_SWARM.get().create(level);
        if (s == null) return null;
        s.moveTo(c.x, c.y - HALF, c.z, 0.0F, 0.0F);
        s.home = c;
        s.setCount(count);
        s.full = s.count();
        return level.addFreshEntity(s) ? s : null;
    }

    // ------------------------------------------------------------------------------------------------ the contract

    /** Whether it has settled on the rock. */
    public boolean landed() { return entityData.get(DATA_LANDED); }
    /** How many fireflies it has. */
    public int count() { return entityData.get(DATA_COUNT); }
    /** The swarm's centre now: the middle of its box (when landed, just off the face of its patch). */
    public Vec3 centre() { return position().add(0.0, HALF, 0.0); }

    /**
     * A hunter takes up to {@code n} fireflies; the rest scatter away from it. Returns how many it took. A swarm left
     * with none is gone.
     */
    public int bite(int n, @Nullable Entity eater) {
        if (level().isClientSide || isRemoved() || n <= 0) return 0;
        int taken = Math.min(n, count());
        if (taken <= 0) return 0;
        setCount(count() - taken);
        scatter(eater != null ? eater.getBoundingBox().getCenter() : centre());
        if (count() <= 0) discard();
        return taken;
    }

    /** Something struck at the swarm from {@code from}: it takes off if it had landed, bursts apart and flees. */
    public void scatter(Vec3 from) {
        if (level().isClientSide || isRemoved()) return;
        entityData.set(DATA_STRIKE, new Vector3f((float) from.x, (float) from.y, (float) from.z));
        entityData.set(DATA_SCATTERS, entityData.get(DATA_SCATTERS) + 1);
        aloneTicks = 0;
        if (landed()) takeOff(from);
        else {
            abandonLanding();
            target = pickTarget(from);
            retarget = target == null ? REPICK : 80;
        }
        fleeTicks = FLEE_TICKS;
        restTicks = Math.max(restTicks, SCARE_REST + random.nextInt(SCARE_REST + 1));
    }

    // ------------------------------------------------------------------------------------------------ the rest of its state

    /** The open block it landed in (meaningful while landed). */
    public BlockPos anchor() { return entityData.get(DATA_ANCHOR); }
    /** The side of the anchor its rock is on: DOWN for a floor, UP for a ceiling, else a wall. */
    public Direction anchorRock() { return Direction.from3DDataValue(entityData.get(DATA_ROCK)); }
    /** The seed every client builds its fireflies from. */
    public int seed() { return entityData.get(DATA_SEED); }
    /** The count it grows back to. */
    public int fullCount() { return full; }
    private void setCount(int n) { entityData.set(DATA_COUNT, Mth.clamp(n, 0, MAX_COUNT)); }
    private void setCentre(Vec3 c) { setPos(c.x, c.y - HALF, c.z); }

    // ------------------------------------------------------------------------------------------------ what it is not

    @Override public boolean isPickable() { return !isRemoved(); }      // a swing at it scatters it (see hurt)
    @Override public boolean canBeHitByProjectile() { return false; }  // an arrow flies through
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public PushReaction getPistonPushReaction() { return PushReaction.IGNORE; }
    @Override public boolean isIgnoringBlockTriggers() { return true; }  // no pressure plates, no tripwires
    @Override public boolean fireImmune() { return true; }
    @Override public boolean canChangeDimensions() { return false; }
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(landed() ? 3.0 : 4.5); }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        double r = SEEN_FROM * getViewScale();
        return d < r * r;
    }

    /** A hit does it no harm: it scatters away from whatever struck it. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isInvulnerableTo(source)) return false;
        Entity direct = source.getDirectEntity();
        Vec3 from = direct != null ? direct.getEyePosition() : source.getSourcePosition();
        if (from != null) scatter(from);
        return false;
    }

    /**
     * As ambient mobs: gone at once far from every player, and in time once none has come near for a while. Its own
     * distances ({@link #DESPAWN_FAR}, {@link #DESPAWN_NEAR}), not its category's: the server calls this for every
     * entity it ticks, whatever its category.
     */
    @Override
    public void checkDespawn() {
        Player p = level().getNearestPlayer(this, -1.0);
        if (p == null) return;
        double d = p.distanceToSqr(this);
        if (d > DESPAWN_FAR * DESPAWN_FAR) { discard(); return; }
        if (d > DESPAWN_NEAR * DESPAWN_NEAR) {
            if (++farTicks > 600 && random.nextInt(800) == 0) discard();
        } else {
            farTicks = 0;
        }
    }

    // ------------------------------------------------------------------------------------------------ the server's tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientTick();
            return;
        }
        if (count() <= 0) { discard(); return; }
        if (home == null) home = centre();
        regrow();
        if (landed()) landedTick();
        else flyTick();
    }

    private void regrow() {
        aloneTicks++;
        if (count() < full && aloneTicks >= REGROW_AFTER && (aloneTicks - REGROW_AFTER) % REGROW_EVERY == 0) setCount(count() + 1);
    }

    private void flyTick() {
        flightTicks++;
        if (restTicks > 0) restTicks--;
        if (fleeTicks > 0) fleeTicks--;
        if (retarget > 0) retarget--;
        Vec3 c = centre();
        // a player close by: away from them, and no landing near them for now
        if (tickCount % 5 == 0) {
            Player p = nearPlayer(c, SHY + 1.0);
            if (p != null) {
                abandonLanding();
                Vec3 away = pickTarget(p.getEyePosition());
                if (away != null) { target = away; retarget = 60; }
                fleeTicks = Math.max(fleeTicks, 10);         // briskly, not in a panic
                restTicks = Math.max(restTicks, 100);
            }
        }
        if (landAt == null && restTicks == 0 && flightTicks > FLY_MIN && random.nextInt(LAND_CHANCE) == 0) planLanding();
        if (landAt != null && ++approachTicks > APPROACH_TICKS) abandonLanding();   // it cannot get there: another time
        if (landAt != null) target = landAt;
        else if (target == null ? retarget <= 0 : (retarget <= 0 || c.distanceToSqr(target) < 0.6 * 0.6)) {
            // with no target it waits out retarget too: a pick that found nothing is tried again REPICK ticks on
            target = pickTarget(null);
            retarget = target == null ? REPICK : 100 + random.nextInt(140);
        }

        Vec3 want = Vec3.ZERO;
        if (target != null) {
            Vec3 to = target.subtract(c);
            double d = to.length();
            double speed = fleeTicks > 0 ? FLEE : DRIFT;
            if (landAt != null) speed = Math.min(speed, 0.015 + d * 0.06);   // slowing in to its patch
            if (d > 1.0E-4) want = to.scale(Math.min(speed, d) / d);
        }
        // a slow rise and fall, so it never goes in dead straight lines
        want = want.add(0.0, Math.sin((tickCount + seed()) * 0.07) * 0.008, 0.0);
        drift = drift.add(want.subtract(drift).scale(STEER));
        Vec3 next = c.add(drift);
        BlockPos here = BlockPos.containing(c);
        if (open(level(), here) && !open(level(), BlockPos.containing(next))) {
            // rock or water ahead: back off and think again
            drift = drift.scale(-0.4);
            abandonLanding();
            target = null;
            retarget = 0;                                    // a new one at once
            return;
        }
        if (!open(level(), here)) next = c.add(0.0, 0.08, 0.0).add(drift);   // shut in (a block set on it): it works its way out
        setCentre(next);
        if (landAt != null && next.distanceToSqr(landAt) < 0.3 * 0.3) land();
    }

    private void landedTick() {
        if (landTicks > 0) landTicks--;
        if (tickCount % 4 == 0) {
            Player p = nearPlayer(centre(), SHY);
            if (p != null) { takeOff(p.getEyePosition()); return; }
        }
        if (tickCount % 20 == 0 && !perchHolds(anchor(), anchorRock())) { takeOff(null); return; }
        if (landTicks <= 0) takeOff(null);
    }

    /** The nearest player (not a spectator) whose body comes within {@code range} of the point, or null. */
    @Nullable
    private Player nearPlayer(Vec3 at, double range) {
        Player p = level().getNearestPlayer(at.x, at.y, at.z, range + 2.0, EntitySelector.NO_SPECTATORS);
        return p != null && p.getBoundingBox().distanceToSqr(at) < range * range ? p : null;
    }

    private void land() {
        if (perch == null || perchRock == null || landAt == null) return;
        setCentre(landAt);
        drift = Vec3.ZERO;
        entityData.set(DATA_ANCHOR, perch);
        entityData.set(DATA_ROCK, (byte) perchRock.get3DDataValue());
        entityData.set(DATA_LANDED, true);
        landTicks = LAND_MIN + random.nextInt(LAND_MAX - LAND_MIN + 1);
        abandonLanding();
    }

    /** Off the rock: away from {@code from} if something scared it, else wherever it likes. */
    private void takeOff(@Nullable Vec3 from) {
        entityData.set(DATA_LANDED, false);
        abandonLanding();
        flightTicks = 0;
        landTicks = 0;
        drift = Vec3.atLowerCornerOf(anchorRock().getOpposite().getNormal()).scale(0.03);   // up off its face
        if (from != null) {
            fleeTicks = Math.max(fleeTicks, 30);
            restTicks = Math.max(restTicks, SCARE_REST);
        }
        target = pickTarget(from);
        retarget = target == null ? REPICK : 100;
    }

    private void abandonLanding() {
        approachTicks = 0;
        perch = null;
        perchRock = null;
        landAt = null;
    }

    /** Looks for a patch to land on near it; if one is found it flies in to it. */
    private void planLanding() {
        Perch p = findPerch(16, PERCH_REACH);
        if (p == null) return;
        perch = p.pos;
        perchRock = p.rock;
        landAt = landingPoint(p.pos, p.rock);
        target = landAt;
    }

    /**
     * For /remix fireflies land: settles at once on the best patch within a few blocks (searched block by block), for
     * the longest stay. Returns false when there is no patch to land on.
     */
    public boolean landNow() {
        if (landed()) takeOff(null);
        Perch p = findPerch(0, PERCH_REACH + 2);
        if (p == null) return false;
        perch = p.pos;
        perchRock = p.rock;
        landAt = landingPoint(p.pos, p.rock);
        land();
        landTicks = LAND_MAX;
        return true;
    }

    /** Where the centre sits on a patch: in the open block, a little off its rock's face. */
    private static Vec3 landingPoint(BlockPos pos, Direction rock) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(rock.getNormal()).scale(0.25));
    }

    private record Perch(BlockPos pos, Direction rock) {}

    /**
     * A patch to land on within {@code reach} of the centre: an open block with a sturdy face of rock beside it, in
     * the open air the swarm can see from where it is, away from players, with as many faces round it as can be found
     * (a patch, not a lone ledge). {@code tries} random blocks are tried, or every block when it is 0.
     */
    @Nullable
    private Perch findPerch(int tries, int reach) {
        Level level = level();
        Vec3 c = centre();
        BlockPos at = BlockPos.containing(c);
        List<BlockPos> blocks = new ArrayList<>();
        if (tries > 0) {
            for (int i = 0; i < tries; i++)
                blocks.add(at.offset(random.nextInt(2 * reach + 1) - reach, random.nextInt(2 * reach + 1) - reach, random.nextInt(2 * reach + 1) - reach));
        } else {
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-reach, -reach, -reach), at.offset(reach, reach, reach))) blocks.add(p.immutable());
        }
        Perch best = null;
        double bestScore = 2.5;
        for (BlockPos p : blocks) {
            if (!level.isLoaded(p) || !open(level, p)) continue;
            List<Direction> rock = new ArrayList<>(6);
            for (Direction d : Direction.values()) if (sturdy(level, p, d)) rock.add(d);
            if (rock.isEmpty()) continue;
            Vec3 pc = Vec3.atCenterOf(p);
            if (!withinLeash(pc) || nearPlayer(pc, PERCH_SHY) != null) continue;
            double score = patch(level, p) - (tries == 0 ? 0.35 * Math.sqrt(p.distToCenterSqr(c)) : 0.0);
            if (score <= bestScore) continue;
            Direction d = rock.get(random.nextInt(rock.size()));      // floor, wall or ceiling, whichever it has
            if (!clear(c, landingPoint(p, d))) continue;
            best = new Perch(p, d);
            bestScore = score;
        }
        return best;
    }

    /** How many rock faces the open blocks round p show (up to 12): a patch worth settling on. */
    private static int patch(Level level, BlockPos p) {
        int faces = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    q.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (!open(level, q)) continue;
                    for (Direction d : Direction.values()) if (sturdy(level, q, d)) faces++;
                }
        return Math.min(12, faces);
    }

    /** Whether its patch is still there: the anchor open, its rock still a face to sit on. */
    private boolean perchHolds(BlockPos pos, Direction rock) {
        return !level().isLoaded(pos) || (open(level(), pos) && sturdy(level(), pos, rock));
    }

    /**
     * A point to drift to: a few blocks off in open air (room round it, no water), in sight, within its leash and its
     * own biome; away from {@code away} when that is given. Far out from home it wants one near home; when none of
     * those is in sight (home is round a bend of its cave) it finds its way back a hop at a time ({@link #wayBack}).
     * Null when it finds none this time.
     */
    @Nullable
    private Vec3 pickTarget(@Nullable Vec3 away) {
        Vec3 c = centre();
        Vec3 h = home != null ? home : c;
        boolean far = away == null && (!withinLeash(c) || c.distanceToSqr(h) > LEASH * LEASH * 0.4);
        for (int i = 0; i < PICK_TRIES; i++) {
            Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian());
            if (dir.lengthSqr() < 1.0E-6) continue;
            dir = dir.normalize();
            double r = 3.0 + random.nextDouble() * 5.0;
            Vec3 from = c;
            if (away != null) {
                Vec3 off = c.subtract(away).multiply(1.0, 0.5, 1.0);
                if (off.lengthSqr() > 1.0E-4) dir = off.normalize().add(dir.scale(0.6)).normalize();
                r += 2.0;
            } else if (far) {
                from = h;                                  // far out: back towards home
                r = random.nextDouble() * 4.0;
            }
            Vec3 t = from.add(dir.scale(r));
            if (withinLeash(t) && canDriftTo(c, t)) return t;
        }
        return far ? wayBack(c, h) : null;
    }

    /**
     * Far out, with no point near home in sight (home is round a bend): an ordinary hop from where it is that brings
     * it nearer home, so it works its way back round the bend; failing that any hop within its leash, so it does not
     * hang in one place until home happens to come into sight. Out of its leash altogether (carried off), any hop
     * nearer home will do. Null when it finds neither this time.
     */
    @Nullable
    private Vec3 wayBack(Vec3 c, Vec3 h) {
        double now = c.distanceToSqr(h);
        boolean out = !withinLeash(c);
        Vec3 any = null;
        for (int i = 0; i < PICK_TRIES; i++) {
            Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian());
            if (dir.lengthSqr() < 1.0E-6) continue;
            Vec3 t = c.add(dir.normalize().scale(3.0 + random.nextDouble() * 5.0));
            boolean nearer = t.distanceToSqr(h) < now;
            if (nearer ? !(out || withinLeash(t)) : (any != null || !withinLeash(t))) continue;
            if (!canDriftTo(c, t)) continue;
            if (nearer) return t;
            any = t;
        }
        return any;
    }

    /** Whether t is open, roomy air in its home biome (and loaded) that it can see from c. */
    private boolean canDriftTo(Vec3 c, Vec3 t) {
        BlockPos tp = BlockPos.containing(t);
        return level().isLoaded(tp) && roomy(level(), tp) && inHomeBiome(tp) && clear(c, t);
    }

    private boolean withinLeash(Vec3 p) {
        if (home == null) return true;
        double dx = p.x - home.x, dz = p.z - home.z;
        return dx * dx + dz * dz <= LEASH * LEASH && Math.abs(p.y - home.y) <= LEASH_UP;
    }

    private boolean inHomeBiome(BlockPos p) {
        if (homeBiome == null) {
            if (home == null) return true;
            Optional<ResourceKey<Biome>> k = level().getBiome(BlockPos.containing(home)).unwrapKey();
            if (k.isEmpty()) return true;
            homeBiome = k.get();
        }
        return level().getBiome(p).is(homeBiome);
    }

    /** Nothing solid, and no water, between the two points. */
    private boolean clear(Vec3 a, Vec3 b) {
        return level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this)).getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------------------------------------ blocks

    /** Open air a firefly can be in: nothing to bump into, no water or lava. */
    public static boolean open(BlockGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    /** Open, with open air on at least three of its six sides (a tunnel two blocks high will do): room to drift through. */
    private static boolean roomy(BlockGetter level, BlockPos p) {
        if (!open(level, p)) return false;
        int shut = 0;
        for (Direction d : Direction.values()) if (!open(level, p.relative(d)) && ++shut > 3) return false;
        return true;
    }

    /** Whether the block beside the open block p in direction d shows p a face a firefly can sit on. */
    private static boolean sturdy(BlockGetter level, BlockPos p, Direction d) {
        BlockPos q = p.relative(d);
        return level.getBlockState(q).isFaceSturdy(level, q, d.getOpposite());
    }

    @Nullable
    private static BlockPos nearestOpen(BlockGetter level, BlockPos c, int reach) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, reach, reach))) {
            double d = p.distSqr(c);
            if (d < bestD && open(level, p)) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Count", count());
        tag.putInt("Full", full);
        tag.putInt("Seed", seed());
        if (home != null) {
            tag.putDouble("HomeX", home.x);
            tag.putDouble("HomeY", home.y);
            tag.putDouble("HomeZ", home.z);
        }
        tag.putBoolean("Landed", landed());
        tag.put("Anchor", NbtUtils.writeBlockPos(anchor()));
        tag.putByte("Rock", entityData.get(DATA_ROCK));
        tag.putInt("LandTicks", landTicks);
        tag.putInt("Rest", restTicks);
        tag.putInt("Alone", aloneTicks);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Count")) setCount(tag.getInt("Count"));
        full = tag.contains("Full") ? Mth.clamp(tag.getInt("Full"), 1, MAX_COUNT) : Math.max(1, count());
        if (tag.contains("Seed")) entityData.set(DATA_SEED, tag.getInt("Seed"));
        home = tag.contains("HomeX") ? new Vec3(tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ")) : null;
        homeBiome = null;
        entityData.set(DATA_ANCHOR, NbtUtils.readBlockPos(tag.getCompound("Anchor")));
        entityData.set(DATA_ROCK, (byte) Mth.clamp(tag.getByte("Rock"), 0, 5));
        entityData.set(DATA_LANDED, tag.getBoolean("Landed"));
        landTicks = tag.getInt("LandTicks");
        if (landed() && landTicks <= 0) landTicks = LAND_MIN;
        restTicks = tag.getInt("Rest");
        aloneTicks = tag.getInt("Alone");
    }

    /** For /remix fireflies list: what it is doing. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        if (landed()) {
            Direction r = anchorRock();
            b.append("landed on the ").append(r == Direction.DOWN ? "floor" : r == Direction.UP ? "ceiling" : "wall (" + r.getName() + ")")
                    .append(" at ").append(anchor().toShortString()).append(", ").append(landTicks / 20).append("s more");
        } else {
            b.append(landAt != null ? "flying in to land at " + (perch == null ? "?" : perch.toShortString()) : "flying");
            if (fleeTicks > 0) b.append(" (fleeing)");
            else if (restTicks > 0) b.append(" (no landing for ").append(restTicks / 20).append("s)");
        }
        b.append(", ").append(count()).append(" of ").append(full).append(" fireflies, seed ").append(seed());
        if (home != null) b.append(String.format(java.util.Locale.ROOT, ", %.0f blocks from home", Math.sqrt(centre().distanceToSqr(home))));
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ client

    /** The client's fireflies (null on the server, and before its first tick). */
    @Nullable public Flock flock() { return flock; }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        // glide to where the server says over the next few ticks instead of jumping there
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpSteps = Math.max(1, steps);
    }

    private void clientTick() {
        if (lerpSteps > 0) {
            setPos(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps, getZ() + (lerpZ - getZ()) / lerpSteps);
            lerpSteps--;
        }
        Vec3 c = centre();
        boolean landed = landed();
        Flock f = flock;
        if (f == null || flockSeed != seed()) {
            // first sight of it: its fireflies already about it (sitting on their faces, if it has landed)
            f = flock = new Flock(seed());
            flockSeed = seed();
            seenScatters = entityData.get(DATA_SCATTERS);
            for (int i = 0; i < Math.min(Flock.MAX, count()); i++) f.add(c.x, c.y, c.z);
            wasLanded = landed;
            firstSight = true;
            perchedAt = null;
            settleWait = 0;
        }
        if (landed) {
            // newly landed (or landed somewhere else): each firefly is given its point; with no faces found (the
            // blocks not there yet) it tries again in a second
            if (!anchor().equals(perchedAt) && --settleWait <= 0) {
                if (settle(f, firstSight)) perchedAt = anchor();
                else settleWait = 20;
            }
        } else if (wasLanded || perchedAt != null) {
            f.takeOff();
            perchedAt = null;
        }
        wasLanded = landed;
        firstSight = false;

        int scatters = entityData.get(DATA_SCATTERS);
        if (scatters != seenScatters) {
            seenScatters = scatters;
            Vector3f s = entityData.get(DATA_STRIKE);
            lastStrike = new Vec3(s.x, s.y, s.z);
            strikeTicks = 10;
            f.burst(s.x, s.y, s.z);
        } else if (strikeTicks > 0) strikeTicks--;

        // the count: eaten ones go from where the jaws closed, new ones join at the centre (and find a perch if it sits)
        int want = Math.min(Flock.MAX, count());
        while (f.size() > want) {
            if (lastStrike != null && strikeTicks > 0) f.removeNearest(lastStrike.x, lastStrike.y, lastStrike.z, 1);
            else f.remove(f.size() - 1);
        }
        while (f.size() < want) {
            int i = f.add(c.x, c.y, c.z);
            // the points were chosen for a full flock in order, so the newcomer's is the next one nobody has
            if (perchedAt != null && i >= 0 && i < spotOrder.length) f.settleOn(i, spots.get(spotOrder[i]), 0);
        }
        f.step(c.x, c.y, c.z, (x, y, z) -> open(level(), BlockPos.containing(x, y, z)));
    }

    /**
     * Gives every firefly a point of its own on the faces round the anchor: seated there at once on first sight, else
     * flying in one by one. False when there is nothing to sit on (they keep flying round the centre).
     */
    private boolean settle(Flock f, boolean atOnce) {
        BlockPos a = anchor();
        spots = perchSpots(a);
        Vec3 at = landingPoint(a, anchorRock());
        spotOrder = Perches.pick(spots, at.x, at.y, at.z, Flock.MAX, seed());
        if (spotOrder.length == 0) return false;
        // each spot, in the order chosen, goes to the nearest firefly not yet given one
        boolean[] given = new boolean[f.size()];
        for (int k = 0; k < f.size(); k++) {
            Perches.Spot s = spots.get(spotOrder[k]);
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < f.size(); i++) {
                if (given[i]) continue;
                double d = Mth.square(f.x(i) - s.x()) + Mth.square(f.y(i) - s.y()) + Mth.square(f.z(i) - s.z());
                if (d < bestD) { bestD = d; best = i; }
            }
            given[best] = true;
            if (atOnce) f.seat(best, s);
            else f.settleOn(best, s, random.nextInt(30));
        }
        return true;
    }

    /**
     * The points the fireflies may sit on round the anchor: the faces of rock seen from the open blocks within two
     * blocks of it that open air joins to it (so none sits behind a wall).
     */
    private List<Perches.Spot> perchSpots(BlockPos anchor) {
        Level level = level();
        List<Perches.Spot> out = new ArrayList<>();
        int r = 2;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        if (!open(level, anchor)) return out;
        todo.add(anchor);
        seen.add(anchor);
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (open(level, q)) {
                    if (Math.abs(q.getX() - anchor.getX()) <= r && Math.abs(q.getY() - anchor.getY()) <= r && Math.abs(q.getZ() - anchor.getZ()) <= r
                            && seen.add(q)) todo.add(q);
                } else if (sturdy(level, p, d)) {
                    Perches.face(p.getX(), p.getY(), p.getZ(), d.getStepX(), d.getStepY(), d.getStepZ(), seed(), out);
                }
            }
        }
        return out;
    }
}
