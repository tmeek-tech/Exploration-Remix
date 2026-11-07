package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbMoveControl;
import com.explorationremix.creature.climb.ClimbNavigation;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.NetworkHooks;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/**
 * The Skein: the Blue Reaches' cave crawler. A long, low, many-legged thing of overlapping cobalt-blue shell plates,
 * about eight blocks from the wedge of its head to the spurs of its tail, a seam of cyan light down its back. Most of
 * the time it lies motionless on a floor, a wall or the ceiling, only its feelers twitching; now and then it goes out
 * round the cave in erratic bursts, a cockroach's way: a dash, a dead stop, a snap to a new heading, another dash
 * ({@link SkeinGait}). It runs up walls and across ceilings as readily as over floors (the climbing pathfinder and
 * move control the Mossback uses, in creature.climb), and its body follows its head's path ({@link SkeinTrail}),
 * pouring over edges and round corners: along a smooth curve through it ({@link SkeinSpine}), so however sharply the
 * head snaps round, the body comes after it in a curve and never folds.
 *
 * <p>It minds a player only when they come close: within about {@link #STRIKE_RANGE} blocks, if it can see or feel
 * them, it rears its front plates for a split second, lunges and bites, then skitters a few blocks off and freezes
 * again. It never chases: it strikes at nobody further than {@link #STRIKE_LEASH} from where it first struck. Hurt from
 * close by, it strikes back; from further off, it bolts. Hungry, it stalks a swarm of blue fireflies that has landed
 * near it ({@link FireflySwarm}), creeping up in short bursts and long freezes, strikes into it and eats a few; then it
 * is fed for a minute or three. It never goes into the Asurine Geodes, the Blue Reaches' refuge.
 *
 * <p>The head carries the main box; the body is {@link #SECTION_AT}.length sections along the trail, and one more for
 * its jaws ({@link Section}, Forge's multipart parts, as the ender dragon has), so any of it can be hit, and a hit on a
 * section hurts the Skein. The trail is saved with it and sent to each client that starts tracking it, so its body
 * comes back lying where it lay.
 */
public class SkeinEntity extends Monster implements ClimbMoveControl.Climber, IEntityAdditionalSpawnData {
    // ------------------------------------------------------------------------------------------------ tuning
    /** Movement speed is blocks a tick at a speed of 1 (ClimbMoveControl): its bursts run at about a player's sprint. */
    public static final double HEALTH = 60.0, ARMOR = 6.0, SPEED = 0.29, KNOCKBACK_RESISTANCE = 0.8;
    static final float BITE = 6.0F;
    /** It strikes at a player this near its jaws that it can see, or nearer than {@link #TOUCH} (or touching its body). */
    static final double STRIKE_RANGE = 4.0, TOUCH = 1.6;
    /** It never lunges at anyone further than this from where it first struck (and never chases at all). */
    static final double STRIKE_LEASH = 8.0;
    /**
     * After this long without striking at a player (or once it has gone its own way beyond its leash from where it
     * struck), a new encounter starts where it next strikes.
     */
    static final int ENCOUNTER_TICKS = 600;
    /**
     * A split second rearing (shorter when it strikes back), the lunge, and four seconds before it strikes again: one
     * standing in reach is bitten every few seconds, not worried to death in a breath.
     */
    static final int REAR_TICKS = 7, SNAP_REAR_TICKS = 3, SWARM_REAR_TICKS = 5, LUNGE_TICKS = 8, STRIKE_COOLDOWN = 80;
    static final double LUNGE_SPEED = 0.62;
    /** After a strike it skitters off this far before it freezes; hurt from afar, it bolts this far. */
    static final double AFTER_STRIKE_MIN = 3.0, AFTER_STRIKE_MAX = 6.0, BOLT_MIN = 10.0, BOLT_MAX = 20.0;
    /** A landed swarm this near draws it when hungry; it strikes into the swarm from this near. */
    static final double SWARM_SENSE = 12.0, SWARM_STRIKE = 2.6, SWARM_BITE = 1.4;
    /**
     * A stalk gives up after this long from its start (less than a swarm's longest stay, FireflySwarm.LAND_MAX, or it
     * never would), or once its bursts get nowhere; its outings are this long (it starts another while the swarm stays).
     */
    static final int STALK_GIVE_UP = 700;
    static final double STALK_BUDGET = 40.0;
    /** Ways it tries for a burst before it gives that pivot up (stalking only one: every way leads to the swarm). */
    static final int PATH_TRIES = 6;
    /**
     * Near the Deep Blue Rift's ledge path (this near it across, and this near its floor up or down) it now and then
     * sets out along the path as its rest ends (this share of them, and once a journey has started, none for a minute
     * or two after): to a spot this far along it, either way, keeping this far from its ends (and at least this far to
     * go, or it does not set out). It goes in runs of at most this many blocks, is there once within this far of it,
     * and gives the journey up after this long.
     */
    static final double JOURNEY_REACH = 16.0, JOURNEY_RISE = 12.0, JOURNEY_NEAR = 40.0, JOURNEY_FAR = 90.0, JOURNEY_END_GAP = 6.0,
            JOURNEY_LEAST = 24.0, JOURNEY_LEG = 14.0, JOURNEY_ARRIVE = 5.0, JOURNEY_CHANCE = 0.3;
    static final int JOURNEY_EVERY_MIN = 1200, JOURNEY_EVERY_MAX = 2400, JOURNEY_GIVE_UP = 2400;
    /**
     * The ways a journey's run tries in turn: how much of the run along the path it aims for, and how far that aim is
     * turned aside (degrees, the side drawn each snap). It goes straight on first, then shorter, then swerves round what
     * is in the way.
     */
    static final double[][] JOURNEY_TRIES = {{1, 0}, {0.5, 0}, {1, 35}, {1, -35}, {0.5, 70}, {0.5, -70}};
    /**
     * In the refuge it looks for the nearest open spot outside it on shells round it this far apart, out to this far,
     * with at most this many points to a shell (so they lie further apart far out; it looks again at every snap).
     */
    static final int WAY_OUT_STEP = 4, WAY_OUT_REACH = 48, WAY_OUT_POINTS = 200;
    /** A meal of fireflies keeps it fed for one to three minutes; it chews this long. */
    static final int MEAL_MIN = 1200, MEAL_MAX = 3600, EAT_TICKS = 50;
    /** How far and how long it may touch nothing (rounding an edge) before it falls (as the Mossback). */
    static final double AIR_REACH = 1.5;
    static final int AIR_GRACE = 40;
    /** Landed: how far past its run through the air it looks back along its trail for the way its body lay; how much
     * keeping near that way counts against open rock (in half blocks of it) when it lays its body afresh. */
    static final double LANDING_LOOK_BACK = 2.0, LANDING_KEEP_WAY = 3.0;
    /** Half blocks of open rock a spawn spot needs behind the head ({@link #openLength}): room for most of its body. */
    public static final int BODY_ROOM = 10;
    /** A patter of feet every this many blocks it runs. */
    static final double PATTER_EVERY = 1.7;
    /** How much of the way to its new heading the drawn head turns each tick. */
    static final double HEAD_SNAP = 0.75;
    /**
     * How far below its box it feels for the floor under its head: its box is wide, and held up by one end while its
     * head is over the floor beyond a step down, or below a step up.
     */
    static final double FLOOR_REACH = 1.1;
    /**
     * How much of a jump in the point under its head is made up each tick (where it goes onto a wall, say, the middle of
     * its box's side is far from the middle of its bottom): the point comes round to its new place over a few ticks.
     */
    static final double FOOT_EASE = 0.4;
    /** A jump in it longer than this (blocks) is a teleport, not eased. */
    static final double FOOT_JUMP = 1.6;

    // ------------------------------------------------------------------------------------------------ the body
    /** Its plates (the last one the tail's), each this far back along its body from the point under its head. */
    public static final int PLATES = 23;
    public static final double PLATE_START = 0.15, PLATE_STEP = 0.30;
    /** Its jaws are this far ahead of the point under its head. */
    public static final double HEAD_REACH = 0.8;
    /** Its length, head to spur tips (blocks). */
    public static final double BODY_LENGTH = PLATE_START + PLATE_STEP * (PLATES - 1) + 0.6;
    /** The trail it keeps: the whole body and the stretch behind it that the curve its body lies along looks to. */
    public static final double TRAIL_KEEP = BODY_LENGTH + 2.4;
    /** The hit sections along the body: how far back along it each lies, and its box (the head has the main box). */
    static final double[] SECTION_AT = {1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 6.9};
    static final float[] SECTION_W = {1.25F, 1.15F, 1.05F, 0.95F, 0.85F, 0.75F, 0.6F},
            SECTION_H = {0.55F, 0.52F, 0.5F, 0.47F, 0.45F, 0.42F, 0.38F};
    /**
     * The stretch of trail the sections' boxes cover between them: from just inside the head's box (heading up or down
     * a wall, that box covers only its height of the body) to the tips of its spurs. Each box runs from half way to the
     * section before to half way to the one after.
     */
    static final double SECTIONS_FROM = 0.3, SECTIONS_TO = 7.3;
    /** Where each section's box starts and ends along the trail, after the head (0): see {@link #SECTIONS_FROM}. */
    private static final double[] SECTION_ENDS = sectionEnds();
    /**
     * The jaws' part: from the tips of its mandibles, this far ahead of the point under its head, back to this far
     * ahead, inside the head's box (heading up or down a wall, the head's box does not reach its jaws). The last part.
     */
    static final double JAWS_TIP = 1.0, JAWS_BACK = 0.25;
    static final float JAWS_W = 0.65F, JAWS_H = 0.4F;
    static final int JAWS = SECTION_AT.length;
    /**
     * Where along its trail its body is saved, and sent to a client that starts tracking it: its head, each of its
     * plates, and the end of what it keeps. A body laid back through these lies as it did, each plate where it was.
     */
    static final double[] KEPT_AT = keptAt();
    /** The joints of the curve its body lies along ({@link SkeinSpine}): far enough back for its tail and its last section. */
    public static final int SPINE_JOINTS = (int) Math.ceil(Math.max(PLATE_START + PLATE_STEP * (PLATES - 1) + 0.3, SECTIONS_TO) / SkeinSpine.LINK) + 1;

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

    public static final ResourceKey<Biome> REFUGE = ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, "asurine_geodes"));
    /**
     * The game's entity id counter (Entity.ENTITY_COUNTER, by its SRG name): ids for the sections are taken from it
     * right after the Skein's own, as the ender dragon takes its parts', so the client numbers them the same way.
     */
    @Nullable private static final AtomicInteger IDS = idCounter();

    // ------------------------------------------------------------------------------------------------ state
    public static final byte STILL = 0, SKITTER = 1, STALK = 2, REAR = 3, LUNGE = 4, BOLT = 5, EAT = 6, JOURNEY = 7;
    /** The state names, by id, for /remix skein. */
    public static final String[] STATE_NAMES = {"still", "skitter", "stalk", "rear", "lunge", "bolt", "eat", "journey"};
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it falls or swims. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.BYTE);
    /** The way its head points along its rock (the server's {@code forward}). */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(SkeinEntity.class, EntityDataSerializers.VECTOR3);

    private final Section[] sections;
    private final SkeinTrail trail = new SkeinTrail(TRAIL_KEEP);
    private final SkeinSpine spine = new SkeinSpine(SPINE_JOINTS);
    private double airRun;                   // how far it has gone touching nothing (each side reckons its own)
    // the point under its head where its trail runs, and its rock's normal there: each side its own (see foot)
    @Nullable private Vec3 foot;
    private Vec3 footUp = new Vec3(0, 1, 0), footLag = Vec3.ZERO;
    private int trace;                       // ticks left to log its body's moves (/remix skein trace)
    private int laidAt = -1;                 // for the trace: the tick its trail was last laid afresh, and the way back from its head
    private Vec3 laidBack = Vec3.ZERO;
    private final SkeinGait gait = new SkeinGait();
    private final DoubleSupplier rnd = () -> random.nextDouble();
    private int stateTicks, stateLength;
    private Vec3 forward = new Vec3(0, 0, 1);
    @Nullable private Vec3 pivotWant;        // PIVOT: the way it snaps to
    @Nullable private Path pendingPath;      // PIVOT: the way the coming burst runs
    private double burstMoved;               // BURST: how far it has gone in this one
    @Nullable private Vec3 threat;           // BOLT: what it gets away from
    @Nullable private FireflySwarm prey;     // STALK, and a strike into a swarm
    private long stalkSince;                 // STALK: when it set out after this swarm (its outings after it do not reset it)
    @Nullable private FireflySwarm shunned;  // a swarm it gave up on, left be while it sits where it was then
    @Nullable private BlockPos shunnedAt;
    private boolean refugeTried;             // in the refuge: it has set out for the way out (again only once a rest ends)
    @Nullable private Vec3 journeyTo;        // JOURNEY: where it is going
    @Nullable private Route journeyRift;     // JOURNEY: the rift whose ledge path it follows there
    private double journeyAt, journeyEnd;    // JOURNEY: how far along the path it has got, and where it is going
    private int journeyWay;                  // JOURNEY: which way along the path (1 or -1)
    private long journeyFrom;                // JOURNEY: when it set out
    private long nextJourney;                // the game time before which it sets out on no journey
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
        this.sections = new Section[SECTION_AT.length + 1];
        for (int i = 0; i < SECTION_AT.length; i++) sections[i] = new Section(this, i, SECTION_W[i], SECTION_H[i]);
        sections[JAWS] = new Section(this, JAWS, JAWS_W, JAWS_H);
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
    /** The chain its body lies along, moved on each tick (see SkeinSpine). */
    public SkeinSpine spine() { return spine; }
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
    /** Its body trails eight blocks behind its box: keep drawing it while any of it is on screen. */
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

    /**
     * Sets each section's box over its stretch of the body ({@link #SECTIONS_FROM}), along the curve it is drawn along,
     * its underside on the rock there, thin across the rock and as wide as its part of the body whichever way the body
     * runs: a box kept upright would cover only its own height of a body running straight up a wall, and a blow at the
     * rest would go through to the rock. The jaws' part goes ahead of the head, the way the server has it heading.
     */
    private void placeSections() {
        double[] at = new double[6 * SECTION_ENDS.length], box = new double[6];
        Vector3f sf = entityData.get(DATA_FORWARD);
        if (!spine.on(trail)) spine.lay(trail);
        spine.sampleAll(SECTION_ENDS, 1.0, at);
        for (int i = 0; i < SECTION_AT.length; i++) {
            SkeinTrail.stretchBox(at, 6 * (i + 1), 6 * (i + 2), sections[i].getBbWidth(), sections[i].getBbHeight(), box);
            fit(sections[i], box);
        }
        // along its rock at the head, or else the way the body runs into the head
        double nx = at[3], ny = at[4], nz = at[5], along = sf.x * nx + sf.y * ny + sf.z * nz;
        double fx = sf.x - nx * along, fy = sf.y - ny * along, fz = sf.z - nz * along;
        if (fx * fx + fy * fy + fz * fz < 1.0E-4) { fx = at[0] - at[6]; fy = at[1] - at[7]; fz = at[2] - at[8]; }
        double fl = Math.max(1.0E-6, Math.sqrt(fx * fx + fy * fy + fz * fz));
        fx /= fl; fy /= fl; fz /= fl;
        double[] jaws = {at[0] + fx * JAWS_TIP, at[1] + fy * JAWS_TIP, at[2] + fz * JAWS_TIP, nx, ny, nz,
                at[0] + fx * JAWS_BACK, at[1] + fy * JAWS_BACK, at[2] + fz * JAWS_BACK, nx, ny, nz};
        SkeinTrail.stretchBox(jaws, 0, 6, JAWS_W, JAWS_H, box);
        fit(sections[JAWS], box);
    }

    /** Moves {@code s} to the box {minX, minY, minZ, maxX, maxY, maxZ}, its feet at the box's bottom middle. */
    private static void fit(Section s, double[] b) {
        s.xo = s.getX(); s.yo = s.getY(); s.zo = s.getZ();
        s.xOld = s.xo; s.yOld = s.yo; s.zOld = s.zo;
        s.setPos((b[0] + b[3]) / 2.0, b[1], (b[2] + b[5]) / 2.0);
        // after setPos, which sets the upright box its size gives (sections never tick, so it stays till the next)
        s.setBoundingBox(new AABB(b[0], b[1], b[2], b[3], b[4], b[5]));
    }

    /**
     * Whether it feels {@code e} against its body: {@code e}'s box against the head's box or a section's, with a clear
     * line from that part to it (it feels nobody through a door, a pane, bars or rock). Not by the jaws' box: that
     * reaches past its head, through a trapdoor it presses on, and a player at its jaws is near by the eyes' clip.
     */
    private boolean touches(Entity e) {
        AABB b = e.getBoundingBox();
        if (getBoundingBox().inflate(0.4).intersects(b) && clearTo(getBoundingBox().getCenter(), e)) return true;
        for (Section s : sections)
            if (s.index != JAWS && s.getBoundingBox().inflate(0.4).intersects(b) && clearTo(s.getBoundingBox().getCenter(), e)) return true;
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

    /** The point on its rock under the middle of its head's box (the side of its box against a wall). */
    public Vec3 feet(Vec3 up) {
        double w = getBbWidth() / 2.0, h = getBbHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        return position().add(0, h, 0).subtract(up.scale(ext));
    }

    /**
     * Where its trail runs, under its head, on {@code rock}: on a floor, the floor under the middle of its box, which
     * may be lower than the box (it is stepping down off an edge, its box held up by its back end) or higher (it is
     * stepping up, the client's box still on its way); elsewhere the middle of its box's side against the rock.
     */
    private Vec3 footOn(@Nullable Direction rock) {
        if (rock == Direction.DOWN) {
            AABB b = getBoundingBox();
            double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
            double y = floorUnder(level(), cx, b.minY + b.getYsize() / 2.0, b.minY - FLOOR_REACH, cz);
            return new Vec3(cx, Double.isNaN(y) ? b.minY : y, cz);
        }
        return feet(normal(rock));
    }

    /**
     * The point under its head this tick, where its trail goes next, and (in footUp) its rock's normal there. It goes
     * as footOn says, but where that jumps (from the bottom of its box to its side as it goes onto a wall, or up a step
     * all at once), the jump is made up over a few ticks ({@link #FOOT_EASE}): the head and the body after it come round
     * onto the new rock, never flick to it and back.
     */
    private Vec3 foot(@Nullable Direction rock) {
        Vec3 want = footOn(rock), upT = normal(rock);
        if (foot == null) {
            footLag = Vec3.ZERO;
        } else {
            // where it would be had it gone on with its box as it was (on a floor, across it only: the height is the
            // floor's); the rest is a jump, eased out (or a teleport, gone at once)
            Vec3 moved = position().subtract(xo, yo, zo);
            if (rock == Direction.DOWN) moved = new Vec3(moved.x, 0.0, moved.z);
            footLag = foot.add(moved).subtract(want).scale(1.0 - FOOT_EASE);
            if (footLag.lengthSqr() < 1.0E-8 || footLag.lengthSqr() > FOOT_JUMP * FOOT_JUMP) footLag = Vec3.ZERO;
            if (footUp.dot(upT) < -0.99) footUp = footUp.add(forward.cross(footUp).scale(0.3));
            upT = footUp.lerp(upT, FOOT_EASE);
            if (upT.lengthSqr() < 1.0E-4) upT = normal(rock);
        }
        footUp = upT.normalize();
        foot = outOfRock(want.add(footLag), footUp);
        return foot;
    }

    /**
     * {@code p} pushed out along {@code up} to the face of the rock it lies in, if it lies in rock: a jump eased over an
     * outer edge (the top of a step, say) would cut through its corner, and the body after it.
     */
    private Vec3 outOfRock(Vec3 p, Vec3 up) {
        for (int i = 0; i <= 20; i++) {
            Vec3 q = p.add(up.scale(0.03 * i));
            if (!inRock(q.add(up.scale(0.02)))) return q;
        }
        return p;
    }

    /** Whether {@code p} lies inside the rock of the block it is in (off the edge of what is loaded, not). */
    private boolean inRock(Vec3 p) {
        BlockPos at = BlockPos.containing(p);
        if (!level().isLoaded(at)) return false;
        VoxelShape shape = level().getBlockState(at).getCollisionShape(level(), at);
        if (shape.isEmpty()) return false;
        double x = p.x - at.getX(), y = p.y - at.getY(), z = p.z - at.getZ();
        for (AABB b : shape.toAabbs()) if (b.contains(x, y, z)) return true;
        return false;
    }

    /**
     * The top of the rock under (x, z) highest below {@code from} (or of the rock {@code from} is in), no lower than
     * {@code to}: NaN for none, or a chunk not loaded.
     */
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
        // off the edge of a step down (its wide box over the step): it drops onto the floor below, not onto the step
        else if (cur == Direction.DOWN && floorBelow(box)) next = Direction.DOWN;
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

    /** Whether there is floor under the middle of {@code box}, no further below it than {@link #FLOOR_REACH}. */
    private boolean floorBelow(AABB box) {
        double cx = (box.minX + box.maxX) / 2.0, cz = (box.minZ + box.maxZ) / 2.0;
        return !Double.isNaN(floorUnder(level(), cx, box.minY + box.getYsize() / 2.0, box.minY - FLOOR_REACH, cz));
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
        } else if (gait.phase() == SkeinGait.Phase.PIVOT && pivotWant != null && (s == SKITTER || s == BOLT || s == STALK || s == JOURNEY)) {
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
        if (s != JOURNEY) { journeyTo = null; journeyRift = null; }
    }

    @Override
    protected void customServerAiStep() {
        updateSurface();
        // in water deeper than its legs it swims up and keeps its head out, as other land beasts do
        if (isInWater() && getFluidHeight(FluidTags.WATER) > getFluidJumpThreshold()) getJumpControl().jump();
        stateTicks++;
        if (hunger > 0) hunger--;
        if (strikeCooldown > 0) strikeCooldown--;
        // the encounter is over a while after its last strike, or once it has gone its own way (it never chases) beyond
        // its leash from where it struck: a player it then meets starts a new one
        byte s = state();
        if (anchor != null && (level().getGameTime() - lastStrike > ENCOUNTER_TICKS
                || s != REAR && s != LUNGE && s != BOLT && position().distanceTo(anchor) > STRIKE_LEASH)) anchor = null;
        switch (state()) {
            case STILL -> still();
            case SKITTER, BOLT -> outing();
            case JOURNEY -> journey();
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
            // its rest is over: out it goes, snapping to its first heading; by the rift, now and then far along it
            if (level().getGameTime() >= nextJourney && random.nextDouble() < JOURNEY_CHANCE && startJourney()) return;
            toState(SKITTER, 0);
            beginPivot();
        }
    }

    /**
     * Sets out along the Deep Blue Rift's ledge path, when it is by one: {@link #JOURNEY_NEAR} to {@link #JOURNEY_FAR}
     * blocks along it, the way there is room for (either way when both have), short of its ends and of unloaded
     * chunks. False when it is by no rift, or there is too little way to go.
     */
    private boolean startJourney() {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null) return false;
        Vec3 c = position();
        Route rift = null;
        double at = 0, bestD = Double.MAX_VALUE;
        for (Route r : t.network.riftsNear(c.x, c.z, JOURNEY_REACH)) {
            double s = r.walkArcNear(c.x, c.y, c.z, JOURNEY_REACH, JOURNEY_RISE);
            if (Double.isNaN(s)) continue;
            double[] q = r.pathAt(s);
            double d = Mth.square(q[0] - c.x) + Mth.square(q[1] - c.z);
            if (d < bestD) { bestD = d; rift = r; at = s; }
        }
        if (rift == null) return false;
        double want = JOURNEY_NEAR + random.nextDouble() * (JOURNEY_FAR - JOURNEY_NEAR);
        double ahead = rift.walkLength - JOURNEY_END_GAP - at, back = at - JOURNEY_END_GAP;
        int way = ahead >= want && back >= want ? (random.nextBoolean() ? 1 : -1) : ahead >= back ? 1 : -1;
        double go = Math.min(want, way > 0 ? ahead : back);
        // never on into chunks that are not loaded
        while (go >= JOURNEY_LEAST && !loadedAt(rift.pathAt(at + way * go))) go -= 8.0;
        if (go < JOURNEY_LEAST) return false;
        journeyRift = rift;
        journeyAt = at;
        journeyWay = way;
        journeyEnd = at + way * go;
        journeyTo = pathPoint(rift, journeyEnd);
        journeyFrom = level().getGameTime();
        nextJourney = journeyFrom + JOURNEY_EVERY_MIN + random.nextInt(JOURNEY_EVERY_MAX - JOURNEY_EVERY_MIN + 1);
        // its way there is never straight: room for the swerves, after which it gives up
        gait.startOuting(SkeinGait.Mood.JOURNEY, go * 1.8 + 20.0, rnd);
        toState(JOURNEY, 0);
        beginPivot();
        return true;
    }

    /** Where it stands on the rift's ledge path {@code s} blocks along it: {x, feet y, z} (see {@link Route#feetAt}). */
    private static Vec3 pathPoint(Route r, double s) {
        double[] q = r.pathAt(s);
        return new Vec3(q[0], Route.feetAt(q[2]), q[1]);
    }

    private boolean loadedAt(double[] q) {
        int x = Mth.floor(q[0]), z = Mth.floor(q[1]);
        return level().hasChunksAt(x - 2, z - 2, x + 2, z + 2);
    }

    /**
     * On a journey: it runs on along the rift's ledge path towards where it is going, striking at whoever comes near
     * as it does roaming, but paying swarms no mind, until it gets there, its way runs out, or it has been at it too
     * long.
     */
    private void journey() {
        if (tickCount % 2 == 0 && strikeAtPlayer()) return;
        if (journeyTo == null || journeyRift == null || level().getGameTime() - journeyFrom > JOURNEY_GIVE_UP) {
            settle();
            return;
        }
        Vec3 c = position();
        double s = journeyRift.walkArcNear(c.x, c.y, c.z, JOURNEY_REACH * 1.5, JOURNEY_RISE * 1.5);
        if (!Double.isNaN(s)) journeyAt = s;
        if (Math.abs(journeyEnd - journeyAt) < JOURNEY_ARRIVE || c.distanceToSqr(journeyTo) < JOURNEY_ARRIVE * JOURNEY_ARRIVE) {
            settle();
            return;
        }
        stepGait();
        if (!gait.outing()) settle();
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

    /**
     * It finds itself in the refuge (set down there, say): it goes out at once, never to come back (its roaming there
     * heads for the nearest way out, see beginPivot). Should that outing end with it still inside, it tries again only
     * when its rest is over, not every second.
     */
    private boolean leaveRefuge() {
        if (!isRefuge(blockPosition())) { refugeTried = false; return false; }
        if (refugeTried) return false;
        refugeTried = true;
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

    /**
     * A snap: it stops dead and picks the way of its coming burst; it turns its head that way over the pivot's ticks.
     * Roaming in the refuge, it first tries for the nearest way out. Stalking, it asks the way only once: the swarm is
     * where every snap leads, so a second try would only search the same way again. On a journey it tries straight on
     * along the rift's path first, then shorter, then swerving ({@link #JOURNEY_TRIES}).
     */
    private void beginPivot() {
        stopDead();
        pendingPath = null;
        Vec3 out = gait.mood() == SkeinGait.Mood.ROAM && isRefuge(blockPosition()) ? wayOutOfRefuge() : null;
        int tries = gait.mood() == SkeinGait.Mood.STALK ? 1 : PATH_TRIES;
        boolean journey = gait.mood() == SkeinGait.Mood.JOURNEY;
        double side = random.nextBoolean() ? 1.0 : -1.0;
        for (int i = 0; i < tries && pendingPath == null; i++) {
            if (i > 0 && !journey) gait.reroll(rnd);
            double[] j = JOURNEY_TRIES[Math.min(i, JOURNEY_TRIES.length - 1)];
            pendingPath = burstPath(i == 0 ? out : null, journey ? j[0] : 1.0, journey ? side * Math.toRadians(j[1]) : 0.0);
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
     * turn; bolting, the same away from what it flees; stalking, towards the swarm; with {@code toward} (the way out
     * of the refuge), towards that whatever the snap; on a journey, to the spot on the rift's ledge path a run on (of
     * up to {@link #JOURNEY_LEG} blocks, times {@code part}), turned {@code off} aside round it. Null when there is no
     * way there (rock, unloaded chunks, or a way through the refuge).
     */
    @Nullable
    private Path burstPath(@Nullable Vec3 toward, double part, double off) {
        Vec3 up = up();
        BlockPos goal;
        if (gait.mood() == SkeinGait.Mood.STALK) {
            if (prey == null) return null;
            goal = nodeNear(prey.centre(), 3);
        } else if (toward != null) {
            goal = nodeNear(toward, 3);
        } else if (gait.mood() == SkeinGait.Mood.JOURNEY) {
            if (journeyRift == null) return null;
            double left = Math.abs(journeyEnd - journeyAt);
            double run = Math.max(2.5, part * Math.min(Math.min(left, JOURNEY_LEG), gait.speed() * SPEED * gait.burstTicks() + 1.5));
            Vec3 aim = pathPoint(journeyRift, journeyAt + journeyWay * run).add(0, 0.35, 0), from = bodyCentre();
            if (off != 0.0) aim = from.add(rotate(aim.subtract(from), up, off));
            goal = nodeNear(aim, 3);
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
     * blocks; never reading an unloaded chunk, never in the refuge unless it is in there itself (set down by an egg or a
     * command: the nearest open spot outside may lie behind the geodes' rock, and roaming the refuge it finds the way
     * out through its tunnel, never to come back).
     */
    @Nullable
    private BlockPos nodeNear(Vec3 p, int r) {
        boolean inRefuge = isRefuge(blockPosition());
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
                    if (!inRefuge && isRefuge(n.offset(1, 0, 1))) continue;
                    bestD = d;
                    best = n;
                }
            }
        return best;
    }

    /**
     * Whether a path runs into the refuge. For one that finds itself in there any path will do: it heads for the way
     * out first and roams the refuge when that is shut, until it is out.
     */
    private boolean crossesRefuge(Path p) {
        if (isRefuge(blockPosition())) return false;
        for (int i = 0; i < p.getNodeCount(); i++) {
            Node n = p.getNode(i);
            if (isRefuge(new BlockPos(n.x + 1, n.y, n.z + 1))) return true;
        }
        return false;
    }

    /** The Asurine Geodes are the Blue Reaches' refuge: no hunter goes there. */
    public boolean isRefuge(BlockPos p) {
        return level().isLoaded(p) && level().getBiome(p).is(REFUGE);
    }

    /**
     * The nearest open spot outside the refuge (the middle of a block with nothing solid in it), looked for on shells
     * round it {@link #WAY_OUT_STEP} blocks apart out to {@link #WAY_OUT_REACH}, points spread evenly over each (a
     * Fibonacci sphere), the one nearest its own level on the nearest shell that has any; never reading an unloaded
     * chunk. Null when there is none so near.
     */
    @Nullable
    private Vec3 wayOutOfRefuge() {
        Vec3 c = bodyCentre();
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int r = WAY_OUT_STEP; r <= WAY_OUT_REACH; r += WAY_OUT_STEP) {
            int n = Math.min(WAY_OUT_POINTS, (int) Math.ceil(4.0 * Math.PI * Mth.square(r / (double) WAY_OUT_STEP)));
            Vec3 best = null;
            double bestRise = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double y = 1.0 - 2.0 * (i + 0.5) / n, flat = Math.sqrt(1.0 - y * y), a = i * golden;
                if (Math.abs(y) >= bestRise) continue;
                BlockPos p = BlockPos.containing(c.x + Math.cos(a) * flat * r, c.y + y * r, c.z + Math.sin(a) * flat * r);
                if (!level().isLoaded(p) || isRefuge(p) || !level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) continue;
                bestRise = Math.abs(y);
                best = Vec3.atCenterOf(p);
            }
            if (best != null) return best;
        }
        return null;
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
            // felt or seen, never through a door, a pane, bars or rock
            boolean near = touches(p) || (d < TOUCH && clearTo(eyes(), p)) || (d < STRIKE_RANGE && canSee(p));
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
        return !t.isInvisible() && clearTo(eyes(), t);
    }

    /** Whether there is a clear line from {@code from} to {@code t}'s eyes or its middle. */
    private boolean clearTo(Vec3 from, Entity t) {
        return clear(from, t.getEyePosition()) || clear(from, t.getBoundingBox().getCenter());
    }

    /** Whether a straight line from {@code from} to {@code to} meets nothing solid (rock, a door, a pane, bars). */
    private boolean clear(Vec3 from, Vec3 to) {
        HitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        return hit.getType() == HitResult.Type.MISS;
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
        // never past its leash from where it struck at a player (a swarm it strikes at wherever it is)
        if (!atSwarm && anchor != null && position().distanceTo(anchor) > STRIKE_LEASH) { afterStrike(aim); return; }
        // it bites only what it reaches in the open: not through a door, a pane, bars or rock
        if (atSwarm) {
            if (prey != null && mouth().distanceTo(prey.centre()) < SWARM_BITE && clear(eyes(), prey.centre())) { eatFrom(prey); return; }
        } else {
            LivingEntity t = getTarget();
            if (t != null && new AABB(mouth(), mouth()).inflate(0.7).intersects(t.getBoundingBox()) && clearTo(eyes(), t)) { bite(t); return; }
        }
        if (stateTicks >= stateLength) afterStrike(aim);
    }

    private void bite(LivingEntity t) {
        playSound(ERSounds.SKEIN_BITE.get(), 1.4F, 0.9F + random.nextFloat() * 0.2F);
        t.hurt(damageSources().mobAttack(this), BITE);
        afterStrike(t.position());
    }

    /**
     * The strike is over, bitten or missed: it skitters a few blocks off and freezes again. A swarm it struck at and
     * missed takes off all the same; only a strike at a player keeps the encounter (and its leash) going.
     */
    private void afterStrike(@Nullable Vec3 from) {
        Vec3 dm = getDeltaMovement();
        setDeltaMovement(dm.x * 0.2, dm.y, dm.z * 0.2);
        if (!atSwarm) lastStrike = level().getGameTime();
        else if (prey != null && prey.isAlive()) prey.scatter(mouth());
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
        startStalk(s);
        return true;
    }

    /** It sets out after swarm {@code s}, for {@link #STALK_GIVE_UP} at most however many outings it takes. */
    private void startStalk(FireflySwarm s) {
        prey = s;
        stalkSince = level().getGameTime();
        startOuting(STALK, SkeinGait.Mood.STALK, STALK_BUDGET);
    }

    /** It gives up on the swarm it stalks, and leaves it be while it sits where it is now. */
    private void shun(FireflySwarm s) {
        shunned = s;
        shunnedAt = s.anchor();
    }

    /** Whether it gave up on {@code s} where it sits (it forgets that once the swarm flies off, or lands elsewhere). */
    private boolean shuns(FireflySwarm s) {
        if (shunned != null && !(shunned.isAlive() && shunned.landed() && shunned.anchor().equals(shunnedAt))) {
            shunned = null;
            shunnedAt = null;
        }
        return s == shunned;
    }

    /** The nearest landed swarm within {@link #SWARM_SENSE} blocks, but not one it gave up on. */
    @Nullable
    private FireflySwarm nearestSwarm() {
        FireflySwarm best = null;
        double bestD = SWARM_SENSE * SWARM_SENSE;
        Vec3 c = bodyCentre();
        for (FireflySwarm s : level().getEntitiesOfClass(FireflySwarm.class, getBoundingBox().inflate(SWARM_SENSE))) {
            if (!stalkable(s) || shuns(s)) continue;
            double d = s.centre().distanceToSqr(c);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    private void stalk() {
        if (tickCount % 2 == 0 && strikeAtPlayer()) return;
        boolean gone = !stalkable(prey);
        if (gone || level().getGameTime() - stalkSince > STALK_GIVE_UP) {
            // it took off, or was eaten up; or it has crept after it long enough, and leaves it be
            if (!gone) shun(prey);
            gait.rest(SkeinGait.SHORT_REST_MIN + random.nextInt(SkeinGait.SHORT_REST_MAX - SkeinGait.SHORT_REST_MIN));
            settle();
            return;
        }
        // it strikes only at a swarm it has a clear line to, never through rock
        if (mouth().distanceTo(prey.centre()) < SWARM_STRIKE && clear(eyes(), prey.centre())) { startSwarmStrike(prey); return; }
        stepGait();
        if (gait.outing()) return;
        if (gait.gaveUp()) {
            // its bursts got nowhere: there is no way to it from here; it rests a little (the gait does) and leaves it be
            shun(prey);
            settle();
        } else startOuting(STALK, SkeinGait.Mood.STALK, STALK_BUDGET);    // the swarm is still there
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

    /**
     * Hurt by {@code by}: struck back at once if it is close (and not in the refuge), otherwise it bolts. Close is by
     * its jaws or within striking range of any of its body: a blade at its tail is as near as one at its head.
     */
    private void answer(LivingEntity by) {
        byte s = state();
        if (s == REAR || s == LUNGE) return;
        double d = Math.sqrt(by.getBoundingBox().distanceToSqr(mouth()));
        if ((d < STRIKE_RANGE + 0.5 || nearBody(by.getBoundingBox(), STRIKE_RANGE)) && !isRefuge(by.blockPosition())) {
            anchor = position();
            startStrike(by, SNAP_REAR_TICKS);
        } else {
            boltFrom(by.position(), BOLT_MIN + random.nextDouble() * (BOLT_MAX - BOLT_MIN));
        }
    }

    /** Whether box {@code b} comes within {@code r} of its body: the head's box or a section's. */
    private boolean nearBody(AABB b, double r) {
        if (gap(getBoundingBox(), b) < r) return true;
        for (Section s : sections) if (gap(s.getBoundingBox(), b) < r) return true;
        return false;
    }

    /** The distance between two boxes (0 where they meet or overlap). */
    static double gap(AABB a, AABB b) {
        double dx = Math.max(0.0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0.0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        double dz = Math.max(0.0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        layTrail(rock);
    }

    /** Its body laid straight behind its head along its rock. */
    private void layTrail(@Nullable Direction rock) {
        layTrail(rock, null);
    }

    /** Its body laid straight from its head along {@code back} on its rock (straight behind its head when null). */
    private void layTrail(@Nullable Direction rock, @Nullable Vec3 back) {
        foot = null;
        Vec3 f = foot(rock), up = footUp;
        Vec3 fwd = forward;
        if (level().isClientSide) {
            // the way the server has it heading (the client's own easing towards it has not begun)
            Vector3f sf = entityData.get(DATA_FORWARD);
            fwd = new Vec3(sf.x, sf.y, sf.z);
            if (fwd.lengthSqr() > 1.0E-4) clientForward = fwd.normalize();
        }
        Vec3 b = back != null ? back : fwd.scale(-1.0);
        trail.lay(f.x, f.y, f.z, b.x, b.y, b.z, up.x, up.y, up.z);
        laidAt = tickCount;
        laidBack = b;
        placeSections();
    }

    /**
     * Landed after a fall or a lunge: the way to lay its body on the rock it landed on. The way its body lay before it
     * left the rock, flattened onto this rock (its head may have turned in the air, to bolt, so straight behind the head
     * could put the body on the far side of it, or back into the wall it lunged off); of eight ways along the rock, the
     * one nearest that with the most open rock behind.
     */
    private Vec3 landingBack(Vec3 up) {
        Vec3 f = feet(up);
        double[] s = new double[6];
        trail.sample(airRun + LANDING_LOOK_BACK, s);
        Vec3 prefer = flatten(new Vec3(s[0] - f.x, s[1] - f.y, s[2] - f.z), up);
        if (prefer == null) prefer = flatten(forward.scale(-1.0), up);
        Vec3 a = Math.abs(up.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 b = up.cross(a);
        if (prefer == null) prefer = a;
        Vec3 best = prefer;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            Vec3 back = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
            double score = openBehind(level(), f, back.scale(-1.0), up) + LANDING_KEEP_WAY * back.dot(prefer);
            if (score > bestScore) { bestScore = score; best = back; }
        }
        return best;
    }

    /** {@code v} with its part along {@code up} taken out, at unit length; null when little is left. */
    @Nullable
    private static Vec3 flatten(Vec3 v, Vec3 up) {
        Vec3 f = v.subtract(up.scale(v.dot(up)));
        return f.lengthSqr() > 0.09 ? f.normalize() : null;
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
        Vec3 feet = placedFeet(type, p);
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

    /**
     * How many half blocks of open rock there are behind a Skein set down at {@code p} with the best heading {@link
     * #openHeading} finds. Its body is laid straight back from its head, and stays so until it has walked its length:
     * the spawner wants {@link #BODY_ROOM} of it, not a body laid into a step or out over a drop.
     */
    public static int openLength(Level level, EntityType<?> type, Placement p) {
        Vec3 feet = placedFeet(type, p);
        return openBehind(level, feet, openHeading(level, type, p, () -> 0.0), normal(p.rock()));
    }

    /** The point on its rock under the middle of the box of a Skein set down at {@code p}, as feet() has it. */
    private static Vec3 placedFeet(EntityType<?> type, Placement p) {
        Vec3 up = normal(p.rock());
        double w = type.getWidth() / 2.0, h = type.getHeight() / 2.0;
        double ext = Math.abs(up.x) * w + Math.abs(up.y) * h + Math.abs(up.z) * w;
        return p.pos().add(0, h, 0).subtract(up.scale(ext));
    }

    /** How many half blocks back from {@code feet} (against {@code fwd}) there is open air over rock, up to its length. */
    private static int openBehind(Level level, Vec3 feet, Vec3 fwd, Vec3 up) {
        int open = 0;
        for (double d = 0.5; d <= BODY_LENGTH; d += 0.5) {
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
        // its body as it lies, so that it does not come back laid straight through the rock
        float[] kept = kept();
        if (kept.length > 0) tag.put("Trail", newFloatList(kept));
        tag.putInt("Hunger", hunger);
        tag.putFloat("ForwardX", (float) forward.x);
        tag.putFloat("ForwardY", (float) forward.y);
        tag.putFloat("ForwardZ", (float) forward.z);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // (its position is read by now; without a trail, or a bad one, it lays one straight on its first tick)
        ListTag kept = tag.getList("Trail", Tag.TAG_FLOAT);
        float[] t = new float[kept.size()];
        for (int i = 0; i < t.length; i++) t[i] = kept.getFloat(i);
        trail.read(t, getX(), getY(), getZ());
        hunger = tag.getInt("Hunger");
        Vec3 f = new Vec3(tag.getFloat("ForwardX"), tag.getFloat("ForwardY"), tag.getFloat("ForwardZ"));
        if (f.lengthSqr() > 1.0E-4) {
            forward = f.normalize();
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        }
    }

    /**
     * Its body as it lies, at {@link #KEPT_AT} along it, as a trail to lay it along again (none before it has one): the
     * curve it lies in, not the way its head went, so the body read back lies as it did, not folded where it swung round.
     */
    private float[] kept() {
        if (!trail.laid()) return new float[0];
        if (!spine.on(trail)) spine.lay(trail);
        return spine.write(KEPT_AT, getX(), getY(), getZ());
    }

    /** Forge's spawn packet, so that it carries its body as it lies to each client that starts tracking it. */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /** The way its head points, and its trail at {@link #KEPT_AT} (none when it has none yet): see readSpawnData. */
    @Override
    public void writeSpawnData(FriendlyByteBuf buf) {
        buf.writeFloat((float) forward.x);
        buf.writeFloat((float) forward.y);
        buf.writeFloat((float) forward.z);
        float[] kept = kept();
        buf.writeVarInt(kept.length);
        for (float v : kept) buf.writeFloat(v);
    }

    /**
     * On the client, as it first sees it (its position set, its synced data not yet come): its body lies as the
     * server's does, never laid straight back from its head through whatever rock is there, and its head is in its
     * pose already. Without a trail it lays one straight on its first tick, as the server did.
     */
    @Override
    public void readSpawnData(FriendlyByteBuf buf) {
        Vec3 fwd = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        int n = buf.readVarInt();
        if (n < 0 || n > 6 * KEPT_AT.length) return;
        float[] kept = new float[n];
        for (int i = 0; i < n; i++) kept[i] = buf.readFloat();
        if (!trail.read(kept, getX(), getY(), getZ())) return;
        spine.lay(trail);
        placeSections();
        double[] head = new double[6];
        trail.sample(0.0, head);
        poseHead(fwd, new Vec3(head[3], head[4], head[5]));
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
        if (journeyTo != null) b.append(String.format(Locale.ROOT, ", going along the rift to %.0f %.0f %.0f (%.0f blocks along it to go, %ds on the way)",
                journeyTo.x, journeyTo.y, journeyTo.z, Math.abs(journeyEnd - journeyAt), (level().getGameTime() - journeyFrom) / 20));
        if (anchor != null) b.append(String.format(Locale.ROOT, ", struck from %.0f %.0f %.0f", anchor.x, anchor.y, anchor.z));
        level().getBiome(blockPosition()).unwrapKey().ifPresent(k -> b.append(", in ").append(k.location().getPath()));
        if (isInWater()) b.append(", in water");
        if (airborne()) b.append(", falling");
        if (getMoveControl() instanceof ClimbMoveControl mc && mc.wedged() > 0) b.append(", wedged ").append(mc.wedged());
        return b.toString();
    }

    /**
     * For /remix skein state: puts it in state {@code s} for about {@code ticks} (STILL, SKITTER, BOLT and JOURNEY go
     * by the gait); a strike or a bolt is at or from {@code who}. Returns false when that state cannot be started now.
     */
    public boolean forceState(byte s, int ticks, @Nullable LivingEntity who) {
        switch (s) {
            case STILL -> { gait.rest(ticks); settle(); }
            case SKITTER -> startOuting(SKITTER, SkeinGait.Mood.ROAM, SkeinGait.OUTING_MAX);
            case BOLT -> boltFrom(who != null ? who.position() : position().add(forward), BOLT_MAX);
            case STALK -> {
                shunned = null;     // told to, it tries again what it gave up on
                FireflySwarm sw = nearestSwarm();
                if (sw == null) return false;
                startStalk(sw);
            }
            case REAR, LUNGE -> {
                if (who == null || !huntable(who)) return false;
                startStrike(who, REAR_TICKS);
            }
            case EAT -> toState(EAT, Math.max(1, ticks));
            case JOURNEY -> { if (!startJourney()) return false; }
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
        moveBody();
    }

    /**
     * The head's new place goes on the trail, and the body's sections move along it. Back on rock after it has gone
     * further than {@link #AIR_REACH} touching nothing (it fell, or lunged off a wall or the ceiling: rounding an edge,
     * where the body pours round after the head, takes less), its body is laid afresh behind it on that rock rather than
     * left hanging along the way it fell; on the client once its own box, a little behind the server's, is down on it.
     */
    private void followHead() {
        boolean client = level().isClientSide;
        Direction rock = client ? clientRock : surface();
        Vec3 up = normal(rock);
        if (!trail.laid()) {
            layTrail(rock);
            // first seen: its head starts in the pose it lies in, not rolling into it from upright
            if (client) poseHead(clientForward, up);
            return;
        }
        if (rock != null && airRun > AIR_REACH && (!client || touching(getBoundingBox(), rock))) {
            Vec3 back = landingBack(up);
            airRun = 0.0;
            layTrail(rock, back);
            return;
        }
        Vec3 f = foot(rock);
        trail.record(f.x, f.y, f.z, footUp.x, footUp.y, footUp.z);
        // (how far it moved, not the trail's step: that jumps from one side of its box to another as it leaves a wall)
        if (rock == null && !isInWater()) airRun += Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
        else if (rock == null || airRun <= AIR_REACH) airRun = 0.0;     // swimming, or round an edge onto rock
    }

    /**
     * Its body comes after its head, once a tick, and its sections with it: after the client has turned its drawn head
     * (the neck comes round after the way that points).
     */
    private void moveBody() {
        if (!trail.laid()) return;
        Vec3 fwd = level().isClientSide ? clientForward : forward;
        spine.follow(trail, fwd.x, fwd.y, fwd.z);
        placeSections();
        if (trace > 0 && !level().isClientSide) {
            trace--;
            // and what went into the trail and the body this tick, so that a replay of the log lays them as here
            Vec3 f = foot == null ? Vec3.ZERO : foot;
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[skein trace] %d %s on %s, foot %.2f %.2f %.2f, step %.3f, %s | rec %.4f %.4f %.4f up %.4f %.4f %.4f fwd %.4f %.4f %.4f%s",
                    tickCount, gait.describe(), surface() == null ? "nothing" : surface().getName(), foot == null ? 0.0 : foot.x,
                    foot == null ? 0.0 : foot.y, foot == null ? 0.0 : foot.z, trail.lastStep(), spine.moves(),
                    f.x, f.y, f.z, footUp.x, footUp.y, footUp.z, fwd.x, fwd.y, fwd.z,
                    laidAt == tickCount ? String.format(Locale.ROOT, " laid back %.4f %.4f %.4f", laidBack.x, laidBack.y, laidBack.z) : ""));
        } else if (CLIENT_TRACE && level().isClientSide) {
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[skein client trace] %d id %d on %s, foot %.2f %.2f %.2f, step %.3f, %s",
                    tickCount, getId(), clientRock == null ? "nothing" : clientRock.getName(), foot == null ? 0.0 : foot.x,
                    foot == null ? 0.0 : foot.y, foot == null ? 0.0 : foot.z, trail.lastStep(), spine.moves()));
        }
    }

    /** For testing only: with ER_SKEIN_TRACE set, the client logs how each Skein's body moves each tick, as the server does for /remix skein trace. */
    private static final boolean CLIENT_TRACE = System.getenv("ER_SKEIN_TRACE") != null;

    /** For /remix skein trace: logs how its body moves each tick for the next {@code ticks}. */
    public void trace(int ticks) { trace = Math.max(0, ticks); }

    // ------------------------------------------------------------------------------------------------ client

    /**
     * The rock it clings to as the client sees it: the synced side while its box (where the client has it) touches that
     * rock, else the rock it was on while it still touches it, else whatever it touches. The client's box lags the
     * server's a little, so the plain synced side would now and then put the trail a hair off the rock. With none
     * synced (it falls or swims), the rock it was on only while its box still touches it (rounding an edge), else
     * none, as on the server: a Skein falling off the ceiling is drawn falling, not still clinging belly up.
     */
    private void updateClientRock() {
        AABB box = getBoundingBox();
        Direction synced = surface();
        if (synced == null) {
            clientRock = !isInWater() && clientRock != null && touching(box, clientRock) ? clientRock : null;
            return;
        }
        if (touching(box, synced)) { clientRock = synced; return; }
        // stepping down off an edge, or up one, its box (behind the server's) not on the floor yet
        if (synced == Direction.DOWN && floorBelow(box)) { clientRock = synced; return; }
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

    /**
     * Puts its head straight into its pose, belly to {@code up}, pointing along {@code fwd} as it lies along that rock:
     * as the client first sees it, so that one on a wall or the ceiling does not roll into its pose from upright.
     */
    private void poseHead(Vec3 fwd, Vec3 up) {
        Vec3 u = up.normalize();
        Vec3 f = fwd.subtract(u.scale(fwd.dot(u)));
        if (u.lengthSqr() < 0.5 || f.lengthSqr() < 1.0E-4) return;
        clientUp = u;
        clientForward = f.normalize();
        headOrient.set(basis(clientForward, clientUp));
        headOrientO.set(headOrient);
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
     * any entity), handing the damage to the Skein. Never saved, never sent: the Skein makes its own on each side and
     * places them by its trail (which is saved and sent).
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
