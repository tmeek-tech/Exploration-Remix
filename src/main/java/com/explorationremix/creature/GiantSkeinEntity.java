package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.Stagger;
import com.explorationremix.combat.WindUp;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNavigation;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
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
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.NetworkHooks;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The giant Skein of the Deep Blue Rift (0.18.8, Taran's 2026-11-18 ask, from their nine card answers): a Skein four
 * times as long, about thirty blocks from its horned head to the fan of spikes at its tail, its shell a pale ice blue,
 * spiky all over, the tips of its spikes and its eyes glowing a soft light blue. One lives in each rift ({@link
 * GiantSkeins}), on its walls. It never leaves its rift, never goes into the Asurine Geodes, and once killed it is gone
 * for good: nothing moves in after it.
 *
 * <p>Most of the time it lies still along a wall, its feelers moving, a slow ripple now and then down its legs; then it
 * crawls a little way, slowly and steadily, its legs rippling in waves down its length, mostly over the walls and now
 * and then down across the ledge path or over a bridge ({@link #roamGoal}). It hunts players in its rift: one it can
 * see within {@link #SIGHT} blocks it turns to, then crawls after (still slow, a walking player outpaces it, but it does
 * not stop), and from about {@link #STRIKE_REACH} blocks it rears its front up off the rock with a deep rattle, coils,
 * and strikes ({@link GiantStrike}); a strong attack landing in the rear spoils the strike (WindUp). It gives up a
 * hunt once it has lost sight of its quarry for a while, after a look where it saw them last. Badly hurt, it draws off
 * up the wall. Small Skeins freeze, then scatter when it comes near (SkeinEntity).
 *
 * <p>Its body is the Skein's (the trail its head lays and the smooth chain its body lies in), worked at four times the
 * size ({@link GiantBody}); its sections are boxes along it that can be hit, as the Skein's are. It is drawn by
 * client.render.GiantSkeinRenderer.
 */
public class GiantSkeinEntity extends Monster implements ClimbMoveControl.Climber, IEntityAdditionalSpawnData, WindUp {
    // ------------------------------------------------------------------------------------------------ tuning
    /** How many times the Skein it is. */
    public static final double SCALE = 4.0;
    /**
     * Tough (Taran's pick): it takes a long fight. Movement speed is blocks a tick at a speed of 1 (ClimbMoveControl):
     * it crawls about its rift at {@link #ROAM_SPEED} of that and hunts at {@link #HUNT_SPEED}, a steady crawl a walking
     * player (0.22 a tick) outpaces; on walls ClimbMoveControl takes it a little slower still.
     */
    public static final double HEALTH = 240.0, ARMOR = 10.0, SPEED = 0.13, KNOCKBACK_RESISTANCE = 1.0;
    static final double ROAM_SPEED = 0.6, HUNT_SPEED = 1.0, SEARCH_SPEED = 0.8, RETREAT_SPEED = 0.9;
    static final float BITE = 12.0F;
    /** It notices a player it can see this near (from its eyes), and strikes once its jaws are this near them. */
    public static final double SIGHT = 48.0, STRIKE_REACH = 8.0;
    /** A player hunted further off than this (or out of its rift) is given up on. */
    static final double LEASH = 72.0;
    /**
     * A strike: a moment turned to its quarry as it notices it, then a second rearing (its rattle, its eyes and spike
     * tips flaring: the strike can be timed, and a strong attack then spoils it), the strike out and back, and a pause
     * before the next.
     */
    static final int ALERT_TICKS = 30, REAR_TICKS = 20, LUNGE_TICKS = 10, RECOVER_TICKS = 26, STRIKE_COOLDOWN = 40;
    /** Hunting, it asks the way again this often; it gives up after this long without sight of its quarry, after a look. */
    static final int REPATH = 10, LOSE_TICKS = 200, SEARCH_TICKS = 400;
    /** It rests this long between crawls (ticks), and goes this far in a crawl's leg, one to three legs a crawl. */
    static final int REST_MIN = 600, REST_MAX = 2400, SHORT_REST = 200;
    static final double LEG_MIN = 10.0, LEG_MAX = 22.0;
    /** Where no leg along its rift has a way, it crawls this far over the rock round it instead. */
    static final double NEAR_MIN = 6.0, NEAR_MAX = 14.0;
    /** Now and then a crawl takes it across the ledge path's height on its wall, or over a bridge to the far wall. */
    static final double CROSS_PATH = 0.3, CROSS_RIFT = 0.12;
    /** Below this share of its health it draws off, for this long. */
    static final double RETREAT_AT = 0.3;
    static final int RETREAT_TICKS = 600;
    /** Cornered while drawing off, it still strikes at one this near its jaws. */
    static final double CORNERED = 4.5;
    /** Small Skeins this near its body get out of its way. */
    public static final double SCATTERS = 14.0;
    /**
     * Lying still with no player within this, it lies dormant (as the Skein does): its ticks are skipped until one
     * comes this near, or it is hurt. Players are looked for once a second.
     */
    public static final double WAKE_RANGE = 128.0;
    static final int WAKE_LOOK = 20;
    /** How far and how long it may have no rock in reach (rounding an edge) before it falls. */
    static final double AIR_REACH = 2.5;
    static final int AIR_GRACE = 40;
    /**
     * Rock this near a side of its box (blocks) still holds it on a wall or ceiling (0.19.3): on rough rock its box often
     * clears the rock it climbs by a few tenths, and it let go and fell. The point under its head is set on the rock
     * itself, and lying still it is drawn back against the rock, at most {@link #CLING_PULL} a tick.
     */
    static final double CLING_REACH = 1.2, CLING_PULL = 0.12;
    /** Its pose turns to a new rock once it has gripped that for this many ticks running (see DATA_POSE). */
    static final int POSE_HOLD = 10;
    /**
     * How far below its box it feels for the floor under its head, and the easing of the point under its head: a jump
     * in it is eased by FOOT_EASE a tick (one over FOOT_JUMP is not: its body is laid afresh). The way its back faces
     * turns FOOT_TURN of the way to a new rock's a tick, and the point under its head goes round its box's edge with it
     * (0.19.3: it jumped from the middle of one side of its box to the next, a block and a half, as it turned from one
     * wall to the next).
     */
    static final double FLOOR_REACH = 1.6, FOOT_EASE = 0.4, FOOT_JUMP = 2.6, FOOT_TURN = 0.2;
    /** A heavy scrape of feet every this many blocks it crawls. */
    static final double STEP_EVERY = 3.0;
    /**
     * Its moves are eased (0.19.3, it jerked from one step to the next): each tick's step goes this share of the way
     * from how it moved last tick to the step it wants (ClimbMoveControl). Crawling about, it slows over the last
     * {@link #SLOW_FROM} blocks of a leg, down to {@link #SLOWEST} of its speed, rather than stopping dead.
     */
    static final double MOVE_EASE = 0.25, SLOW_FROM = 3.0, SLOWEST = 0.35;
    /** The next node of its way this far off (it slid or was carried off its way): it asks the way afresh. */
    static final double STRAYED = 6.0;
    /**
     * How much of the way to its new heading its head turns each tick (it turns slowly, as a big thing does): the
     * client's head to the server's heading, and the server's heading to its quarry. Crawling, its heading comes round
     * to the way it goes over about {@link #TURN_BLOCKS} of crawl (0.19.3: turned by the tick, it swung from side to
     * side as its way stepped from block to block).
     */
    static final double HEAD_TURN = 0.3, TURN_RATE = 0.25, TURN_BLOCKS = 0.8;

    // ------------------------------------------------------------------------------------------------ the body
    /** Its plates (the last one the tail's), each this far back along its body from the point under its head. */
    public static final int PLATES = 23;
    public static final double PLATE_START = 0.6, PLATE_STEP = 1.2;
    /** Its jaws are this far ahead of the point under its head. */
    public static final double HEAD_REACH = 1.6;
    /** Its length, head to the tips of its tail fan (blocks). */
    public static final double BODY_LENGTH = PLATE_START + PLATE_STEP * (PLATES - 1) + 2.4;
    /** The trail it keeps: the whole body and the stretch behind it that the curve its body lies along looks to. */
    public static final double TRAIL_KEEP = BODY_LENGTH + 9.6;
    /** The hit sections along the body: how far back along it each lies, and its box (the head has the main box). */
    static final double[] SECTION_AT = {1.8, 4.2, 6.6, 9.0, 11.4, 13.8, 16.2, 18.6, 21.0, 23.4, 25.8, 28.0};
    static final float[] SECTION_W = {2.3F, 2.25F, 2.15F, 2.05F, 1.95F, 1.85F, 1.75F, 1.65F, 1.55F, 1.45F, 1.35F, 1.2F},
            SECTION_H = {1.7F, 1.65F, 1.6F, 1.55F, 1.5F, 1.45F, 1.4F, 1.35F, 1.3F, 1.25F, 1.2F, 1.1F};
    /** The stretch the sections' boxes cover between them: from inside the head's box to the tips of its tail fan. */
    static final double SECTIONS_FROM = 1.0, SECTIONS_TO = 29.2;
    private static final double[] SECTION_ENDS = sectionEnds();
    /** The jaws' part: ahead of the point under its head, from this far to this far, and its box. The last part. */
    static final double JAWS_TIP = 2.6, JAWS_BACK = 0.8;
    static final float JAWS_W = 1.6F, JAWS_H = 1.3F;
    static final int JAWS = SECTION_AT.length;
    /** Where along its body it is saved, and sent to a client that starts tracking it: head, plates, end of trail. */
    static final double[] KEPT_AT = keptAt();

    public static double plateAt(int k) { return PLATE_START + PLATE_STEP * k; }

    private static double[] sectionEnds() {
        double[] e = new double[SECTION_AT.length + 2];
        e[1] = SECTIONS_FROM;
        for (int i = 1; i < SECTION_AT.length; i++) e[i + 1] = (SECTION_AT[i - 1] + SECTION_AT[i]) / 2.0;
        e[SECTION_AT.length + 1] = SECTIONS_TO;
        return e;
    }

    private static double[] keptAt() {
        double[] a = new double[PLATES + 2];
        for (int k = 0; k < PLATES; k++) a[k + 1] = plateAt(k);
        a[PLATES + 1] = TRAIL_KEEP;
        return a;
    }

    /** The game's entity id counter (Entity.ENTITY_COUNTER), so the sections' ids follow its own (see SkeinEntity). */
    @Nullable private static final AtomicInteger IDS = idCounter();

    // ------------------------------------------------------------------------------------------------ state
    public static final byte REST = 0, CRAWL = 1, ALERT = 2, HUNT = 3, REAR = 4, LUNGE = 5, RECOVER = 6, SEARCH = 7, RETREAT = 8;
    /** The state names, by id, for /remix giant. */
    public static final String[] STATE_NAMES = {"rest", "crawl", "alert", "hunt", "rear", "lunge", "recover", "search", "retreat"};
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it falls or swims. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.BYTE);
    /**
     * The rock its pose is set by (0.19.3): the rock it grips, once it has gripped that for {@link #POSE_HOLD} ticks
     * running (at once after a fall, or when the rock it showed is out of reach); -1 while it falls or swims. On rough
     * rock its grip goes from face to face every block or two, and its head and body turned with it, jerkily.
     */
    private static final EntityDataAccessor<Byte> DATA_POSE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.BYTE);
    /** The way its head points along its rock. */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.VECTOR3);
    /** What its strike is at (where it means its jaws to close). */
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.VECTOR3);
    /** For /remix giant hold (pictures of its strike): how far through its phase the strike is held, or -1. */
    private static final EntityDataAccessor<Float> DATA_HELD = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.FLOAT);

    private final Section[] sections;
    private final GiantBody body = new GiantBody(SCALE, TRAIL_KEEP, SkeinEntity.SPINE_JOINTS);
    @Nullable private Vec3 foot;
    private Vec3 footUp = new Vec3(0, 1, 0), footLag = Vec3.ZERO;
    private double airRun, airDist, movedLast;
    private double clingGap;                 // server: how far its box is off the wall or ceiling it clings to
    @Nullable private Direction grip = Direction.DOWN;      // server: the rock it grips (see grip())
    @Nullable private Direction poseNext = Direction.DOWN;  // server: the grip its pose may turn to, and for how long it has held
    private int poseWait;
    private int airTicks;
    private Vec3 forward = new Vec3(0, 0, 1);
    private int stateTicks, stateLength;
    private int legsLeft;                    // CRAWL: legs left in this crawl
    private final Deque<Vec3> waypoints = new ArrayDeque<>();   // CRAWL: the rest of a way over a bridge
    @Nullable private Vec3 lastSeenAt;       // HUNT and SEARCH: where its quarry was last seen
    private long lastSeen;
    private int strikeCooldown;
    private boolean bit;                     // LUNGE: it has bitten in this strike
    @Nullable private double[] jawsWere;     // LUNGE: its jaws a tick ago (a fast strike is checked along its sweep)
    private long retreatUntil;
    private long askedAt = -1000L;           // when it last asked its way afresh (askAgain)
    private String noWay = "";               // why goTo last found no way (for /remix giant roam)
    private int bites;                       // for /remix giant: how many times it has bitten since it was loaded
    private float stepDist;
    private boolean dormant;
    private int trace;
    final GiantRoamTest roamTest = new GiantRoamTest();
    // the rift it lives in (its cell in the Blue Reaches' plan), and that rift once looked up
    private boolean hasRift;
    private int riftX, riftZ;
    @Nullable private Route rift;
    private boolean riftLooked;

    // client-side animation state (see GiantSkeinRenderer)
    public float run, runO, legPhase, legPhaseO, glow, glowO, lift, liftO, jaw, jawO, shudder, shudderO;
    public float ripple = -1, rippleO = -1;
    public final float[] feelerYaw = new float[2], feelerYawO = new float[2], feelerPitch = new float[2], feelerPitchO = new float[2];
    private final float[] feelerYawT = new float[2], feelerPitchT = new float[2];
    private final int[] feelerTimer = new int[2];
    public final Quaternionf headOrient = new Quaternionf(), headOrientO = new Quaternionf();
    private Vec3 clientForward = new Vec3(0, 0, 1), clientUp = new Vec3(0, 1, 0);
    @Nullable private Direction clientRock = Direction.DOWN;
    private int clientStateTicks;

    public GiantSkeinEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.sections = new Section[SECTION_AT.length + 1];
        for (int i = 0; i < SECTION_AT.length; i++) sections[i] = new Section(this, i, SECTION_W[i], SECTION_H[i]);
        sections[JAWS] = new Section(this, JAWS, JAWS_W, JAWS_H);
        if (IDS != null) setId(IDS.getAndAdd(sections.length + 1) + 1);
        this.moveControl = new ClimbMoveControl(this, MOVE_EASE, true);
        this.xpReward = 120;
        setMaxUpStep(1.0F);
        stateLength = 200 + random.nextInt(600);
    }

    @Nullable
    private static AtomicInteger idCounter() {
        try {
            return (AtomicInteger) ObfuscationReflectionHelper.findField(Entity.class, "f_19843_").get(null);
        } catch (RuntimeException | IllegalAccessException e) {
            ExplorationRemix.LOGGER.warn("Giant Skein: no entity id counter, its body sections take ids of their own", e);
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
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STATE, REST);
        entityData.define(DATA_SURFACE, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_POSE, (byte) Direction.DOWN.get3DDataValue());
        entityData.define(DATA_FORWARD, new Vector3f(0, 0, 1));
        entityData.define(DATA_AIM, new Vector3f());
        entityData.define(DATA_HELD, -1.0F);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_STATE.equals(key) && level().isClientSide) clientStateTicks = 0;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ClimbNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        // its mind is its own state machine (customServerAiStep), not goals
    }

    public byte state() { return entityData.get(DATA_STATE); }
    public boolean dormant() { return dormant; }
    @Nullable public Direction surface() {
        byte b = entityData.get(DATA_SURFACE);
        return b < 0 ? null : Direction.from3DDataValue(b);
    }
    /** The rock its pose is set by: the rock it has gripped for a moment now; null while it falls or swims. */
    @Nullable public Direction poseRock() {
        byte b = entityData.get(DATA_POSE);
        return b < 0 ? null : Direction.from3DDataValue(b);
    }
    /** Server: the rock it grips (its surface, or the one it had while it rounds an edge); null while it falls or swims. */
    @Nullable Direction grip() { return grip; }
    public GiantBody body() { return body; }
    public Section[] sections() { return sections; }

    @Override public boolean onFloor() { return surface() == Direction.DOWN; }
    @Override public boolean canSteer() { return true; }
    @Override public boolean airborne() { return airDist > AIR_REACH || airTicks > AIR_GRACE; }
    @Override public boolean holdStill() { return gripsOffFloor(); }
    @Override public boolean onClimbable() { return false; }
    @Override public boolean isPushable() { return false; }
    /** Its body trails thirty blocks behind its box: keep drawing it while any of it is on screen. */
    @Override public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(TRAIL_KEEP); }
    /** Seen from across the rift: drawn as far off as the client tracks it. */
    @Override public boolean shouldRenderAtSqrDistance(double d) { return d < 256.0 * 256.0; }

    // ------------------------------------------------------------------------------------------------ the body's parts

    @Override public boolean isMultipartEntity() { return true; }
    @Override public PartEntity<?>[] getParts() { return sections; }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (sections != null) for (int i = 0; i < sections.length; i++) sections[i].setId(id + i + 1);
    }

    boolean hurtFromSection(Section s, DamageSource source, float amount) {
        return hurt(source, amount);
    }

    /**
     * Sets each section's box over its stretch of the body, where the body is drawn (its reared front too, while it
     * strikes), and the jaws' box ahead of its head (where its jaws are, in a strike).
     */
    private void placeSections() {
        double[] at = new double[6 * SECTION_ENDS.length], box = new double[6];
        if (!body.onTrail()) body.layBody();
        bodyAt(SECTION_ENDS, 1.0F, at);
        for (int i = 0; i < SECTION_AT.length; i++) {
            GiantBody.stretchBox(at, 6 * (i + 1), 6 * (i + 2), sections[i].getBbWidth(), sections[i].getBbHeight(), box);
            fit(sections[i], box);
        }
        double[] jaws = new double[12];
        double[] head = new double[6];
        if (strikeHead(1.0F, head)) {
            double[] j = new double[3];
            GiantStrike.jaws(head, j);
            jaws = new double[]{j[0], j[1], j[2], at[3], at[4], at[5], head[0], head[1], head[2], at[3], at[4], at[5]};
        } else {
            Vector3f sf = entityData.get(DATA_FORWARD);
            double nx = at[3], ny = at[4], nz = at[5], along = sf.x * nx + sf.y * ny + sf.z * nz;
            double fx = sf.x - nx * along, fy = sf.y - ny * along, fz = sf.z - nz * along;
            if (fx * fx + fy * fy + fz * fz < 1.0E-4) { fx = at[0] - at[6]; fy = at[1] - at[7]; fz = at[2] - at[8]; }
            double fl = Math.max(1.0E-6, Math.sqrt(fx * fx + fy * fy + fz * fz));
            fx /= fl; fy /= fl; fz /= fl;
            jaws = new double[]{at[0] + fx * JAWS_TIP, at[1] + fy * JAWS_TIP, at[2] + fz * JAWS_TIP, nx, ny, nz,
                    at[0] + fx * JAWS_BACK, at[1] + fy * JAWS_BACK, at[2] + fz * JAWS_BACK, nx, ny, nz};
        }
        GiantBody.stretchBox(jaws, 0, 6, JAWS_W, JAWS_H, box);
        fit(sections[JAWS], box);
    }

    private static void fit(Section s, double[] b) {
        s.xo = s.getX(); s.yo = s.getY(); s.zo = s.getZ();
        s.xOld = s.xo; s.yOld = s.yo; s.zOld = s.zo;
        s.setPos((b[0] + b[3]) / 2.0, b[1], (b[2] + b[5]) / 2.0);
        s.setBoundingBox(new AABB(b[0], b[1], b[2], b[3], b[4], b[5]));
    }

    // ------------------------------------------------------------------------------------------------ the strike's shape

    /** The strike's phase now (GiantStrike's), from its state. */
    public int strikePhase() {
        return switch (state()) {
            case REAR -> GiantStrike.COIL;
            case LUNGE -> GiantStrike.OUT;
            case RECOVER -> GiantStrike.BACK;
            default -> GiantStrike.NONE;
        };
    }

    /** How far through its phase the strike is (0 to 1), {@code pt} of a tick on (each side counts its own ticks). */
    public double strikeProgress(float pt) {
        float held = entityData.get(DATA_HELD);
        if (held >= 0.0F) return Math.min(1.0F, held);
        int len = switch (state()) {
            case REAR -> REAR_TICKS;
            case LUNGE -> LUNGE_TICKS;
            case RECOVER -> RECOVER_TICKS;
            default -> 1;
        };
        double ticks = level().isClientSide ? clientStateTicks + pt : stateTicks;
        return Mth.clamp(ticks / len, 0.0, 1.0);
    }

    /**
     * Where its head is in the strike, {@code pt} of a tick on: its point at out[0..2] and the way it points at
     * out[3..5] (see GiantStrike#head). False when it is not striking.
     */
    public boolean strikeHead(float pt, double[] out) {
        int phase = strikePhase();
        if (phase == GiantStrike.NONE || !body.laid()) return false;
        double[] s = new double[18];
        body.sample(GiantStrike.NECK, pt, s, 0);
        body.sample(GiantStrike.NECK - 0.6, pt, s, 6);
        body.sample(0.0, pt, s, 12);
        double[] a = {s[0], s[1], s[2]}, n = {s[3], s[4], s[5]};
        double[] d = GiantStrike.unit(new double[]{s[6] - s[0], s[7] - s[1], s[8] - s[2]}, new double[]{forward.x, forward.y, forward.z});
        double[] h0 = {s[12], s[13], s[14]};
        Vector3f t = entityData.get(DATA_AIM);
        GiantStrike.head(a, n, d, h0, new double[]{t.x, t.y, t.z}, phase, strikeProgress(pt), out);
        return true;
    }

    /**
     * Its body at {@code arcs} along it (blocks back from the head), {@code pt} of a tick on: each point's position and
     * the way its back faces, six numbers each in out, as the body lies, except that while it strikes its front (up to
     * GiantStrike.NECK back) is drawn up off the rock along the strike's curve.
     */
    public void bodyAt(double[] arcs, float pt, double[] out) {
        body.sampleAll(arcs, pt, out);
        int phase = strikePhase();
        if (phase == GiantStrike.NONE) return;
        double k = strikeProgress(pt), w = GiantStrike.weight(phase, k);
        if (w <= 1.0E-4) return;
        double[] s = new double[12];
        body.sample(GiantStrike.NECK, pt, s, 0);
        body.sample(GiantStrike.NECK - 0.6, pt, s, 6);
        double[] a = {s[0], s[1], s[2]}, n = {s[3], s[4], s[5]};
        double[] d = GiantStrike.unit(new double[]{s[6] - s[0], s[7] - s[1], s[8] - s[2]}, new double[]{forward.x, forward.y, forward.z});
        double[] head = new double[6];
        if (!strikeHead(pt, head)) return;
        double[] c = new double[6];
        for (int i = 0; i < arcs.length; i++) {
            if (arcs[i] >= GiantStrike.NECK) continue;
            int o = 6 * i;
            GiantStrike.neck(a, n, d, head, Math.max(0.0, arcs[i]) / GiantStrike.NECK, c, 0);
            // its back on the curve: the rock's normal at the anchor, made square to the way the curve runs
            double along = n[0] * c[3] + n[1] * c[4] + n[2] * c[5];
            double[] up = GiantStrike.unit(new double[]{n[0] - c[3] * along, n[1] - c[4] * along, n[2] - c[5] * along},
                    new double[]{out[o + 3], out[o + 4], out[o + 5]});
            for (int q = 0; q < 3; q++) out[o + q] += (c[q] - out[o + q]) * w;
            double ux = out[o + 3] + (up[0] - out[o + 3]) * w, uy = out[o + 4] + (up[1] - out[o + 4]) * w, uz = out[o + 5] + (up[2] - out[o + 5]) * w;
            double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (ul > 1.0E-6) { out[o + 3] = ux / ul; out[o + 4] = uy / ul; out[o + 5] = uz / ul; }
        }
    }

    // ------------------------------------------------------------------------------------------------ geometry

    public Vec3 up() { return normal(poseRock()); }

    static Vec3 normal(@Nullable Direction rock) {
        return rock == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(rock.getOpposite().getNormal());
    }

    /** The point on its rock under the middle of its head's box (the side of its box against a wall). */
    public Vec3 feet(Vec3 up) {
        double w = getBbWidth() / 2.0, h = getBbHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        return position().add(0, h, 0).subtract(up.scale(ext));
    }

    /**
     * The point on its rock under its head, its back facing {@code up}: the middle of the side of its box against
     * {@code rock} (eased round the box's edge while {@code up} turns from one rock to the next), set down on the rock
     * itself when its box is held off it by a little (a few tenths over rough rock, or over the floor under it).
     */
    private Vec3 footOn(@Nullable Direction rock, Vec3 up) {
        AABB b = getBoundingBox();
        double w = getBbWidth() / 2.0, h = getBbHeight() / 2.0;
        Vec3 c = b.getCenter(), f = c.subtract(up.x * w, up.y * h, up.z * w);
        if (rock == null) return f;
        double gap;
        if (rock == Direction.DOWN) {
            double y = floorUnder(level(), c.x, c.y, b.minY - FLOOR_REACH, c.z);
            gap = Double.isNaN(y) ? 0.0 : Math.max(0.0, b.minY - y);
        } else gap = touching(b, rock) ? 0.0 : gapOr0(rockGap(level(), this, b, rock, CLING_REACH));
        Vec3 n = normal(rock);
        double along = Math.max(0.0, up.dot(n));
        return f.subtract(n.scale(gap * along * along));
    }

    /**
     * The point under its head this tick (as the Skein's: a jump in it is eased over a few ticks), and the way its back
     * faces there, turning to a new rock over a few ticks.
     */
    private Vec3 foot(@Nullable Direction rock) {
        Vec3 n = normal(rock);
        if (foot == null) footUp = n;
        else {
            if (footUp.dot(n) < -0.99) footUp = footUp.add(forward.cross(footUp).scale(0.3));
            Vec3 u = footUp.lerp(n, FOOT_TURN);
            footUp = u.lengthSqr() < 1.0E-4 ? n : u.normalize();
        }
        Vec3 want = footOn(rock, footUp);
        if (foot == null) {
            footLag = Vec3.ZERO;
        } else {
            Vec3 moved = position().subtract(xo, yo, zo);
            if (rock == Direction.DOWN) moved = new Vec3(moved.x, 0.0, moved.z);
            footLag = foot.add(moved).subtract(want).scale(1.0 - FOOT_EASE);
            if (footLag.lengthSqr() < 1.0E-8 || footLag.lengthSqr() > FOOT_JUMP * FOOT_JUMP) footLag = Vec3.ZERO;
        }
        foot = outOfRock(level(), want.add(footLag), footUp);
        return foot;
    }

    /** {@code p} pushed out along {@code up} to the face of the rock it lies in, if it lies in rock. */
    static Vec3 outOfRock(LevelReader level, Vec3 p, Vec3 up) {
        for (int i = 0; i <= 30; i++) {
            Vec3 q = p.add(up.scale(0.03 * i));
            if (!inRock(level, q.add(up.scale(0.02)))) return q;
        }
        return p;
    }

    /** Whether {@code p} lies inside the rock of the block it is in (off the edge of what is loaded, not). */
    static boolean inRock(LevelReader level, Vec3 p) {
        BlockPos at = BlockPos.containing(p);
        if (!loaded(level, at)) return false;
        VoxelShape shape = level.getBlockState(at).getCollisionShape(level, at);
        if (shape.isEmpty()) return false;
        double x = p.x - at.getX(), y = p.y - at.getY(), z = p.z - at.getZ();
        for (AABB b : shape.toAabbs()) if (b.contains(x, y, z)) return true;
        return false;
    }

    private static double floorUnder(Level level, double x, double from, double to, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        double lx = x - bx, lz = z - bz;
        for (int by = Mth.floor(from); by >= Mth.floor(to); by--) {
            p.set(bx, by, bz);
            if (!level.isLoaded(p)) return Double.NaN;
            VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
            if (shape.isEmpty()) continue;
            double top = Double.NaN;
            for (AABB b : shape.toAabbs()) {
                if (lx < b.minX || lx > b.maxX || lz < b.minZ || lz > b.maxZ || by + b.minY > from) continue;
                if (Double.isNaN(top) || by + b.maxY > top) top = by + b.maxY;
            }
            if (!Double.isNaN(top)) return top >= to ? top : Double.NaN;
        }
        return Double.NaN;
    }

    public Vec3 lookDir() { return forward; }
    public Vec3 bodyCentre() { return feet(up()).add(up().scale(0.6)); }
    public Vec3 mouth() { return feet(up()).add(forward.scale(HEAD_REACH)).add(up().scale(0.5)); }
    public Vec3 eyes() { return feet(up()).add(forward.scale(1.0)).add(up().scale(1.1)); }

    /** Whether it grips a wall or ceiling (rounding an edge of one too). */
    private boolean gripsOffFloor() {
        return grip != null && grip != Direction.DOWN;
    }

    // ------------------------------------------------------------------------------------------------ surface

    private boolean touching(AABB box, Direction d) {
        return rockBeside(level(), this, box, d);
    }

    static boolean rockBeside(CollisionGetter level, @Nullable Entity who, AABB box, Direction d) {
        return rockBeside(level, who, box, d, 0.08);
    }

    /** How far off side {@code d} of the box the nearest rock is (to a tenth), or NaN when none is within {@code max}. */
    static double rockGap(CollisionGetter level, @Nullable Entity who, AABB box, Direction d, double max) {
        for (double t = 0.08; t <= max + 1.0E-9; t += 0.1) if (rockBeside(level, who, box, d, t)) return t;
        return Double.NaN;
    }

    /** Rock within {@code e} of side {@code d} of the box (its edges trimmed a little, so a corner alone does not count). */
    static boolean rockBeside(CollisionGetter level, @Nullable Entity who, AABB box, Direction d, double e) {
        double m = 0.06;
        AABB slab = switch (d) {
            case DOWN -> new AABB(box.minX + m, box.minY - e, box.minZ + m, box.maxX - m, box.minY, box.maxZ - m);
            case UP -> new AABB(box.minX + m, box.maxY, box.minZ + m, box.maxX - m, box.maxY + e, box.maxZ - m);
            case WEST -> new AABB(box.minX - e, box.minY + 0.02, box.minZ + m, box.minX, box.maxY - 0.02, box.maxZ - m);
            case EAST -> new AABB(box.maxX, box.minY + 0.02, box.minZ + m, box.maxX + e, box.maxY - 0.02, box.maxZ - m);
            case NORTH -> new AABB(box.minX + m, box.minY + 0.02, box.minZ - e, box.maxX - m, box.maxY - 0.02, box.minZ);
            case SOUTH -> new AABB(box.minX + m, box.minY + 0.02, box.maxZ, box.maxX - m, box.maxY - 0.02, box.maxZ + e);
        };
        return !level.noCollision(who, slab);
    }

    /**
     * The side its rock is on this tick: the floor while it stands on one; else the wall or ceiling it grips while its
     * box touches that, else any its box touches (a wall it moves towards first, then the ceiling); else the floor while
     * it has just stepped off one with floor close under it; else the wall or ceiling it gripped while that is still in
     * reach ({@link #CLING_REACH}), else the nearest rock in reach; nothing only with no rock in reach at all. Through
     * a moment with nothing in reach (rounding an edge) it grips the rock it had, gravity still off; it falls once that
     * lasts ({@link #airborne}). Down again after a fall, it asks the way afresh: the way it was on is behind it (it
     * flew straight back up at the next node of it, dozens of blocks overhead, and fell again).
     */
    private void updateSurface() {
        AABB box = getBoundingBox();
        Direction had = grip;
        Direction next = null;
        if (onGround() || touching(box, Direction.DOWN)) next = Direction.DOWN;
        else if (!isInWater()) {
            if (had != null && had != Direction.DOWN && touching(box, had)) next = had;
            if (next == null) next = touchedSide(level(), this, box, getDeltaMovement());
            if (next == null && had == Direction.DOWN && floorBelow(box)) next = Direction.DOWN;
            if (next == null && had != null && had != Direction.DOWN && rockBeside(level(), this, box, had, CLING_REACH)) next = had;
            // falling past rock in reach, it catches hold of it
            if (next == null) next = nearestSide(level(), this, box);
        }
        clingGap = next == null || next == Direction.DOWN || touching(box, next) ? 0.0 : gapOr0(rockGap(level(), this, box, next, CLING_REACH));
        entityData.set(DATA_SURFACE, (byte) (next == null ? -1 : next.get3DDataValue()));
        boolean fell = airborne();
        if (next == null && !isInWater()) airTicks++;
        else {
            airTicks = 0;
            airDist = 0.0;
        }
        grip = next != null ? next : isInWater() || airborne() ? null : had;
        setNoGravity(gripsOffFloor());
        if (gripsOffFloor()) resetFallDistance();
        updatePose(box);
        if (fell && !airborne() && !getNavigation().isDone()) askAgain();
    }

    /**
     * Its pose turns to the rock it grips once it has gripped that for {@link #POSE_HOLD} ticks running; at once when
     * it falls, after a fall, or when the rock it showed is out of reach.
     */
    private void updatePose(AABB box) {
        Direction pose = poseRock();
        if (grip != poseNext) {
            poseNext = grip;
            poseWait = 0;
        }
        if (grip == pose) return;
        if (pose == null || grip == null || ++poseWait >= POSE_HOLD || !inReach(box, pose))
            entityData.set(DATA_POSE, (byte) (grip == null ? -1 : grip.get3DDataValue()));
    }

    private boolean floorBelow(AABB box) {
        double cx = (box.minX + box.maxX) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
        return !Double.isNaN(floorUnder(level(), cx, box.minY + box.getYsize() / 2.0, box.minY - FLOOR_REACH, cz));
    }

    private static double gapOr0(double gap) { return Double.isNaN(gap) ? 0.0 : gap; }

    /**
     * The wall or ceiling to cling to: one its box touches (a wall it moves towards first, then the ceiling), else the
     * nearest within {@link #CLING_REACH} (a wall before the ceiling at the same reach); null with none in reach.
     */
    @Nullable
    static Direction clingTo(CollisionGetter level, @Nullable Entity who, AABB box, Vec3 m) {
        Direction d = touchedSide(level, who, box, m);
        return d != null ? d : nearestSide(level, who, box);
    }

    /** A wall its box touches (the one it moves towards most), else the ceiling if it touches that; else null. */
    @Nullable
    static Direction touchedSide(CollisionGetter level, @Nullable Entity who, AABB box, Vec3 m) {
        Direction best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!rockBeside(level, who, box, d)) continue;
            if (best == null || m.dot(Vec3.atLowerCornerOf(d.getNormal())) > m.dot(Vec3.atLowerCornerOf(best.getNormal()))) best = d;
        }
        if (best != null) return best;
        return rockBeside(level, who, box, Direction.UP) ? Direction.UP : null;
    }

    /** The wall or ceiling nearest its box within {@link #CLING_REACH} (a wall before the ceiling at the same reach). */
    @Nullable
    static Direction nearestSide(CollisionGetter level, @Nullable Entity who, AABB box) {
        Direction best = null;
        double bestGap = Double.MAX_VALUE;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP}) {
            double gap = rockGap(level, who, box, d, CLING_REACH);
            if (!Double.isNaN(gap) && gap < bestGap - 1.0E-6) { bestGap = gap; best = d; }
        }
        return best;
    }

    /**
     * The way it points along its rock: the way it moved this tick, or (turned to its quarry) towards what it hunts or
     * strikes at, turning slowly, as a big thing does.
     */
    private void updateForward() {
        Vec3 up = up();
        Vec3 mv = position().subtract(xo, yo, zo);
        Vec3 along = mv.subtract(up.scale(mv.dot(up)));
        Vec3 want = null;
        byte s = state();
        LivingEntity t = getTarget();
        Vec3 face = s == REAR ? aim() : (s == ALERT || ((s == HUNT || s == RECOVER) && along.lengthSqr() < 1.0E-4)) && t != null ? t.getEyePosition() : null;
        if (face != null) {
            Vec3 to = face.subtract(feet(up));
            Vec3 flat = to.subtract(up.scale(to.dot(up)));
            if (flat.lengthSqr() > 1.0E-4) want = flat.normalize();
        } else if (along.lengthSqr() > 1.0E-4) want = along.normalize();
        if (want != null) {
            if (forward.dot(want) < -0.99) forward = forward.add(up.cross(forward).scale(0.3));
            forward = forward.lerp(want, face != null ? TURN_RATE * 0.5 : 1.0 - Math.exp(-along.length() / TURN_BLOCKS));
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

    /**
     * On a wall or ceiling it presses against the rock its box touches. Lying still, it is drawn back against its rock
     * when its box has come off it by a little (at most {@link #CLING_PULL} a tick). Crawling, its way keeps it on the
     * rock (each waypoint is pressed against it), and it presses only against rock it touches, and not while its way
     * leads off that rock (0.19.3: drawn back to its rock as it crawled, it was dragged against its way, and on rough
     * corners flipped from face to face and caught on edges).
     */
    @Override
    public void travel(Vec3 input) {
        Direction d = grip;
        if (d != null && d != Direction.DOWN && !level().isClientSide) {
            Vec3 n = Vec3.atLowerCornerOf(d.getNormal());
            double push;
            if (getNavigation().isDone()) push = clingGap > 0.1 ? Math.min(CLING_PULL, clingGap) : 0.04;
            else {
                MoveControl mc = getMoveControl();
                boolean leaving = (mc.getWantedX() - getX()) * n.x + (mc.getWantedY() - getY()) * n.y + (mc.getWantedZ() - getZ()) * n.z < -0.3;
                push = !leaving && touching(getBoundingBox(), d) ? 0.04 : 0.0;
            }
            if (push > 0.0) setDeltaMovement(getDeltaMovement().add(n.scale(push)));
        }
        super.travel(input);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ its rift

    /** The rift it lives in (looked up once from the Blue Reaches' plan), or null for one set down elsewhere. */
    @Nullable
    public Route rift() {
        if (riftLooked) return rift;
        riftLooked = true;
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null) return null;
        if (!hasRift) {
            // set down by an egg or a command: the rift it is in, if any
            Route best = null;
            double bestD = Double.MAX_VALUE;
            for (Route r : t.network.riftsNear(getX(), getZ(), 64)) {
                double[] loc = r.isRift() ? r.locate(getX(), getZ()) : null;
                if (loc == null || Math.abs(loc[2]) > r.riftHalfAt(loc[1]) + 8 || loc[0] >= bestD) continue;
                best = r;
                bestD = loc[0];
            }
            if (best != null) setRift(best);
            return rift = best;
        }
        for (Route r : t.network.riftsNear(getX(), getZ(), 256))
            if (r.isRift() && r.cellX == riftX && r.cellZ == riftZ) return rift = r;
        return null;
    }

    /** It lives in rift {@code r}. */
    public void setRift(Route r) {
        hasRift = true;
        riftX = r.cellX;
        riftZ = r.cellZ;
        rift = r;
        riftLooked = true;
    }

    /** Whether a point is in its rift (anywhere at all for one that lives in none). */
    public boolean inRift(double x, double y, double z) {
        Route r = rift();
        if (r == null) return true;
        double[] loc = r.locate(x, z);
        if (loc == null) return false;
        return Math.abs(loc[2]) <= r.riftHalfAt(loc[1]) + 4.0 && y <= r.riftTopAt(loc[1]) + 10.0 && y >= r.W - 6.0
                && loc[1] > 4.0 && loc[1] < r.length - 4.0;
    }

    private boolean inRift(Vec3 p) { return inRift(p.x, p.y, p.z); }

    /** The Asurine Geodes are the Blue Reaches' refuge: no hunter goes there. */
    public boolean isRefuge(BlockPos p) {
        return level().isLoaded(p) && level().getBiome(p).is(SkeinEntity.REFUGE);
    }

    // ------------------------------------------------------------------------------------------------ the mind

    private void toState(byte s, int length) {
        entityData.set(DATA_STATE, s);
        stateTicks = 0;
        stateLength = length;
        if (s != CRAWL) waypoints.clear();
    }

    @Override
    protected void customServerAiStep() {
        updateSurface();
        Path way = getNavigation().getPath();
        if (!airborne() && way != null && !way.isDone() && way.getNextEntityPos(this).distanceToSqr(position()) > STRAYED * STRAYED)
            askAgain();
        // held for a picture of its strike (/remix giant hold): it does nothing else
        if (entityData.get(DATA_HELD) >= 0.0F) { stopDead(); return; }
        if (isInWater() && getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold()) getJumpControl().jump();
        stateTicks++;
        if (strikeCooldown > 0) strikeCooldown--;
        // reeling (a strong attack spoiled its strike, or its poise broke) it does nothing
        if (Stagger.reeling(this)) { stopDead(); return; }
        switch (state()) {
            case REST -> rest();
            case CRAWL -> crawl();
            case ALERT -> alert();
            case HUNT -> hunt();
            case REAR -> rear();
            case LUNGE -> lunge();
            case RECOVER -> recover();
            case SEARCH -> search();
            case RETREAT -> retreat();
            default -> settle(SHORT_REST);
        }
    }

    /** Back to lying still for {@code ticks}. */
    private void settle(int ticks) {
        stopDead();
        setTarget(null);
        toState(REST, ticks);
    }

    private int restLength() {
        return REST_MIN + random.nextInt(REST_MAX - REST_MIN + 1);
    }

    // --- resting, and crawling about its rift

    private void rest() {
        stopDead();
        if (tickCount % 10 == 0 && notice()) return;
        if (stateTicks >= stateLength || (roamTest.on() && stateTicks >= 20)) {
            legsLeft = 1 + random.nextInt(3);
            toState(CRAWL, 0);
            nextLeg();
        }
    }

    private void crawl() {
        if (tickCount % 10 == 0 && notice()) return;
        PathNavigation nav = getNavigation();
        if (nav.isDone() || nav.isStuck() || stateTicks > 900) {
            if (!nextLeg()) settle(restLength());
            return;
        }
        nav.setSpeedModifier(ROAM_SPEED * arriving(nav));
        steps();
    }

    /** The next leg of a crawl (the next waypoint of a way over a bridge first); false when the crawl is over. */
    private boolean nextLeg() {
        stopDead();
        stateTicks = 0;
        while (!waypoints.isEmpty()) {
            Vec3 w = waypoints.poll();
            if (goTo(w, 4, ROAM_SPEED)) return true;
        }
        if (legsLeft-- <= 0) return false;
        StringBuilder why = roamTest.on() ? new StringBuilder() : null;
        for (int i = 0; i < 6; i++) {
            Vec3 g = roamGoal();
            if (g != null && goTo(g, 3, ROAM_SPEED)) {
                if (roamTest.on()) roamTest.leg(true);
                return true;
            }
            if (why != null) why.append(i == 0 ? "" : "; ").append(noWay);
        }
        // no way along its rift from here (its wall broken, or the rift's end near): a short crawl over the rock round
        // it instead, so it never lies in one place crawl after crawl
        for (int i = 0; i < 4; i++) {
            if (goTo(nearGoal(NEAR_MIN, NEAR_MAX), 4, ROAM_SPEED)) {
                if (roamTest.on()) roamTest.nearLeg(why == null ? "" : why.toString());
                return true;
            }
            if (why != null) why.append(i == 0 ? "; near: " : "; ").append(noWay);
        }
        if (roamTest.on()) roamTest.leg(false, why == null ? "" : why.toString());
        return false;
    }

    /** A spot on the rock round it, {@code min} to {@code max} blocks off, ahead of it or off to either side. */
    private Vec3 nearGoal(double min, double max) {
        Vec3 heading = SkeinEntity.rotate(forward, up(), (random.nextDouble() - 0.5) * Math.PI * 1.4);
        return bodyCentre().add(heading.scale(min + random.nextDouble() * (max - min)));
    }

    /** Sets out for a node near {@code p} (within {@code r}) at {@code speed}; false when there is no way there. */
    private boolean goTo(Vec3 p, int r, double speed) {
        BlockPos n = nodeNear(p, r);
        if (n == null) { noWay = "no node near " + BlockPos.containing(p).toShortString(); return false; }
        Path path = getNavigation().createPath(n.offset(1, 0, 1), 0);
        if (path == null || path.getNodeCount() < 2) { noWay = "no path"; return false; }
        if (leavesRift(path)) { noWay = "leaves its rift"; return false; }
        Node end = path.getEndNode();
        if (end == null || Mth.square(end.x + 1.5 - getX()) + Mth.square(end.y - getY()) + Mth.square(end.z + 1.5 - getZ()) < 4.0) {
            noWay = "ends too near";
            return false;
        }
        getNavigation().moveTo(path, speed);
        return true;
    }

    /**
     * Where a leg of its crawl goes: in its rift, a spot on its wall {@link #LEG_MIN} to {@link #LEG_MAX} blocks along
     * the rift either way, at about its own height or (now and then) across the ledge path's height on the wall the
     * path keeps to, so it crosses the path; more rarely over a bridge to the far wall ({@link #CROSS_RIFT}). Mostly up
     * on the walls, well above the stream. Out of any rift, a spot on the rock round it.
     */
    @Nullable
    private Vec3 roamGoal() {
        Route r = rift();
        double[] loc = r == null ? null : r.locate(getX(), getZ());
        if (r == null || loc == null) return nearGoal(LEG_MIN, LEG_MAX);
        double s0 = loc[1], side = loc[2] >= 0 ? 1.0 : -1.0;
        if (random.nextDouble() < CROSS_RIFT && overBridge(r, s0, side)) return waypoints.poll();
        double len = LEG_MIN + random.nextDouble() * (LEG_MAX - LEG_MIN);
        double s1 = Mth.clamp(s0 + (random.nextBoolean() ? len : -len), 24.0, r.length - 24.0);
        double bed = r.W, top = r.riftTopAt(s1), depth = top - bed;
        double y = getY() + (random.nextDouble() - 0.5) * 12.0;
        double pathY = Route.feetAt(r.floorAt(s1));
        boolean pathSide = r.pathSideAt(s1) * side > 0.3;
        if (pathSide && random.nextDouble() < CROSS_PATH) {
            // across the path's height on its wall: from above the path to below it, or from below to above
            y = getY() > pathY ? pathY - 5.0 - random.nextDouble() * 10.0 : pathY + 5.0 + random.nextDouble() * 12.0;
        } else if (pathSide && Math.abs(y - pathY) < 4.0) {
            y += y >= pathY ? 4.0 : -4.0;          // a wall, not the path itself
        }
        // mostly the upper walls: well above the stream, under the rim
        y = Mth.clamp(y, bed + Math.max(8.0, depth * 0.3), top - 5.0);
        Vec3 w = wallAt(r, s1, side, y);
        if (w == null && roamTest.on()) noWay = GiantRoamTest.noWall(level(), r, s1, side, y);
        return w;
    }

    /**
     * A way over the nearest bridge within reach to the far wall, as waypoints (onto the bridge, across it, onto the
     * far wall at its height); false when there is none near.
     */
    private boolean overBridge(Route r, double s0, double side) {
        Route.Bridge best = null;
        for (Route.Bridge b : r.bridges)
            if (Math.abs(b.s() - s0) < 48.0 && (best == null || Math.abs(b.s() - s0) < Math.abs(best.s() - s0))) best = b;
        if (best == null) return false;
        double[] c = r.pointAt(best.s()), d = r.directionAt(best.s());
        double half = r.riftHalfAt(best.s());
        double y = best.y() + 1.0;
        Vec3 near = wallAt(r, best.s(), side, y + 3.0);
        Vec3 mid = new Vec3(c[0], y, c[1]);
        Vec3 far = wallAt(r, best.s(), -side, y + 3.0);
        if (near == null || far == null) return false;
        waypoints.clear();
        waypoints.add(near);
        waypoints.add(new Vec3(c[0] - d[1] * side * half * 0.4, y, c[1] + d[0] * side * half * 0.4));
        waypoints.add(mid);
        waypoints.add(new Vec3(c[0] + d[1] * side * half * 0.4, y, c[1] - d[0] * side * half * 0.4));
        waypoints.add(far);
        return true;
    }

    /**
     * The face of the rift's wall on {@code side} at arc length {@code s} and height {@code y}: from the centre line out
     * along the way across until rock, the point a little off it. Null where the way runs into unloaded chunks or finds
     * no wall.
     */
    @Nullable
    Vec3 wallAt(Route r, double s, double side, double y) {
        return wallAt(level(), r, s, side, y);
    }

    @Nullable
    static Vec3 wallAt(LevelReader level, Route r, double s, double side, double y) {
        double[] c = r.pointAt(s), d = r.directionAt(s);
        double px = -d[1] * side, pz = d[0] * side;
        double reach = r.riftHalfAt(s) + 12.0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double v = 0.0; v <= reach; v += 0.5) {
            p.set(Mth.floor(c[0] + px * v), Mth.floor(y), Mth.floor(c[1] + pz * v));
            if (!loaded(level, p)) return null;
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty())
                return new Vec3(c[0] + px * (v - 1.6), y, c[1] + pz * (v - 1.6));
        }
        return null;
    }

    // --- noticing, and the hunt

    /** A player it sees in its rift: it turns to them (ALERT), then hunts them. */
    private boolean notice() {
        Player p = spot();
        if (p == null) return false;
        startAlert(p);
        return true;
    }

    private void startAlert(LivingEntity t) {
        stopDead();
        setTarget(t);
        lastSeen = level().getGameTime();
        lastSeenAt = t.position();
        toState(ALERT, ALERT_TICKS);
        playSound(ERSounds.GIANT_SKEIN_NOTICE.get(), 2.0F, 0.9F + random.nextFloat() * 0.15F);
    }

    /** The nearest player it can see within {@link #SIGHT} who is in its rift, out of the refuge, to be hunted. */
    @Nullable
    private Player spot() {
        if (level().getDifficulty() == Difficulty.PEACEFUL) return null;
        Vec3 e = eyes();
        Player best = null;
        double bestD = SIGHT * SIGHT;
        for (Player p : level().players()) {
            if (!huntable(p)) continue;
            double d = p.distanceToSqr(e);
            if (d >= bestD || !inRift(p.position()) || isRefuge(p.blockPosition()) || !canSee(p)) continue;
            bestD = d;
            best = p;
        }
        return best;
    }

    private void alert() {
        stopDead();
        LivingEntity t = getTarget();
        if (!hunting(t)) { settle(SHORT_REST); return; }
        if (canSee(t)) { lastSeen = level().getGameTime(); lastSeenAt = t.position(); }
        if (stateTicks >= stateLength) toState(HUNT, 0);
    }

    /** Whether {@code t} is still to be hunted: alive, in its rift, out of the refuge, near enough, not in Peaceful. */
    private boolean hunting(@Nullable LivingEntity t) {
        return t != null && huntable(t) && level().getDifficulty() != Difficulty.PEACEFUL && inRift(t.position())
                && !isRefuge(t.blockPosition()) && t.distanceToSqr(this) < LEASH * LEASH;
    }

    private void hunt() {
        LivingEntity t = getTarget();
        if (!hunting(t)) { lostQuarry(); return; }
        long now = level().getGameTime();
        boolean sees = canSee(t);
        if (sees) { lastSeen = now; lastSeenAt = t.position(); }
        else if (now - lastSeen > LOSE_TICKS) { startSearch(); return; }
        if (tickCount % 2 == 0 && canStrike(t)) { startStrike(t); return; }
        PathNavigation nav = getNavigation();
        if (stateTicks % REPATH == 1 || nav.isDone() || nav.isStuck()) {
            Vec3 goal = sees || lastSeenAt == null ? t.position() : lastSeenAt;
            if (!goTo(goal, 4, HUNT_SPEED)) goTo(position().add(goal.subtract(position()).scale(0.5)), 4, HUNT_SPEED);
        }
        nav.setSpeedModifier(HUNT_SPEED);
        steps();
    }

    /** It has lost its quarry (gone out of its rift, into the refuge, dead, or far off): a short rest, then its own way. */
    private void lostQuarry() {
        settle(SHORT_REST + random.nextInt(SHORT_REST));
    }

    private void startSearch() {
        toState(SEARCH, SEARCH_TICKS);
        if (lastSeenAt == null || !goTo(lastSeenAt, 4, SEARCH_SPEED)) lostQuarry();
    }

    private void search() {
        LivingEntity t = getTarget();
        if (hunting(t) && canSee(t)) {
            lastSeen = level().getGameTime();
            lastSeenAt = t.position();
            toState(HUNT, 0);
            return;
        }
        if (tickCount % 10 == 0 && notice()) return;
        PathNavigation nav = getNavigation();
        if (stateTicks >= stateLength || nav.isDone() || nav.isStuck()) { lostQuarry(); return; }
        nav.setSpeedModifier(SEARCH_SPEED);
        steps();
    }

    /** Whether it can strike at {@code t} now: in reach of its jaws, with a clear line from its eyes. */
    private boolean canStrike(LivingEntity t) {
        return strikeCooldown == 0 && inReach(t, STRIKE_REACH) && clearTo(eyes(), t) && inRift(t.position());
    }

    /**
     * Whether {@code t} is within {@code near} of its mouth and in reach of its strike: its front rears from {@link
     * GiantStrike#NECK} back along its body, so what it strikes at must lie within the strike's reach of there.
     */
    private boolean inReach(LivingEntity t, double near) {
        if (!body.laid() || Math.sqrt(t.getBoundingBox().distanceToSqr(mouth())) > near) return false;
        double[] a = new double[6];
        body.sample(GiantStrike.NECK, 1.0F, a, 0);
        Vec3 c = t.getBoundingBox().getCenter();
        return Math.sqrt(Mth.square(c.x - a[0]) + Mth.square(c.y - a[1]) + Mth.square(c.z - a[2])) <= GiantStrike.REACH + GiantStrike.JAWS - 0.5;
    }

    /** It rears its front up off the rock at {@code t}, with a deep rattle, and coils (a second), then strikes. */
    private void startStrike(LivingEntity t) {
        stopDead();
        setTarget(t);
        setAim(t.getBoundingBox().getCenter());
        toState(REAR, REAR_TICKS);
        playSound(ERSounds.GIANT_SKEIN_WINDUP.get(), 2.0F, 0.95F + random.nextFloat() * 0.1F);
        if (level() instanceof ServerLevel l) RuneFx.windup(l, eyes().add(up().scale(1.5)), forward, 1.8F);
    }

    private Vec3 aim() {
        Vector3f a = entityData.get(DATA_AIM);
        return new Vec3(a.x, a.y, a.z);
    }

    private void setAim(Vec3 p) {
        Vector3f a = entityData.get(DATA_AIM);
        if (Mth.square(a.x - p.x) + Mth.square(a.y - p.y) + Mth.square(a.z - p.z) > 0.01)
            entityData.set(DATA_AIM, new Vector3f((float) p.x, (float) p.y, (float) p.z));
    }

    private void rear() {
        stopDead();
        LivingEntity t = getTarget();
        // it follows its quarry with its coiled head as it rears, then commits
        if (t != null && huntable(t) && stateTicks % 2 == 0) setAim(t.getBoundingBox().getCenter());
        if (stateTicks >= stateLength) {
            bit = false;
            jawsWere = null;
            toState(LUNGE, LUNGE_TICKS);
        }
    }

    private void lunge() {
        stopDead();
        double[] head = new double[6], jaws = new double[3];
        if (strikeHead(0.0F, head)) {
            GiantStrike.jaws(head, jaws);
            LivingEntity t = getTarget();
            if (!bit && t != null && huntable(t)) {
                Vec3 to = new Vec3(jaws[0], jaws[1], jaws[2]);
                Vec3 from = jawsWere == null ? to : new Vec3(jawsWere[0], jawsWere[1], jawsWere[2]);
                AABB box = t.getBoundingBox().inflate(0.7);
                boolean hit = box.contains(to) || box.clip(from, to).isPresent();
                if (hit && clear(eyes(), t.getBoundingBox().getCenter())) { bite(t, to.subtract(from)); bit = true; }
            }
            jawsWere = jaws;
        }
        if (stateTicks >= stateLength) {
            strikeCooldown = STRIKE_COOLDOWN;
            toState(RECOVER, RECOVER_TICKS);
        }
    }

    private void bite(LivingEntity t, Vec3 sweep) {
        bites++;
        playSound(ERSounds.GIANT_SKEIN_BITE.get(), 2.0F, 0.9F + random.nextFloat() * 0.2F);
        t.hurt(damageSources().mobAttack(this), BITE);
        Vec3 push = sweep.lengthSqr() > 1.0E-4 ? sweep.normalize() : t.position().subtract(position()).normalize();
        t.knockback(0.6, -push.x, -push.z);
    }

    private void recover() {
        stopDead();
        if (stateTicks < stateLength) return;
        LivingEntity t = getTarget();
        if (level().getGameTime() < retreatUntil) { toState(RETREAT, RETREAT_TICKS); return; }
        if (hunting(t)) toState(HUNT, 0);
        else lostQuarry();
    }

    // --- hurt

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            dormant = false;
            Entity attacker = source.getEntity();
            if (getHealth() < getMaxHealth() * RETREAT_AT && level().getGameTime() >= retreatUntil) {
                // badly hurt: it draws off up its wall, away from whoever hurt it
                retreatUntil = level().getGameTime() + RETREAT_TICKS;
                byte s = state();
                if (s != REAR && s != LUNGE && s != RECOVER) startRetreat(attacker);
            } else if (attacker instanceof LivingEntity le && le != this && huntable(le) && level().getDifficulty() != Difficulty.PEACEFUL) {
                byte s = state();
                if (s == REST || s == CRAWL || s == SEARCH) {
                    if (inRift(le.position()) && !isRefuge(le.blockPosition())) startAlert(le);
                } else if (s == HUNT && getTarget() != le && le.distanceToSqr(this) < Mth.square(STRIKE_REACH * 1.5)) {
                    setTarget(le);
                    lastSeen = level().getGameTime();
                    lastSeenAt = le.position();
                }
            }
        }
        return hurt;
    }

    private void startRetreat(@Nullable Entity from) {
        setTarget(null);
        toState(RETREAT, RETREAT_TICKS);
        Vec3 away = from == null ? forward : position().subtract(from.position());
        Vec3 flat = away.subtract(up().scale(away.dot(up())));
        Vec3 dir = flat.lengthSqr() > 1.0E-4 ? flat.normalize() : forward;
        // away, and up its wall where it can
        Vec3 goal = bodyCentre().add(dir.scale(18.0)).add(0, 8.0, 0);
        if (!goTo(goal, 4, RETREAT_SPEED)) goTo(bodyCentre().add(dir.scale(14.0)), 4, RETREAT_SPEED);
    }

    private void retreat() {
        // cornered, it still strikes at one right by its jaws
        if (tickCount % 4 == 0 && strikeCooldown == 0) {
            for (Player p : level().players()) {
                if (!hunting(p) || !inReach(p, CORNERED) || !clearTo(eyes(), p)) continue;
                startStrike(p);
                return;
            }
        }
        PathNavigation nav = getNavigation();
        if (stateTicks >= stateLength) { settle(restLength()); return; }
        if (nav.isDone() || nav.isStuck()) {
            stopDead();
            if (stateTicks % 40 == 0) {
                Vec3 g = roamGoal();
                if (g != null) goTo(g, 3, RETREAT_SPEED);
            }
            return;
        }
        nav.setSpeedModifier(RETREAT_SPEED * arriving(nav));
        steps();
    }

    @Override
    public boolean windingUp() { return !level().isClientSide && state() == REAR; }

    /** A strong attack caught it rearing: no strike; its front sinks back to the rock (it reels a moment too). */
    @Override
    public void interrupt(LivingEntity by) {
        strikeCooldown = STRIKE_COOLDOWN * 2;
        toState(RECOVER, RECOVER_TICKS);
        playSound(ERSounds.GIANT_SKEIN_HURT.get(), 2.0F, 1.2F);
    }

    // --- senses

    /** It sees what is in the open round its head, not through rock. */
    public boolean canSee(LivingEntity t) {
        return !t.isInvisible() && clearTo(eyes(), t);
    }

    /** Whether it sees the point {@code p} from its eyes (for /remix giant path). */
    public boolean sees(Vec3 p) { return clear(eyes(), p); }

    private boolean clearTo(Vec3 from, Entity t) {
        return clear(from, t.getEyePosition()) || clear(from, t.getBoundingBox().getCenter());
    }

    private boolean clear(Vec3 from, Vec3 to) {
        HitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean huntable(LivingEntity t) {
        if (!t.isAlive() || t.isRemoved() || t.level() != level()) return false;
        return !(t instanceof Player p) || (!p.isCreative() && !p.isSpectator());
    }

    // --- moving

    /** Freezes dead: no path, no step this tick, no coasting. */
    private void stopDead() {
        getNavigation().stop();
        getMoveControl().strafe(0.0F, 0.0F);
        if (grip != null) {
            Vec3 dm = getDeltaMovement();
            setDeltaMovement(0.0, gripsOffFloor() ? 0.0 : Math.min(0.0, dm.y), 0.0);
        }
    }

    /** Its way is behind it (it fell off it, or slid far off it): it asks the way to the same place afresh, or stops. */
    private void askAgain() {
        PathNavigation nav = getNavigation();
        BlockPos to = nav.getTargetPos();
        nav.stop();
        long now = level().getGameTime();
        if (to == null || now - askedAt < 20) return;
        askedAt = now;
        Path p = nav.createPath(to, 0);
        if (p != null && p.getNodeCount() >= 2 && !leavesRift(p)) nav.moveTo(p, ROAM_SPEED);
    }

    /** The share of its speed it keeps as it comes up to the end of its way: all of it until {@link #SLOW_FROM} out. */
    private double arriving(PathNavigation nav) {
        Path p = nav.getPath();
        Node end = p == null ? null : p.getEndNode();
        if (end == null || p.getNodeCount() - p.getNextNodeIndex() > 6) return 1.0;
        // it is done with its way a block or so short of the end node's middle
        double d = Math.sqrt(Mth.square(end.x + 1.5 - getX()) + Mth.square(end.y - getY()) + Mth.square(end.z + 1.5 - getZ())) - 1.0;
        return Mth.clamp(SLOWEST + (1.0 - SLOWEST) * d / SLOW_FROM, SLOWEST, 1.0);
    }

    /** The heavy scrape of its feet as it crawls. */
    private void steps() {
        stepDist += (float) movedLast;
        if (stepDist > STEP_EVERY && surface() != null) {
            stepDist = 0;
            playSound(ERSounds.GIANT_SKEIN_STEP.get(), 0.8F, 0.9F + random.nextFloat() * 0.2F);
        }
    }

    /**
     * The walkable node (floor, wall or ceiling, not water) whose box (3 x 2 x 3 cells) would sit nearest {@code p},
     * within {@code r} blocks; never reading an unloaded chunk, never in the refuge, never out of its rift.
     */
    @Nullable
    BlockPos nodeNear(Vec3 p, int r) {
        int bx = Mth.floor(p.x - 1.5), by = Mth.floor(p.y - 0.6), bz = Mth.floor(p.z - 1.5);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!level().hasChunksAt(x - 1, z - 1, x + 4, z + 4)) continue;
                for (int dy = -r - 1; dy <= r; dy++) {
                    int y = by + dy;
                    double d = Mth.square(x + 1.5 - p.x) + Mth.square(y + 0.6 - p.y) + Mth.square(z + 1.5 - p.z);
                    if (d >= bestD) continue;
                    byte k = ClimbNodeEvaluator.classify(level(), x, y, z, 3, 2, 3);
                    if (k < ClimbNodeEvaluator.FLOOR || k == ClimbNodeEvaluator.WATER) continue;
                    if (!inRift(x + 1.5, y, z + 1.5) || isRefuge(new BlockPos(x + 1, y, z + 1))) continue;
                    bestD = d;
                    best = new BlockPos(x, y, z);
                }
            }
        return best;
    }

    /** Whether a path runs out of its rift, or into the refuge. */
    private boolean leavesRift(Path p) {
        for (int i = 0; i < p.getNodeCount(); i++) {
            Node n = p.getNode(i);
            if (!inRift(n.x + 1.5, n.y, n.z + 1.5) || isRefuge(new BlockPos(n.x + 1, n.y, n.z + 1))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    protected SoundEvent getAmbientSound() {
        return state() == REST ? ERSounds.GIANT_SKEIN_CHITTER.get() : null;
    }

    @Override public int getAmbientSoundInterval() { return 400; }
    @Override protected float getSoundVolume() { return 1.6F; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.GIANT_SKEIN_HURT.get(); }
    @Override protected SoundEvent getDeathSound() { return ERSounds.GIANT_SKEIN_DEATH.get(); }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { }

    // ------------------------------------------------------------------------------------------------ life and death

    /** One to a rift, for good: never despawned, not even in Peaceful (where it hunts no one). */
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }
    @Override public boolean canBeLeashed(Player player) { return false; }
    @Override public int getMaxAirSupply() { return 600; }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        setPersistenceRequired();
        return super.finalizeSpawn(level, difficulty, reason, data, tag);
    }

    /**
     * Its death: its legs curl and its lights go out over two and a half seconds, then it crumbles along all its
     * length. Gone for good (Taran's pick): nothing moves into its rift after it ({@link GiantSkeins}).
     */
    @Override
    protected void tickDeath() {
        ++deathTime;
        if (deathTime >= 50 && !level().isClientSide() && !isRemoved()) {
            if (level() instanceof ServerLevel l) {
                double[] at = new double[6 * SECTION_ENDS.length];
                if (body.laid()) {
                    bodyAt(SECTION_ENDS, 1.0F, at);
                    for (int i = 0; i < SECTION_ENDS.length; i++)
                        l.sendParticles(ParticleTypes.POOF, at[6 * i] + at[6 * i + 3] * 0.6, at[6 * i + 1] + at[6 * i + 4] * 0.6,
                                at[6 * i + 2] + at[6 * i + 5] * 0.6, 8, 0.6, 0.4, 0.6, 0.02);
                }
            }
            GiantSkeins.killed(this);
            remove(RemovalReason.KILLED);
        }
    }

    /**
     * What it leaves lands at the feet of the player who killed it, when they are near: dying on a wall, it would drop
     * it all into the rift.
     */
    @Nullable
    @Override
    public ItemEntity spawnAtLocation(ItemStack stack, float yOffset) {
        Player p = lastHurtByPlayer;
        if (!stack.isEmpty() && !level().isClientSide && p != null && p.isAlive() && p.level() == level() && p.distanceToSqr(this) < 40.0 * 40.0) {
            ItemEntity item = new ItemEntity(level(), p.getX(), p.getY() + 0.5, p.getZ(), stack);
            item.setDefaultPickUpDelay();
            level().addFreshEntity(item);
            return item;
        }
        return super.spawnAtLocation(stack, yOffset);
    }

    // ------------------------------------------------------------------------------------------------ setting it down

    /** Where one set down on a climbing node sits: its position (its box's bottom middle) and its rock's side. */
    public record Placement(Vec3 pos, Direction rock) {}

    /**
     * Where one set down on the climbing node with low corner {@code n} (3 x 2 x 3 cells) sits, pressed against its
     * rock: on the floor, on the ceiling, or against the wall beside it. Null when the node is none of these, or its box
     * does not fit there.
     */
    @Nullable
    public static Placement placement(LevelReader level, EntityType<?> type, BlockPos n) {
        byte k = ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 3, 2, 3);
        float w = type.getWidth(), h = type.getHeight();
        Vec3 mid = new Vec3(n.getX() + 1.5, n.getY(), n.getZ() + 1.5);
        Placement p = switch (k) {
            case ClimbNodeEvaluator.FLOOR -> new Placement(mid, Direction.DOWN);
            case ClimbNodeEvaluator.CEILING -> new Placement(mid.add(0, 2.0 - h, 0), Direction.UP);
            case ClimbNodeEvaluator.WALL -> wallPlacement(level, mid, w, h);
            default -> null;
        };
        if (p == null || !level.noCollision(type.getDimensions().makeBoundingBox(p.pos()))) return null;
        return p;
    }

    @Nullable
    private static Placement wallPlacement(LevelReader level, Vec3 mid, float w, float h) {
        double gap = 1.5 - w / 2.0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vec3 at = mid.add(d.getStepX() * gap, 0, d.getStepZ() * gap);
            AABB box = new AABB(at.x - w / 2.0, at.y, at.z - w / 2.0, at.x + w / 2.0, at.y + h, at.z + w / 2.0);
            if (rockBeside(level, null, box, d)) return new Placement(at, d);
        }
        return null;
    }

    /** The point on its rock under the middle of the box of one set down at {@code p}, as feet() has it. */
    static Vec3 placedFeet(EntityType<?> type, Placement p) {
        Vec3 up = normal(p.rock());
        double w = type.getWidth() / 2.0, h = type.getHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        return p.pos().add(0, h, 0).subtract(up.scale(ext));
    }

    /**
     * Sets it down at {@code spot}, its head pointing along {@code fwd}, its body draped back over the rock along
     * {@code back} ({@link #drape}): as GiantSkeins sets it down in its rift and /remix giant spawn does.
     */
    public void placeOn(LevelReader level, Placement spot, Vec3 fwd, Vec3 back) {
        aim(spot.pos(), spot.rock(), fwd);
        Vec3 up = normal(spot.rock());
        float[] laid = drape(level, placedFeet(getType(), spot), up, back);
        if (body.read(laid, getX(), getY(), getZ())) {
            body.layBody();
            placeSections();
        }
        foot = null;
    }

    private void aim(Vec3 pos, Direction rock, Vec3 fwd) {
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
    }

    /**
     * Its body laid back from its head at {@code feet} over the rock along {@code back}, as a trail to read: a point every
     * {@link #DRAPE_STEP} blocks, each moved onto the face of the rock across from where the line runs (out of rock it
     * would cut into, down onto rock it would float over), with the rock's normal there; past where the rock gives out,
     * straight on. Relative to its position, as GiantBody#read takes it.
     */
    float[] drape(LevelReader level, Vec3 feet, Vec3 up, Vec3 back) {
        Vec3 b = back.subtract(up.scale(back.dot(up)));
        b = b.lengthSqr() > 1.0E-4 ? b.normalize() : forward.scale(-1.0);
        int n = (int) Math.ceil(TRAIL_KEEP / DRAPE_STEP) + 1;
        float[] out = new float[6 * n];
        Vec3 p = feet, nrm = up;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                Vec3 q = p.add(b.scale(DRAPE_STEP));
                Vec3 onto = onRock(level, q, nrm);
                if (onto != null) {
                    // the way on bends with the rock: along the new stretch, kept on the rock's plane
                    Vec3 step = onto.subtract(p);
                    if (step.lengthSqr() > 1.0E-4) {
                        Vec3 sb = step.normalize();
                        b = sb.subtract(nrm.scale(sb.dot(nrm)));
                        b = b.lengthSqr() > 1.0E-4 ? b.normalize() : sb;
                    }
                    p = onto;
                } else p = q;
            }
            int o = 6 * i;
            out[o] = (float) (p.x - getX());
            out[o + 1] = (float) (p.y - getY());
            out[o + 2] = (float) (p.z - getZ());
            out[o + 3] = (float) nrm.x;
            out[o + 4] = (float) nrm.y;
            out[o + 5] = (float) nrm.z;
        }
        return out;
    }

    static final double DRAPE_STEP = 0.75;

    /**
     * The face of the rock across from {@code q} along {@code up}: out of the rock if {@code q} is in it, else down
     * onto rock within reach under it; null when there is none (it is out over an edge).
     */
    @Nullable
    private static Vec3 onRock(LevelReader level, Vec3 q, Vec3 up) {
        if (inRock(level, q.add(up.scale(0.02)))) {
            for (int i = 1; i <= 100; i++) {
                Vec3 t = q.add(up.scale(0.03 * i));
                if (!inRock(level, t.add(up.scale(0.02)))) return t;
            }
            return null;
        }
        for (int i = 1; i <= 80; i++) {
            Vec3 t = q.subtract(up.scale(0.03 * i));
            if (inRock(level, t.subtract(up.scale(0.02)))) return t;
        }
        return null;
    }

    /** How far along {@code back} its body could drape over the rock from {@code feet} (blocks, up to its length). */
    static double drapeRoom(LevelReader level, Vec3 feet, Vec3 up, Vec3 back) {
        Vec3 b = back.subtract(up.scale(back.dot(up)));
        if (b.lengthSqr() < 1.0E-4) return 0.0;
        b = b.normalize();
        Vec3 p = feet;
        double room = 0.0;
        while (room < BODY_LENGTH) {
            Vec3 q = p.add(b.scale(DRAPE_STEP));
            Vec3 onto = onRock(level, q, up);
            if (onto == null || onto.distanceTo(q) > 1.2) break;
            p = onto;
            room += DRAPE_STEP;
        }
        return room;
    }

    @SuppressWarnings("deprecation")
    static boolean loaded(LevelReader level, BlockPos p) {
        return level instanceof Level l ? l.isLoaded(p) : level.hasChunkAt(p);
    }

    // ------------------------------------------------------------------------------------------------ saving

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        float[] kept = kept();
        if (kept.length > 0) {
            ListTag list = new ListTag();
            for (float v : kept) list.add(net.minecraft.nbt.FloatTag.valueOf(v));
            tag.put("Trail", list);
        }
        tag.putFloat("ForwardX", (float) forward.x);
        tag.putFloat("ForwardY", (float) forward.y);
        tag.putFloat("ForwardZ", (float) forward.z);
        if (hasRift) {
            tag.putInt("RiftX", riftX);
            tag.putInt("RiftZ", riftZ);
        }
        tag.putLong("RetreatUntil", retreatUntil);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ListTag kept = tag.getList("Trail", Tag.TAG_FLOAT);
        float[] t = new float[kept.size()];
        for (int i = 0; i < t.length; i++) t[i] = kept.getFloat(i);
        body.read(t, getX(), getY(), getZ());
        Vec3 f = new Vec3(tag.getFloat("ForwardX"), tag.getFloat("ForwardY"), tag.getFloat("ForwardZ"));
        if (f.lengthSqr() > 1.0E-4) {
            forward = f.normalize();
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        }
        if (tag.contains("RiftX")) {
            hasRift = true;
            riftX = tag.getInt("RiftX");
            riftZ = tag.getInt("RiftZ");
            riftLooked = false;
        }
        retreatUntil = tag.getLong("RetreatUntil");
        setPersistenceRequired();
    }

    /** Its body as it lies, at {@link #KEPT_AT} along it (none before it has one). */
    private float[] kept() {
        if (!body.laid()) return new float[0];
        if (!body.onTrail()) body.layBody();
        return body.write(KEPT_AT, getX(), getY(), getZ());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void writeSpawnData(FriendlyByteBuf buf) {
        buf.writeFloat((float) forward.x);
        buf.writeFloat((float) forward.y);
        buf.writeFloat((float) forward.z);
        float[] kept = kept();
        buf.writeVarInt(kept.length);
        for (float v : kept) buf.writeFloat(v);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buf) {
        Vec3 fwd = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        int n = buf.readVarInt();
        if (n < 0 || n > 6 * KEPT_AT.length) return;
        float[] kept = new float[n];
        for (int i = 0; i < n; i++) kept[i] = buf.readFloat();
        if (!body.read(kept, getX(), getY(), getZ())) return;
        body.layBody();
        placeSections();
        double[] head = new double[6];
        body.trailSample(0.0, head);
        poseHead(fwd, new Vec3(head[3], head[4], head[5]));
    }

    // ------------------------------------------------------------------------------------------------ dev commands

    /** For /remix giant: what it is doing. */
    public String describe() {
        byte s = state();
        Direction d = surface();
        StringBuilder b = new StringBuilder();
        b.append(s >= 0 && s < STATE_NAMES.length ? STATE_NAMES[s] : "?")
                .append(" on ").append(d == null ? "nothing" : d == Direction.DOWN ? "floor" : d == Direction.UP ? "ceiling" : "wall " + d.getName())
                .append(String.format(Locale.ROOT, ", health %.0f/%.0f", getHealth(), getMaxHealth()))
                .append(Stagger.reeling(this) ? ", reeling" : "");
        if (s == REST) b.append(", resting ").append(Math.max(0, stateLength - stateTicks) / 20).append("s more");
        if (s == CRAWL) b.append(", ").append(legsLeft + 1).append(" legs to go").append(waypoints.isEmpty() ? "" : ", over a bridge");
        LivingEntity t = getTarget();
        if (t != null) b.append(", after ").append(t.getName().getString())
                .append(String.format(Locale.ROOT, " (%.0f blocks, seen %ds ago)", t.distanceTo(this), (level().getGameTime() - lastSeen) / 20));
        Route r = rift();
        if (r != null) {
            double[] loc = r.locate(getX(), getZ());
            b.append(String.format(Locale.ROOT, ", in the rift of cell %d %d", r.cellX, r.cellZ));
            if (loc != null) b.append(String.format(Locale.ROOT, " (%.0f along it, %.0f from its middle, path at y %d)",
                    loc[1], loc[2], Route.feetAt(r.floorAt(loc[1]))));
        } else b.append(", in no rift");
        if (level().getGameTime() < retreatUntil) b.append(", drawing off");
        if (bites > 0) b.append(", has bitten ").append(bites).append(bites == 1 ? " time" : " times");
        float held = entityData.get(DATA_HELD);
        if (held >= 0.0F) b.append(String.format(Locale.ROOT, ", held %.2f through its %s", held, STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, s))]));
        if (dormant) b.append(", dormant");
        return b.toString();
    }

    /** For /remix giant state: puts it in state {@code s}; a hunt or strike is at {@code who}. False when it cannot. */
    public boolean forceState(byte s, int ticks, @Nullable LivingEntity who) {
        switch (s) {
            case REST -> settle(Math.max(1, ticks));
            case CRAWL -> {
                legsLeft = 2;
                toState(CRAWL, 0);
                if (!nextLeg()) { settle(SHORT_REST); return false; }
            }
            case ALERT, HUNT -> {
                if (who == null || !huntable(who)) return false;
                startAlert(who);
                if (s == HUNT) toState(HUNT, 0);
            }
            case REAR, LUNGE -> {
                if (who == null || !huntable(who)) return false;
                strikeCooldown = 0;
                startStrike(who);
            }
            case RETREAT -> { retreatUntil = level().getGameTime() + RETREAT_TICKS; startRetreat(who); }
            default -> { return false; }
        }
        return true;
    }

    public void trace(int ticks) { trace = Math.max(0, ticks); }

    /**
     * For /remix giant roam: crawls leg after leg for {@code ticks} with no rests, counting how well it holds on (see
     * {@link GiantRoamTest}); 0 ends a check under way. The reply says how it has gone so far.
     */
    public String roam(int ticks) {
        if (ticks <= 0) {
            String r = roamTest.report();
            roamTest.left = 0;
            ExplorationRemix.LOGGER.info("[giant roam] " + r);
            return r;
        }
        roamTest.start(ticks);
        if (state() == REST) toState(REST, 0);
        return "Roaming for " + ticks + " ticks; the totals go to the server log at the end";
    }

    double movedLastTick() { return movedLast; }
    /** For /remix giant roam: whether its box touches rock on any side (or stands, or swims). */
    boolean boxOnRock() {
        if (onGround() || isInWater()) return true;
        AABB box = getBoundingBox();
        for (Direction d : Direction.values()) if (touching(box, d)) return true;
        return false;
    }
    @Nullable Vec3 footPoint() { return foot; }

    /**
     * For /remix giant hold (pictures of its strike): holds it {@code k} (0 to 1) through the strike's phase {@code s}
     * (REAR, LUNGE or RECOVER), striking at {@code aim}, until let go ({@link #letGo}). False for another state.
     */
    public boolean hold(byte s, float k, Vec3 aim) {
        if (s != REAR && s != LUNGE && s != RECOVER) return false;
        stopDead();
        setAim(aim);
        toState(s, 1000);
        entityData.set(DATA_HELD, Mth.clamp(k, 0.0F, 1.0F));
        dormant = false;
        return true;
    }

    /** Lets go of a held strike: its front sinks back, and it rests a while. */
    public void letGo() {
        if (entityData.get(DATA_HELD) < 0.0F) return;
        entityData.set(DATA_HELD, -1.0F);
        toState(RECOVER, RECOVER_TICKS);
    }

    /** For /remix giant strike: it rears and strikes at {@code t} now, whatever it was doing. */
    public void strikeAt(LivingEntity t) {
        entityData.set(DATA_HELD, -1.0F);
        strikeCooldown = 0;
        dormant = false;
        startStrike(t);
    }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!level().isClientSide && dormantNow()) return;
        super.tick();
        if (!level().isClientSide && isAlive()) {
            movedLast = Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
            if (airTicks > 0) airDist += movedLast;
            updateForward();
            if (roamTest.on()) roamTest.tick(this);
        }
        if (level().isClientSide) updateClientRock();
        followHead();
        if (level().isClientSide) clientTick();
        moveBody();
    }

    /** Server: whether it lies dormant this tick: resting on its rock, its body laid, unhurt, with no player near. */
    private boolean dormantNow() {
        if (state() != REST || !body.laid() || !isAlive() || hurtTime > 0 || isOnFire() || isInWater() || isInLava()
                || surface() == null || isPassenger() || trace > 0 || roamTest.on()) return dormant = false;
        if ((tickCount + getId()) % WAKE_LOOK == 0) {
            boolean near = false;
            for (Player p : level().players()) if (p.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE) { near = true; break; }
            dormant = !near;
        }
        return dormant;
    }

    /** The head's new place goes on the trail (laid afresh behind it on the rock where it lands after a fall). */
    private void followHead() {
        boolean client = level().isClientSide;
        Direction rock = client ? clientRock : poseRock();
        Vec3 up = normal(rock);
        if (!body.laid()) {
            foot = null;
            Vec3 f = foot(rock);
            Vec3 fwd = client ? clientForward : forward;
            body.lay(f.x, f.y, f.z, -fwd.x, -fwd.y, -fwd.z, footUp.x, footUp.y, footUp.z);
            placeSections();
            if (client) poseHead(clientForward, up);
            return;
        }
        if (rock != null && airRun > AIR_REACH && (!client || touching(getBoundingBox(), rock))) {
            airRun = 0.0;
            foot = null;
            Vec3 f = foot(rock);
            Vec3 back = forward.scale(-1.0);
            body.lay(f.x, f.y, f.z, back.x, back.y, back.z, footUp.x, footUp.y, footUp.z);
            return;
        }
        Vec3 f = foot(rock);
        body.record(f.x, f.y, f.z, footUp.x, footUp.y, footUp.z);
        if (rock == null && !isInWater()) airRun += Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
        else if (rock == null || airRun <= AIR_REACH) airRun = 0.0;
    }

    private void moveBody() {
        if (!body.laid()) return;
        Vec3 fwd = level().isClientSide ? clientForward : forward;
        body.follow(fwd.x, fwd.y, fwd.z);
        placeSections();
        if (trace > 0 && !level().isClientSide) {
            trace--;
            Vec3 f = foot == null ? Vec3.ZERO : foot;
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant trace] %d %s on %s, foot %.2f %.2f %.2f, step %.3f, %s",
                    tickCount, STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, state()))], surface() == null ? "nothing" : surface().getName(),
                    f.x, f.y, f.z, body.lastStep(), body.moves()));
        }
    }

    // ------------------------------------------------------------------------------------------------ client

    /**
     * The rock it clings to as the client sees it (as the Skein's: the client's box lags the server's), with the
     * server's reach: the side the server poses it on while that is in reach of the client's box, else the one it had.
     */
    private void updateClientRock() {
        AABB box = getBoundingBox();
        Direction synced = poseRock();
        if (synced == null) {
            clientRock = !isInWater() && clientRock != null && inReach(box, clientRock) ? clientRock : null;
            return;
        }
        if (inReach(box, synced)) { clientRock = synced; return; }
        if (clientRock != null && inReach(box, clientRock)) return;
        if (touching(box, Direction.DOWN)) { clientRock = Direction.DOWN; return; }
        Direction any = clingTo(level(), this, box, getDeltaMovement());
        clientRock = any != null ? any : synced;
    }

    /** Whether side {@code d} still holds the box: the floor under it (or close under it), a wall or ceiling in reach. */
    private boolean inReach(AABB box, Direction d) {
        return d == Direction.DOWN ? touching(box, d) || floorBelow(box) : rockBeside(level(), this, box, d, CLING_REACH);
    }

    private void clientTick() {
        runO = run; legPhaseO = legPhase; glowO = glow; liftO = lift; jawO = jaw; rippleO = ripple; shudderO = shudder;
        System.arraycopy(feelerYaw, 0, feelerYawO, 0, 2);
        System.arraycopy(feelerPitch, 0, feelerPitchO, 0, 2);
        headOrientO.set(headOrient);
        clientStateTicks++;
        double moved = body.lastStep();
        boolean moving = moved > 0.02;
        run += ((moving ? 1.0F : 0.0F) - run) * 0.2F;
        // the legs' wave keeps pace with the ground it covers (each foot set down where it is carried back from)
        legPhase += (float) (moved * 2.1);
        byte s = state();
        float liftT = s == ALERT ? 1.0F : s == HUNT ? 0.35F : 0.0F;
        lift += (liftT - lift) * 0.12F;
        float jawT = switch (s) {
            case REAR -> 0.6F + 0.4F * (float) strikeProgress(0.0F);
            case LUNGE -> 1.0F;
            case RECOVER -> 0.3F;
            case HUNT, ALERT -> 0.2F + 0.1F * Mth.sin(tickCount * 0.3F);
            default -> 0.05F;
        };
        jaw += (jawT - jaw) * 0.35F;
        if (s == REAR) shudder = Math.max(shudder, 0.2F + 0.6F * (float) strikeProgress(0.0F));
        else shudder *= 0.8F;
        // its tips glow steadily enough to show it across the rift, brighter as it hunts and brightest as it strikes
        float glowT = isDeadOrDying() ? 0.0F : switch (s) {
            case REAR, LUNGE -> 1.0F;
            case ALERT, HUNT, RECOVER, SEARCH -> 0.9F;
            case CRAWL, RETREAT -> 0.8F;
            default -> 0.75F;
        };
        glow += (glowT - glow) * (glowT > glow ? 0.15F : 0.03F);
        feelers(moving, s == HUNT || s == ALERT || s == SEARCH);
        ripples(moving);
        orientHead();
    }

    /** The long feelers sweep about slowly, now and then flicking; hunting, they reach ahead. */
    private void feelers(boolean moving, boolean hunting) {
        for (int i = 0; i < 2; i++) {
            if (--feelerTimer[i] <= 0) {
                feelerTimer[i] = 8 + random.nextInt(moving ? 10 : 30);
                feelerYawT[i] = hunting ? -0.15F + (random.nextFloat() - 0.5F) * 0.3F
                        : moving ? 0.25F + (random.nextFloat() - 0.5F) * 0.3F : (random.nextFloat() - 0.5F) * 0.9F;
                feelerPitchT[i] = hunting ? -0.25F + (random.nextFloat() - 0.5F) * 0.2F : (random.nextFloat() - 0.5F) * 0.6F;
            }
            feelerYaw[i] += (feelerYawT[i] - feelerYaw[i]) * 0.12F;
            feelerPitch[i] += (feelerPitchT[i] - feelerPitch[i]) * 0.12F;
        }
    }

    /** Now and then, lying still, a slow ripple runs down its legs from head to tail. */
    private void ripples(boolean moving) {
        if (ripple >= 0) {
            ripple += 0.35F;
            if (ripple > PLATES + 2 || moving) ripple = rippleO = -1;
        } else if (!moving && run < 0.1F && !isDeadOrDying() && random.nextInt(160) == 0) {
            ripple = rippleO = 0;
        }
    }

    /** Its head's turn: belly to its rock, pointing the way the server says, turning slowly. */
    private void orientHead() {
        Vec3 upT = normal(clientRock);
        if (clientUp.dot(upT) < -0.99) clientUp = clientUp.add(clientForward.cross(clientUp).scale(0.3));
        clientUp = clientUp.lerp(upT, 0.25);
        if (clientUp.lengthSqr() < 1.0E-4) clientUp = upT;
        clientUp = clientUp.normalize();
        Vector3f sf = entityData.get(DATA_FORWARD);
        Vec3 want = new Vec3(sf.x, sf.y, sf.z);
        if (clientForward.dot(want) < -0.99) clientForward = clientForward.add(clientUp.cross(clientForward).scale(0.3));
        clientForward = clientForward.lerp(want, HEAD_TURN);
        Vec3 f = clientForward.subtract(clientUp.scale(clientForward.dot(clientUp)));
        if (f.lengthSqr() < 1.0E-4) f = Math.abs(clientUp.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        clientForward = f.normalize();
        headOrient.set(SkeinEntity.basis(clientForward, clientUp));
        if (tickCount < 2) headOrientO.set(headOrient);
    }

    private void poseHead(Vec3 fwd, Vec3 up) {
        Vec3 u = up.normalize();
        Vec3 f = fwd.subtract(u.scale(fwd.dot(u)));
        if (u.lengthSqr() < 0.5 || f.lengthSqr() < 1.0E-4) return;
        clientUp = u;
        clientForward = f.normalize();
        headOrient.set(SkeinEntity.basis(clientForward, clientUp));
        headOrientO.set(headOrient);
    }

    public float run(float pt) { return Mth.lerp(pt, runO, run); }
    public float legPhase(float pt) { return Mth.lerp(pt, legPhaseO, legPhase); }
    public float glow(float pt) { return Mth.lerp(pt, glowO, glow); }
    public float lift(float pt) { return Mth.lerp(pt, liftO, lift); }
    public float jaw(float pt) { return Mth.lerp(pt, jawO, jaw); }
    public float shudder(float pt) { return Mth.lerp(pt, shudderO, shudder); }
    public float ripple(float pt) { return ripple < 0 ? -1 : rippleO < 0 ? ripple : Mth.lerp(pt, rippleO, ripple); }
    public float feelerYaw(int i, float pt) { return Mth.lerp(pt, feelerYawO[i], feelerYaw[i]); }
    public float feelerPitch(int i, float pt) { return Mth.lerp(pt, feelerPitchO[i], feelerPitch[i]); }

    // ------------------------------------------------------------------------------------------------ the sections

    /** One section of its long body: a box on its trail that can be hit, handing the damage to the giant. */
    public static final class Section extends PartEntity<GiantSkeinEntity> {
        public final int index;
        private final EntityDimensions size;

        Section(GiantSkeinEntity parent, int index, float width, float height) {
            super(parent);
            this.index = index;
            this.size = EntityDimensions.scalable(width, height);
            refreshDimensions();
        }

        @Override protected void defineSynchedData() { }
        @Override protected void readAdditionalSaveData(CompoundTag tag) { }
        @Override protected void addAdditionalSaveData(CompoundTag tag) { }
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
