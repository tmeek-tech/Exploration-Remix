package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNavigation;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.registry.ERTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The Mossback: the Upper Caves' lizard. A heavy, territorial ambush hunter that walks floors, walls and ceilings,
 * with moss and ferns on its back. It mostly roams its patch of a plant cave and sometimes settles still among the
 * plants to wait; still, it is dim and blends in, and its green markings light up as soon as it moves again.
 *
 * <p>It sees what is in front of it, not behind; it chases slower than a sprinting player, gives up about twenty
 * seconds after losing sight of you, and searches where it last saw you first. Close in, it rears up (its throat
 * open: a hit from in front then does far more and spoils the lunge), lunges, and clamps on to shake its catch,
 * hurting it every second until the catch hits it hard enough or it lets go. A raised shield turns the lunge aside
 * but is knocked out of use for a while. It also hunts cave animals when hungry, and fights other Mossbacks that cross
 * its patch.
 *
 * <p>Mossbacks live in families at the dens on the den gates' landings ({@link MossbackDen}). The guard never goes
 * far from its den: it roams and lies in wait round it and chases no further than a short leash. The roamers go out
 * on long forays, legs of a couple of dozen blocks leading away from the den, then wandering, dropping crumbs of the
 * way as they go ({@link Breadcrumbs}). On a foray a roamer takes any cave animal it sees; it carries the catch home
 * in its jaws along its crumbs, at a pace a walking player can follow, minding nobody unless it is hurt or someone
 * comes right up in front of it, and eats it at the den. One that catches nothing comes home after a while all the
 * same. Den-mates share their den as home, so they never fight each other; other families' lizards are rivals.
 */
public class MossbackEntity extends Monster implements ClimbMoveControl.Climber {
    // ------------------------------------------------------------------------------------------------ tuning
    public static final double HEALTH = 80.0, ARMOR = 4.0, SPEED = 0.2;
    static final double ROAM_SPEED = 0.5, SEARCH_SPEED = 0.85, CHASE_SPEED = 1.2;
    /** Heading home with a catch (2.6 blocks a second on a floor), or without one: a walking player keeps up. */
    static final double CARRY_SPEED = 0.65, HOME_SPEED = 0.6;
    static final int TERRITORY = 24, LEASH = 64;
    /** A guard keeps within this of its den, and chases no further from it than the leash. */
    static final int GUARD_RANGE = 14, GUARD_LEASH = 32;
    /** A foray: legs this long, leading away from the den until this far out, then wandering; home after this long. */
    static final int LEG_MIN = 16, LEG_MAX = 24, FORAY_TICKS = 20 * 60 * 10;
    static final double FORAY_OUT = 120.0;
    /** This near its den, the way home is a straight path to it rather than the crumbs. */
    static final double DIRECT_HOME = 32.0;
    /** Within this of its den and stuck {@link #HOME_NEAR_TICKS} on the way home, it counts as home. */
    static final double HOME_NEAR = 8.0;
    static final int HOME_NEAR_TICKS = 300;
    /** How far and how long it may touch nothing (rounding an edge) before it falls. */
    static final double AIR_REACH = 1.5;
    static final int AIR_GRACE = 40;
    /** A den's lizards never choose a spot more than this far under the den's own height (the gate's passage). */
    static final int BELOW_DEN = 3;
    /** This long (five minutes) on the way home without reaching a crumb or getting nearer the den: it is lost. */
    static final int LOST_TICKS = 6000;
    /** Carrying, it turns only on a player this close in front of it (or on what hurts it). */
    static final double CARRY_NOTICE = 5.0;
    static final int MEAL_TICKS = 240;
    static final double SIGHT = 24.0, SIGHT_COS = Math.cos(Math.toRadians(65)), TOUCH = 1.8;
    static final int GIVE_UP_TICKS = 400, SEARCH_AFTER_TICKS = 80;
    static final double LUNGE_RANGE = 3.4;
    static final float BITE = 5.0F, SHAKE_PLAYER = 2.5F, SHAKE_PREY = 4.0F, RIVAL_BITE = 6.0F;
    static final float BREAK_FREE = 8.0F, THROAT_MULTIPLIER = 2.5F;
    static final int REAR_TICKS = 12, AMBUSH_REAR_TICKS = 6, LUNGE_TICKS = 10, MAX_HOLD = 80;

    public static final TagKey<EntityType<?>> PREY = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(ExplorationRemix.MOD_ID, "mossback_prey"));
    private static final UUID HELD_SLOW = UUID.fromString("7d3c6b1e-2f4a-4c55-9a1b-6a0d7b1c9e21");

    // ------------------------------------------------------------------------------------------------ state
    public static final byte ROAM = 0, WAIT = 1, ALERT = 2, CHASE = 3, REAR = 4, LUNGE = 5, GRAB = 6, RECOIL = 7, SEARCH = 8,
            EAT = 9, DROP = 10, CARRY = 11, HOME = 12;
    /** The state names, by id, for /remix mossback (MossbackCommands keeps its list in step with this one). */
    public static final String[] STATE_NAMES = {"roam", "wait", "alert", "chase", "rear", "lunge", "grab", "recoil", "search", "eat",
            "drop", "carry", "home"};
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it falls or swims. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_VARIANT = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_HELD = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.INT);
    /** The way its head points along a wall or ceiling (the server's {@code forward}), for drawing it there. */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.VECTOR3);
    /** The kind of animal it carries home in its jaws (an entity type id), empty when it carries nothing. */
    private static final EntityDataAccessor<String> DATA_CARRY = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.STRING);

    public static final byte MOSS = 0, FROST = 1;
    /** A roamer's trip: at home (resting between forays), out on a foray, or on its way back. */
    static final byte AT_HOME = 0, OUT = 1, RETURN = 2;

    @Nullable private BlockPos den;
    @Nullable private BlockPos home;
    private int stateTicks, stateLength;
    private boolean ambushing;               // WAIT: settled in the plants (false while it walks there)
    @Nullable private BlockPos waitSpot;
    private int pauseTicks;
    @Nullable private Vec3 lastSeen;
    private long lastSeenTime, chaseStart;
    private int hunger = 1200;
    private float grabDamage;
    @Nullable private LivingEntity heldRef;  // the catch itself, so it can be let go even after it left this level
    private boolean waitPathSent;
    private int unreachable;                 // CHASE: paths in a row that could not reach the quarry
    private Vec3 lungeDir = Vec3.ZERO;
    private int detachTicks;
    private int legFails;                    // foray legs in a row that got it nowhere
    @Nullable private Vec3 legFrom;          // where the last foray leg began
    private int airTicks;                    // ticks in a row touching no rock and no water
    private double airDist;                  // how far it has gone in that time
    @Nullable private BlockPos kinDen;       // a stray's old den: it and that den's family leave each other be
    private Vec3 forward = new Vec3(0, 0, 1);
    private long ignoreRivalsUntil;
    private float stepDist;
    private boolean throatHit;
    private boolean frozen;                  // /remix mossback freeze: holds still where it is, in the state it is in

    // its family's den (see MossbackDen and DenRegistry; the den's record says which role it has)
    @Nullable private DenRegistry.Role role;
    private byte trip = AT_HOME;
    private int forayTicks, restTicks;       // ticks out on this foray (only while it ticks); ticks of rest left at home
    private boolean calledHome;              // its guard is gone: home, where the first back becomes the guard
    private final Breadcrumbs crumbs = new Breadcrumbs();
    private double legHeading;
    @Nullable private BlockPos chaseFrom;    // a roamer's leash runs from where its chase began
    // heading home: the leg's goal, when it began, and how the walk is going
    @Nullable private BlockPos legGoal;
    private int legStart, crumbFails, denFails, homeStall;
    private double homeBest = Double.MAX_VALUE;
    // ticks since the walk home last got anywhere (a crumb reached, or the den a few blocks nearer than at the last
    // crumb reached), and that distance
    private int homeLost;
    private double lostBest = Double.MAX_VALUE;

    // client-side animation state (see MossbackModel and MossbackRenderer)
    public float glow, glowO, jaw, jawO, rear, rearO, frill, frillO;
    public final Quaternionf orient = new Quaternionf(), orientO = new Quaternionf();
    private Vec3 clientForward = new Vec3(0, 0, 1);
    private Vec3 clientUp = new Vec3(0, 1, 0);

    public MossbackEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.moveControl = new ClimbMoveControl(this);
        this.xpReward = 40;
        setMaxUpStep(1.0F);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ARMOR, ARMOR)
                .add(Attributes.MOVEMENT_SPEED, SPEED)
                .add(Attributes.ATTACK_DAMAGE, BITE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STATE, ROAM);
        entityData.define(DATA_SURFACE, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_VARIANT, MOSS);
        entityData.define(DATA_HELD, -1);
        entityData.define(DATA_FORWARD, new Vector3f(0, 0, 1));
        entityData.define(DATA_CARRY, "");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ClimbNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        // the Mossback's mind is its own state machine (customServerAiStep), not goals
    }

    public byte state() { return entityData.get(DATA_STATE); }
    public byte variant() { return entityData.get(DATA_VARIANT); }
    public void setVariant(byte v) { entityData.set(DATA_VARIANT, v); }
    @Nullable public Direction surface() {
        byte b = entityData.get(DATA_SURFACE);
        return b < 0 ? null : Direction.from3DDataValue(b);
    }
    @Nullable public Entity held() {
        int id = entityData.get(DATA_HELD);
        return id < 0 ? null : level().getEntity(id);
    }
    public int heldId() { return entityData.get(DATA_HELD); }
    /** The entity type id of what it carries home in its jaws, or "" when it carries nothing. */
    public String carried() { return entityData.get(DATA_CARRY); }
    public boolean carrying() { return !carried().isEmpty(); }
    private void setCarried(String id) { entityData.set(DATA_CARRY, id); }
    @Nullable public BlockPos den() { return den; }
    /** A den's lizards all take the den as home, so den-mates are never rivals. */
    public void setDen(@Nullable BlockPos pos) {
        if (pos == null && den != null) kinDen = den;      // its den is gone: its old family are still kin
        this.den = pos;
        if (pos != null) this.home = pos;
        else leaveFamily();
    }
    /** No den any more: its patch becomes wherever it is now (so a newcomer at its old den is a rival). */
    void becomeStray() { this.kinDen = den != null ? den : kinDen; this.den = null; this.home = blockPosition(); leaveFamily(); }

    private void leaveFamily() {
        role = null;
        trip = AT_HOME;
        calledHome = false;
        crumbs.clear();
    }

    /** Its role in its den's family, or null for a lizard with no den (a stray, or one set down by a command). */
    @Nullable public DenRegistry.Role role() { return den == null ? null : role; }
    public boolean isGuard() { return den != null && role == DenRegistry.Role.GUARD; }
    public boolean isRoamer() { return den != null && role == DenRegistry.Role.ROAMER; }

    /** Within {@code r} blocks of its den. */
    boolean nearDen(double r) {
        return den != null && position().distanceToSqr(den.getX() + 1.0, den.getY(), den.getZ() + 1.0) < r * r;
    }

    /**
     * What its den's record says (from {@link MossbackDen}): its role in the family, and whether the family is called
     * home. A new guard stays at home from now on; a roamer called home goes, with or without a catch.
     */
    void learn(DenRegistry.Role r, boolean comeHome) {
        DenRegistry.Role was = role;
        role = r;
        if (r == DenRegistry.Role.GUARD) {
            calledHome = false;
            if (was != DenRegistry.Role.GUARD) { trip = AT_HOME; crumbs.clear(); }
            return;
        }
        if (was != DenRegistry.Role.ROAMER) {
            // a new roamer rests a little first; one taken in far from the den goes home before its first foray
            trip = nearDen(2 * GUARD_RANGE) ? AT_HOME : RETURN;
            restTicks = 60 + random.nextInt(200);
        }
        if (comeHome && !calledHome) {
            calledHome = true;
            if (trip != RETURN) {
                trip = RETURN;
                if (state() == ROAM || state() == WAIT) toState(HOME, 0);
            }
        } else if (!comeHome) {
            calledHome = false;
        }
    }

    @Override public boolean onFloor() { return surface() == Direction.DOWN; }
    @Override public boolean canSteer() { byte s = state(); return s != LUNGE && s != DROP && hurtTime < 6; }
    /** Rounding an edge it touches nothing for a moment; further than {@link #AIR_REACH} or longer than that, it falls. */
    @Override public boolean airborne() { return airDist > AIR_REACH || airTicks > AIR_GRACE; }
    @Override public boolean holdStill() {
        Direction d = surface();
        return d != null && d != Direction.DOWN && !(state() == RECOIL && stateTicks < 10);
    }
    /** It climbs on its own; vines and ladders must not lift it the way they lift a player. */
    @Override public boolean onClimbable() { return false; }
    /** Its snout and long tail reach well past its box: keep drawing it while any of it is on screen. */
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(2.5); }

    // ------------------------------------------------------------------------------------------------ geometry

    /** The way its head points: along the floor its head's turn, elsewhere the way it last moved. */
    public Vec3 lookDir() {
        if (onFloor()) return Vec3.directionFromRotation(0, getYHeadRot());
        return forward;
    }

    /** The outward normal of the rock it clings to (up on a floor). */
    public Vec3 up() {
        Direction d = surface();
        return d == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(d.getOpposite().getNormal());
    }

    /** The middle of its body, which lies against its rock rather than in the middle of its square box. */
    public Vec3 bodyCentre() {
        Vec3 up = up();
        double w = getBbWidth() / 2.0, h = getBbHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        Vec3 c = position().add(0, h, 0);
        return c.subtract(up.scale(ext)).add(up.scale(0.45));
    }

    public Vec3 mouth() { return bodyCentre().add(lookDir().scale(1.25)); }
    public Vec3 eyes() { return bodyCentre().add(lookDir().scale(1.0)).add(up().scale(0.25)); }

    // ------------------------------------------------------------------------------------------------ surface

    private boolean touching(AABB box, Direction d) {
        double e = 0.08, m = 0.06;
        AABB slab = switch (d) {
            case DOWN -> new AABB(box.minX + m, box.minY - e, box.minZ + m, box.maxX - m, box.minY, box.maxZ - m);
            case UP -> new AABB(box.minX + m, box.maxY, box.minZ + m, box.maxX - m, box.maxY + e, box.maxZ - m);
            // the sides reach almost to the box's bottom and top, so it keeps hold of a wall until it is over its edge
            case WEST -> new AABB(box.minX - e, box.minY + 0.02, box.minZ + m, box.minX, box.maxY - 0.02, box.maxZ - m);
            case EAST -> new AABB(box.maxX, box.minY + 0.02, box.minZ + m, box.maxX + e, box.maxY - 0.02, box.maxZ - m);
            case NORTH -> new AABB(box.minX + m, box.minY + 0.02, box.minZ - e, box.maxX - m, box.maxY - 0.02, box.minZ);
            case SOUTH -> new AABB(box.minX + m, box.minY + 0.02, box.maxZ, box.maxX - m, box.maxY - 0.02, box.maxZ + e);
        };
        return !level().noCollision(this, slab);
    }

    /** Which rock it clings to: the floor when it touches one, else the wall or ceiling it was on, else any wall, else the ceiling. */
    private void updateSurface() {
        if (detachTicks > 0) detachTicks--;
        AABB box = getBoundingBox();
        Direction cur = surface();
        Direction next = null;
        if (onGround() || touching(box, Direction.DOWN)) next = Direction.DOWN;
        else if (isInWater()) next = null;
        else if (detachTicks > 0) next = null;
        else if (cur != null && cur != Direction.DOWN && touching(box, cur)) next = cur;
        else {
            Vec3 m = getDeltaMovement();
            Direction best = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!touching(box, d)) continue;
                if (best == null || m.dot(Vec3.atLowerCornerOf(d.getNormal())) > m.dot(Vec3.atLowerCornerOf(best.getNormal()))) best = d;
            }
            next = best != null ? best : (touching(box, Direction.UP) ? Direction.UP : null);
        }
        entityData.set(DATA_SURFACE, (byte) (next == null ? -1 : next.get3DDataValue()));
        // the distance it goes while touching nothing is added up after each move (in tick)
        if (next == null && !isInWater()) airTicks++;
        else {
            airTicks = 0;
            airDist = 0.0;
        }
        setNoGravity(next != null && next != Direction.DOWN);
        if (next != null && next != Direction.DOWN) resetFallDistance();
    }

    /**
     * The way it points along its rock: the way it moved this tick, or, while it faces its quarry (alert, rearing,
     * holding), turning towards it. Runs after travel, when this tick's motion is known.
     */
    private void updateForward() {
        Vec3 up = up();
        Vec3 mv = position().subtract(xo, yo, zo);
        Vec3 along = mv.subtract(up.scale(mv.dot(up)));
        LivingEntity t = getTarget();
        byte s = state();
        Vec3 want = null;
        double rate = 1.0;
        if (t != null && (s == ALERT || s == REAR || s == GRAB)) {
            Vec3 to = t.getBoundingBox().getCenter().subtract(bodyCentre());
            Vec3 flat = to.subtract(up.scale(to.dot(up)));
            if (flat.lengthSqr() > 1.0E-4) { want = flat.normalize(); rate = 0.35; }
        } else if (along.lengthSqr() > 1.0E-4) want = along.normalize();
        if (want != null) {
            if (forward.dot(want) < -0.99) forward = forward.add(up.cross(forward).scale(0.3));   // never lerp through zero
            forward = forward.lerp(want, rate);
        }
        Vec3 f = forward.subtract(up.scale(forward.dot(up)));
        forward = f.lengthSqr() > 1.0E-3 ? f.normalize() : Vec3.directionFromRotation(0, getYRot());
        Vector3f synced = entityData.get(DATA_FORWARD);
        if (synced.x * forward.x + synced.y * forward.y + synced.z * forward.z < 0.995)
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
    }

    @Override
    public void travel(Vec3 input) {
        // press against the wall or ceiling so it stays in touch with it
        Direction d = surface();
        if (d != null && d != Direction.DOWN && !level().isClientSide) {
            Vec3 n = Vec3.atLowerCornerOf(d.getNormal());
            setDeltaMovement(getDeltaMovement().add(n.scale(0.04)));
        }
        super.travel(input);
    }

    @Override
    public void calculateEntityAnimation(boolean includeHeight) {
        super.calculateEntityAnimation(true);             // climbing counts as walking
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ the mind

    private void toState(byte s, int length) {
        entityData.set(DATA_STATE, s);
        stateTicks = 0;
        stateLength = length;
        waitPathSent = false;
        if (s != CHASE) unreachable = 0;
        if (s != WAIT) { ambushing = false; waitSpot = null; }
        if (s == CARRY || s == HOME) {
            // the walk home starts afresh (not down whatever path the last state left it on)
            resetHoming();
            getNavigation().stop();
        }
    }

    @Override
    protected void customServerAiStep() {
        if (frozen) {                                   // held in place for a picture (/remix mossback freeze)
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        updateSurface();
        // in water deeper than its legs it swims up and keeps its head out, as other land beasts do
        if (isInWater() && getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold()) getJumpControl().jump();
        if (home == null) home = blockPosition();
        stateTicks++;
        if (hunger > 0) hunger--;
        tripTick();
        LivingEntity t = getTarget();
        if (t != null && state() == GRAB && t.getId() == heldId() && t.isDeadOrDying()) {
            // the shake killed its catch: it eats
            release(false);
            afterKill(t);
            t = null;
        } else if (t != null && !huntable(t)) {
            release(false);
            setTarget(null);
            t = null;
            if (state() != ROAM && state() != WAIT && state() != EAT) toState(ROAM, 0);
        }
        switch (state()) {
            case ROAM -> roam();
            case WAIT -> waitInPlants();
            case ALERT -> alert(t);
            case CHASE -> chase(t);
            case REAR -> rear(t);
            case LUNGE -> lunge(t);
            case GRAB -> grab();
            case RECOIL -> recoil(t);
            case SEARCH -> search(t);
            case EAT -> eat();
            case DROP -> drop(t);
            case CARRY -> headHome(true);
            case HOME -> headHome(false);
            default -> toState(ROAM, 0);
        }
        if (den != null && tickCount % 100 == 0) MossbackDen.checkIn(this);
    }

    /** A roamer's trip goes on: rest at home counts down; out on a foray it drops crumbs, and in time turns for home. */
    private void tripTick() {
        if (!isRoamer()) return;
        if (trip == AT_HOME) {
            if (restTicks > 0) restTicks--;
        } else if (trip == OUT) {
            forayTicks++;
            if (tickCount % 10 == 0 && surface() != null && !isInWater()) crumbs.record(nodeX(), nodeY(), nodeZ());
            // nothing caught all this while: home with empty jaws
            if (forayTicks >= FORAY_TICKS) trip = RETURN;
        }
    }

    /** The low corner of the path node it stands on (its box fills 2 x 1 x 2 cells; see ClimbNodeEvaluator). */
    private int nodeX() { return Mth.floor(getX() - 0.5); }
    private int nodeY() { return Mth.floor(getY() + 0.05); }
    private int nodeZ() { return Mth.floor(getZ() - 0.5); }

    // --- roaming and waiting

    private void roam() {
        // a roamer on its way back goes on home after whatever stopped it; a guard far from its den goes straight back
        if (carrying() || (isRoamer() && trip == RETURN)) { toState(carrying() ? CARRY : HOME, 0); return; }
        if (isGuard() && !nearDen(2 * GUARD_RANGE + 8)) { toState(HOME, 0); return; }
        if (tickCount % 5 == 0 && spot(true)) return;
        PathNavigation nav = getNavigation();
        if (pauseTicks > 0) { pauseTicks--; return; }
        if (nav.isDone() || nav.isStuck()) {
            if (stateTicks > 40) {
                if (isRoamer() && trip == AT_HOME && restTicks <= 0) startForay();
                boolean out = onForay();
                // a leg is done: sometimes settle in the plants to wait (a guard often, a roamer out on a foray seldom
                // and not for long), otherwise pause and pick the next leg
                float waits = isGuard() ? 0.35F : out ? 0.1F : 0.25F;
                if (random.nextFloat() < waits && findWaitSpot()) { toState(WAIT, out ? 200 + random.nextInt(400) : 600 + random.nextInt(1200)); return; }
                pauseTicks = out ? 10 + random.nextInt(25) : 20 + random.nextInt(50);
                stateTicks = 0;
                // a foray leg that got it nowhere (water or rock it cannot cross that way): the next one fans wider
                if (out) {
                    legFails = legFrom != null && position().distanceTo(legFrom) < 4.0 ? legFails + 1 : 0;
                    legFrom = position();
                }
                Vec3 to = roamTarget();
                if (to != null) nav.moveTo(to.x, to.y, to.z, ROAM_SPEED);
            } else if (stateTicks == 1) {
                Vec3 to = roamTarget();
                if (to != null) nav.moveTo(to.x, to.y, to.z, ROAM_SPEED);
            }
        }
    }

    private boolean onForay() { return isRoamer() && trip == OUT; }

    /** Out from the den: a fresh trail of crumbs, starting at the den, and a way to set off. */
    private void startForay() {
        trip = OUT;
        forayTicks = 0;
        legFails = 0;
        legFrom = null;
        if (den != null) crumbs.start(den.getX(), den.getY(), den.getZ());
        else crumbs.clear();
        legHeading = random.nextDouble() * Math.PI * 2.0;
    }

    /**
     * Where to walk next: out on a foray, the next leg; otherwise a random walkable spot in its patch (towards home
     * when it has strayed). A den's lizards keep close to the den while at home; a stray keeps its old territory.
     */
    @Nullable
    private Vec3 roamTarget() {
        // a den's lizard down its gate's passage (after a chase): back up to the den first
        if (den != null && belowDen(blockPosition())) return new Vec3(den.getX() + 1.0, den.getY(), den.getZ() + 1.0);
        if (onForay()) {
            Vec3 leg = forayTarget();
            return leg != null ? leg : spotNear(blockPosition(), 12, 4);
        }
        BlockPos h = home != null ? home : blockPosition();
        int range = den != null ? GUARD_RANGE : TERRITORY;
        boolean strayed = blockPosition().distSqr(h) > (double) range * range;
        return strayed ? spotNear(h, 4, 2) : spotNear(h, range, 8);
    }

    /** A random walkable spot within {@code r} blocks across and {@code ry} up or down of {@code c}, in loaded chunks. */
    @Nullable
    private Vec3 spotNear(BlockPos c, int r, int ry) {
        for (int i = 0; i < 12; i++) {
            Vec3 v = walkableNear(c.offset(random.nextInt(2 * r + 1) - r, random.nextInt(2 * ry + 1) - ry, random.nextInt(2 * r + 1) - r));
            if (v != null) return v;
        }
        return null;
    }

    /**
     * A foray's next leg, {@link #LEG_MIN} to {@link #LEG_MAX} blocks: within {@link #FORAY_OUT} of the den it leads
     * away from the den (fanning out wider when the way straight on is rock or unloaded), past that any way at all.
     */
    @Nullable
    private Vec3 forayTarget() {
        if (den == null) return null;
        double dx = getX() - (den.getX() + 1.0), dz = getZ() - (den.getZ() + 1.0);
        double out = Math.sqrt(dx * dx + dz * dz);
        double away = out > 2.0 ? Math.atan2(dz, dx) : legHeading;
        for (int i = 0; i < 12; i++) {
            double spread = 100 + 15 * i + 70 * legFails;
            double a = out < FORAY_OUT && spread < 360 ? away + (random.nextDouble() - 0.5) * Math.toRadians(spread) : random.nextDouble() * Math.PI * 2.0;
            double len = LEG_MIN + random.nextInt(LEG_MAX - LEG_MIN + 1);
            Vec3 v = walkableNear(BlockPos.containing(getX() + Math.cos(a) * len, getY() + random.nextInt(9) - 4, getZ() + Math.sin(a) * len));
            if (v != null) { legHeading = a; return v; }
        }
        return null;
    }

    /** The walkable node nearest {@code c} up or down, as a place to walk to, never reading an unloaded chunk. */
    @Nullable
    private Vec3 walkableNear(BlockPos c) {
        if (!loadedAround(c)) return null;
        BlockPos n = nearestWalkable(c, 6);
        if (n == null || !level().isLoaded(n) || isRefuge(n) || belowDen(n)) return null;
        return new Vec3(n.getX() + 1.0, n.getY(), n.getZ() + 1.0);
    }

    /**
     * Under its den's floor: down the gate's passage, or the Blue Reaches beyond it. A den's lizards hunt the Upper
     * Caves round the den and never choose to walk down there, so the way they lead a player home ends at the gate.
     */
    private boolean belowDen(BlockPos p) {
        return den != null && p.getY() < den.getY() - BELOW_DEN;
    }

    /** Whether the columns a node test round {@code c} reads are all loaded (reading others would load them). */
    private boolean loadedAround(BlockPos c) {
        return level().hasChunksAt(c.getX() - 1, c.getZ() - 1, c.getX() + 2, c.getZ() + 2);
    }

    @Nullable
    private BlockPos nearestWalkable(BlockPos c, int reach) {
        for (int dy = 0; dy <= reach; dy++) {
            for (int sign : new int[]{-1, 1}) {
                int y = c.getY() + sign * dy;
                byte k = ClimbNodeEvaluator.classify(level(), c.getX(), y, c.getZ(), 2, 1, 2);
                if (k >= ClimbNodeEvaluator.FLOOR && k != ClimbNodeEvaluator.WATER) return new BlockPos(c.getX(), y, c.getZ());
                if (dy == 0) break;
            }
        }
        return null;
    }

    /** Dripstone caves are the Upper Caves' refuge: it never chooses to go there. */
    private boolean isRefuge(BlockPos p) {
        return level().getBiome(p).is(net.minecraft.world.level.biome.Biomes.DRIPSTONE_CAVES);
    }

    /** A spot among thick plants near it, on a floor, wall or ceiling, from which to ambush. */
    private boolean findWaitSpot() {
        BlockPos best = null;
        int bestScore = 5;
        BlockPos me = blockPosition();
        for (int i = 0; i < 24; i++) {
            BlockPos c = me.offset(random.nextInt(21) - 10, random.nextInt(9) - 4, random.nextInt(21) - 10);
            if (!loadedAround(c)) continue;
            BlockPos n = nearestWalkable(c, 3);
            if (n == null || isRefuge(n) || belowDen(n)) continue;
            // at home it waits by its den: never further out than a guard roams
            if (den != null && !onForay() && n.distSqr(den) > (double) GUARD_RANGE * GUARD_RANGE) continue;
            int score = plantsAround(n);
            if (score > bestScore) { bestScore = score; best = n; }
        }
        if (best == null) return false;
        waitSpot = best;
        ambushing = false;
        return true;
    }

    private int plantsAround(BlockPos n) {
        int score = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 2; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 2; dz++) {
                    BlockState s = level().getBlockState(p.set(n.getX() + dx, n.getY() + dy, n.getZ() + dz));
                    if (s.is(ERTags.PLANTS) || s.is(BlockTags.LEAVES) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.BIG_DRIPLEAF)
                            || s.is(Blocks.BIG_DRIPLEAF_STEM) || s.is(Blocks.SMALL_DRIPLEAF)) score++;
                }
        return score;
    }

    private void waitInPlants() {
        if (waitSpot == null || (isRoamer() && trip == RETURN)) { toState(ROAM, 0); return; }
        PathNavigation nav = getNavigation();
        if (!ambushing) {
            if (tickCount % 5 == 0 && spot(true)) return;
            if (stateTicks > 400) { toState(ROAM, 0); return; }
            if (nav.isDone()) {
                if (position().distanceToSqr(waitSpot.getX() + 1.0, waitSpot.getY(), waitSpot.getZ() + 1.0) < 2.5) {
                    ambushing = true;
                    stateTicks = 0;
                } else if (waitPathSent) {
                    toState(ROAM, 0);                      // it got as near as it could: not near enough, so it moves on
                } else if (stateTicks > 10) {
                    waitPathSent = true;
                    // to the spot's own node (moveTo's usual reach of one node would stop it a step short)
                    if (!nav.moveTo(nav.createPath(waitSpot.offset(1, 0, 1), 0), ROAM_SPEED)) toState(ROAM, 0);
                }
            }
            return;
        }
        // still among the plants: it watches, and strikes at what comes close
        nav.stop();
        if (tickCount % 4 == 0 && spot(false)) return;
        if (stateTicks > stateLength) toState(ROAM, 0);
    }

    // --- senses

    /** Looks for something to hunt; on finding it, turns to face it. Returns true when the state changed. */
    private boolean spot(boolean roaming) {
        LivingEntity seen = scan();
        if (seen == null) return false;
        notice(seen, !roaming && ambushing);
        return true;
    }

    @Nullable
    private LivingEntity scan() {
        if (state() == CARRY) return closeInFront();
        AABB around = getBoundingBox().inflate(SIGHT);
        Player player = null;
        double best = Double.MAX_VALUE;
        for (Player p : level().players()) {
            if (!huntable(p) || !around.contains(p.position()) || !withinLeash(p)) continue;
            double d = p.distanceToSqr(this);
            if (d < best && (d < TOUCH * TOUCH || canSee(p))) { best = d; player = p; }
        }
        if (player != null) return player;
        long now = level().getGameTime();
        if (now >= ignoreRivalsUntil && home != null) {
            for (MossbackEntity m : level().getEntitiesOfClass(MossbackEntity.class, getBoundingBox().inflate(12), m -> m != this && m.isAlive())) {
                if (m.home != null && !m.home.equals(home) && !denMate(m) && m.blockPosition().distSqr(home) < (double) TERRITORY * TERRITORY && canSee(m)) return m;
            }
        }
        // out on a foray a roamer takes any cave animal it sees, hungry or not: it is hunting for the den
        if (hunger <= 0 || (isRoamer() && !calledHome && !carrying())) {
            List<LivingEntity> prey = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(16), e -> e.getType().is(PREY) && e.isAlive());
            LivingEntity near = null;
            double nb = Double.MAX_VALUE;
            for (LivingEntity e : prey) {
                double d = e.distanceToSqr(this);
                if (d < nb && withinLeash(e) && canSee(e)) { nb = d; near = e; }
            }
            return near;
        }
        return null;
    }

    /** Carrying its catch home it minds nobody but a player right up in front of it (or touching it). */
    @Nullable
    private Player closeInFront() {
        Player near = null;
        double best = CARRY_NOTICE * CARRY_NOTICE;
        for (Player p : level().players()) {
            if (!huntable(p)) continue;
            double d = p.distanceToSqr(this);
            if (d < best && canSee(p)) { best = d; near = p; }
        }
        return near;
    }

    /** A guard goes for nothing further from its den than its leash; every other lizard, anything it sees. */
    private boolean withinLeash(Entity e) {
        return !isGuard() || e.blockPosition().distSqr(den) <= (double) GUARD_LEASH * GUARD_LEASH;
    }

    /** It sees what is in front of it and in the open, not behind it. */
    public boolean canSee(LivingEntity t) {
        Vec3 eye = eyes();
        Vec3 to = t.getEyePosition().subtract(eye);
        double dist = to.length();
        double range = t.isInvisible() ? 6.0 : SIGHT;
        if (dist > range) return false;
        if (dist > TOUCH && to.scale(1.0 / dist).dot(lookDir()) < SIGHT_COS) return false;
        HitResult hit = level().clip(new ClipContext(eye, t.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean huntable(LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.level() != level()) return false;
        if (t instanceof Player p) return !p.isCreative() && !p.isSpectator();
        return t.distanceToSqr(this) < (double) LEASH * LEASH;
    }

    private void notice(LivingEntity t, boolean fromAmbush) {
        dropCatch();                               // its jaws are wanted
        setTarget(t);
        lastSeen = t.position();
        lastSeenTime = level().getGameTime();
        chaseStart = lastSeenTime;
        chaseFrom = blockPosition();
        Direction s = surface();
        if (fromAmbush) {
            // from the plants: it strikes at once if the quarry is close, dropping on it from a wall or ceiling
            if (s != null && s != Direction.DOWN && t.getY() < getY() - 0.5 && horizontalDistance(t) < 3.0) { startDrop(t); return; }
            if (inStrikingReach(t, LUNGE_RANGE + 1.0)) { toState(REAR, AMBUSH_REAR_TICKS); return; }
        }
        toState(ALERT, 15);
        playSound(ERSounds.MOSSBACK_HISS.get(), 1.4F, 0.9F + random.nextFloat() * 0.2F);
    }

    /**
     * Close enough to lunge, and not round an edge of the rock it clings to: from a wall, quarry standing on top of
     * that wall lies behind the wall's own plane, and a lunge would only drive it into the rock. It climbs over first.
     */
    private boolean inStrikingReach(LivingEntity t, double range) {
        Vec3 c = t.getBoundingBox().getCenter();
        if (mouth().distanceTo(c) >= range) return false;
        return !clingsOffFloor() || c.subtract(bodyCentre()).dot(up()) > -0.3;
    }

    private double horizontalDistance(Entity e) {
        return Math.sqrt(Mth.square(e.getX() - getX()) + Mth.square(e.getZ() - getZ()));
    }

    // --- the hunt

    private void alert(@Nullable LivingEntity t) {
        getNavigation().stop();
        if (t == null) { toState(ROAM, 0); return; }
        getLookControl().setLookAt(t, 40.0F, 40.0F);
        if (stateTicks >= stateLength) toState(CHASE, 0);
    }

    private void chase(@Nullable LivingEntity t) {
        if (t == null) { toState(SEARCH, 0); return; }
        long now = level().getGameTime();
        getLookControl().setLookAt(t, 30.0F, 30.0F);
        boolean sees = canSee(t);
        if (sees) { lastSeen = t.position(); lastSeenTime = now; }
        if (t instanceof MossbackEntity && getHealth() < getMaxHealth() * 0.4F) { flee(); return; }
        if (sees && inStrikingReach(t, LUNGE_RANGE)) {
            toState(REAR, REAR_TICKS);
            playSound(ERSounds.MOSSBACK_HISS.get(), 1.2F, 1.15F);
            return;
        }
        if (now - lastSeenTime > SEARCH_AFTER_TICKS) { toState(SEARCH, 0); return; }
        if (pastLeash(t, sees)) { giveUp(); return; }
        PathNavigation nav = getNavigation();
        // a fresh path every half second, or as soon as one that reached its goal is walked; when the quarry is out of
        // reach (up a pillar, behind a crack) only every two seconds
        Path cur = nav.getPath();
        int every = unreachable >= 2 ? 40 : 10;
        if (stateTicks % every == 1 || (nav.isDone() && (cur == null || cur.canReach()) && unreachable == 0)) {
            Vec3 at = sees ? t.position() : lastSeen;
            Path p = nav.createPath(BlockPos.containing(at), 1);
            if (p != null) nav.moveTo(p, CHASE_SPEED);
            unreachable = p != null && p.canReach() ? 0 : unreachable + 1;
        }
    }

    /**
     * Whether a chase has gone too far. A guard turns back at its leash from the den (or once its quarry is well past
     * it), whatever it sees; a roamer's leash runs from where the chase began, since it hunts far from home; a stray's
     * from its home. Those two give up only once they have also lost sight of the quarry.
     */
    private boolean pastLeash(LivingEntity t, boolean sees) {
        if (isGuard()) {
            double far = (double) GUARD_LEASH * GUARD_LEASH, farther = (GUARD_LEASH + 4.0) * (GUARD_LEASH + 4.0);
            return blockPosition().distSqr(den) > far || t.blockPosition().distSqr(den) > farther;
        }
        BlockPos from = isRoamer() ? (chaseFrom != null ? chaseFrom : blockPosition()) : home;
        return from != null && blockPosition().distSqr(from) > (double) LEASH * LEASH && !sees;
    }

    private void rear(@Nullable LivingEntity t) {
        getNavigation().stop();
        if (t == null) { toState(ROAM, 0); return; }
        getLookControl().setLookAt(t, 60.0F, 60.0F);
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(0, clingsOffFloor() ? 0 : dm.y, 0);
        if (stateTicks >= stateLength) {
            Vec3 to = t.getBoundingBox().getCenter().subtract(mouth());
            lungeDir = to.lengthSqr() > 1.0E-4 ? to.normalize() : lookDir();
            if (!onFloor()) detachTicks = 20;
            toState(LUNGE, LUNGE_TICKS);
            playSound(ERSounds.MOSSBACK_BITE.get(), 1.2F, 0.8F + random.nextFloat() * 0.2F);
        }
    }

    /** On a wall or ceiling (gravity off), not on the floor and not falling. */
    private boolean clingsOffFloor() {
        Direction d = surface();
        return d != null && d != Direction.DOWN;
    }

    private void lunge(@Nullable LivingEntity t) {
        if (t == null) { toState(RECOIL, 15); return; }
        double sp = stateTicks < 5 ? 0.55 : 0.3;
        Vec3 v = lungeDir.scale(sp);
        setDeltaMovement(v.x, onFloor() ? Math.max(getDeltaMovement().y, v.y + 0.04) : v.y, v.z);
        AABB jaws = new AABB(mouth(), mouth()).inflate(0.7);
        if (jaws.intersects(t.getBoundingBox())) { strike(t); return; }
        if (stateTicks >= stateLength) toState(RECOIL, 15);
    }

    private void startDrop(LivingEntity t) {
        detachTicks = 30;
        setNoGravity(false);
        Vec3 to = t.position().subtract(position());
        setDeltaMovement(to.x * 0.12, -0.1, to.z * 0.12);
        toState(DROP, 40);
        playSound(ERSounds.MOSSBACK_HISS.get(), 1.4F, 0.8F);
    }

    private void drop(@Nullable LivingEntity t) {
        if (t == null) { toState(ROAM, 0); return; }
        if (onGround() || isInWater() || stateTicks > stateLength) {
            if (mouth().distanceTo(t.getBoundingBox().getCenter()) < 2.4) strike(t);
            else toState(CHASE, 0);
        }
    }

    /** The jaws reach their quarry: a shield turns them aside, a rival is bitten, anything else is held. */
    private void strike(LivingEntity t) {
        DamageSource src = damageSources().mobAttack(this);
        if (t instanceof Player p && p.isBlocking() && p.isDamageSourceBlocked(src)) {
            p.disableShield(true);
            p.knockback(0.6, getX() - p.getX(), getZ() - p.getZ());
            p.hurtMarked = true;
            toState(RECOIL, 25);
            return;
        }
        if (t instanceof MossbackEntity rival) {
            rival.hurt(src, RIVAL_BITE);
            toState(RECOIL, 20);
            return;
        }
        if (!t.hurt(src, BITE)) { toState(RECOIL, 15); return; }
        if (!t.isAlive()) { afterKill(t); return; }
        entityData.set(DATA_HELD, t.getId());
        heldRef = t;
        grabDamage = 0;
        AttributeInstance speed = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(HELD_SLOW) == null)
            speed.addTransientModifier(new AttributeModifier(HELD_SLOW, "Held by a Mossback", -0.7, AttributeModifier.Operation.MULTIPLY_TOTAL));
        toState(GRAB, MAX_HOLD);
        playSound(ERSounds.MOSSBACK_SHAKE.get(), 1.3F, 0.9F + random.nextFloat() * 0.2F);
    }

    private void grab() {
        Entity e = held();
        getNavigation().stop();
        if (!(e instanceof LivingEntity t) || !t.isAlive()) {
            boolean killed = e instanceof LivingEntity le && !le.isAlive();
            release(false);
            if (killed) afterKill((LivingEntity) e);
            else toState(getTarget() != null ? CHASE : ROAM, 0);
            return;
        }
        getLookControl().setLookAt(t, 60.0F, 60.0F);
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(0, clingsOffFloor() ? 0 : dm.y, 0);
        Vec3 m = mouth();
        if (t.getBoundingBox().getCenter().distanceTo(m) > 3.5 || grabDamage >= BREAK_FREE || stateTicks >= stateLength) {
            release(true);
            return;
        }
        // shake: it jerks its catch from side to side and holds it at its jaws
        Vec3 side = lookDir().cross(up());
        if (side.lengthSqr() < 1.0E-4) side = new Vec3(1, 0, 0);
        double sway = ((stateTicks / 6) % 2 == 0 ? 1 : -1) * (stateTicks % 6 == 0 ? 0.35 : 0.0);
        Vec3 pull = new Vec3(m.x - t.getX(), 0, m.z - t.getZ()).scale(0.45);
        if (pull.length() > 0.5) pull = pull.normalize().scale(0.5);
        Vec3 v = pull.add(side.normalize().scale(sway));
        if (t instanceof Player) {
            t.setDeltaMovement(v.x, t.getDeltaMovement().y, v.z);
            t.hurtMarked = true;
        } else {
            double y = m.y - t.getBbHeight() * 0.5;
            // falling with it, the catch goes down in its jaws; on rock it is never pushed into the ground
            t.setPos(m.x, surface() == null ? y : Math.max(t.getY(), y), m.z);
            t.setDeltaMovement(Vec3.ZERO);
            if (t instanceof Mob mob) mob.getNavigation().stop();
        }
        if (stateTicks % 20 == 0) {
            if (t instanceof Player p && p.isBlocking()) p.disableShield(true);     // no shielding from inside its jaws
            t.hurt(damageSources().mobAttack(this), t instanceof Player ? SHAKE_PLAYER : SHAKE_PREY);
            playSound(ERSounds.MOSSBACK_SHAKE.get(), 1.0F, 0.85F + random.nextFloat() * 0.3F);
        }
    }

    private void release(boolean backOff) {
        LivingEntity t = heldRef;
        if (t == null && held() instanceof LivingEntity le) t = le;
        if (t != null) {
            AttributeInstance speed = t.getAttribute(Attributes.MOVEMENT_SPEED);
            int id = t.getId();
            boolean heldByAnother = !t.level().getEntitiesOfClass(MossbackEntity.class, t.getBoundingBox().inflate(6),
                    m -> m != this && m.heldId() == id).isEmpty();
            if (speed != null && !heldByAnother) speed.removeModifier(HELD_SLOW);
        }
        heldRef = null;
        entityData.set(DATA_HELD, -1);
        if (backOff) {
            Vec3 back = lookDir().scale(-0.35);
            setDeltaMovement(back.x, onFloor() ? 0.25 : back.y, back.z);
            toState(RECOIL, 30);
        }
    }

    private void afterKill(LivingEntity t) {
        setTarget(null);
        if (t instanceof Player) { toState(ROAM, 0); return; }
        hunger = 20 * 60 * (3 + random.nextInt(4));
        if (isRoamer() && !carrying() && t.getType().is(PREY)) {
            // a roamer takes its catch home to the den: the body goes (what it drops already fell where it died), and
            // the catch is drawn in its jaws instead (MossbackRenderer)
            setCarried(EntityType.getKey(t.getType()).toString());
            t.discard();
            trip = RETURN;
            toState(CARRY, 0);
            return;
        }
        toState(EAT, MEAL_TICKS);
    }

    private void recoil(@Nullable LivingEntity t) {
        if (t != null && stateTicks < 10) {
            Vec3 away = position().subtract(t.position());
            if (away.lengthSqr() > 1.0E-4 && canSteer()) {
                away = away.normalize().scale(0.12);
                setDeltaMovement(away.x, getDeltaMovement().y, away.z);
            }
        }
        if (stateTicks < stateLength) return;
        if (t == null) toState(ROAM, 0);
        else if (canSee(t)) toState(CHASE, 0);
        else toState(SEARCH, 0);
    }

    private void search(@Nullable LivingEntity t) {
        long now = level().getGameTime();
        if (t != null && canSee(t)) { lastSeen = t.position(); lastSeenTime = now; toState(CHASE, 0); return; }
        if (now - lastSeenTime > GIVE_UP_TICKS || lastSeen == null) { giveUp(); return; }
        // a guard never searches beyond its leash
        if (isGuard() && lastSeen.distanceToSqr(den.getX() + 1.0, den.getY(), den.getZ() + 1.0) > (double) GUARD_LEASH * GUARD_LEASH) { giveUp(); return; }
        PathNavigation nav = getNavigation();
        if (stateTicks == 1) nav.moveTo(lastSeen.x, lastSeen.y, lastSeen.z, SEARCH_SPEED);
        else if (nav.isDone() && stateTicks % 30 == 0) {
            // look about where it lost you, a few steps either way
            Vec3 at = lastSeen.add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
            nav.moveTo(at.x, at.y, at.z, SEARCH_SPEED);
        }
        if (tickCount % 5 == 0) {
            LivingEntity seen = scan();
            if (seen != null) { setTarget(seen); lastSeen = seen.position(); lastSeenTime = now; toState(CHASE, 0); }
        }
    }

    private void giveUp() {
        setTarget(null);
        lastSeen = null;
        toState(ROAM, 0);
    }

    private void flee() {
        setTarget(null);
        ignoreRivalsUntil = level().getGameTime() + 600;
        toState(RECOIL, 40);
        if (home != null) getNavigation().moveTo(home.getX() + 1.0, home.getY(), home.getZ() + 1.0, CHASE_SPEED);
    }

    private void eat() {
        getNavigation().stop();
        if (stateTicks % 25 == 0) playSound(SoundEvents.GENERIC_EAT, 0.8F, 0.6F);
        if (stateTicks > 20 && tickCount % 5 == 0) {
            // eating, it still turns on a player close by
            for (Player p : level().players()) {
                if (huntable(p) && p.distanceToSqr(this) < 36 && canSee(p)) { notice(p, false); return; }
            }
        }
        if (stateTicks >= stateLength) {
            if (carrying()) finishMeal();
            toState(ROAM, 0);
        }
    }

    /** The catch it brought home is eaten: its bones go among the den's scraps, and a roamer rests before going out again. */
    private void finishMeal() {
        setCarried("");
        if (nearDen(MossbackDen.SCRAPS_REACH)) {
            MossbackDen.leaveScraps(this);
            // its guard died while it ate: it takes the guard's place now, rather than after another foray
            if (calledHome) MossbackDen.arrivedHome(this);
        }
        if (isRoamer()) restAtHome(400 + random.nextInt(800));
    }

    private void restAtHome(int ticks) {
        trip = AT_HOME;
        restTicks = ticks;
        crumbs.clear();
    }

    /**
     * It lets go of the catch it carries (to fight, or because it was hurt): it is gone. At the den, half eaten, it
     * leaves its bones there; on the way home, a roamer goes back to hunting.
     */
    private void dropCatch() {
        if (!carrying()) return;
        boolean atDen = nearDen(MossbackDen.SCRAPS_REACH);
        if (state() == EAT && atDen) MossbackDen.leaveScraps(this);
        setCarried("");
        if (isRoamer() && trip == RETURN && !calledHome) {
            if (atDen) restAtHome(200 + random.nextInt(400));
            else trip = OUT;
        }
    }

    // --- home

    /**
     * Back to the den, with a catch or without: along its crumbs in reverse, a path to each in turn, skipping one it
     * cannot reach; within {@link #DIRECT_HOME} of the den, straight to it. With no crumbs (or none that work) it
     * makes greedy legs towards the den, going round sideways when those stop getting it nearer.
     */
    private void headHome(boolean withCatch) {
        if (withCatch && !carrying()) { toState(HOME, 0); return; }        // nothing in its jaws after all
        if (den == null) {
            // no den to go to any more: it eats where it is
            if (carrying()) toState(EAT, MEAL_TICKS);
            else toState(ROAM, 0);
            return;
        }
        if (tickCount % 5 == 0 && spot(true)) return;
        Vec3 goal = new Vec3(den.getX() + 1.0, den.getY(), den.getZ() + 1.0);
        Vec3 at = position();
        if (Mth.square(at.x - goal.x) + Mth.square(at.z - goal.z) < 2.5 * 2.5 && Math.abs(at.y - goal.y) < 2.0) { arriveHome(); return; }
        // past any crumb it now stands near (and every later one: the trail behind it)
        if (crumbs.reached(nodeX(), nodeY(), nodeZ(), 3.0)) { crumbFails = 0; denFails = 0; legGoal = null; homeLost = 0; lostBest = Double.MAX_VALUE; }
        double toDen = at.distanceTo(goal);
        if (toDen < lostBest - 4.0) { lostBest = toDen; homeLost = 0; }
        PathNavigation nav = getNavigation();
        // stuck a while close to home (a ledge it cannot get down from, say), with no way on: near enough
        Path way = nav.getPath();
        boolean noWay = nav.isDone() || nav.isStuck() || (way != null && !way.canReach());
        if (++homeLost > HOME_NEAR_TICKS && toDen < HOME_NEAR && noWay) { arriveHome(); return; }
        if (homeLost > LOST_TICKS) { lostOnWayHome(); return; }
        double speed = carrying() ? CARRY_SPEED : HOME_SPEED;
        if (!nav.isDone() && !nav.isStuck() && stateTicks - legStart < 200) return;      // on its way (a fresh look every ten seconds)
        // a path used up at once (it stands at the end of the nearest it can get) waits a moment before the next
        if (stateTicks - legStart < 10) return;
        if (nav.isStuck()) nav.stop();
        // the last leg ended short of its goal: a crumb missed twice is skipped, and the next one tried
        if (legGoal != null) {
            if (legGoal.equals(den)) denFails++;
            else if (!crumbs.isEmpty() && legGoal.equals(lastCrumb()) && ++crumbFails >= 2) { crumbs.pop(); crumbFails = 0; }
        }
        legStart = stateTicks;
        // the first crumb is the den itself: once only that is left, the greedy legs below take over
        BlockPos target = toDen < DIRECT_HOME && denFails < 3 ? den : crumbs.size() > 1 ? lastCrumb() : null;
        if (target != null) {
            legGoal = target;
            Path p = nav.createPath(target.offset(1, 0, 1), 1);
            if (p != null) nav.moveTo(p, speed);         // no way at all from here: missed, and counted on the next look
            return;
        }
        legGoal = null;
        if (toDen < homeBest - 2.0) { homeBest = toDen; homeStall = 0; }
        else homeStall++;
        Vec3 to = goal;
        if (homeStall >= 2) {
            Vec3 side = sideLeg(goal);
            if (side != null) to = side;
            homeStall = 0;
            denFails = 0;
        }
        nav.moveTo(to.x, to.y, to.z, speed);
    }

    private BlockPos lastCrumb() {
        int i = crumbs.size() - 1;
        return new BlockPos(crumbs.x(i), crumbs.y(i), crumbs.z(i));
    }

    /** A leg off to one side of the way to {@code goal}, to get round whatever stopped it going straight. */
    @Nullable
    private Vec3 sideLeg(Vec3 goal) {
        double base = Math.atan2(goal.z - getZ(), goal.x - getX());
        for (int i = 0; i < 8; i++) {
            double a = base + (random.nextBoolean() ? 1 : -1) * Math.toRadians(50 + random.nextInt(80));
            double len = 12 + random.nextInt(9);
            Vec3 v = walkableNear(BlockPos.containing(getX() + Math.cos(a) * len, getY() + random.nextInt(9) - 4, getZ() + Math.sin(a) * len));
            if (v != null) return v;
        }
        return spotNear(blockPosition(), 12, 4);
    }

    private void resetHoming() {
        legGoal = null;
        legStart = -10;
        crumbFails = denFails = homeStall = 0;
        homeBest = Double.MAX_VALUE;
        homeLost = 0;
        lostBest = Double.MAX_VALUE;
    }

    /**
     * {@link #LOST_TICKS} on the way home without getting anywhere: it has lost the way. It eats what it carries where
     * it stands and leaves its family to roam on alone, and the den brings out another in its place a day later.
     */
    private void lostOnWayHome() {
        getNavigation().stop();
        boolean meal = carrying();
        MossbackDen.lizardStrayed(this);
        becomeStray();
        if (meal) toState(EAT, MEAL_TICKS);
        else toState(ROAM, 0);
    }

    /** At the den: a catch is eaten there; a roamer rests; one called home may find it is the guard now. */
    private void arriveHome() {
        getNavigation().stop();
        MossbackDen.arrivedHome(this);
        crumbs.clear();
        if (carrying()) { toState(EAT, MEAL_TICKS); return; }
        if (isRoamer()) restAtHome(200 + random.nextInt(400));
        toState(ROAM, 0);
    }

    // ------------------------------------------------------------------------------------------------ damage

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) return false;
        Entity attacker = source.getEntity();
        throatHit = false;
        if (state() == REAR && attacker != null) {
            Vec3 to = attacker.position().subtract(position());
            if (to.lengthSqr() > 1.0E-4 && to.normalize().dot(lookDir()) > 0.3) {
                amount *= THROAT_MULTIPLIER;
                throatHit = true;
            }
        }
        float before = getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            if (state() == GRAB && attacker != null && attacker.getId() == heldId()) grabDamage += Math.max(0.0F, before - getHealth());
            if (throatHit) {
                toState(RECOIL, 25);
                playSound(ERSounds.MOSSBACK_HURT.get(), 1.5F, 1.3F);
            }
            if (attacker instanceof LivingEntity le && le != this && huntable(le) && !denMate(le) && state() != GRAB && getTarget() != le
                    && (getTarget() == null || !(getTarget() instanceof Player))) {
                dropCatch();                       // hurt while it carries its catch home: it lets go and fights
                setTarget(le);
                lastSeen = le.position();
                lastSeenTime = level().getGameTime();
                byte s = state();
                if (s == ROAM || s == WAIT || s == EAT || s == SEARCH || s == CARRY || s == HOME) {
                    chaseFrom = blockPosition();
                    toState(CHASE, 0);
                }
            }
        }
        return hurt;
    }

    /** One of its own family (they share the den as home), or of the family one of them left: kin never fight. */
    private boolean denMate(Entity e) {
        if (!(e instanceof MossbackEntity m)) return false;
        BlockPos mine = den != null ? den : kinDen, theirs = m.den != null ? m.den : m.kinDen;
        return mine != null && mine.equals(theirs);
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            release(false);
            setCarried("");
            MossbackDen.lizardDied(this);
        }
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide) {
            release(false);
            // taken out of the level without dying (Peaceful, the void, another dimension): it leaves its family
            if ((reason == RemovalReason.DISCARDED || reason == RemovalReason.CHANGED_DIMENSION) && !isDeadOrDying()) MossbackDen.lizardGone(this);
        }
        super.remove(reason);
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    protected SoundEvent getAmbientSound() {
        byte s = state();
        // silent while it lies in wait; otherwise you hear it coming
        if ((s == WAIT && ambushing) || s == EAT) return null;
        return ERSounds.MOSSBACK_CALL.get();
    }

    @Override public int getAmbientSoundInterval() { return 140; }
    @Override protected float getSoundVolume() { return 1.6F; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.MOSSBACK_HURT.get(); }
    @Override protected SoundEvent getDeathSound() { return ERSounds.MOSSBACK_DEATH.get(); }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { }

    private void stepSounds() {
        double moved = position().distanceTo(new Vec3(xo, yo, zo));
        stepDist += (float) moved;
        if (stepDist > 1.4F && surface() != null) {
            stepDist = 0;
            playSound(ERSounds.MOSSBACK_STEP.get(), 0.7F, 0.85F + random.nextFloat() * 0.3F);
        }
    }

    // ------------------------------------------------------------------------------------------------ life

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        if (isColdPlace(level, blockPosition())) setVariant(FROST);
        if (home == null) home = blockPosition();
        return super.finalizeSpawn(level, difficulty, reason, data, tag);
    }

    public static boolean isColdPlace(ServerLevelAccessor level, BlockPos pos) {
        var biome = level.getBiome(pos);
        return biome.is(ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "frost_fern_grotto")))
                || biome.is(ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "frozen_rootvaults")))
                || biome.value().coldEnoughToSnow(pos);
    }

    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean canBeLeashed(Player player) { return false; }
    /** It holds its breath twice as long as a player (it surfaces long before that). */
    @Override public int getMaxAirSupply() { return 600; }
    @Override public int getMaxHeadYRot() { return 50; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Variant", variant());
        if (den != null) tag.put("Den", NbtUtils.writeBlockPos(den));
        if (kinDen != null) tag.put("KinDen", NbtUtils.writeBlockPos(kinDen));
        if (home != null) tag.put("Home", NbtUtils.writeBlockPos(home));
        tag.putInt("Hunger", hunger);
        // the synced catch and the trip are not saved with the state (it reloads roaming, and goes on from there)
        if (carrying()) tag.putString("Carry", carried());
        if (role != null) tag.putString("Role", role.name());
        tag.putByte("Trip", trip);
        tag.putInt("Foray", forayTicks);
        tag.putInt("Rest", restTicks);
        tag.putBoolean("CalledHome", calledHome);
        if (!crumbs.isEmpty()) tag.putIntArray("Crumbs", crumbs.toArray());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setVariant(tag.getByte("Variant"));
        den = tag.contains("Den") ? NbtUtils.readBlockPos(tag.getCompound("Den")) : null;
        kinDen = tag.contains("KinDen") ? NbtUtils.readBlockPos(tag.getCompound("KinDen")) : null;
        home = tag.contains("Home") ? NbtUtils.readBlockPos(tag.getCompound("Home")) : den;
        hunger = tag.getInt("Hunger");
        setCarried(tag.getString("Carry"));
        try {
            role = tag.contains("Role") ? DenRegistry.Role.valueOf(tag.getString("Role")) : null;
        } catch (IllegalArgumentException e) {
            role = null;                           // its den tells it again at its next check-in
        }
        trip = tag.getByte("Trip");
        forayTicks = tag.getInt("Foray");
        restTicks = tag.getInt("Rest");
        calledHome = tag.getBoolean("CalledHome");
        crumbs.fromArray(tag.getIntArray("Crumbs"));
    }

    /** For /remix mossback: what it is doing. */
    public String describe() {
        byte s = state();
        Direction d = surface();
        return (s >= 0 && s < STATE_NAMES.length ? STATE_NAMES[s] : "?") + (s == WAIT ? (ambushing ? " (still)" : " (going)") : "")
                + " on " + (d == null ? "nothing" : d == Direction.DOWN ? "floor" : d == Direction.UP ? "ceiling" : "wall " + d.getName())
                + ", health " + Math.round(getHealth()) + ", target " + (getTarget() == null ? "none" : getTarget().getName().getString())
                + ", den " + (den == null ? "none" : den.toShortString()) + family() + (carrying() ? ", carrying " + carried() : "")
                + ", hunger " + hunger / 20 + "s" + motionNote();
    }

    /** How it is moving, for {@link #describe}: in water or in the air, wedged, and where its path leads next. */
    private String motionNote() {
        StringBuilder b = new StringBuilder();
        if (isInWater()) b.append(String.format(java.util.Locale.ROOT, ", in water %.2f deep", getFluidHeight(FluidTags.WATER)));
        if (airborne()) b.append(", falling");
        if (getMoveControl() instanceof ClimbMoveControl mc && mc.wedged() > 0) b.append(", wedged ").append(mc.wedged());
        PathNavigation nav = getNavigation();
        Path p = nav.getPath();
        if (p == null || p.isDone()) b.append(", no path");
        else {
            net.minecraft.core.Vec3i n = p.getNextNodePos();
            b.append(", next ").append(n.getX()).append(' ').append(n.getY()).append(' ').append(n.getZ())
                    .append(" (").append(p.getNextNodeIndex()).append('/').append(p.getNodeCount())
                    .append(p.canReach() ? "" : ", short").append(nav.isStuck() ? ", stuck" : "").append(')');
        }
        return b.toString();
    }

    /** Its place in its family, for {@link #describe}. */
    private String family() {
        if (den == null) return "";
        if (role == null) return " (no role yet)";
        if (role == DenRegistry.Role.GUARD) return " (guard)";
        int far = (int) Math.round(Math.sqrt(position().distanceToSqr(den.getX() + 1.0, den.getY(), den.getZ() + 1.0)));
        String where = switch (trip) {
            case OUT -> "out " + forayTicks / 20 + "s of " + FORAY_TICKS / 20 + "s";
            case RETURN -> calledHome ? "called home" : "heading home";
            default -> restTicks > 0 ? "resting " + restTicks / 20 + "s more" : "about to go out";
        };
        return " (roamer, " + where + ", " + far + " blocks from it, " + crumbs.size() + " crumbs)";
    }

    public void forceState(byte s, int length) {
        if ((s == CARRY || s == HOME) && isRoamer()) trip = RETURN;
        toState(s, length);
    }

    /** For /remix mossback catch: a roamer is given a catch (as if it had just killed one) and heads home with it. */
    public boolean giveCatch(EntityType<?> type) {
        if (!isRoamer()) return false;
        release(false);
        setTarget(null);
        setCarried(EntityType.getKey(type).toString());
        trip = RETURN;
        toState(CARRY, 0);
        return true;
    }

    /** For /remix mossback foray: a roamer sets out on a foray now, whatever it was doing. */
    public boolean sendOut() {
        if (!isRoamer()) return false;
        release(false);
        setTarget(null);
        setCarried("");
        calledHome = false;
        startForay();
        toState(ROAM, 0);
        return true;
    }

    /** For /remix mossback freeze: holds it still where it is, gravity off, in the state it is in (for pictures). */
    public void setFrozen(boolean f) {
        frozen = f;
        if (f) setNoGravity(true);
        else setNoGravity(surface() != null && surface() != Direction.DOWN);
    }
    public boolean frozen() { return frozen; }

    /** For /remix mossback put: sets it on a rock face, heading along {@code fwd}, and freezes it there (for pictures). */
    public void pose(Vec3 pos, Direction rock, Vec3 fwd) {
        moveTo(pos.x, pos.y, pos.z, getYRot(), 0.0F);
        setDeltaMovement(Vec3.ZERO);
        entityData.set(DATA_SURFACE, (byte) rock.get3DDataValue());
        forward = fwd.normalize();
        entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        if (rock == Direction.DOWN) { setYRot((float) (Mth.atan2(fwd.z, fwd.x) * Mth.RAD_TO_DEG) - 90.0F); yBodyRot = getYRot(); yHeadRot = getYRot(); }
        setFrozen(true);
    }

    // ------------------------------------------------------------------------------------------------ client

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) clientTick();
        else if (isAlive()) {
            if (airTicks > 0) airDist += Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
            updateForward();
            stepSounds();
        }
    }

    private void clientTick() {
        glowO = glow; jawO = jaw; rearO = rear; frillO = frill;
        orientO.set(orient);
        byte s = state();
        double moved = Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
        boolean lit = moved > 0.012 || s == ALERT || s == REAR || s == LUNGE || s == GRAB || s == DROP || isDeadOrDying();
        if (isDeadOrDying()) lit = deathTime < 10;
        glow = lit ? Math.min(1.0F, glow + 0.25F) : Math.max(0.0F, glow - 0.035F);
        float jawT = switch (s) {
            case REAR -> 0.6F;
            case LUNGE -> 1.0F;
            case GRAB -> 0.3F + 0.12F * Mth.sin(tickCount * 0.9F);
            case ALERT -> 0.3F;
            case EAT -> 0.25F + 0.2F * Mth.sin(tickCount * 0.45F);
            case CARRY -> 0.22F;                      // a little open round the catch in its jaws
            default -> 0.0F;
        };
        if (carrying()) jawT = Math.max(jawT, 0.22F);
        jaw += (jawT - jaw) * 0.35F;
        float rearT = s == REAR ? 1.0F : s == ALERT ? 0.35F : 0.0F;
        rear += (rearT - rear) * 0.3F;
        float frillT = (s == ALERT || s == REAR || s == LUNGE || s == GRAB || s == CHASE || s == DROP) ? 1.0F : 0.0F;
        frill += (frillT - frill) * 0.25F;

        // orientation: its belly to its rock, its head the way it goes
        Direction d = surface();
        Vec3 upT = d == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(d.getOpposite().getNormal());
        if (clientUp.dot(upT) < -0.99) clientUp = clientUp.add(clientForward.cross(clientUp).scale(0.3));   // roll over, keeping its heading
        clientUp = clientUp.lerp(upT, 0.35);
        if (clientUp.lengthSqr() < 1.0E-4) clientUp = upT;
        clientUp = clientUp.normalize();
        Vector3f sf = entityData.get(DATA_FORWARD);
        Vec3 want;
        if (d == Direction.DOWN || d == null) want = Vec3.directionFromRotation(0, yBodyRot);
        else want = new Vec3(sf.x, sf.y, sf.z);
        if (clientForward.dot(want) < -0.99) clientForward = clientForward.add(clientUp.cross(clientForward).scale(0.3));
        clientForward = clientForward.lerp(want, 0.3);
        Vec3 f = clientForward.subtract(clientUp.scale(clientForward.dot(clientUp)));
        if (f.lengthSqr() < 1.0E-4) f = Vec3.directionFromRotation(0, yBodyRot).subtract(clientUp.scale(0.0));
        f = f.subtract(clientUp.scale(f.dot(clientUp)));
        if (f.lengthSqr() < 1.0E-4) f = Math.abs(clientUp.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        f = f.normalize();
        clientForward = f;
        orient.set(basis(f, clientUp));
        if (tickCount < 2) orientO.set(orient);
    }

    /** The rotation that turns the model's back up along {@code up} and its head along {@code fwd}. */
    public static Quaternionf basis(Vec3 fwd, Vec3 up) {
        // JOML takes the columns: the model's x (right), y (back) and z (tail) in the world
        Vec3 right = fwd.cross(up).normalize();
        Vec3 uu = up.subtract(fwd.scale(up.dot(fwd))).normalize();
        Matrix3f m = new Matrix3f(
                (float) right.x, (float) right.y, (float) right.z,
                (float) uu.x, (float) uu.y, (float) uu.z,
                (float) -fwd.x, (float) -fwd.y, (float) -fwd.z);
        return new Quaternionf().setFromNormalized(m);
    }

    public float glow(float pt) { return Mth.lerp(pt, glowO, glow); }
    public float jaw(float pt) { return Mth.lerp(pt, jawO, jaw); }
    public float rear(float pt) { return Mth.lerp(pt, rearO, rear); }
    public float frill(float pt) { return Mth.lerp(pt, frillO, frill); }
}
