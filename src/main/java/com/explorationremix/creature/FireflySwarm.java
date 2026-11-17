package com.explorationremix.creature;

import com.explorationremix.creature.firefly.Flock;
import com.explorationremix.creature.firefly.Perches;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraftforge.entity.PartEntity;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Blue fireflies: one entity is a whole swarm of them (30 to 60, now and then a huge one of 120 to 160, see
 * {@link BlueSpawnRules#swarmSize}; at most {@link #MAX_COUNT}), so a cave full of them costs a few entities, not
 * thousands. The Blue Reaches' small lights, and what the Skein hunts.
 *
 * <p>The server moves only the swarm's centre. It drifts slowly through the open air of its cave, never more than
 * {@link #LEASH} blocks from its home nor into another biome, and keeps out of rock and water. Now and then, after at
 * least fifteen seconds in the air, it picks a patch of floor, wall or ceiling a few blocks off, out of the reach of
 * every player, flies in and settles there for ten to forty seconds, then takes off again. A flying swarm lets players
 * be: its fireflies part round anyone moving through them and keep a little room round one standing among them (the
 * client's {@link Flock}). A landed swarm is wary: a player who comes within {@link #FLUSH} blocks of it in its sight
 * ({@link #FLUSH_SNEAK} sneaking, {@link #FLUSH_SPRINT} further running) startles it up off the rock with a soft
 * flutter of wings ({@link #flush}); it flies hard away from them until it is {@link #GOAL_MIN} to {@link #GOAL_MAX}
 * blocks off, makes that its home, and lands again there a few seconds later, so it can be followed from rock to
 * rock. A strike (a hit, an explosion, a hunter's jaws) scatters it, and it flees for a few seconds and keeps off the
 * rock a while. {@link #bite} takes fireflies away (the Skein eating); a swarm with none left is gone. Left alone for a
 * couple of minutes, a swarm slowly grows back towards the number it began with.
 *
 * <p>Each client simulates the fireflies themselves round the centre ({@link Flock}): a loose, looping flock in flight
 * that parts round the bodies moving among them, bursts apart when the swarm scatters and gathers again; landed, each
 * firefly flies to a point of its own on the real block faces round the landing anchor ({@link Perches}) and sits
 * there, its light breathing slowly, out of step with the rest ({@link com.explorationremix.creature.firefly.Pulse});
 * startled, they go up in a wave from the side nearest the player. The server syncs what the flock needs: flying or
 * landed, the anchor and the side its rock is on, the count, a seed (so every client sees the same fireflies), each
 * scatter with where it came from, and each startled take-off with who startled it.
 *
 * <p>It is no mob: it has no health, never blocks anyone, is not pushed by water, pistons or other entities, sets off
 * no pressure plates, and an arrow flies through it. A swing at it only scatters it. Being no mob, its type is of the
 * MISC category, not AMBIENT: it counts against no mob cap (an ambient one would crowd the bats out of its own caves)
 * and wants no attributes. All the same it goes away as ambient mobs do, once no player has been near it a while
 * ({@link #checkDespawn}, with distances of its own).
 */
public class FireflySwarm extends Entity {
    // ------------------------------------------------------------------------------------------------ tuning
    /** The most fireflies one swarm holds. */
    public static final int MAX_COUNT = Flock.MAX;
    /** It keeps within this many blocks of its home, sideways, and up or down. */
    static final double LEASH = 12.0, LEASH_UP = 6.0;
    /** The centre's drifting and fleeing speeds (blocks a tick), and how much of the way to its wanted motion it turns a tick. */
    static final double DRIFT = 0.05, FLEE = 0.14, STEER = 0.08;
    /**
     * A landed swarm takes off, startled, when a player comes within this many blocks of it (of its nearest
     * fireflies, about) and it can see them; within {@link #FLUSH_SNEAK} only, sneaking; {@link #FLUSH_SPRINT} further
     * off, running. Nearer than {@link #FLUSH_HEARD} it goes whether it sees them or not. (0.16.0, Taran's pick: about
     * 6 blocks, 3 sneaking; it was 3 for everyone.)
     */
    public static final double FLUSH = 6.0, FLUSH_SNEAK = 3.0, FLUSH_SPRINT = 2.0, FLUSH_HEARD = 2.0;
    /** It picks its patch to land on within this of its centre, and never where a player would startle it at once. */
    static final int PERCH_REACH = 4;
    static final double PERCH_SHY = FLUSH + 1.5;
    /**
     * Startled up, it flies at this speed away from whoever startled it, a hop at a time, until it is
     * {@link #GOAL_MIN} to {@link #GOAL_MAX} blocks from them (or after {@link #FLUSH_FLIGHT} ticks, or when it finds no
     * hop further off), then makes where it is its home and lands again {@link #RESETTLE_MIN} to {@link #RESETTLE_MAX}
     * ticks later. (0.16.0, Taran's pick: it lands again a little way off.)
     */
    static final double FLUSH_SPEED = 0.2, GOAL_MIN = 10.0, GOAL_MAX = 20.0;
    static final int FLUSH_FLIGHT = 200, RESETTLE_MIN = 60, RESETTLE_MAX = 160;
    /** Settling again after a startled flight, it looks for a patch this often (a chance a tick). */
    static final int RESETTLE_CHANCE = 15;
    /**
     * Landed this long (ticks: 10 to 40 s); in the air at least {@link #FLY_MIN} first (15 s), then a chance a tick
     * (another 20 s on average), so a swarm spends more of its time flying than sitting.
     */
    static final int LAND_MIN = 200, LAND_MAX = 800, FLY_MIN = 300, LAND_CHANCE = 400;
    /** Flying in to its patch takes no longer than this, or it gives the patch up. */
    static final int APPROACH_TICKS = 300;
    /** After a scare it flees this long, and keeps off the rock at least {@link #SCARE_REST} (to twice that). */
    static final int FLEE_TICKS = 60, SCARE_REST = 300;
    /**
     * Left alone (no bite, no strike) this long, it gains a firefly (one for each {@link #REGROW_SHARE} of its full
     * count), and as many again each {@link #REGROW_EVERY}, up to its full count: a big swarm grows back as fast as a
     * small one, for its size.
     */
    static final int REGROW_AFTER = 2400, REGROW_EVERY = 1200, REGROW_SHARE = 24;
    /** Half the box's height: the box sits round the centre. */
    static final double HALF = 0.3;
    /** Drawn while within this many blocks (times the view-distance scale): a light in the dark carries far. */
    static final double SEEN_FROM = 96.0;
    /** Random points it tries for its next one, at each pick; startled, for each hop away. */
    static final int PICK_TRIES = 12, FLEE_TRIES = 16;
    /** After a pick that found nowhere to go, it hovers this long (ticks) before it tries again, not every tick. */
    static final int REPICK = 20;
    /** Bodies further than this beyond the swarm's radius are not looked at by its fireflies. */
    static final double BODY_REACH = 6.0;
    /** A body moving further than this in a tick has teleported; one moving faster than {@link #FASTEST_BODY} counts as that fast (blocks a tick). */
    static final double TELEPORT = 4.0, FASTEST_BODY = 0.6;
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
    /** How many times it has been startled up off its patch, and where from (the clients lift the patch in a wave from there). */
    private static final EntityDataAccessor<Integer> DATA_FLUSHES = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> DATA_FLUSH_FROM = SynchedEntityData.defineId(FireflySwarm.class, EntityDataSerializers.VECTOR3);

    // ------------------------------------------------------------------------------------------------ server
    @Nullable private Vec3 home;              // where it came into the world, or last settled after a fright: it keeps near it
    @Nullable private ResourceKey<Biome> homeBiome;
    private int full = 1;                     // the count it grows back to
    @Nullable private Vec3 target;            // the point it drifts towards
    private int retarget;                     // ticks until it picks a new one anyway
    private Vec3 drift = Vec3.ZERO;           // the centre's motion (blocks a tick)
    private int flightTicks, landTicks, restTicks, fleeTicks, aloneTicks, farTicks;
    @Nullable private BlockPos perch;         // flying in to land: the open block of its patch, its rock, the point it settles at
    @Nullable private Direction perchRock;
    @Nullable private Vec3 landAt;
    private int approachTicks;                // how long it has been flying in to it
    // startled up off its patch: who from (and where they were, should they go), how far from them it wants to be,
    // how long it has flown; then whether it is looking to land again
    @Nullable private UUID startler;
    @Nullable private Vec3 startledFrom;
    private double goal;
    private int flushTicks;
    private boolean resettling;

    // ------------------------------------------------------------------------------------------------ client
    @Nullable private Flock flock;
    private int flockSeed, seenScatters, seenFlushes;
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
            setCount(BlueSpawnRules.swarmSize(random.nextDouble()));
            full = count();
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_LANDED, false);
        entityData.define(DATA_ANCHOR, BlockPos.ZERO);
        entityData.define(DATA_ROCK, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_COUNT, BlueSpawnRules.SWARM_MIN_COUNT);
        entityData.define(DATA_SEED, 0);
        entityData.define(DATA_SCATTERS, 0);
        entityData.define(DATA_STRIKE, new Vector3f());
        entityData.define(DATA_FLUSHES, 0);
        entityData.define(DATA_FLUSH_FROM, new Vector3f());
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
        endFlight(false);
        if (landed()) takeOff(from);
        else {
            abandonLanding();
            target = pickTarget(from);
            retarget = target == null ? REPICK : 80;
        }
        fleeTicks = FLEE_TICKS;
        restTicks = Math.max(restTicks, SCARE_REST + random.nextInt(SCARE_REST + 1));
    }

    /**
     * Player {@code p} startled the landed swarm: it goes up off the rock with a flutter (its fireflies in a wave from
     * the side nearest them, on every client) and flies hard away from them until it is {@link #GOAL_MIN} to
     * {@link #GOAL_MAX} blocks off, where it settles again a few seconds later. Nothing when it has not landed.
     */
    public void flush(Player p) {
        if (level().isClientSide || isRemoved() || !landed()) return;
        Vec3 from = p.getEyePosition();
        entityData.set(DATA_FLUSH_FROM, new Vector3f((float) from.x, (float) from.y, (float) from.z));
        entityData.set(DATA_FLUSHES, entityData.get(DATA_FLUSHES) + 1);
        takeOff(from);
        startler = p.getUUID();
        startledFrom = p.position();
        goal = GOAL_MIN + random.nextDouble() * (GOAL_MAX - GOAL_MIN);
        flushTicks = 0;
        resettling = false;
        target = fleeHop(startledFrom);
        retarget = target == null ? REPICK : 60;
        // the flutter: louder and lower for a bigger swarm
        float size = Math.min(1.0F, count() / 120.0F);
        level().playSound(null, getX(), getY(), getZ(), ERSounds.FIREFLY_FLUTTER.get(), SoundSource.AMBIENT,
                0.35F + 0.55F * size, 1.08F - 0.16F * size + (random.nextFloat() - 0.5F) * 0.1F);
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
    /** Whether it is flying off after a fright (startled up off its patch). */
    public boolean startled() { return startledFrom != null; }
    private void setCount(int n) { entityData.set(DATA_COUNT, Mth.clamp(n, 0, MAX_COUNT)); }
    private void setCentre(Vec3 c) { setPos(c.x, c.y - HALF, c.z); }

    /**
     * How far a landed swarm of {@code n} reaches from its anchor, about, to the nearest of its fireflies on the side
     * a player comes from: the patch they sit in ({@link Perches#pick} packs them about half a block apart).
     */
    static double patchExtent(int n) {
        return Math.min(Perches.reach(n), 0.3 + Math.sqrt(Math.max(0, n) * 0.3 / Math.PI));
    }

    /**
     * How near a player may come before a landed swarm takes off: {@link #FLUSH_SNEAK} sneaking, {@link #FLUSH} walking,
     * {@link #FLUSH} and {@link #FLUSH_SPRINT} running.
     */
    static double flushRange(boolean sneaking, boolean sprinting) {
        return sneaking ? FLUSH_SNEAK : sprinting ? FLUSH + FLUSH_SPRINT : FLUSH;
    }

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

    @Override
    public AABB getBoundingBoxForCulling() {
        int n = count();
        return getBoundingBox().inflate(landed() ? Perches.reach(n) + 1.0 : Flock.radius(n) + 2.7);
    }

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
        if (count() < full && aloneTicks >= REGROW_AFTER && (aloneTicks - REGROW_AFTER) % REGROW_EVERY == 0)
            setCount(Math.min(full, count() + Math.max(1, full / REGROW_SHARE)));
    }

    private void flyTick() {
        flightTicks++;
        if (restTicks > 0) restTicks--;
        if (fleeTicks > 0) fleeTicks--;
        if (retarget > 0) retarget--;
        Vec3 c = centre();
        // flying in to land where a player has come within reach of the patch: not now
        if (landAt != null && tickCount % 5 == 0 && nearPlayer(landAt, PERCH_SHY + patchExtent(count())) != null) {
            abandonLanding();
            target = null;
            retarget = REPICK;
        }
        if (startledFrom != null) fleeing(c);
        else if (landAt == null && restTicks == 0 && (resettling ? random.nextInt(RESETTLE_CHANCE) == 0
                : flightTicks > FLY_MIN && random.nextInt(LAND_CHANCE) == 0)) planLanding();
        if (landAt != null && ++approachTicks > APPROACH_TICKS) abandonLanding();   // it cannot get there: another time
        if (landAt != null) target = landAt;
        else if (startledFrom == null && (target == null ? retarget <= 0 : (retarget <= 0 || c.distanceToSqr(target) < 0.6 * 0.6))) {
            // with no target it waits out retarget too: a pick that found nothing is tried again REPICK ticks on
            target = pickTarget(null);
            retarget = target == null ? REPICK : 100 + random.nextInt(140);
        }

        Vec3 want = Vec3.ZERO;
        if (target != null) {
            Vec3 to = target.subtract(c);
            double d = to.length();
            double speed = startledFrom != null ? FLUSH_SPEED : fleeTicks > 0 ? FLEE : DRIFT;
            if (landAt != null) speed = Math.min(speed, 0.015 + d * 0.06);   // slowing in to its patch
            if (d > 1.0E-4) want = to.scale(Math.min(speed, d) / d);
        }
        // a slow rise and fall, so it never goes in dead straight lines
        want = want.add(0.0, Math.sin((tickCount + seed()) * 0.07) * 0.008, 0.0);
        // startled, it gets away quicker: it turns to its way faster
        drift = drift.add(want.subtract(drift).scale(startledFrom != null ? STEER * 2.0 : STEER));
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

    /**
     * Startled up off its patch: away from whoever startled it, a hop at a time, until it is {@link #goal} blocks from
     * them (or has flown {@link #FLUSH_FLIGHT} ticks, or finds no hop further off); then that is its home, and a few
     * seconds on it lands again.
     */
    private void fleeing(Vec3 c) {
        flushTicks++;
        Player p = startler != null && level() instanceof ServerLevel l && l.getEntity(startler) instanceof Player pl && pl.isAlive() ? pl : null;
        if (p != null && p.level() == level()) startledFrom = p.position();
        boolean far = c.distanceTo(startledFrom) >= goal;
        if (far || flushTicks > FLUSH_FLIGHT) { endFlight(true); return; }
        if (target == null ? retarget <= 0 : (retarget <= 0 || c.distanceToSqr(target) < 1.0)) {
            target = fleeHop(startledFrom);
            retarget = target == null ? REPICK : 60;
            if (target == null && flushTicks > 40) endFlight(true);   // nowhere further to go: it settles down here
        }
    }

    /**
     * The startled flight is over: with {@code settle}, where it is now becomes its home and it lands again a few
     * seconds on (out of every player's reach); without, it just stops fleeing (a strike took over).
     */
    private void endFlight(boolean settle) {
        if (startledFrom == null) return;
        startler = null;
        startledFrom = null;
        flushTicks = 0;
        target = null;
        retarget = REPICK;
        if (!settle) return;
        home = centre();
        flightTicks = 0;
        restTicks = RESETTLE_MIN + random.nextInt(RESETTLE_MAX - RESETTLE_MIN + 1);
        resettling = true;
    }

    /**
     * A hop away from {@code threat}, startled: of {@link #FLEE_TRIES} points four to ten blocks off, mostly away from
     * it, open, roomy and in sight (its leash does not hold it now), the one that takes it furthest from the threat;
     * null when none takes it further than it is.
     */
    @Nullable
    private Vec3 fleeHop(Vec3 threat) {
        Vec3 c = centre();
        Vec3 off = c.subtract(threat).multiply(1.0, 0.0, 1.0);
        Vec3 away = off.lengthSqr() > 1.0E-4 ? off.normalize() : new Vec3(random.nextGaussian(), 0.0, random.nextGaussian()).normalize();
        double now = c.distanceTo(threat);
        Vec3 best = null;
        double bestScore = now + 0.5;
        for (int i = 0; i < FLEE_TRIES; i++) {
            Vec3 dir = away.add(random.nextGaussian() * 0.55, random.nextGaussian() * 0.25 + 0.08, random.nextGaussian() * 0.55);
            if (dir.lengthSqr() < 1.0E-6) continue;
            Vec3 t = c.add(dir.normalize().scale(4.0 + random.nextDouble() * 6.0));
            double score = t.distanceTo(threat);
            if (score <= bestScore) continue;
            BlockPos tp = BlockPos.containing(t);
            if (!level().isLoaded(tp) || !roomy(level(), tp) || !inHomeBiome(tp) || !clear(c, t)) continue;
            best = t;
            bestScore = score;
        }
        return best;
    }

    private void landedTick() {
        if (landTicks > 0) landTicks--;
        if (tickCount % 2 == 0) {
            Player p = startlingPlayer();
            if (p != null) { flush(p); return; }
        }
        if (tickCount % 20 == 0 && !perchHolds(anchor(), anchorRock())) { takeOff(null); return; }
        if (landTicks <= 0) takeOff(null);
    }

    /**
     * The nearest player (no spectator) who has come near enough to startle it from its patch: within their
     * {@link #flushRange} of its nearest fireflies (about: {@link #patchExtent} from its anchor), and in its sight,
     * unless they are within {@link #FLUSH_HEARD}. Null when there is none.
     */
    @Nullable
    private Player startlingPlayer() {
        Vec3 c = centre();
        double extent = patchExtent(count());
        double reach = extent + FLUSH + FLUSH_SPRINT;
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : level().players()) {
            if (!EntitySelector.NO_SPECTATORS.test(p) || !p.isAlive()) continue;
            double d = Math.sqrt(p.getBoundingBox().distanceToSqr(c)) - extent;
            if (d > reach - extent || d >= bestD) continue;
            boolean sneaking = p.isShiftKeyDown() || p.isCrouching();
            if (d >= flushRange(sneaking, p.isSprinting())) continue;
            if (d >= FLUSH_HEARD && !clear(p.getEyePosition(), c)) continue;
            best = p;
            bestD = d;
        }
        return best;
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
        resettling = false;
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
        Perch p = findPerch(16, PERCH_REACH, true);
        if (p == null) return;
        perch = p.pos;
        perchRock = p.rock;
        landAt = landingPoint(p.pos, p.rock);
        target = landAt;
    }

    /**
     * For /remix fireflies land: settles at once on the best patch within a few blocks (searched block by block), for
     * the longest stay, wherever the players are. Returns false when there is no patch to land on.
     */
    public boolean landNow() {
        return landNow(LAND_MAX);
    }

    /** As {@link #landNow()}, staying {@code ticks} (for pictures, which take longer to settle than a stay lasts). */
    public boolean landNow(int ticks) {
        endFlight(false);
        if (landed()) takeOff(null);
        Perch p = findPerch(0, PERCH_REACH + 2, false);
        if (p == null) return false;
        perch = p.pos;
        perchRock = p.rock;
        landAt = landingPoint(p.pos, p.rock);
        land();
        landTicks = Math.max(1, ticks);
        return true;
    }

    /** Where the centre sits on a patch: in the open block, a little off its rock's face. */
    private static Vec3 landingPoint(BlockPos pos, Direction rock) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(rock.getNormal()).scale(0.25));
    }

    private record Perch(BlockPos pos, Direction rock) {}

    /**
     * A patch to land on within {@code reach} of the centre: an open block with a sturdy face of rock beside it, in
     * the open air the swarm can see from where it is, with as many faces round it as can be found (a patch, not a
     * lone ledge); with {@code shy}, none where a player would startle it at once. {@code tries} random blocks are
     * tried, or every block when it is 0.
     */
    @Nullable
    private Perch findPerch(int tries, int reach, boolean shy) {
        Level level = level();
        Vec3 c = centre();
        BlockPos at = BlockPos.containing(c);
        double keepOff = PERCH_SHY + patchExtent(count());
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
            if (!withinLeash(pc) || (shy && nearPlayer(pc, keepOff) != null)) continue;
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
            if (startledFrom != null) b.append(String.format(java.util.Locale.ROOT, " (startled: %.0f of %.0f blocks off)", centre().distanceTo(startledFrom), goal));
            else if (fleeTicks > 0) b.append(" (fleeing)");
            else if (restTicks > 0) b.append(resettling ? " (landing again in " : " (no landing for ").append(restTicks / 20).append("s)");
            else if (resettling) b.append(" (looking to land again)");
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
            seenFlushes = entityData.get(DATA_FLUSHES);
            for (int i = 0; i < Math.min(Flock.MAX, count()); i++) f.add(c.x, c.y, c.z);
            wasLanded = landed;
            firstSight = true;
            perchedAt = null;
            settleWait = 0;
        }
        // startled up: the patch lifts in a wave from the side nearest whoever startled it (before the calm take-off
        // below, which then leaves those already lifting as they are)
        int flushes = entityData.get(DATA_FLUSHES);
        if (flushes != seenFlushes) {
            seenFlushes = flushes;
            Vector3f s = entityData.get(DATA_FLUSH_FROM);
            f.flush(s.x, s.y, s.z);
            perchedAt = null;
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
        gatherBodies(f, c);
        f.step(c.x, c.y, c.z, (x, y, z) -> open(level(), BlockPos.containing(x, y, z)));
    }

    /**
     * Tells the flock about the bodies near enough to matter this tick, so its fireflies keep clear of them: every
     * player (no spectator) and creature within {@link #BODY_REACH} of the swarm's cloud, with how far each moved in the
     * last tick (a teleport counts as standing still).
     */
    private void gatherBodies(Flock f, Vec3 c) {
        f.clearBodies();
        if (landed() && perchedAt != null && f.hurry() == 0.0) return;    // all sitting: nothing to dodge
        double r = Flock.radius(f.size()) + BODY_REACH;
        for (Entity e : level().getEntities(this, new AABB(c, c).inflate(r), FireflySwarm::isBody)) {
            AABB b = e.getBoundingBox();
            double mx = e.getX() - e.xo, my = e.getY() - e.yo, mz = e.getZ() - e.zo;
            // a jump of more than a few blocks is a teleport, not a move; anything quicker than a gallop pushes the air as one
            double sp = Math.sqrt(mx * mx + my * my + mz * mz);
            if (sp > TELEPORT) mx = my = mz = 0.0;
            else if (sp > FASTEST_BODY) { double k = FASTEST_BODY / sp; mx *= k; my *= k; mz *= k; }
            f.addBody(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, mx, my, mz);
            if (f.bodies() >= Flock.MAX_BODIES) break;
        }
    }

    /** Whether the fireflies keep clear of this one: a player (not a spectator), a creature, or a piece of a big one's body. */
    private static boolean isBody(Entity e) {
        return e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e instanceof PartEntity<?>);
    }

    /**
     * Gives every firefly a point of its own on the faces round the anchor: seated there at once on first sight, else
     * flying in one by one. False when there is nothing to sit on (they keep flying round the centre).
     */
    private boolean settle(Flock f, boolean atOnce) {
        BlockPos a = anchor();
        // the points for the swarm as it is and a few more, so a firefly that grows back has one waiting
        int n = Math.min(Flock.MAX, Math.max(f.size(), count()) + 16);
        spots = perchSpots(a, Mth.ceil(Perches.reach(n)));
        Vec3 at = landingPoint(a, anchorRock());
        spotOrder = Perches.pick(spots, at.x, at.y, at.z, n, seed());
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
     * The points the fireflies may sit on round the anchor: the faces of rock seen from the open blocks within
     * {@code r} blocks of it that open air joins to it (so none sits behind a wall).
     */
    private List<Perches.Spot> perchSpots(BlockPos anchor, int r) {
        Level level = level();
        List<Perches.Spot> out = new ArrayList<>();
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
