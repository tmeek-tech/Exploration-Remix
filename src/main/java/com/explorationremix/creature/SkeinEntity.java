package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNavigation;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * The Skein: the Blue Reaches' cave crawler. A long, low, many-legged thing of overlapping cobalt-blue shell plates,
 * about five blocks from the wedge of its head to the spurs of its tail, a seam of cyan light down its back. Most of
 * the time it lies motionless on a floor, a wall or the ceiling, only its feelers twitching; now and then it goes out
 * round the cave in erratic bursts, a cockroach's way: a dash, a dead stop, a snap to a new heading, another dash
 * ({@link SkeinGait}). It runs up walls and across ceilings as readily as over floors (the climbing pathfinder and
 * move control the Mossback uses, in creature.climb), and its body follows its head's exact path like a train ({@link
 * SkeinTrail}), pouring over edges and round corners.
 *
 * <p>It minds a player only when they come close: within about {@link #STRIKE_RANGE} blocks, if it can see or feel
 * them, it rears its front plates for a split second, lunges and bites, then skitters a few blocks off and freezes
 * again. It never chases: it strikes at nobody further than {@link #STRIKE_LEASH} from where it first struck. Hurt from
 * close by, it strikes back; from further off, it bolts. Hungry, it stalks a swarm of blue fireflies that has landed
 * near it ({@link FireflySwarm}), creeping up in short bursts and long freezes, strikes into it and eats a few; then it
 * is fed for a minute or three. It never goes into the Asurine Geodes, the Blue Reaches' refuge.
 *
 * <p>The head carries the main box; the body is {@link #SECTION_AT}.length sections along the trail ({@link Section},
 * Forge's multipart parts, as the ender dragon has), so any of it can be hit, and a hit on a section hurts the Skein.
 */
public class SkeinEntity extends Monster implements ClimbMoveControl.Climber {
    // ------------------------------------------------------------------------------------------------ tuning
    /** Movement speed is blocks a tick at a speed of 1 (ClimbMoveControl): its bursts run at about a player's sprint. */
    public static final double HEALTH = 60.0, ARMOR = 6.0, SPEED = 0.29, KNOCKBACK_RESISTANCE = 0.8;
    static final float BITE = 6.0F;
    /** It strikes at a player this near its jaws that it can see, or nearer than {@link #TOUCH} (or touching its body). */
    static final double STRIKE_RANGE = 4.0, TOUCH = 1.6;
    /** It never lunges at anyone further than this from where it first struck (and never chases at all). */
    static final double STRIKE_LEASH = 8.0;
    /** After this long without striking, a new encounter starts where it next strikes. */
    static final int ENCOUNTER_TICKS = 600;
    /** A split second rearing (shorter when it strikes back), the lunge, and a moment before it strikes again. */
    static final int REAR_TICKS = 7, SNAP_REAR_TICKS = 3, SWARM_REAR_TICKS = 5, LUNGE_TICKS = 8, STRIKE_COOLDOWN = 30;
    static final double LUNGE_SPEED = 0.62;
    /** After a strike it skitters off this far before it freezes; hurt from afar, it bolts this far. */
    static final double AFTER_STRIKE_MIN = 3.0, AFTER_STRIKE_MAX = 6.0, BOLT_MIN = 10.0, BOLT_MAX = 20.0;
    /** A landed swarm this near draws it when hungry; it strikes into the swarm from this near. */
    static final double SWARM_SENSE = 12.0, SWARM_STRIKE = 2.6, SWARM_BITE = 1.4;
    /** A stalk gives up after this long; its outings are this long (it starts another while the swarm stays). */
    static final int STALK_GIVE_UP = 1200;
    static final double STALK_BUDGET = 40.0;
    /** A meal of fireflies keeps it fed for one to three minutes; it chews this long. */
    static final int MEAL_MIN = 1200, MEAL_MAX = 3600, EAT_TICKS = 50;
    /** How far and how long it may touch nothing (rounding an edge) before it falls (as the Mossback). */
    static final double AIR_REACH = 1.5;
    static final int AIR_GRACE = 40;
    /** A patter of feet every this many blocks it runs. */
    static final double PATTER_EVERY = 1.7;
    /** How much of the way to its new heading the drawn head turns each tick. */
    static final double HEAD_SNAP = 0.75;

    // ------------------------------------------------------------------------------------------------ the body
    /** Its plates (the last one the tail's), each this far back along the trail from the point under its head. */
    public static final int PLATES = 13;
    public static final double PLATE_START = 0.12, PLATE_STEP = 0.30;
    /** Its jaws are this far ahead of the point under its head. */
    public static final double HEAD_REACH = 0.8;
    /** The trail it keeps: the whole body and a little more. */
    public static final double TRAIL_KEEP = PLATE_START + PLATE_STEP * (PLATES - 1) + 1.6;
    /** The hit sections along the body: how far back along the trail each lies, and its box (the head has the main box). */
    static final double[] SECTION_AT = {1.3, 2.15, 2.95, 3.7};
    static final float[] SECTION_W = {1.25F, 1.05F, 0.85F, 0.65F}, SECTION_H = {0.55F, 0.5F, 0.45F, 0.4F};

    public static double plateAt(int k) { return PLATE_START + PLATE_STEP * k; }

    public static final ResourceKey<Biome> REFUGE = ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "asurine_geodes"));
    /**
     * The game's entity id counter (Entity.ENTITY_COUNTER, by its SRG name): ids for the sections are taken from it
     * right after the Skein's own, as the ender dragon takes its parts', so the client numbers them the same way.
     */
    @Nullable private static final AtomicInteger IDS = idCounter();

    // ------------------------------------------------------------------------------------------------ state
    public static final byte STILL = 0, SKITTER = 1, STALK = 2, REAR = 3, LUNGE = 4, BOLT = 5, EAT = 6;
    /** The state names, by id, for /remix skein. */
    public static final String[] STATE_NAMES = {"still", "skitter", "stalk", "rear", "lunge", "bolt", "eat"};
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it falls or swims. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.BYTE);
    /** The way its head points along its rock (the server's {@code forward}). */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.VECTOR3);

    private final Section[] sections;
    private final SkeinTrail trail = new SkeinTrail(TRAIL_KEEP);
    private final SkeinGait gait = new SkeinGait();
    private final DoubleSupplier rnd = () -> random.nextDouble();
    private int stateTicks, stateLength;
    private Vec3 forward = new Vec3(0, 0, 1);
    @Nullable private Vec3 pivotWant;        // PIVOT: the way it snaps to
    @Nullable private Path pendingPath;      // PIVOT: the way the coming burst runs
    private double burstMoved;               // BURST: how far it has gone in this one
    @Nullable private Vec3 threat;           // BOLT: what it gets away from
    @Nullable private FireflySwarm prey;     // STALK, and a strike into a swarm
    private boolean atSwarm;                 // REAR and LUNGE: the strike is at the swarm, not a player
    @Nullable private Vec3 anchor;           // where it first struck in this encounter
    private long lastStrike = -ENCOUNTER_TICKS;
    private int strikeCooldown;
    private int hunger = 600;
    private Vec3 lungeDir = Vec3.ZERO;
    private int detachTicks, airTicks;
    private double airDist, movedLast;
    private float stepDist;

    // client-side animation state (see SkeinRenderer)
    public float run, runO, legPhase, legPhaseO, sway, swayO, shudder, shudderO, rear, rearO, jaw, jawO, glow, glowO;
    public float ripple = -1, rippleO = -1;
    public final float[] feelerYaw = new float[2], feelerYawO = new float[2], feelerPitch = new float[2], feelerPitchO = new float[2];
    private final float[] feelerYawT = new float[2], feelerPitchT = new float[2];
    private final int[] feelerTimer = new int[2];
    public final Quaternionf headOrient = new Quaternionf(), headOrientO = new Quaternionf();
    private Vec3 clientForward = new Vec3(0, 0, 1), clientUp = new Vec3(0, 1, 0);
    @Nullable private Direction clientRock = Direction.DOWN;
    private double movedO;

    public SkeinEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.sections = new Section[SECTION_AT.length];
        for (int i = 0; i < sections.length; i++) sections[i] = new Section(this, i, SECTION_W[i], SECTION_H[i]);
        if (IDS != null) setId(IDS.getAndAdd(sections.length + 1) + 1);
        this.moveControl = new ClimbMoveControl(this);
        this.xpReward = 25;
        setMaxUpStep(1.0F);
        gait.rest(40 + random.nextInt(200));
    }

    @Nullable
    private static AtomicInteger idCounter() {
        try {
            return (AtomicInteger) ObfuscationReflectionHelper.findField(Entity.class, "f_19843_").get(null);
        } catch (RuntimeException | IllegalAccessException e) {
            // without it the sections keep the ids they were made with (one after another all the same, as a rule)
            ExplorationRemix.LOGGER.warn("Skein: no entity id counter, its body sections take ids of their own", e);
            return null;
        }
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ARMOR, ARMOR)
                .add(Attributes.MOVEMENT_SPEED, SPEED)
                .add(Attributes.ATTACK_DAMAGE, BITE)
                .add(Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_RESISTANCE)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STATE, STILL);
        entityData.define(DATA_SURFACE, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_FORWARD, new Vector3f(0, 0, 1));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ClimbNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        // the Skein's mind is its own state machine (customServerAiStep), not goals
    }

    public byte state() { return entityData.get(DATA_STATE); }
    @Nullable public Direction surface() {
        byte b = entityData.get(DATA_SURFACE);
        return b < 0 ? null : Direction.from3DDataValue(b);
    }
    public SkeinTrail trail() { return trail; }
    public SkeinGait gait() { return gait; }
    public Section[] sections() { return sections; }

    @Override public boolean onFloor() { return surface() == Direction.DOWN; }
    @Override public boolean canSteer() { return state() != LUNGE; }
    @Override public boolean airborne() { return airDist > AIR_REACH || airTicks > AIR_GRACE; }
    @Override public boolean holdStill() { return clingsOffFloor(); }
    /** It climbs on its own; vines and ladders must not lift it the way they lift a player. */
    @Override public boolean onClimbable() { return false; }
    /** Nothing shoves it off its rock (it shoves, though). */
    @Override public boolean isPushable() { return false; }
    /** Its body trails five blocks behind its box: keep drawing it while any of it is on screen. */
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(TRAIL_KEEP); }

    // ------------------------------------------------------------------------------------------------ the body's parts

    @Override public boolean isMultipartEntity() { return true; }
    @Override public PartEntity<?>[] getParts() { return sections; }

    @Override
    public void setId(int id) {
        super.setId(id);
        // the sections' ids follow the Skein's, on the server and on every client alike
        if (sections != null) for (int i = 0; i < sections.length; i++) sections[i].setId(id + i + 1);
    }

    /** A hit on a body section hurts the Skein. */
    boolean hurtFromSection(Section s, DamageSource source, float amount) {
        return hurt(source, amount);
    }

    /** Sets each section on the trail where it lies, its box against the rock under it. */
    private void placeSections() {
        double[] out = new double[6 * sections.length];
        trail.sampleAll(SECTION_AT, out);
        for (int i = 0; i < sections.length; i++) {
            Section s = sections[i];
            double ux = out[6 * i + 3], uy = out[6 * i + 4], uz = out[6 * i + 5];
            double w = s.getBbWidth() / 2.0, h = s.getBbHeight() / 2.0;
            double ext = Math.abs(ux) * w + Math.abs(uy) * h + Math.abs(uz) * w;
            double cx = out[6 * i] + ux * ext, cy = out[6 * i + 1] + uy * ext, cz = out[6 * i + 2] + uz * ext;
            s.xo = s.getX(); s.yo = s.getY(); s.zo = s.getZ();
            s.xOld = s.xo; s.yOld = s.yo; s.zOld = s.zo;
            s.setPos(cx, cy - h, cz);
        }
    }

    /** Whether {@code e}'s box is against its body (the head's box or a section's). */
    private boolean touches(Entity e) {
        AABB b = e.getBoundingBox();
        if (getBoundingBox().inflate(0.4).intersects(b)) return true;
        for (Section s : sections) if (s.getBoundingBox().inflate(0.4).intersects(b)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** The outward normal of the rock it clings to (up on a floor, and while it falls or swims). */
    public Vec3 up() {
        return normal(surface());
    }

    private static Vec3 normal(@Nullable Direction rock) {
        return rock == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(rock.getOpposite().getNormal());
    }

    /** The point on its rock under the middle of its head's box: where its trail runs. */
    public Vec3 feet(Vec3 up) {
        double w = getBbWidth() / 2.0, h = getBbHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        return position().add(0, h, 0).subtract(up.scale(ext));
    }

    /** The way its head points along its rock. */
    public Vec3 lookDir() { return forward; }
    public Vec3 bodyCentre() { return feet(up()).add(up().scale(0.25)); }
    public Vec3 mouth() { return feet(up()).add(forward.scale(HEAD_REACH)).add(up().scale(0.15)); }
    public Vec3 eyes() { return feet(up()).add(forward.scale(0.5)).add(up().scale(0.35)); }

    private boolean clingsOffFloor() {
        Direction d = surface();
        return d != null && d != Direction.DOWN;
    }

    // ------------------------------------------------------------------------------------------------ surface

    private boolean touching(AABB box, Direction d) {
        return rockBeside(level(), this, box, d);
    }

    /** Whether there is rock against side {@code d} of {@code box} (for {@code who}'s collisions; null for anyone's). */
    private static boolean rockBeside(Level level, @Nullable Entity who, AABB box, Direction d) {
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
        return !level.noCollision(who, slab);
    }

    /** Which rock it clings to: the floor when it touches one, else the wall or ceiling it was on, else any wall, else the ceiling. */
    private void updateSurface() {
        if (detachTicks > 0) detachTicks--;
        AABB box = getBoundingBox();
        Direction cur = surface();
        Direction next;
        if (onGround() || touching(box, Direction.DOWN)) next = Direction.DOWN;
        else if (isInWater() || detachTicks > 0) next = null;
        else if (cur != null && cur != Direction.DOWN && touching(box, cur)) next = cur;
        else next = anyRock(box, getDeltaMovement());
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

    /** The wall it is heading into most (or any it touches), else the ceiling, else nothing. */
    @Nullable
    private Direction anyRock(AABB box, Vec3 m) {
        Direction best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!touching(box, d)) continue;
            if (best == null || m.dot(Vec3.atLowerCornerOf(d.getNormal())) > m.dot(Vec3.atLowerCornerOf(best.getNormal()))) best = d;
        }
        return best != null ? best : touching(box, Direction.UP) ? Direction.UP : null;
    }

    /**
     * The way it points along its rock: the way it moved this tick; snapping to its new heading while it pivots; or
     * turning to its quarry while it rears. Runs after travel, when this tick's motion is known.
     */
    private void updateForward() {
        Vec3 up = up();
        Vec3 mv = position().subtract(xo, yo, zo);
        Vec3 along = mv.subtract(up.scale(mv.dot(up)));
        Vec3 want = null;
        double rate = 0.8;
        byte s = state();
        Vec3 aim = s == REAR ? strikeAim() : null;
        if (aim != null) {
            Vec3 to = aim.subtract(feet(up));
            Vec3 flat = to.subtract(up.scale(to.dot(up)));
            if (flat.lengthSqr() > 1.0E-4) { want = flat.normalize(); rate = 0.6; }
        } else if (gait.phase() == SkeinGait.Phase.PIVOT && pivotWant != null && (s == SKITTER || s == BOLT || s == STALK)) {
            Vec3 flat = pivotWant.subtract(up.scale(pivotWant.dot(up)));
            if (flat.lengthSqr() > 1.0E-4) { want = flat.normalize(); rate = 0.65; }    // a snap: done in two or three ticks
        } else if (along.lengthSqr() > 1.0E-4) want = along.normalize();
        if (want != null) {
            if (forward.dot(want) < -0.99) forward = forward.add(up.cross(forward).scale(0.3));   // never lerp through zero
            forward = forward.lerp(want, rate);
        }
        Vec3 f = forward.subtract(up.scale(forward.dot(up)));
        forward = f.lengthSqr() > 1.0E-3 ? f.normalize() : Vec3.directionFromRotation(0, getYRot());
        if (onFloor()) {
            float yaw = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90.0F;
            setYRot(yaw);
            yBodyRot = yaw;
            yHeadRot = yaw;
        }
        Vector3f synced = entityData.get(DATA_FORWARD);
        if (synced.x * forward.x + synced.y * forward.y + synced.z * forward.z < 0.998)
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
    }

    @Override
    public void travel(Vec3 input) {
        // press against the wall or ceiling so it stays in touch with it
        Direction d = surface();
        if (d != null && d != Direction.DOWN && !level().isClientSide && state() != LUNGE) {
            Vec3 n = Vec3.atLowerCornerOf(d.getNormal());
            setDeltaMovement(getDeltaMovement().add(n.scale(0.04)));
        }
        super.travel(input);
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
        if (s != REAR && s != LUNGE) atSwarm = false;
    }

    @Override
    protected void customServerAiStep() {
        updateSurface();
        // in water deeper than its legs it swims up and keeps its head out, as other land beasts do
        if (isInWater() && getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold()) getJumpControl().jump();
        stateTicks++;
        if (hunger > 0) hunger--;
        if (strikeCooldown > 0) strikeCooldown--;
        if (anchor != null && level().getGameTime() - lastStrike > ENCOUNTER_TICKS) anchor = null;
        switch (state()) {
            case STILL -> still();
            case SKITTER, BOLT -> outing();
            case STALK -> stalk();
            case REAR -> rear();
            case LUNGE -> lunge();
            case EAT -> eat();
            default -> settle();
        }
    }

    // --- lying still, and going out

    private void still() {
        stopDead();
        if (tickCount % 2 == 0 && strikeAtPlayer()) return;
        if (tickCount % 20 == 0 && (lookForSwarm() || leaveRefuge())) return;
        if (!gait.outing() && gait.tick(0.0, rnd) && gait.outing()) {
            // its rest is over: out it goes, snapping to its first heading
            toState(SKITTER, 0);
            beginPivot();
        }
    }

    /** Out roaming (SKITTER) or getting away (BOLT): burst, freeze and snap until the outing's way is covered. */
    private void outing() {
        // roaming it strikes at whoever comes near; bolting, only once it has frozen again
        boolean bolting = state() == BOLT;
        if (tickCount % 2 == 0 && (!bolting || gait.still()) && strikeAtPlayer()) return;
        if (!bolting && tickCount % 20 == 0 && lookForSwarm()) return;
        stepGait();
        if (!gait.outing()) settle();
    }

    /** Back to lying still (the gait is resting already, or rests now). */
    private void settle() {
        stopDead();
        if (gait.outing()) gait.rest(rnd);
        threat = null;
        prey = null;
        setTarget(null);
        toState(STILL, 0);
    }

    /** It finds itself in the refuge (set down there, say): it goes out at once, never to come back. */
    private boolean leaveRefuge() {
        if (!isRefuge(blockPosition())) return false;
        startOuting(SKITTER, SkeinGait.Mood.ROAM, SkeinGait.OUTING_MAX);
        return true;
    }

    private void startOuting(byte state, SkeinGait.Mood mood, double blocks) {
        gait.startOuting(mood, blocks, rnd);
        toState(state, 0);
        beginPivot();
    }

    /** One tick of the gait: snap, run, freeze. */
    private void stepGait() {
        boolean changed = gait.tick(movedLast, rnd);
        switch (gait.phase()) {
            case PIVOT -> { if (changed) beginPivot(); else stopDead(); }
            case BURST -> { if (changed) beginBurst(); else burstTick(); }
            case FREEZE, REST -> stopDead();
        }
    }

    /** A snap: it stops dead and picks the way of its coming burst; it turns its head that way over the pivot's ticks. */
    private void beginPivot() {
        stopDead();
        pendingPath = null;
        for (int i = 0; i < 6 && pendingPath == null; i++) {
            if (i > 0) gait.reroll(rnd);
            pendingPath = burstPath();
        }
        pivotWant = pendingPath != null ? firstLeg(pendingPath) : null;
    }

    private void beginBurst() {
        if (pendingPath == null) {
            // nowhere to go that way: it twitches where it is, and tries again after a freeze
            gait.endBurst(true, rnd);
            stopDead();
            return;
        }
        PathNavigation nav = getNavigation();
        nav.moveTo(pendingPath, gait.speed());
        pendingPath = null;
        burstMoved = 0.0;
        // off at once, at full speed (the navigation ran before this tick's mind; it sets the first step now)
        nav.tick();
    }

    private void burstTick() {
        PathNavigation nav = getNavigation();
        burstMoved += movedLast;
        if (nav.isDone() || nav.isStuck()) {
            gait.endBurst(nav.isStuck() || burstMoved < 0.5, rnd);
            stopDead();
            return;
        }
        // never at an even pace: the speed flickers through the burst
        nav.setSpeedModifier(gait.speed() * (0.8 + 0.4 * random.nextDouble()));
    }

    /** Freezes dead: no path, no step this tick, no coasting. */
    private void stopDead() {
        getNavigation().stop();
        getMoveControl().strafe(0.0F, 0.0F);
        if (surface() != null && state() != LUNGE) {
            Vec3 dm = getDeltaMovement();
            setDeltaMovement(0.0, clingsOffFloor() ? 0.0 : Math.min(0.0, dm.y), 0.0);
        }
    }

    /**
     * The way the coming burst runs: roaming, a spot about the burst's length along its heading snapped by the gait's
     * turn; bolting, the same away from what it flees; stalking, towards the swarm. Null when there is no way there
     * (rock, unloaded chunks, or a way through the refuge).
     */
    @Nullable
    private Path burstPath() {
        Vec3 up = up();
        BlockPos goal;
        if (gait.mood() == SkeinGait.Mood.STALK) {
            if (prey == null) return null;
            goal = nodeNear(prey.centre(), 3);
        } else {
            Vec3 base = gait.mood() == SkeinGait.Mood.BOLT ? awayFrom(threat, up) : forward;
            Vec3 heading = rotate(base, up, gait.turn());
            double dist = Math.min(gait.budget(), gait.speed() * SPEED * gait.burstTicks()) + 1.5;
            goal = nodeNear(bodyCentre().add(heading.scale(Math.max(2.5, dist))), 2);
        }
        if (goal == null) return null;
        Path p = getNavigation().createPath(goal.offset(1, 0, 1), 0);
        if (p == null || p.getNodeCount() < 2 || crossesRefuge(p)) return null;
        Node end = p.getEndNode();
        if (end == null || Mth.square(end.x + 1.0 - getX()) + Mth.square(end.y - getY()) + Mth.square(end.z + 1.0 - getZ()) < 2.25) return null;
        return p;
    }

    /** The way along its rock away from {@code from} (or its own heading, with nothing to flee). */
    private Vec3 awayFrom(@Nullable Vec3 from, Vec3 up) {
        if (from == null) return forward;
        Vec3 a = position().subtract(from);
        a = a.subtract(up.scale(a.dot(up)));
        return a.lengthSqr() > 1.0E-4 ? a.normalize() : forward;
    }

    /** Where the path's first stretch leads from where it stands. */
    @Nullable
    private Vec3 firstLeg(Path p) {
        for (int i = p.getNextNodeIndex(); i < p.getNodeCount(); i++) {
            Vec3 at = p.getEntityPosAtNode(this, i);
            Vec3 to = at.subtract(position());
            if (to.lengthSqr() > 0.6) return to.normalize();
        }
        return null;
    }

    /**
     * The walkable node (floor, wall or ceiling, not water) whose box would sit nearest {@code p}, within {@code r}
     * blocks; never reading an unloaded chunk, never in the refuge.
     */
    @Nullable
    private BlockPos nodeNear(Vec3 p, int r) {
        int bx = Mth.floor(p.x - 1.0), by = Mth.floor(p.y - 0.35), bz = Mth.floor(p.z - 1.0);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!level().hasChunksAt(x - 1, z - 1, x + 2, z + 2)) continue;
                for (int dy = -r - 1; dy <= r; dy++) {
                    int y = by + dy;
                    double d = Mth.square(x + 1.0 - p.x) + Mth.square(y + 0.35 - p.y) + Mth.square(z + 1.0 - p.z);
                    if (d >= bestD) continue;
                    byte k = ClimbNodeEvaluator.classify(level(), x, y, z, 2, 1, 2);
                    if (k < ClimbNodeEvaluator.FLOOR || k == ClimbNodeEvaluator.WATER) continue;
                    BlockPos n = new BlockPos(x, y, z);
                    if (isRefuge(n.offset(1, 0, 1))) continue;
                    bestD = d;
                    best = n;
                }
            }
        return best;
    }

    /** Whether a path runs into the refuge (out of it is fine, for one that finds itself there). */
    private boolean crossesRefuge(Path p) {
        boolean inside = isRefuge(blockPosition());
        for (int i = inside ? p.getNodeCount() - 1 : 0; i < p.getNodeCount(); i++) {
            Node n = p.getNode(i);
            if (isRefuge(new BlockPos(n.x + 1, n.y, n.z + 1))) return true;
        }
        return false;
    }

    /** The Asurine Geodes are the Blue Reaches' refuge: no hunter goes there. */
    public boolean isRefuge(BlockPos p) {
        return level().isLoaded(p) && level().getBiome(p).is(REFUGE);
    }

    /** {@code v} turned by {@code a} radians about the unit axis {@code k} (Rodrigues). */
    static Vec3 rotate(Vec3 v, Vec3 k, double a) {
        double cos = Math.cos(a), sin = Math.sin(a);
        return v.scale(cos).add(k.cross(v).scale(sin)).add(k.scale(k.dot(v) * (1 - cos)));
    }

    // --- senses and the strike

    /** A player it can strike at now: close to its jaws and seen, or right by it, or touching its body. */
    @Nullable
    private Player strikable() {
        if (strikeCooldown > 0) return null;
        Vec3 m = mouth();
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : level().players()) {
            if (!huntable(p) || p.distanceToSqr(this) > 100.0 || isRefuge(p.blockPosition())) continue;
            // it never goes for anyone beyond its leash from where it first struck
            if (anchor != null && p.position().distanceTo(anchor) > STRIKE_LEASH) continue;
            double d = Math.sqrt(p.getBoundingBox().distanceToSqr(m));
            boolean near = touches(p) || d < TOUCH || (d < STRIKE_RANGE && canSee(p));
            if (near && d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private boolean strikeAtPlayer() {
        Player p = strikable();
        if (p == null) return false;
        startStrike(p, REAR_TICKS);
        return true;
    }

    /** It sees what is in the open round its head (its feelers take in every side), not through rock. */
    public boolean canSee(LivingEntity t) {
        if (t.isInvisible()) return false;
        Vec3 eye = eyes();
        for (Vec3 at : new Vec3[]{t.getEyePosition(), t.getBoundingBox().getCenter()}) {
            HitResult hit = level().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    private boolean huntable(LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.level() != level()) return false;
        return !(t instanceof Player p) || (!p.isCreative() && !p.isSpectator());
    }

    /** It rears at {@code t} (its rattle), then lunges. */
    private void startStrike(LivingEntity t, int rearTicks) {
        stopDead();
        setTarget(t);
        atSwarm = false;
        prey = null;
        if (anchor == null) anchor = position();
        toState(REAR, rearTicks);
        playSound(ERSounds.SKEIN_RATTLE.get(), 1.3F, 0.9F + random.nextFloat() * 0.25F);
    }

    private void startSwarmStrike(FireflySwarm s) {
        stopDead();
        setTarget(null);
        prey = s;
        toState(REAR, SWARM_REAR_TICKS);
        atSwarm = true;
        playSound(ERSounds.SKEIN_RATTLE.get(), 0.8F, 1.15F + random.nextFloat() * 0.2F);
    }

    /** What the strike is at: the player's middle, or the swarm's; null once that is gone. */
    @Nullable
    private Vec3 strikeAim() {
        if (atSwarm) return prey != null && prey.isAlive() && prey.count() > 0 ? prey.centre() : null;
        LivingEntity t = getTarget();
        return t != null && huntable(t) ? t.getBoundingBox().getCenter() : null;
    }

    private void rear() {
        stopDead();
        Vec3 aim = strikeAim();
        if (aim == null) { settle(); return; }
        if (stateTicks >= stateLength) {
            Vec3 to = aim.subtract(mouth());
            lungeDir = to.lengthSqr() > 1.0E-4 ? to.normalize() : forward;
            // off a wall or the ceiling, the lunge takes it off its rock
            if (!onFloor()) detachTicks = 12;
            toState(LUNGE, LUNGE_TICKS);
        }
    }

    private void lunge() {
        Vec3 aim = strikeAim();
        if (aim == null) { afterStrike(null); return; }
        double sp = stateTicks <= 3 ? LUNGE_SPEED : LUNGE_SPEED * 0.45;
        Vec3 v = lungeDir.scale(sp);
        setDeltaMovement(v.x, onFloor() ? Math.max(getDeltaMovement().y, v.y + 0.02) : v.y, v.z);
        // never past its leash
        if (anchor != null && position().distanceTo(anchor) > STRIKE_LEASH) { afterStrike(aim); return; }
        if (atSwarm) {
            if (prey != null && mouth().distanceTo(prey.centre()) < SWARM_BITE) { eatFrom(prey); return; }
        } else {
            LivingEntity t = getTarget();
            if (t != null && new AABB(mouth(), mouth()).inflate(0.7).intersects(t.getBoundingBox())) { bite(t); return; }
        }
        if (stateTicks >= stateLength) afterStrike(aim);
    }

    private void bite(LivingEntity t) {
        playSound(ERSounds.SKEIN_BITE.get(), 1.4F, 0.9F + random.nextFloat() * 0.2F);
        t.hurt(damageSources().mobAttack(this), BITE);
        afterStrike(t.position());
    }

    /** The strike is over, bitten or missed: it skitters a few blocks off and freezes again. */
    private void afterStrike(@Nullable Vec3 from) {
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(dm.x * 0.2, dm.y, dm.z * 0.2);
        lastStrike = level().getGameTime();
        strikeCooldown = STRIKE_COOLDOWN;
        setTarget(null);
        boltFrom(from != null ? from : position().add(forward), AFTER_STRIKE_MIN + random.nextDouble() * (AFTER_STRIKE_MAX - AFTER_STRIKE_MIN));
    }

    private void boltFrom(Vec3 from, double blocks) {
        threat = from;
        prey = null;
        startOuting(BOLT, SkeinGait.Mood.BOLT, blocks);
    }

    // --- fireflies

    private boolean stalkable(@Nullable FireflySwarm s) {
        return s != null && s.isAlive() && !s.isRemoved() && s.level() == level() && s.landed() && s.count() > 0
                && s.centre().distanceToSqr(bodyCentre()) < Mth.square(SWARM_SENSE + 6.0) && !isRefuge(BlockPos.containing(s.centre()));
    }

    /** Hungry, it goes for the nearest swarm that has landed within {@link #SWARM_SENSE} blocks. */
    private boolean lookForSwarm() {
        if (hunger > 0) return false;
        FireflySwarm s = nearestSwarm();
        if (s == null) return false;
        prey = s;
        startOuting(STALK, SkeinGait.Mood.STALK, STALK_BUDGET);
        return true;
    }

    @Nullable
    private FireflySwarm nearestSwarm() {
        FireflySwarm best = null;
        double bestD = SWARM_SENSE * SWARM_SENSE;
        Vec3 c = bodyCentre();
        for (FireflySwarm s : level().getEntitiesOfClass(FireflySwarm.class, getBoundingBox().inflate(SWARM_SENSE))) {
            if (!stalkable(s)) continue;
            double d = s.centre().distanceToSqr(c);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    private void stalk() {
        if (tickCount % 2 == 0 && strikeAtPlayer()) return;
        if (!stalkable(prey) || stateTicks > STALK_GIVE_UP) {
            // it took off, or was eaten up, or it has crept after it long enough
            gait.rest(SkeinGait.SHORT_REST_MIN + random.nextInt(SkeinGait.SHORT_REST_MAX - SkeinGait.SHORT_REST_MIN));
            settle();
            return;
        }
        if (mouth().distanceTo(prey.centre()) < SWARM_STRIKE) { startSwarmStrike(prey); return; }
        stepGait();
        if (!gait.outing()) startOuting(STALK, SkeinGait.Mood.STALK, STALK_BUDGET);    // the swarm is still there
    }

    private void eatFrom(FireflySwarm s) {
        playSound(ERSounds.SKEIN_BITE.get(), 1.0F, 1.2F + random.nextFloat() * 0.2F);
        int got = s.bite(2 + random.nextInt(4), this);
        prey = null;
        if (got <= 0) { afterStrike(s.centre()); return; }
        hunger = MEAL_MIN + random.nextInt(MEAL_MAX - MEAL_MIN + 1);
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(dm.x * 0.1, dm.y, dm.z * 0.1);
        toState(EAT, EAT_TICKS);
    }

    private void eat() {
        stopDead();
        if (tickCount % 2 == 0 && strikeAtPlayer()) return;
        if (stateTicks % 14 == 0) playSound(ERSounds.SKEIN_CHITTER.get(), 0.45F, 1.5F + random.nextFloat() * 0.2F);
        if (stateTicks >= stateLength) {
            gait.rest(rnd);
            settle();
        }
    }

    // ------------------------------------------------------------------------------------------------ damage

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            Entity attacker = source.getEntity();
            if (attacker instanceof LivingEntity le && le != this && huntable(le)) answer(le);
        }
        return hurt;
    }

    /** Hurt by {@code by}: struck back at once if it is close (and not in the refuge), otherwise it bolts. */
    private void answer(LivingEntity by) {
        byte s = state();
        if (s == REAR || s == LUNGE) return;
        double d = Math.sqrt(by.getBoundingBox().distanceToSqr(mouth()));
        if ((d < STRIKE_RANGE + 0.5 || touches(by)) && !isRefuge(by.blockPosition())) {
            anchor = position();
            startStrike(by, SNAP_REAR_TICKS);
        } else {
            boltFrom(by.position(), BOLT_MIN + random.nextDouble() * (BOLT_MAX - BOLT_MIN));
        }
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    protected SoundEvent getAmbientSound() {
        // it chitters now and then while it lies still; on the move you hear its feet instead
        return gait.still() || state() == EAT ? ERSounds.SKEIN_CHITTER.get() : null;
    }

    @Override public int getAmbientSoundInterval() { return 220; }
    @Override protected float getSoundVolume() { return 0.9F; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.SKEIN_HURT.get(); }
    @Override protected SoundEvent getDeathSound() { return ERSounds.SKEIN_DEATH.get(); }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { }

    /** The dry patter of many feet while it runs. */
    private void patter() {
        stepDist += (float) movedLast;
        if (stepDist > PATTER_EVERY && surface() != null) {
            stepDist = 0;
            playSound(ERSounds.SKEIN_PATTER.get(), 0.6F, 0.9F + random.nextFloat() * 0.3F);
        }
    }

    // ------------------------------------------------------------------------------------------------ life

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        hunger = random.nextInt(MEAL_MAX);
        return super.finalizeSpawn(level, difficulty, reason, data, tag);
    }

    /**
     * Sets it down at {@code pos} on {@code rock} (the side its rock is on), heading along {@code fwd}, its body laid
     * straight behind it, lying still (for the spawner and /remix skein spawn).
     */
    public void placeOn(Vec3 pos, Direction rock, Vec3 fwd) {
        moveTo(pos.x, pos.y, pos.z, getYRot(), 0.0F);
        setDeltaMovement(Vec3.ZERO);
        entityData.set(DATA_SURFACE, (byte) rock.get3DDataValue());
        Vec3 up = normal(rock);
        Vec3 f = fwd.subtract(up.scale(fwd.dot(up)));
        forward = f.lengthSqr() > 1.0E-4 ? f.normalize() : (Math.abs(up.y) > 0.9 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0));
        entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        setNoGravity(rock != Direction.DOWN);
        if (rock == Direction.DOWN) {
            float yaw = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90.0F;
            setYRot(yaw); yBodyRot = yaw; yHeadRot = yaw;
        }
        layTrail(up);
    }

    /** Its body laid straight behind its head along its rock. */
    private void layTrail(Vec3 up) {
        Vec3 f = feet(up);
        Vec3 fwd = forward;
        if (level().isClientSide) {
            // the way the server has it heading (the client's own easing towards it has not begun)
            Vector3f sf = entityData.get(DATA_FORWARD);
            fwd = new Vec3(sf.x, sf.y, sf.z);
            if (fwd.lengthSqr() > 1.0E-4) clientForward = fwd.normalize();
        }
        trail.lay(f.x, f.y, f.z, -fwd.x, -fwd.y, -fwd.z, up.x, up.y, up.z);
        placeSections();
    }

    /** Where a Skein set down on a climbing node sits: its position (its box's bottom middle) and its rock's side. */
    public record Placement(Vec3 pos, Direction rock) {}

    /**
     * Where a Skein set down on the climbing node with low corner {@code n} (a 2 x 1 x 2 cell, as its pathfinder reads
     * them) sits, pressed against its rock: on the floor, on the ceiling, or against the wall beside it. Null when the
     * node is none of these, or its box does not fit there.
     */
    @Nullable
    public static Placement placement(Level level, EntityType<?> type, BlockPos n) {
        byte k = ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2);
        float w = type.getWidth(), h = type.getHeight();
        Vec3 mid = new Vec3(n.getX() + 1.0, n.getY(), n.getZ() + 1.0);
        Placement p = switch (k) {
            case ClimbNodeEvaluator.FLOOR -> new Placement(mid, Direction.DOWN);
            case ClimbNodeEvaluator.CEILING -> new Placement(mid.add(0, 1.0 - h, 0), Direction.UP);
            case ClimbNodeEvaluator.WALL -> wallPlacement(level, mid, w, h);
            default -> null;
        };
        if (p == null || !level.noCollision(type.getDimensions().makeBoundingBox(p.pos()))) return null;
        return p;
    }

    /** Against whichever wall beside the cell its box touches once moved over to it. */
    @Nullable
    private static Placement wallPlacement(Level level, Vec3 mid, float w, float h) {
        double gap = 1.0 - w / 2.0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vec3 at = mid.add(d.getStepX() * gap, 0, d.getStepZ() * gap);
            AABB box = new AABB(at.x - w / 2.0, at.y, at.z - w / 2.0, at.x + w / 2.0, at.y + h, at.z + w / 2.0);
            if (rockBeside(level, null, box, d)) return new Placement(at, d);
        }
        return null;
    }

    /**
     * The heading to set a Skein down with at {@code p}: of eight ways along its rock, the one with the most rock
     * behind it for its body to lie on in the open (a body laid into a wall would stick out of it until it walked),
     * ties broken by {@code r}.
     */
    public static Vec3 openHeading(Level level, EntityType<?> type, Placement p, DoubleSupplier r) {
        Vec3 up = normal(p.rock());
        Vec3 a = Math.abs(up.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 b = up.cross(a);
        // the point on its rock under the middle of its box, as feet() has it
        double w = type.getWidth() / 2.0, h = type.getHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        Vec3 feet = p.pos().add(0, h, 0).subtract(up.scale(ext));
        Vec3 best = a;
        double bestScore = -1;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            Vec3 fwd = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
            double score = openBehind(level, feet, fwd, up) + r.getAsDouble() * 0.5;
            if (score > bestScore) { bestScore = score; best = fwd; }
        }
        return best;
    }

    /** How many half blocks back from {@code feet} (against {@code fwd}) there is open air over rock, up to its length. */
    private static int openBehind(Level level, Vec3 feet, Vec3 fwd, Vec3 up) {
        int open = 0;
        for (double d = 0.5; d <= TRAIL_KEEP; d += 0.5) {
            Vec3 q = feet.subtract(fwd.scale(d));
            BlockPos air = BlockPos.containing(q.add(up.scale(0.3))), rock = BlockPos.containing(q.subtract(up.scale(0.3)));
            if (!level.isLoaded(air) || !level.isLoaded(rock)) break;
            if (!level.getBlockState(air).getCollisionShape(level, air).isEmpty()) break;
            if (level.getBlockState(rock).getCollisionShape(level, rock).isEmpty()) break;
            open++;
        }
        return open;
    }

    @Override public boolean canBeLeashed(Player player) { return false; }
    @Override public int getMaxAirSupply() { return 600; }
    @Nullable @Override public ItemStack getPickResult() { return super.getPickResult(); }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Hunger", hunger);
        tag.putFloat("ForwardX", (float) forward.x);
        tag.putFloat("ForwardY", (float) forward.y);
        tag.putFloat("ForwardZ", (float) forward.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        hunger = tag.getInt("Hunger");
        Vec3 f = new Vec3(tag.getFloat("ForwardX"), tag.getFloat("ForwardY"), tag.getFloat("ForwardZ"));
        if (f.lengthSqr() > 1.0E-4) {
            forward = f.normalize();
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        }
    }

    // ------------------------------------------------------------------------------------------------ dev commands

    /** For /remix skein: what it is doing. */
    public String describe() {
        byte s = state();
        Direction d = surface();
        StringBuilder b = new StringBuilder();
        b.append(s >= 0 && s < STATE_NAMES.length ? STATE_NAMES[s] : "?").append(" (").append(gait.describe()).append(')')
                .append(" on ").append(d == null ? "nothing" : d == Direction.DOWN ? "floor" : d == Direction.UP ? "ceiling" : "wall " + d.getName())
                .append(", health ").append(Math.round(getHealth()))
                .append(", hunger ").append(hunger / 20).append('s');
        if (prey != null) b.append(", after a swarm of ").append(prey.count());
        if (anchor != null) b.append(String.format(Locale.ROOT, ", struck from %.0f %.0f %.0f", anchor.x, anchor.y, anchor.z));
        if (isInWater()) b.append(", in water");
        if (airborne()) b.append(", falling");
        if (getMoveControl() instanceof ClimbMoveControl mc && mc.wedged() > 0) b.append(", wedged ").append(mc.wedged());
        return b.toString();
    }

    /**
     * For /remix skein state: puts it in state {@code s} for about {@code ticks} (STILL, SKITTER and BOLT go by the
     * gait); a strike or a bolt is at or from {@code who}. Returns false when that state cannot be started now.
     */
    public boolean forceState(byte s, int ticks, @Nullable LivingEntity who) {
        switch (s) {
            case STILL -> { gait.rest(ticks); settle(); }
            case SKITTER -> startOuting(SKITTER, SkeinGait.Mood.ROAM, SkeinGait.OUTING_MAX);
            case BOLT -> boltFrom(who != null ? who.position() : position().add(forward), BOLT_MAX);
            case STALK -> {
                FireflySwarm sw = nearestSwarm();
                if (sw == null) return false;
                prey = sw;
                startOuting(STALK, SkeinGait.Mood.STALK, STALK_BUDGET);
            }
            case REAR, LUNGE -> {
                if (who == null || !huntable(who)) return false;
                startStrike(who, REAR_TICKS);
            }
            case EAT -> toState(EAT, Math.max(1, ticks));
            default -> { return false; }
        }
        return true;
    }

    /** For /remix skein state: makes it hungry now (so it goes for the nearest landed swarm). */
    public void starve() { hunger = 0; }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isAlive()) {
            movedLast = Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
            if (airTicks > 0) airDist += movedLast;
            updateForward();
            patter();
        }
        if (level().isClientSide) updateClientRock();
        followHead();
        if (level().isClientSide) clientTick();
    }

    /** The head's new place goes on the trail, and the body's sections move along it. */
    private void followHead() {
        Vec3 up = level().isClientSide ? normal(clientRock) : up();
        if (!trail.laid()) { layTrail(up); return; }
        Vec3 f = feet(up);
        trail.record(f.x, f.y, f.z, up.x, up.y, up.z);
        placeSections();
    }

    // ------------------------------------------------------------------------------------------------ client

    /**
     * The rock it clings to as the client sees it: the synced side while its box (where the client has it) touches that
     * rock, else the rock it was on while it still touches it, else whatever it touches. The client's box lags the
     * server's a little, so the plain synced side would now and then put the trail a hair off the rock.
     */
    private void updateClientRock() {
        AABB box = getBoundingBox();
        Direction synced = surface();
        if (synced == null) { clientRock = isInWater() ? null : clientRock; return; }
        if (touching(box, synced)) { clientRock = synced; return; }
        if (clientRock != null && touching(box, clientRock)) return;
        if (touching(box, Direction.DOWN)) { clientRock = Direction.DOWN; return; }
        Direction any = anyRock(box, getDeltaMovement());
        clientRock = any != null ? any : synced;
    }

    private void clientTick() {
        runO = run; legPhaseO = legPhase; swayO = sway; shudderO = shudder; rearO = rear; jawO = jaw; glowO = glow; rippleO = ripple;
        System.arraycopy(feelerYaw, 0, feelerYawO, 0, 2);
        System.arraycopy(feelerPitch, 0, feelerPitchO, 0, 2);
        headOrientO.set(headOrient);
        double moved = trail.lastStep();
        boolean running = moved > 0.04;
        // the legs start and stop at once
        run += ((running ? 1.0F : 0.0F) - run) * (running ? 0.7F : 0.5F);
        legPhase += (float) (moved * 7.5);
        sway += (float) (moved * 2.6);
        // stopped dead: a shudder runs through it
        if (movedO > 0.07 && moved < 0.02) shudder = 1.0F;
        else shudder *= 0.72F;
        movedO = moved;
        byte s = state();
        float rearT = s == REAR ? 1.0F : s == LUNGE ? 0.55F : 0.0F;
        rear += (rearT - rear) * (rearT > rear ? 0.6F : 0.25F);
        float jawT = switch (s) {
            case REAR -> 1.0F;
            case LUNGE -> 0.8F;
            case EAT -> 0.35F + 0.35F * Mth.sin(tickCount * 1.4F);
            default -> 0.05F;
        };
        jaw += (jawT - jaw) * 0.5F;
        float glowT = isDeadOrDying() ? 0.0F : running || s == REAR || s == LUNGE || s == BOLT ? 1.0F : 0.35F;
        glow += (glowT - glow) * (glowT > glow ? 0.3F : 0.05F);
        feelers(running);
        ripples(running);
        orientHead();
    }

    /** The feelers twitch in small fast jerks: a new aim every few ticks, reached almost at once. */
    private void feelers(boolean running) {
        for (int i = 0; i < 2; i++) {
            if (--feelerTimer[i] <= 0) {
                feelerTimer[i] = 2 + random.nextInt(running ? 4 : 10);
                // swept back while it runs; still, they sweep about and flick
                feelerYawT[i] = running ? 0.45F + (random.nextFloat() - 0.5F) * 0.2F : (random.nextFloat() - 0.5F) * 0.9F;
                feelerPitchT[i] = running ? 0.15F : (random.nextFloat() - 0.5F) * 0.7F;
            }
            feelerYaw[i] += (feelerYawT[i] - feelerYaw[i]) * 0.65F;
            feelerPitch[i] += (feelerPitchT[i] - feelerPitch[i]) * 0.65F;
        }
    }

    /** Now and then, lying still, a twitch runs down a few of its legs. */
    private void ripples(boolean running) {
        if (ripple >= 0) {
            ripple += 1.0F;
            if (ripple > PLATES + 1 || running) ripple = rippleO = -1;
        } else if (!running && run < 0.1F && !isDeadOrDying() && random.nextInt(80) == 0) {
            ripple = rippleO = random.nextInt(PLATES / 2);
        }
    }

    /** Its head's turn: belly to its rock, pointing the way the server says, easing quickly from one to the next. */
    private void orientHead() {
        Vec3 upT = normal(clientRock);
        if (clientUp.dot(upT) < -0.99) clientUp = clientUp.add(clientForward.cross(clientUp).scale(0.3));
        clientUp = clientUp.lerp(upT, 0.4);
        if (clientUp.lengthSqr() < 1.0E-4) clientUp = upT;
        clientUp = clientUp.normalize();
        Vector3f sf = entityData.get(DATA_FORWARD);
        Vec3 want = new Vec3(sf.x, sf.y, sf.z);
        if (clientForward.dot(want) < -0.99) clientForward = clientForward.add(clientUp.cross(clientForward).scale(0.3));
        // its head snaps round in a tick or two, not a smooth turn
        clientForward = clientForward.lerp(want, HEAD_SNAP);
        Vec3 f = clientForward.subtract(clientUp.scale(clientForward.dot(clientUp)));
        if (f.lengthSqr() < 1.0E-4) f = Math.abs(clientUp.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        clientForward = f.normalize();
        headOrient.set(basis(clientForward, clientUp));
        if (tickCount < 2) headOrientO.set(headOrient);
    }

    /** The rotation that turns a model's back up along {@code up} and its head along {@code fwd}. */
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

    public float run(float pt) { return Mth.lerp(pt, runO, run); }
    public float legPhase(float pt) { return Mth.lerp(pt, legPhaseO, legPhase); }
    public float sway(float pt) { return Mth.lerp(pt, swayO, sway); }
    public float shudder(float pt) { return Mth.lerp(pt, shudderO, shudder); }
    public float rear(float pt) { return Mth.lerp(pt, rearO, rear); }
    public float jaw(float pt) { return Mth.lerp(pt, jawO, jaw); }
    public float glow(float pt) { return Mth.lerp(pt, glowO, glow); }
    public float ripple(float pt) { return ripple < 0 ? -1 : rippleO < 0 ? ripple : Mth.lerp(pt, rippleO, ripple); }
    public float feelerYaw(int i, float pt) { return Mth.lerp(pt, feelerYawO[i], feelerYaw[i]); }
    public float feelerPitch(int i, float pt) { return Mth.lerp(pt, feelerPitchO[i], feelerPitch[i]); }

    // ------------------------------------------------------------------------------------------------ the sections

    /**
     * One section of the Skein's long body: a box on its trail that can be hit (and is hit by arrows and blades like
     * any entity), handing the damage to the Skein. Never saved, never sent: the Skein makes and places its own on
     * each side.
     */
    public static final class Section extends PartEntity<SkeinEntity> {
        public final int index;
        private final EntityDimensions size;

        Section(SkeinEntity parent, int index, float width, float height) {
            super(parent);
            this.index = index;
            this.size = EntityDimensions.scalable(width, height);
            refreshDimensions();
        }

        @Override protected void defineSynchedData() { }
        @Override protected void readAdditionalSaveData(CompoundTag tag) { }
        @Override protected void addAdditionalSaveData(CompoundTag tag) { }
        /** Hit like any entity while the Skein lives (not once it lies dying). */
        @Override public boolean isPickable() { return getParent().isAlive(); }
        @Override public boolean shouldBeSaved() { return false; }
        @Override public EntityDimensions getDimensions(Pose pose) { return size; }
        @Override public boolean is(Entity e) { return this == e || getParent() == e; }
        @Nullable @Override public ItemStack getPickResult() { return getParent().getPickResult(); }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return !isInvulnerableTo(source) && getParent().hurtFromSection(this, source, amount);
        }
    }
}
