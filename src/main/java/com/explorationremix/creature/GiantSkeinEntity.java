package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.SilverCattailsBlock;
import com.explorationremix.combat.Stagger;
import com.explorationremix.combat.WindUp;
import com.explorationremix.creature.body.GiantCrawl;
import com.explorationremix.creature.body.GiantRope;
import com.explorationremix.creature.body.LevelTerrain;
import com.explorationremix.creature.body.RockClearance;
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
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.sounds.SoundEvents;
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
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The giant Skein of the Deep Blue Rift (0.18.8, Taran's 2026-11-18 ask, from his nine card answers): a Skein four
 * times as long, about thirty blocks from its horned head to the fan of spikes at its tail, its shell a pale ice blue,
 * spiky all over, the tips of its spikes and its eyes glowing a soft light blue. One lives in each rift ({@link
 * GiantSkeins}), on its walls. It never leaves its rift, never goes into the Asurine Geodes, and once killed it is gone
 * for good: nothing moves in after it.
 *
 * <p>Most of the time it lies still along a wall, its feelers moving, a slow ripple now and then down its legs; then it
 * crawls a little way, slowly and steadily, its legs rippling in waves down its length, mostly over the walls and now
 * and then down across the ledge path or over a bridge ({@link #roamGoal}). It hunts players in its rift: one it can
 * see within {@link #SIGHT} blocks it turns to, then comes after as fast as a sprinting player while it sees them
 * (0.21.5, Taran's ask: it speeds up when it sees you), and from about {@link #STRIKE_REACH} blocks it rears its front up off the
 * rock with a deep rattle, coils, and strikes ({@link GiantStrike}); a strong attack landing in the rear spoils the
 * strike (WindUp). Under a roof (or at the foot of a wall) it climbs up over its quarry instead, hangs its head down
 * with a rattle and a fall of grit, and drops on them, its body pouring down after it like a heavy rope; it lies dazed a
 * moment where it lands ({@link #canDrop}). It gives up a hunt once it has lost sight of its quarry for a while, after a
 * look where it saw them last. Badly hurt, it draws off up the wall. Small Skeins freeze, then scatter when it comes near
 * (SkeinEntity).
 *
 * <p>Its head crawls over the rock itself (0.21.5: {@link GiantCrawl}), held out from it on a smooth skin over the rock,
 * never by the game's collision, which let its box catch, slip and fall, and its body cut through walls; the path its
 * way is found along only says which way to go. Its body is the Skein's (the trail its head lays and the smooth chain
 * its body lies in), worked at four times the size ({@link GiantBody}), kept to within a block and a half of the way its
 * head went and out of the rock everywhere along it, on the server and every client alike; its sections are boxes along
 * it that can be hit, as the Skein's are. It is drawn by client.render.GiantSkeinRenderer.
 */
public class GiantSkeinEntity extends Monster implements ClimbMoveControl.Climber, IEntityAdditionalSpawnData, WindUp {
    // ------------------------------------------------------------------------------------------------ tuning
    /** How many times the Skein it is. */
    public static final double SCALE = 4.0;
    /** Tough (Taran's pick): it takes a long fight. Its pace is its own (below), not the movement speed's. */
    public static final double HEALTH = 240.0, ARMOR = 10.0, SPEED = 0.13, KNOCKBACK_RESISTANCE = 1.0;
    /**
     * Its pace (blocks a tick): crawling about its rift, slowly; hunting a quarry it sees, as fast as a sprinting
     * player (0.28 a tick, 5.6 blocks a second: Taran's card, 24 Nov 2026), easing up to it over a second; after one it
     * has lost sight of; drawing off.
     */
    static final double ROAM_PACE = 0.078, HUNT_PACE = 0.28, SEARCH_PACE = 0.15, RETREAT_PACE = 0.16;
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
    /**
     * A quarry hidden in silver cattails (0.21.7, Taran's card: "Hidden while you're in them") it neither sees nor
     * strikes at unless they are this near its jaws, right by it; hunting one that hides there, it loses them after
     * this long and goes to look where it saw them last.
     */
    static final double FOUND_IN_REEDS = 3.0;
    static final int HIDDEN_LOSE_TICKS = 30;
    /** It rests this long between crawls (ticks), and goes this far in a crawl's leg, one to three legs a crawl. */
    static final int REST_MIN = 600, REST_MAX = 2400, SHORT_REST = 200;
    /**
     * Hunting with no way to its quarry this long (they are where it cannot go, or every way there leaves its rift), it
     * gives them up, and pays them no heed for {@link #GIVE_UP_TICKS} (it goes its own way, not stare at them forever).
     */
    static final int NO_WAY_TICKS = 100, GIVE_UP_TICKS = 600;
    static final double LEG_MIN = 10.0, LEG_MAX = 22.0;
    /** A short leg over the rock round it, where its rift's own legs all fail (blocks). */
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
    /** A heavy scrape of feet every this many blocks it crawls. */
    static final double STEP_EVERY = 3.0;
    /** How much of the way to its new heading its drawn head turns each tick (it turns slowly, as a big thing does). */
    static final double HEAD_TURN = 0.3;
    /** How far in from the middle of its head (its crawl's point) the middle of its box sits, towards its rock. */
    static final double BOX_IN = 0.6;
    /**
     * Following its way: the node it heads for is behind it once it is this near (blocks; the last one, this near), or
     * once it has gone past it this near; a way it makes no headway along for this long (ticks) is given up.
     */
    static final double WAY_NEAR = 2.2, WAY_END = 1.6, WAY_PASSED = 3.5;
    static final int WAY_IDLE = 60;
    /**
     * Its body kept out of the rock: the line this far out along its back clear of it, its flanks this wide either side
     * at the head narrowing to this at the tail, kept this far from the rock too (see GiantBody#keepOut).
     */
    static final double BODY_LIFT = 0.75, BODY_HALF = 1.05, BODY_TAIL = 0.45, BODY_ROOM = 0.25;
    /**
     * The drop (Taran's ask, 24 Nov 2026, and his cards): it hangs its head this far down off its rock (blocks) and this
     * far back towards its body, for this long (ticks: grit falling and a rattle the while, fair warning), then lets go.
     */
    static final double HANG_LOW = 3.5;
    /** As it hangs, its head reaches across this far to be over its quarry, and keeps this far out from a wall (blocks). */
    static final double HANG_REACH = 3.5, HANG_IN = 1.6;
    /** From a wall it hangs its head out only this far (blocks): over a ledge at the wall's foot, not past its edge. */
    static final double HANG_OUT = 2.5;
    static final int HANG_TICKS = 30;
    /**
     * It drops on a quarry no further below where its head hangs than this (blocks, Taran's card) and no nearer than this
     * (nearer, it strikes), within this of the line straight down from its head, sideways.
     */
    static final double DROP_HEIGHT = 24.0, DROP_LOW = 3.0, DROP_OVER = 2.0;
    /** Of the nine blocks under where it would land and round it, this many must be rock: a floor, not a sliver of ledge. */
    static final int LANDING_FLOOR = 7;
    /**
     * Landing on its quarry it hits what lies within this of its head (and this far up from it) as hard as its bite,
     * knocking them back this hard; it lies dazed this long (ticks) after its body has come to rest (less after a fall
     * it did not mean), and drops again no sooner than this after.
     */
    static final double DROP_HIT_RADIUS = 2.5, DROP_HIT_HEIGHT = 3.0, DROP_KNOCKBACK = 1.1;
    static final int DROP_DAZE = 40, FALL_DAZE = 20, DROP_COOLDOWN = 200;
    /** Come this near where it means to drop from (blocks) it waits there for its chance, no longer than this (ticks). */
    static final double PERCHED = 3.0;
    /** A way up over its quarry must end this near its perch's node (blocks), or it crawls to them instead. */
    static final double PERCH_REACHED = 3.0;
    /** Climbing up over its quarry, it strikes only when they come this near its jaws (blocks). */
    static final double CLOSE_STRIKE = 4.5;
    static final int PERCH_WAIT = 30;

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
    public static final byte REST = 0, CRAWL = 1, ALERT = 2, HUNT = 3, REAR = 4, LUNGE = 5, RECOVER = 6, SEARCH = 7, RETREAT = 8,
            HANG = 9, DROP = 10, FALL = 11;
    /** The state names, by id, for /remix giant. */
    public static final String[] STATE_NAMES = {"rest", "crawl", "alert", "hunt", "rear", "lunge", "recover", "search", "retreat",
            "hang", "drop", "fall"};
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it drops or falls. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.BYTE);
    /** The way its head points along its rock. */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.VECTOR3);
    /** What its strike is at (where it means its jaws to close). */
    private static final EntityDataAccessor<Vector3f> DATA_AIM = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.VECTOR3);
    /** For /remix giant hold (pictures of its strike): how far through its phase the strike is held, or -1. */
    private static final EntityDataAccessor<Float> DATA_HELD = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.FLOAT);
    /** The way out from the rock under its head (its crawl's: the client finds the point under its head from it). */
    private static final EntityDataAccessor<Vector3f> DATA_UP = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.VECTOR3);
    /** How far its head coils up off its rock in this strike (GiantStrike#COIL_RISE, less under a low roof). */
    private static final EntityDataAccessor<Float> DATA_RISE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.FLOAT);
    /** Where the rope of its last drop or fall started (see {@link #ropeTag}): each client drops the same rope. */
    private static final EntityDataAccessor<CompoundTag> DATA_ROPE = SynchedEntityData.defineId(GiantSkeinEntity.class, EntityDataSerializers.COMPOUND_TAG);

    private final Section[] sections;
    private final GiantBody body = new GiantBody(SCALE, TRAIL_KEEP, SkeinEntity.SPINE_JOINTS);
    // its head's crawl over the rock (server; the client finds the point under its head with it), and the rock it feels
    private final GiantCrawl crawl = new GiantCrawl();
    private boolean crawlSet;
    @Nullable private LevelTerrain rockView;
    private final Vector3d footV = new Vector3d(), footN = new Vector3d();
    @Nullable private Vec3 footPoint;        // the point under its head this tick (the start of its trail)
    // the way it is crawling (path nodes it heads for in turn), its pace, and what it turns to while it lies still
    @Nullable private Path way;
    private int wayAt, wayIdle;
    private double wayBest, pace;
    private boolean wayStuck;
    private long wayRetry;
    @Nullable private Vec3 faceTo;
    // the drop: its body as a rope while it falls (on the server and on each client alike), and how the drop is going
    @Nullable private GiantRope rope;
    private int ropeDaze = DROP_DAZE;        // DROP, FALL: how long it lies dazed where it lands (as the rope's restful)
    private boolean ropeLanded, perching;
    @Nullable private Vec3 perchAt;          // HUNT: where it climbs to over its quarry, while perching
    @Nullable private String wayWhy;         // for the dev listing: why its last try for a way found none
    private int noWay;                       // HUNT: ticks running it has had no way to its quarry
    @Nullable private UUID givenUp;          // the quarry it last gave up for want of a way, and till when it lets them be
    private long givenUpUntil;
    private int landedAt, dropCooldown, drops, landings, perchWait;
    private byte fellFrom;
    private double movedLast;
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
    private int bites;                       // for /remix giant: how many times it has bitten since it was loaded
    private float stepDist;
    private boolean dormant;
    private int trace;
    private int slips, waysGivenUp;          // for /remix giant: lets go of its rock (and falls), ways it gave up
    @Nullable private Watch watching;         // /remix giant watch
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
    private int clientStateTicks;

    public GiantSkeinEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.sections = new Section[SECTION_AT.length + 1];
        for (int i = 0; i < SECTION_AT.length; i++) sections[i] = new Section(this, i, SECTION_W[i], SECTION_H[i]);
        sections[JAWS] = new Section(this, JAWS, JAWS_W, JAWS_H);
        if (IDS != null) setId(IDS.getAndAdd(sections.length + 1) + 1);
        this.moveControl = new ClimbMoveControl(this);
        this.xpReward = 120;
        setMaxUpStep(1.0F);
        // its head moves itself over the rock (GiantCrawl): the game's collision and gravity have no part in it
        this.noPhysics = true;
        setNoGravity(true);
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
        entityData.define(DATA_FORWARD, new Vector3f(0, 0, 1));
        entityData.define(DATA_AIM, new Vector3f());
        entityData.define(DATA_HELD, -1.0F);
        entityData.define(DATA_UP, new Vector3f(0, 1, 0));
        entityData.define(DATA_RISE, (float) GiantStrike.COIL_RISE);
        entityData.define(DATA_ROPE, new CompoundTag());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (!level().isClientSide) return;
        if (DATA_STATE.equals(key)) {
            clientStateTicks = 0;
            GiantRope r = rope;
            if (r != null && !dropping()) {
                // the drop is over: its body lies where the rope came to rest, as it does on the server
                for (int i = 0; i < 400 && !r.restful(ropeDaze); i++) r.tick(rock());
                layFromRope(r);
                rope = null;
            }
        } else if (DATA_ROPE.equals(key) && dropping() && body.laid()) {
            rope = ropeFrom(entityData.get(DATA_ROPE), body.length());
            ropeDaze = state() == DROP ? DROP_DAZE : FALL_DAZE;
        }
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
    public GiantBody body() { return body; }
    public Section[] sections() { return sections; }

    @Override public boolean onFloor() { return surface() == Direction.DOWN; }
    @Override public boolean canSteer() { return true; }
    @Override public boolean airborne() { return dropping(); }
    @Override public boolean holdStill() { return clingsOffFloor(); }
    @Override public boolean onClimbable() { return false; }
    @Override public boolean isPushable() { return false; }
    /** It crawls on along the bed of a stream it crosses (its head holds the rock under the water too). */
    @Override public boolean canBreatheUnderwater() { return true; }
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

    /** The strike's phase now (GiantStrike's), from its state: hanging to drop, its front is drawn as it coils. */
    public int strikePhase() {
        return switch (state()) {
            case REAR, HANG -> GiantStrike.COIL;
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
            case HANG -> HANG_TICKS;
            default -> 1;
        };
        double ticks = level().isClientSide ? clientStateTicks + pt : stateTicks;
        return Mth.clamp(ticks / len, 0.0, 1.0);
    }

    /**
     * Where its head is in the strike, {@code pt} of a tick on: its point at out[0..2] and the way it points at
     * out[3..5] (see GiantStrike#head; hanging to drop, {@link #hangHead}). False when it is not striking.
     */
    public boolean strikeHead(float pt, double[] out) {
        int phase = strikePhase();
        if (phase == GiantStrike.NONE || !body.laid()) return false;
        if (state() == HANG) { hangHead(pt, out); return true; }
        double[] s = new double[18];
        body.sample(GiantStrike.NECK, pt, s, 0);
        body.sample(GiantStrike.NECK - 0.6, pt, s, 6);
        body.sample(0.0, pt, s, 12);
        double[] a = {s[0], s[1], s[2]}, n = {s[3], s[4], s[5]};
        double[] d = GiantStrike.unit(new double[]{s[6] - s[0], s[7] - s[1], s[8] - s[2]}, new double[]{forward.x, forward.y, forward.z});
        double[] h0 = {s[12], s[13], s[14]};
        Vector3f t = entityData.get(DATA_AIM);
        GiantStrike.head(a, n, d, h0, new double[]{t.x, t.y, t.z}, phase, strikeProgress(pt), entityData.get(DATA_RISE), out);
        return true;
    }

    /**
     * Its body at {@code arcs} along it (blocks back from the head), {@code pt} of a tick on: each point's position and
     * the way its back faces, six numbers each in out, as the body lies, except that while it strikes its front (up to
     * GiantStrike.NECK back) is drawn up off the rock along the strike's curve.
     */
    public void bodyAt(double[] arcs, float pt, double[] out) {
        GiantRope r = rope;
        if (r != null && dropping()) {
            // dropping or falling: its body is the rope (still, once it has come to rest)
            double at = r.restful(ropeDaze) ? 1.0 : pt;
            for (int i = 0; i < arcs.length; i++) r.sample(arcs[i], at, out, 6 * i);
            return;
        }
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

    /** Where its head hangs before it drops, over what it means to drop on (the aim, fixed as it begins to hang). */
    Vec3 hangPoint(float pt) {
        Vector3f a = entityData.get(DATA_AIM);
        return hangPoint(pt, new Vec3(a.x, a.y, a.z));
    }

    /**
     * Where its head would hang to drop on {@code aim} ({@code pt} of a tick on): off its rock from where it lies,
     * {@link #HANG_LOW} straight down from a ceiling or {@link #HANG_OUT} out from a wall, then reaching across to be
     * straight over the aim, as far as {@link #HANG_REACH}, never back nearer a wall it lies on than {@link #HANG_IN},
     * and no further from where its neck rises ({@link GiantStrike#NECK} back) than its neck is long, so its neck curves
     * down to it without stretching. It drops straight down from here.
     */
    Vec3 hangPoint(float pt, Vec3 aim) {
        double[] s = new double[12];
        body.sample(0.0, pt, s, 0);
        body.sample(GiantStrike.NECK, pt, s, 6);
        double ux = s[3], uy = s[4], uz = s[5];
        boolean wall = Math.abs(uy) < 0.5;
        double out = wall ? HANG_OUT : HANG_LOW;
        double x = s[0] + ux * out, y = s[1] + uy * out, z = s[2] + uz * out;
        double dx = aim.x - x, dz = aim.z - z, h = Math.sqrt(dx * dx + dz * dz);
        if (h > HANG_REACH) {
            dx *= HANG_REACH / h;
            dz *= HANG_REACH / h;
        }
        x += dx;
        z += dz;
        double hl = Math.sqrt(ux * ux + uz * uz);
        if (wall && hl > 1.0E-6) {
            double nx = ux / hl, nz = uz / hl, o = (x - s[0]) * nx + (z - s[2]) * nz;
            if (o < HANG_IN) {
                x += nx * (HANG_IN - o);
                z += nz * (HANG_IN - o);
            }
        }
        double ax = x - s[6], ay = y - s[7], az = z - s[8], al = Math.sqrt(ax * ax + ay * ay + az * az), most = GiantStrike.NECK - 0.4;
        if (al > most) {
            double k = most / al;
            x = s[6] + ax * k;
            y = s[7] + ay * k;
            z = s[8] + az * k;
        }
        return new Vec3(x, y, z);
    }

    /**
     * Its head as it hangs to drop, {@code pt} of a tick on: eased down off its rock from where it lies to {@link
     * #hangPoint}, turning to look at what it means to drop on (the aim). Its point at out[0..2], the way it points at
     * out[3..5], as GiantStrike#head gives them.
     */
    private void hangHead(float pt, double[] out) {
        double[] s = new double[12];
        body.sample(0.0, pt, s, 0);
        body.sample(0.6, pt, s, 6);
        double[] n0 = {s[3], s[4], s[5]};
        double[] d = GiantStrike.unit(new double[]{s[0] - s[6], s[1] - s[7], s[2] - s[8]}, new double[]{forward.x, forward.y, forward.z});
        Vec3 c = hangPoint(pt);
        double e = GiantStrike.ease(strikeProgress(pt));
        double px = s[0] + (c.x - s[0]) * e, py = s[1] + (c.y - s[1]) * e, pz = s[2] + (c.z - s[2]) * e;
        Vector3f t = entityData.get(DATA_AIM);
        double[] look = GiantStrike.unit(new double[]{t.x - px, t.y - py, t.z - pz}, n0);
        double[] f = GiantStrike.unit(new double[]{d[0] + (look[0] - d[0]) * e, d[1] + (look[1] - d[1]) * e, d[2] + (look[2] - d[2]) * e}, d);
        out[0] = px; out[1] = py; out[2] = pz;
        out[3] = f[0]; out[4] = f[1]; out[5] = f[2];
    }

    // ------------------------------------------------------------------------------------------------ geometry

    /** The way out from the rock under its head (its crawl's; while it drops, the way its head's back faces). */
    public Vec3 up() {
        if (!level().isClientSide && crawlSet && !dropping()) return new Vec3(crawl.up.x, crawl.up.y, crawl.up.z);
        return syncedUp();
    }

    private Vec3 syncedUp() {
        Vector3f u = entityData.get(DATA_UP);
        double l = Math.sqrt(u.x * u.x + u.y * u.y + u.z * u.z);
        return l > 0.5 ? new Vec3(u.x / l, u.y / l, u.z / l) : new Vec3(0, 1, 0);
    }

    static Vec3 normal(@Nullable Direction rock) {
        return rock == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(rock.getOpposite().getNormal());
    }

    /** The point on the rock under the middle of its head (where its trail starts). */
    public Vec3 feet() {
        if (footPoint != null) return footPoint;
        return position().add(0.0, getBbHeight() / 2.0, 0.0).add(up().scale(BOX_IN - GiantCrawl.RIDE));
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

    public Vec3 lookDir() { return forward; }
    public Vec3 bodyCentre() { return feet().add(up().scale(0.6)); }
    public Vec3 mouth() { return feet().add(forward.scale(HEAD_REACH)).add(up().scale(0.5)); }
    public Vec3 eyes() { return feet().add(forward.scale(1.0)).add(up().scale(1.1)); }

    private boolean clingsOffFloor() {
        Direction d = surface();
        return d != null && d != Direction.DOWN;
    }

    /** Whether there is rock on side {@code d} of {@code box}, touching it (within a hair). */
    static boolean rockBeside(CollisionGetter level, @Nullable Entity who, AABB box, Direction d) {
        double e = 0.08, m = 0.06;
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

    // ------------------------------------------------------------------------------------------------ moving (0.21.5)

    /** The rock round it as its head and body feel it (never loading a chunk to read it). */
    private LevelTerrain rock() {
        if (rockView == null) rockView = new LevelTerrain(level(), true);
        return rockView;
    }

    /** Whether it is dropping or falling: its body is a rope then ({@link GiantRope}). */
    public boolean dropping() {
        byte s = state();
        return s == DROP || s == FALL;
    }

    /** Its box where its head is: its middle {@link #BOX_IN} in from the middle of its head, towards the rock. */
    private void placeBox() {
        Vector3d p = crawl.pos, u = crawl.up;
        setPos(p.x - u.x * BOX_IN, p.y - u.y * BOX_IN - getBbHeight() / 2.0, p.z - u.z * BOX_IN);
        setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Its head's crawl set down where its box is (loaded from a save, or set down without {@link #placeOn}), out from the
     * rock nearest it; with no rock near, it falls.
     */
    private void setCrawl() {
        Vec3 u = syncedUp();
        Vec3 c = position().add(0.0, getBbHeight() / 2.0, 0.0).add(u.scale(BOX_IN));
        crawl.place(c.x, c.y, c.z, forward.x, forward.y, forward.z);
        crawl.up.set(u.x, u.y, u.z);
        crawlSet = true;
        if (crawl.settle(rock())) placeBox();
        else startDrop(false);
    }

    /**
     * Server, each tick after its mind: its head crawls a step along its way at its pace (or turns where it lies to what
     * it faces, or lies still), and its box goes where its head is. With the rock under it gone it falls. Dropping or
     * falling, its body falls on as a rope instead ({@link #dropTick}).
     */
    private void moveHead() {
        if (dropping()) { dropTick(); return; }
        if (!crawlSet) {
            setCrawl();
            if (dropping()) return;
        }
        Vec3 goal = wayGoal();
        double want = goal == null ? 0.0 : pace;
        if (goal == null) goal = faceTo;
        boolean held = true;
        if (goal != null) held = crawl.step(rock(), goal.x, goal.y, goal.z, want);
        else if (crawl.speed > 1.0E-4 || tickCount % 10 == 0) {
            // lying still it holds where it is (felt again now and then, in case the rock under it has gone)
            Vector3d p = crawl.pos, h = crawl.heading;
            held = crawl.step(rock(), p.x + h.x * 4.0, p.y + h.y * 4.0, p.z + h.z * 4.0, 0.0);
        }
        if (!held) { slips++; startDrop(false); return; }
        placeBox();
    }

    /**
     * The node of its way it heads for now (the middle of the node's box), moving on along the way as it comes to each
     * (or goes past it); null when it has no way, or its way is done, or given up (no headway for {@link #WAY_IDLE}).
     */
    @Nullable
    private Vec3 wayGoal() {
        Path p = way;
        if (p == null) return null;
        Vec3 at = new Vec3(crawl.pos.x, crawl.pos.y, crawl.pos.z), h = new Vec3(crawl.heading.x, crawl.heading.y, crawl.heading.z);
        while (wayAt < p.getNodeCount()) {
            Node n = p.getNode(wayAt);
            Vec3 c = new Vec3(n.x + 1.5, n.y + 1.0, n.z + 1.5);
            double d = at.distanceTo(c);
            boolean last = wayAt == p.getNodeCount() - 1;
            if (d < (last ? WAY_END : WAY_NEAR) || (!last && d < WAY_PASSED && c.subtract(at).dot(h) < 0.0)) {
                wayAt++;
                wayBest = Double.MAX_VALUE;
                wayIdle = 0;
                continue;
            }
            if (d < wayBest - 0.05) {
                wayBest = d;
                wayIdle = 0;
            } else if (++wayIdle > WAY_IDLE) {
                wayStuck = true;
                waysGivenUp++;
                way = null;
                return null;
            }
            return c;
        }
        way = null;
        return null;
    }

    /**
     * The way it points along its rock (its crawl's heading; dropping, the way its head falls), and its way out and its
     * rock's side, sent to the client.
     */
    private void updateForward() {
        Vec3 up;
        GiantRope r = rope;
        if (dropping() && r != null) {
            double[] a = new double[12];
            r.sample(0.0, 1.0, a, 0);
            r.sample(GiantRope.SPACE, 1.0, a, 6);
            Vec3 f = new Vec3(a[0] - a[6], a[1] - a[7], a[2] - a[8]);
            if (f.lengthSqr() > 1.0E-6) forward = f.normalize();
            up = new Vec3(a[3], a[4], a[5]);
        } else {
            forward = new Vec3(crawl.heading.x, crawl.heading.y, crawl.heading.z);
            up = new Vec3(crawl.up.x, crawl.up.y, crawl.up.z);
        }
        if (onFloor()) {
            float yaw = (float) (Mth.atan2(forward.z, forward.x) * Mth.RAD_TO_DEG) - 90.0F;
            setYRot(yaw);
            yBodyRot = yaw;
            yHeadRot = yaw;
        }
        Vector3f sf = entityData.get(DATA_FORWARD);
        if (sf.x * forward.x + sf.y * forward.y + sf.z * forward.z < 0.998)
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
        Vector3f su = entityData.get(DATA_UP);
        if (su.x * up.x + su.y * up.y + su.z * up.z < 0.9995)
            entityData.set(DATA_UP, new Vector3f((float) up.x, (float) up.y, (float) up.z));
        byte side = dropping() ? -1 : (byte) Direction.getNearest(-up.x, -up.y, -up.z).get3DDataValue();
        if (entityData.get(DATA_SURFACE) != side) entityData.set(DATA_SURFACE, side);
    }

    @Override
    public void travel(Vec3 input) {
        // its head moves itself (moveHead): nothing of the game's own movement
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
        // held for a picture of its strike (/remix giant hold): it does nothing else
        if (entityData.get(DATA_HELD) >= 0.0F) { stopDead(); return; }
        stateTicks++;
        if (strikeCooldown > 0) strikeCooldown--;
        if (dropCooldown > 0) dropCooldown--;
        // dropping or falling its body falls as a rope (moveHead) and its mind waits for it to come to rest
        if (dropping()) return;
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
            case HANG -> hang();
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
        if (stateTicks >= stateLength) {
            legsLeft = 1 + random.nextInt(3);
            toState(CRAWL, 0);
            nextLeg();
        }
    }

    private void crawl() {
        if (tickCount % 10 == 0 && notice()) return;
        if (way == null || stateTicks > 900) {
            if (!nextLeg()) settle(restLength());
            return;
        }
        steps();
    }

    /** The next leg of a crawl (the next waypoint of a way over a bridge first); false when the crawl is over. */
    private boolean nextLeg() {
        stopDead();
        stateTicks = 0;
        while (!waypoints.isEmpty()) {
            Vec3 w = waypoints.poll();
            if (goTo(w, 4, ROAM_PACE)) return true;
        }
        if (legsLeft-- <= 0) return false;
        for (int i = 0; i < 6; i++) {
            Vec3 g = roamGoal();
            if (g != null && goTo(g, 3, ROAM_PACE)) return true;
        }
        // where its rift's own legs all fail (near the rift's ends its wall turns away from the rift's line, and no wall
        // is found where a leg would go): a short way over the rock round it, mostly towards the rift's middle, so it
        // never lies there for good
        Vec3 toward = forward, u = up();
        Route r = rift();
        double[] loc = r == null ? null : r.locate(getX(), getZ());
        if (r != null && loc != null) {
            double[] d = r.directionAt(loc[1]);
            double sgn = loc[1] < r.length / 2.0 ? 1.0 : -1.0;
            Vec3 along = new Vec3(d[0] * sgn, 0.0, d[1] * sgn), flat = along.subtract(u.scale(along.dot(u)));
            if (flat.lengthSqr() > 1.0E-4) toward = flat.normalize();
        }
        for (int i = 0; i < 4; i++) {
            Vec3 heading = SkeinEntity.rotate(toward, u, (random.nextDouble() - 0.5) * Math.PI * (i < 2 ? 0.8 : 2.0));
            if (goTo(bodyCentre().add(heading.scale(NEAR_MIN + random.nextDouble() * (NEAR_MAX - NEAR_MIN))), 3, ROAM_PACE)) return true;
        }
        return false;
    }

    /**
     * Sets out for a node near {@code p} (within {@code r}) at a pace of {@code speed} (blocks a tick): the way there is
     * found over the climbing nodes, and its head crawls from node to node of it ({@link #wayGoal}). False when there is
     * no way there.
     */
    private boolean goTo(Vec3 p, int r, double speed) {
        return goTo(p, r, speed, Double.MAX_VALUE);
    }

    /** As {@link #goTo(Vec3, int, double)}, only for a way that ends within {@code within} blocks of the node there. */
    private boolean goTo(Vec3 p, int r, double speed, double within) {
        BlockPos n = nodeNear(p, r);
        if (n == null) { wayWhy = "no rock to stand on there"; return false; }
        Path path = getNavigation().createPath(n.offset(1, 0, 1), 0);
        if (path == null || path.getNodeCount() < 2) { wayWhy = "none found"; return false; }
        if (leavesRift(path)) { wayWhy = "it leaves the rift"; return false; }
        Node end = path.getEndNode();
        if (end == null || Mth.square(end.x + 1.5 - getX()) + Mth.square(end.y - getY()) + Mth.square(end.z + 1.5 - getZ()) < 4.0) {
            wayWhy = "it ends where it is";
            return false;
        }
        if (within < Double.MAX_VALUE && Mth.square(end.x - n.getX()) + Mth.square(end.y - n.getY()) + Mth.square(end.z - n.getZ()) > within * within) {
            wayWhy = "it ends short";
            return false;
        }
        wayWhy = null;
        way = path;
        wayAt = 1;
        wayIdle = 0;
        wayBest = Double.MAX_VALUE;
        wayStuck = false;
        pace = speed;
        faceTo = null;
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
        if (r == null || loc == null) {
            Vec3 heading = SkeinEntity.rotate(forward, up(), (random.nextDouble() - 0.5) * Math.PI * 1.4);
            return bodyCentre().add(heading.scale(LEG_MIN + random.nextDouble() * (LEG_MAX - LEG_MIN)));
        }
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
        return wallAt(r, s1, side, y);
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
            if (p.getUUID().equals(givenUp) && level().getGameTime() < givenUpUntil) continue;
            bestD = d;
            best = p;
        }
        return best;
    }

    private void alert() {
        stopDead();
        LivingEntity t = getTarget();
        if (!hunting(t)) { settle(SHORT_REST); return; }
        faceTo = t.getEyePosition();
        if (canSee(t)) { lastSeen = level().getGameTime(); lastSeenAt = t.position(); }
        if (stateTicks >= stateLength) toState(HUNT, 0);
    }

    /** Whether {@code t} is still to be hunted: alive, in its rift, out of the refuge, near enough, not in Peaceful. */
    private boolean hunting(@Nullable LivingEntity t) {
        return t != null && huntable(t) && level().getDifficulty() != Difficulty.PEACEFUL && inRift(t.position())
                && !isRefuge(t.blockPosition()) && t.distanceToSqr(this) < LEASH * LEASH;
    }

    /**
     * The hunt: in reach of its jaws, it strikes; over its quarry where it can drop on them, it hangs and drops; else it
     * crawls after them, as fast as a sprinting player while it sees them (Taran's ask), up onto the roof over them when
     * there is one to drop from (or a wall over them), along the rock to them when not, or after a drop (it hunts them
     * on the floor a while, then climbs back up if they are still under a roof: Taran's card).
     */
    private void hunt() {
        LivingEntity t = getTarget();
        if (!hunting(t)) { lostQuarry(); return; }
        long now = level().getGameTime();
        boolean sees = canSee(t);
        if (sees) { lastSeen = now; lastSeenAt = t.position(); }
        else if (now - lastSeen > (SilverCattailsBlock.inCattails(t) ? HIDDEN_LOSE_TICKS : LOSE_TICKS)) { startSearch(); return; }
        // on its way up over them it keeps to the climb, striking only if they come right up to its jaws
        if (tickCount % 2 == 0 && (!perching || inReach(t, CLOSE_STRIKE)) && canStrike(t)) { startStrike(t); return; }
        if (tickCount % 2 == 1 && sees && canDrop(t)) { startHang(t); return; }
        if (stateTicks % REPATH == 1 || (way == null && now >= wayRetry)) {
            Vec3 goal = sees || lastSeenAt == null ? t.position() : lastSeenAt;
            Vec3 perch = sees && dropCooldown == 0 ? perchOver(t) : null;
            if (perch != null && perch.distanceToSqr(crawl.pos.x, crawl.pos.y, crawl.pos.z) < PERCHED * PERCHED) {
                // over them already: it waits a moment for its chance to drop, then gives the roof up a while
                way = null;
                perching = true;
                wayRetry = now + REPATH;
                if ((perchWait += REPATH) > PERCH_WAIT) {
                    perchWait = 0;
                    perching = false;
                    dropCooldown = Math.max(dropCooldown, PERCH_WAIT * 3);
                }
            } else {
                perchWait = 0;
                // only a way that gets all the way up there (not one that ends short of it, by them on the floor)
                perching = perch != null && goTo(perch, 2, HUNT_PACE, PERCH_REACHED);
                perchAt = perching ? perch : null;
                if (!perching && !goTo(goal, 4, HUNT_PACE) && !goTo(position().add(goal.subtract(position()).scale(0.5)), 4, HUNT_PACE))
                    wayRetry = now + REPATH;
            }
        }
        // no way to them for long (not in reach of its jaws, where it needs none): it gives them up a while
        noWay = way == null && !perching && t.getBoundingBox().distanceToSqr(mouth()) > Mth.square(STRIKE_REACH + 2.0) ? noWay + 1 : 0;
        if (noWay > NO_WAY_TICKS) {
            givenUp = t.getUUID();
            givenUpUntil = now + GIVE_UP_TICKS;
            noWay = 0;
            setTarget(null);
            lostQuarry();
            return;
        }
        // it speeds up while it sees its quarry, slowing to a search's pace when it has lost sight of them
        pace = sees ? HUNT_PACE : SEARCH_PACE;
        if (way == null && !perching) faceTo = t.getEyePosition();
        steps();
    }

    /** It has lost its quarry (gone out of its rift, into the refuge, dead, or far off): a short rest, then its own way. */
    private void lostQuarry() {
        settle(SHORT_REST + random.nextInt(SHORT_REST));
    }

    private void startSearch() {
        toState(SEARCH, SEARCH_TICKS);
        if (lastSeenAt == null || !goTo(lastSeenAt, 4, SEARCH_PACE)) lostQuarry();
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
        if (stateTicks >= stateLength || way == null) { lostQuarry(); return; }
        steps();
    }

    /** Whether it can strike at {@code t} now: in reach of its jaws, with a clear line from its eyes. */
    private boolean canStrike(LivingEntity t) {
        return strikeCooldown == 0 && inReach(t, STRIKE_REACH) && clearTo(eyes(), t) && !SilverCattailsBlock.hides(t, mouth(), FOUND_IN_REEDS)
                && inRift(t.position()) && roomToStrike(t);
    }

    /**
     * Whether there is room for its strike at {@code t}: from where its front leaves the rock up to where its head coils,
     * and from there to them, open (under a low roof its coiled head would go into the floor, or on a floor into the
     * roof: it crawls nearer, or climbs up and drops, instead).
     */
    private boolean roomToStrike(LivingEntity t) {
        if (!body.laid()) return false;
        double[] s = new double[12];
        body.sample(GiantStrike.NECK, 1.0F, s, 0);
        body.sample(GiantStrike.NECK - 0.6, 1.0F, s, 6);
        double[] a = {s[0], s[1], s[2]}, n = {s[3], s[4], s[5]};
        double rise = strikeRise(a, n);
        if (rise < LOWEST_COIL) return false;
        double[] d = GiantStrike.unit(new double[]{s[6] - s[0], s[7] - s[1], s[8] - s[2]}, new double[]{forward.x, forward.y, forward.z});
        Vec3 aim = t.getBoundingBox().getCenter();
        double[] c = GiantStrike.coil(a, n, d, new double[]{aim.x, aim.y, aim.z}, rise);
        Vec3 from = new Vec3(a[0] + n[0] * 0.6, a[1] + n[1] * 0.6, a[2] + n[2] * 0.6), coil = new Vec3(c[0], c[1], c[2]);
        return clear(from, coil) && clear(coil, aim);
    }

    /** Its coiled head keeps this far (blocks) from a roof over it, and coils at least this high, or does not strike. */
    static final double COIL_ROOM = 1.2, LOWEST_COIL = 0.8;

    /** How far its head can coil up off its rock over the anchor {@code a} (facing {@code n}): as high as it may, or the room under a roof. */
    private double strikeRise(double[] a, double[] n) {
        Vec3 from = new Vec3(a[0] + n[0] * 0.3, a[1] + n[1] * 0.3, a[2] + n[2] * 0.3);
        double reach = GiantStrike.COIL_RISE + COIL_ROOM;
        Vec3 to = from.add(n[0] * reach, n[1] * reach, n[2] * reach);
        HitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        double room = hit.getType() == HitResult.Type.MISS ? reach + 0.3 : hit.getLocation().distanceTo(from) + 0.3;
        return Math.min(GiantStrike.COIL_RISE, room - COIL_ROOM);
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
        double[] s = new double[6];
        if (body.laid()) {
            body.sample(GiantStrike.NECK, 1.0F, s, 0);
            entityData.set(DATA_RISE, (float) Math.max(LOWEST_COIL, strikeRise(new double[]{s[0], s[1], s[2]}, new double[]{s[3], s[4], s[5]})));
        }
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
        faceTo = aim();
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
                if (s != REAR && s != LUNGE && s != RECOVER && !dropping()) startRetreat(attacker);
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
        if (!goTo(goal, 4, RETREAT_PACE)) goTo(bodyCentre().add(dir.scale(14.0)), 4, RETREAT_PACE);
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
        if (stateTicks >= stateLength) { settle(restLength()); return; }
        if (way == null) {
            stopDead();
            if (stateTicks % 40 == 0) {
                Vec3 g = roamGoal();
                if (g != null) goTo(g, 3, RETREAT_PACE);
            }
            return;
        }
        steps();
    }

    @Override
    public boolean windingUp() {
        byte s = state();
        return !level().isClientSide && (s == REAR || s == HANG);
    }

    /**
     * A strong attack caught it rearing: no strike; its front sinks back to the rock (it reels a moment too). Caught
     * hanging to drop, it draws its head back up and hunts on, and does not drop again for a while.
     */
    @Override
    public void interrupt(LivingEntity by) {
        playSound(ERSounds.GIANT_SKEIN_HURT.get(), 2.0F, 1.2F);
        if (state() == HANG) {
            dropCooldown = DROP_COOLDOWN;
            toState(HUNT, 0);
            return;
        }
        strikeCooldown = STRIKE_COOLDOWN * 2;
        toState(RECOVER, RECOVER_TICKS);
    }

    // --- the drop (Taran's ask, 24 Nov 2026: it climbs onto the ceiling over you and drops down)

    /**
     * Whether it can drop on {@code t} now: on a ceiling (or a wall) with its head over them, so that where its head
     * would hang ({@link #hangPoint}) is no more than {@link #DROP_OVER} to the side of them and between {@link #DROP_LOW}
     * and {@link #DROP_HEIGHT} above their feet, the way down to them open, in its rift, and not too soon after its last.
     */
    private boolean canDrop(LivingEntity t) {
        if (dropCooldown > 0 || !crawlSet || !body.laid() || !inRift(t.position())) return false;
        Vec3 up = up();
        if (up.y > 0.5) return false;
        Vec3 hang = hangPoint(1.0F, t.getBoundingBox().getCenter()), f = t.position();
        if (Mth.square(hang.x - f.x) + Mth.square(hang.z - f.z) > DROP_OVER * DROP_OVER) return false;
        double below = hang.y - f.y;
        if (below < DROP_LOW || below > DROP_HEIGHT) return false;
        // the way down open: from its head out to where it hangs, straight down from there, and from there to them
        if (!clear(feet().add(up.scale(0.6)), hang) || !clear(hang, new Vec3(hang.x, f.y + 0.3, hang.z))
                || !(clear(hang, t.getEyePosition()) || clear(hang, t.getBoundingBox().getCenter()))) return false;
        // and it lands about where they stand (on their ledge, not past its edge into the deep), on a floor broad enough
        // for its head and the body that pours down after it (not a sliver of ledge over a drop, which it would slide off)
        HitResult down = level().clip(new ClipContext(hang, new Vec3(hang.x, f.y - DROP_HEIGHT, hang.z), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this));
        if (down.getType() == HitResult.Type.MISS || down.getLocation().y < f.y - 1.5) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int solid = 0, ly = Mth.floor(down.getLocation().y - 0.5), lx = Mth.floor(hang.x), lz = Mth.floor(hang.z);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                p.set(lx + dx, ly, lz + dz);
                if (loaded(level(), p) && !level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) solid++;
            }
        return solid >= LANDING_FLOOR;
    }

    /**
     * Where it climbs to drop on {@code t}: the roof straight over them (high enough over them to drop from, no higher
     * than its drop), else the face of a wall beside them that runs up over them, high enough to drop from (Taran's
     * card: no ceiling near, it drops from the wall above you); null when there is neither (it crawls to them and
     * strikes), or the roof over them is too low to drop from.
     */
    @Nullable
    private Vec3 perchOver(LivingEntity t) {
        Vec3 f = t.position();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = Mth.floor(f.x), z = Mth.floor(f.z);
        int top = Mth.floor(f.y + DROP_HEIGHT + HANG_LOW);
        for (int y = Mth.floor(f.y + t.getBbHeight()); y <= top; y++) {
            p.set(x, y, z);
            if (!loaded(level(), p)) return null;
            if (level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) continue;
            // the underside of the roof: too low to hang from over them, it strikes instead
            if (y - HANG_LOW - f.y < DROP_LOW) return null;
            return new Vec3(f.x, y - 1.0, f.z);
        }
        // open over them: a wall beside them, some way up, with its face about where its head would hang out from it
        int wy = Mth.floor(f.y + DROP_LOW + 3.5);
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int out = 1; out <= 7; out++) {
                p.set(x + d.getStepX() * out, wy, z + d.getStepZ() * out);
                if (!loaded(level(), p)) break;
                if (level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) continue;
                // its head hangs out from the wall's face and reaches across over them: the nearest wall it reaches them from
                double face = out - 0.5;
                if (face <= HANG_OUT + HANG_REACH + DROP_OVER && face < bestD) {
                    bestD = face;
                    double on = face - GiantCrawl.RIDE;
                    best = new Vec3(x + 0.5 + d.getStepX() * on, wy + 0.5, z + 0.5 + d.getStepZ() * on);
                }
                break;
            }
        }
        return best;
    }

    /** It hangs its head down over {@code t} to drop on them: a deep rattle, grit falling, a long moment (fair warning). */
    private void startHang(LivingEntity t) {
        stopDead();
        setTarget(t);
        setAim(t.getBoundingBox().getCenter());
        toState(HANG, HANG_TICKS);
        playSound(ERSounds.GIANT_SKEIN_WINDUP.get(), 2.5F, 0.8F + random.nextFloat() * 0.1F);
        if (level() instanceof ServerLevel l) RuneFx.windup(l, hangPoint(1.0F), up().scale(-1.0), 2.2F);
    }

    private void hang() {
        stopDead();
        // it drops where it hung its head as it began, over where they stood then: step out from under it
        grit();
        if (stateTicks >= stateLength) startDrop(true);
    }

    /** Grit trickles from the rock round its head, and down from where its head hangs, as it hangs to drop. */
    private void grit() {
        if (!(level() instanceof ServerLevel l) || stateTicks % 2 != 0) return;
        Vec3 f = feet(), up = up();
        BlockPos at = BlockPos.containing(f.subtract(up.scale(0.3)));
        BlockState s = l.getBlockState(at);
        if (s.isAir()) return;
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.FALLING_DUST, s);
        l.sendParticles(dust, f.x + up.x * 0.3, f.y + up.y * 0.3, f.z + up.z * 0.3, 3, 1.0, 0.2, 1.0, 0.0);
        Vec3 h = hangPoint(1.0F);
        l.sendParticles(dust, h.x, h.y - 0.5, h.z, 2, 0.5, 0.3, 0.5, 0.0);
    }

    /**
     * It lets go: dropping on its quarry from where its head hangs ({@code attack}), or falling, the rock under it gone.
     * Its body becomes a heavy rope from where it lies (GiantRope, Taran's card: head first, its body pouring after),
     * its head plunging straight down, the rest peeling off the rock after it; each client drops the same rope from the
     * same start ({@link #DATA_ROPE}), so it falls and comes to rest alike everywhere.
     */
    private void startDrop(boolean attack) {
        if (!body.laid()) return;
        byte from = state();
        GiantRope r = new GiantRope(body.length(), BODY_HALF, BODY_TAIL, BODY_ROOM);
        double[] arcs = new double[r.beads()];
        for (int i = 0; i < arcs.length; i++) arcs[i] = GiantRope.beadAt(i);
        double[] at = new double[6 * arcs.length];
        bodyAt(arcs, 1.0F, at);
        if (attack && from == HANG) {
            // its head plunges from right where it hung (the line its drop was judged along), not a little out from it
            Vec3 h = hangPoint(1.0F);
            at[0] = h.x - at[3] * GiantRope.CLEAR;
            at[1] = h.y - at[4] * GiantRope.CLEAR;
            at[2] = h.z - at[5] * GiantRope.CLEAR;
        }
        double free = attack ? GiantStrike.NECK : 0.0;
        if (attack) drops++;
        CompoundTag tag = ropeTag(at, free);
        r.start(at, free);
        stopDead();
        rope = r;
        ropeDaze = attack ? DROP_DAZE : FALL_DAZE;
        if (trace > 0 || watching != null) {
            LivingEntity t = getTarget();
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant drop] %s from %s: head %.2f %.2f %.2f, %s",
                    attack ? "drops" : "falls", STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, from))], at[0], at[1], at[2],
                    t == null ? "no quarry" : String.format(Locale.ROOT, "quarry's feet %.2f %.2f %.2f", t.getX(), t.getY(), t.getZ())));
        }
        ropeLanded = false;
        fellFrom = from;
        toState(attack ? DROP : FALL, 0);
        entityData.set(DATA_ROPE, tag);
        if (attack) playSound(ERSounds.GIANT_SKEIN_NOTICE.get(), 2.5F, 0.7F);
    }

    /**
     * The rope's start as it is sent to the clients: each bead's place (from a block corner near it, as floats) and back.
     * The values in {@code at} are rounded to just what is sent, so the server drops exactly the rope the clients do.
     */
    private CompoundTag ropeTag(double[] at, double free) {
        int ox = Mth.floor(getX()), oy = Mth.floor(getY()), oz = Mth.floor(getZ());
        int[] bits = new int[at.length];
        for (int i = 0; i < at.length; i++) {
            int k = i % 6;
            double o = k == 0 ? ox : k == 1 ? oy : k == 2 ? oz : 0.0;
            float v = (float) (at[i] - o);
            bits[i] = Float.floatToIntBits(v);
            at[i] = o + v;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("N", drops);
        t.putInt("X", ox);
        t.putInt("Y", oy);
        t.putInt("Z", oz);
        t.putFloat("Free", (float) free);
        t.putIntArray("At", bits);
        return t;
    }

    /** A rope started as the tag from {@link #ropeTag} has it, or null when the tag holds none. */
    @Nullable
    static GiantRope ropeFrom(CompoundTag t, double length) {
        if (!t.contains("At")) return null;
        GiantRope r = new GiantRope(length, BODY_HALF, BODY_TAIL, BODY_ROOM);
        int[] bits = t.getIntArray("At");
        if (bits.length != 6 * r.beads()) return null;
        int ox = t.getInt("X"), oy = t.getInt("Y"), oz = t.getInt("Z");
        double[] at = new double[bits.length];
        for (int i = 0; i < bits.length; i++) {
            int k = i % 6;
            at[i] = (k == 0 ? ox : k == 1 ? oy : k == 2 ? oz : 0.0) + Float.intBitsToFloat(bits[i]);
        }
        r.start(at, t.getFloat("Free"));
        return r;
    }

    /**
     * Server, each tick of a drop or fall: the rope falls on (until it has come to rest), its box goes with its head,
     * its head's landing is felt, and once it has lain dazed long enough it crawls out of the coils it lies in.
     */
    private void dropTick() {
        GiantRope r = rope;
        if (r == null) { endDrop(); return; }
        if (!r.restful(ropeDaze)) r.tick(rock());
        if (trace > 0 && r.ticks() % 10 == 0) ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                "[giant drop] rope tick %d: head %s, %d beads holding, fastest bead %d moved %.3f", r.ticks(), r.headDown() ? "down" : "falling",
                r.holding(), r.movingAt(), r.moving()));
        double[] h = new double[3];
        r.head(h);
        setPos(h[0], h[1] - getBbHeight() / 2.0, h[2]);
        setDeltaMovement(Vec3.ZERO);
        resetFallDistance();
        if (!ropeLanded && r.headDown()) {
            ropeLanded = true;
            landedAt = stateTicks;
            land(new Vec3(h[0], h[1], h[2]), state() == DROP);
        }
        // dazed a moment where it lands, and up again once its body has come down after it
        if (r.restful(ropeDaze)) endDrop();
    }

    /**
     * Its head comes down: a heavy landing (the slam of a body against rock, the ground shaking near it). Dropped on its
     * quarry, it hits all that is under it as hard as its bite and throws them back (Taran's card): fair, as it hung a
     * long moment first, rattling, grit falling, and drops only where its head hung.
     */
    private void land(Vec3 at, boolean attack) {
        if (!(level() instanceof ServerLevel l)) return;
        Vec3 on = at.subtract(0.0, GiantRope.CLEAR - 0.05, 0.0);
        BlockPos under = BlockPos.containing(on.x, on.y - 0.2, on.z);
        BlockState floor = l.getBlockState(under);
        SoundEvent thud = floor.isAir() ? SoundEvents.STONE_BREAK : floor.getSoundType(l, under, this).getBreakSound();
        RuneFx.slam(l, on, new Vec3(0, 1, 0), attack ? 1.0F : 0.6F, thud);
        playSound(ERSounds.GIANT_SKEIN_STEP.get(), 2.5F, 0.6F);
        if (trace > 0 || watching != null) ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                "[giant drop] its head comes down at %.2f %.2f %.2f after %d ticks", at.x, at.y, at.z, stateTicks));
        if (!attack) return;
        playSound(ERSounds.GIANT_SKEIN_BITE.get(), 2.0F, 0.8F);
        AABB box = new AABB(at.x - DROP_HIT_RADIUS, at.y - 1.5, at.z - DROP_HIT_RADIUS, at.x + DROP_HIT_RADIUS, at.y + DROP_HIT_HEIGHT, at.z + DROP_HIT_RADIUS);
        for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && huntable(e)
                && !(e instanceof GiantSkeinEntity) && !(e instanceof SkeinEntity))) {
            if (Mth.square(e.getX() - at.x) + Mth.square(e.getZ() - at.z) > DROP_HIT_RADIUS * DROP_HIT_RADIUS) continue;
            if (!clear(at.add(0.0, 0.3, 0.0), e.getBoundingBox().getCenter())) continue;
            if (e.hurt(damageSources().mobAttack(this), BITE)) landings++;
            e.knockback(DROP_KNOCKBACK, at.x - e.getX(), at.z - e.getZ());
        }
    }

    /**
     * The drop is over: its body lies where the rope came to rest, its head's crawl is set down on the rock under its
     * head, and it hunts on (on the floor a while: it drops again no sooner than {@link #DROP_COOLDOWN}), or, after a fall
     * it did not mean, goes back to what it was doing.
     */
    private void endDrop() {
        GiantRope r = rope;
        byte was = state();
        if (r != null) layFromRope(r);
        rope = null;
        ropeLanded = false;
        entityData.set(DATA_ROPE, new CompoundTag());
        double[] s = new double[12];
        body.sample(0.0, 1.0F, s, 0);
        body.sample(0.6, 1.0F, s, 6);
        crawl.place(s[0] + s[3] * GiantCrawl.RIDE, s[1] + s[4] * GiantCrawl.RIDE, s[2] + s[5] * GiantCrawl.RIDE, s[0] - s[6], s[1] - s[7], s[2] - s[8]);
        crawl.up.set(s[3], s[4], s[5]);
        crawlSet = true;
        boolean held = crawl.settle(rock());
        if (trace > 0 || watching != null) ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                "[giant drop] up again after %d ticks (rope %d), head at %.2f %.2f %.2f, %s", stateTicks, r == null ? -1 : r.ticks(),
                s[0], s[1], s[2], held ? "on its rock" : "no rock to take hold of: it falls"));
        if (!held) { slips++; startDrop(false); return; }
        placeBox();
        dropCooldown = Math.max(dropCooldown, was == DROP ? DROP_COOLDOWN : 0);
        LivingEntity t = getTarget();
        boolean hunted = was == DROP || fellFrom == HUNT || fellFrom == ALERT || fellFrom == SEARCH || fellFrom == HANG
                || fellFrom == REAR || fellFrom == LUNGE || fellFrom == RECOVER;
        if (level().getGameTime() < retreatUntil) toState(RETREAT, RETREAT_TICKS);
        else if (hunted && hunting(t)) toState(HUNT, 0);
        else settle(SHORT_REST);
    }

    /**
     * Its body laid where the rope of its drop came to rest (on the server and each client alike): the body's own pose
     * from the rope, and its trail along the rope, straight on past its tail.
     */
    private void layFromRope(GiantRope r) {
        double len = (r.beads() - 1) * GiantRope.SPACE;
        double[] h = new double[6], s = new double[6], q = new double[6];
        r.sample(0.0, 1.0, h, 0);
        double[] joints = new double[6 * body.joints()];
        for (int j = 0; j < body.joints(); j++) {
            r.sample(Math.min(body.jointAt(j), len), 1.0, s, 0);
            System.arraycopy(s, 0, joints, 6 * j, 6);
        }
        r.sample(len - GiantRope.SPACE, 1.0, q, 0);
        r.sample(len, 1.0, s, 0);
        double tx = s[0] - q[0], ty = s[1] - q[1], tz = s[2] - q[2], tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl > 1.0E-6) { tx /= tl; ty /= tl; tz /= tl; }
        int n = (int) Math.ceil(TRAIL_KEEP / GiantRope.SPACE) + 1;
        float[] trail = new float[6 * n];
        for (int k = 0; k < n; k++) {
            double arc = k * GiantRope.SPACE;
            r.sample(Math.min(arc, len), 1.0, s, 0);
            double past = Math.max(0.0, arc - len);
            trail[6 * k] = (float) (s[0] + tx * past - h[0]);
            trail[6 * k + 1] = (float) (s[1] + ty * past - h[1]);
            trail[6 * k + 2] = (float) (s[2] + tz * past - h[2]);
            for (int i = 3; i < 6; i++) trail[6 * k + i] = (float) s[i];
        }
        body.pose(joints);
        body.read(trail, h[0], h[1], h[2]);
    }

    // --- senses

    /** It sees what is in the open round its head, not through rock, and not a player hidden in cattails unless right by its jaws. */
    public boolean canSee(LivingEntity t) {
        return !t.isInvisible() && !SilverCattailsBlock.hides(t, mouth(), FOUND_IN_REEDS) && clearTo(eyes(), t);
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

    /** Freezes dead: no way, no step this tick, no coasting, nothing it turns to. */
    private void stopDead() {
        getNavigation().stop();
        way = null;
        pace = 0.0;
        faceTo = null;
        perching = false;
        perchAt = null;
        crawl.speed = 0.0;
        setDeltaMovement(Vec3.ZERO);
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
        Vec3 up = normal(spot.rock()), feet = placedFeet(getType(), spot);
        // its head's crawl set down on its ride out from the rock there, and its box where its head is
        crawl.place(feet.x + up.x * GiantCrawl.RIDE, feet.y + up.y * GiantCrawl.RIDE, feet.z + up.z * GiantCrawl.RIDE, forward.x, forward.y, forward.z);
        crawl.up.set(up.x, up.y, up.z);
        crawlSet = crawl.settle(new LevelTerrain(level, true));
        if (crawlSet) {
            placeBox();
            forward = new Vec3(crawl.heading.x, crawl.heading.y, crawl.heading.z);
            entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
            entityData.set(DATA_UP, new Vector3f((float) crawl.up.x, (float) crawl.up.y, (float) crawl.up.z));
        }
        float[] laid = drape(level, feet, up, back);
        if (body.read(laid, getX(), getY(), getZ())) {
            body.layBody();
            placeSections();
        }
        footPoint = null;
    }

    private void aim(Vec3 pos, Direction rock, Vec3 fwd) {
        moveTo(pos.x, pos.y, pos.z, getYRot(), 0.0F);
        setDeltaMovement(Vec3.ZERO);
        entityData.set(DATA_SURFACE, (byte) rock.get3DDataValue());
        Vec3 up = normal(rock);
        entityData.set(DATA_UP, new Vector3f((float) up.x, (float) up.y, (float) up.z));
        Vec3 f = fwd.subtract(up.scale(fwd.dot(up)));
        forward = f.lengthSqr() > 1.0E-4 ? f.normalize() : (Math.abs(up.y) > 0.9 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0));
        entityData.set(DATA_FORWARD, new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
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
        Vec3 up = up();
        tag.putFloat("UpX", (float) up.x);
        tag.putFloat("UpY", (float) up.y);
        tag.putFloat("UpZ", (float) up.z);
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
        // its head's crawl is set down afresh where its box is, on its first tick (setCrawl)
        Vec3 up = new Vec3(tag.getFloat("UpX"), tag.getFloat("UpY"), tag.getFloat("UpZ"));
        if (up.lengthSqr() > 0.25) {
            up = up.normalize();
            entityData.set(DATA_UP, new Vector3f((float) up.x, (float) up.y, (float) up.z));
        }
        crawlSet = false;
        if (tag.contains("RiftX")) {
            hasRift = true;
            riftX = tag.getInt("RiftX");
            riftZ = tag.getInt("RiftZ");
            riftLooked = false;
        }
        retreatUntil = tag.getLong("RetreatUntil");
        setPersistenceRequired();
    }

    /** Its body as it lies, at {@link #KEPT_AT} along it (none before it has one; dropping, the rope as it falls). */
    private float[] kept() {
        GiantRope r = rope;
        if (r != null && dropping()) {
            double len = (r.beads() - 1) * GiantRope.SPACE;
            double[] s = new double[6];
            float[] out = new float[6 * KEPT_AT.length];
            for (int k = 0; k < KEPT_AT.length; k++) {
                r.sample(Math.min(KEPT_AT[k], len), 1.0, s, 0);
                out[6 * k] = (float) (s[0] - getX());
                out[6 * k + 1] = (float) (s[1] - getY());
                out[6 * k + 2] = (float) (s[2] - getZ());
                for (int i = 3; i < 6; i++) out[6 * k + i] = (float) s[i];
            }
            return out;
        }
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
        Vec3 up = up();
        buf.writeFloat((float) up.x);
        buf.writeFloat((float) up.y);
        buf.writeFloat((float) up.z);
        float[] kept = kept();
        buf.writeVarInt(kept.length);
        for (float v : kept) buf.writeFloat(v);
    }

    @Override
    public void readSpawnData(FriendlyByteBuf buf) {
        Vec3 fwd = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        Vec3 up = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
        int n = buf.readVarInt();
        if (n < 0 || n > 6 * KEPT_AT.length) return;
        float[] kept = new float[n];
        for (int i = 0; i < n; i++) kept[i] = buf.readFloat();
        clientForward = fwd.lengthSqr() > 1.0E-4 ? fwd.normalize() : clientForward;
        if (!body.read(kept, getX(), getY(), getZ())) return;
        body.layBody();
        placeSections();
        poseHead(fwd, up.lengthSqr() > 0.25 ? up : new Vec3(0, 1, 0));
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
        if (way != null) b.append(String.format(Locale.ROOT, ", node %d of %d of its way", wayAt, way.getNodeCount()))
                .append(perching && perchAt != null ? String.format(Locale.ROOT, ", climbing over its quarry (to %.0f %.0f %.0f)", perchAt.x, perchAt.y, perchAt.z) : "");
        else if (perching) b.append(", over its quarry, waiting its chance to drop");
        else if (wayStuck) b.append(", gave up its last way (no headway)");
        else if (wayWhy != null && (s == HUNT || s == SEARCH || s == CRAWL)) b.append(", no way (").append(wayWhy).append(")");
        if (crawlSet && !dropping()) b.append(String.format(Locale.ROOT, ", pace %.3f (gap %.2f)", crawl.speed, crawl.gap()));
        GiantRope rp = rope;
        if (s == DROP || s == FALL) b.append(rp == null ? "" : rp.headDown() ? rp.holding() > 0 ? ", dazed, its body pouring down" : ", dazed where it fell" : ", its head falling");
        if (dropCooldown > 0) b.append(", drops again in ").append(dropCooldown / 20).append("s");
        if (drops > 0) b.append(", has dropped ").append(drops).append(drops == 1 ? " time" : " times")
                .append(landings > 0 ? " (landed on " + landings + ")" : "");
        if (slips > 0) b.append(", has let go of its rock ").append(slips).append(slips == 1 ? " time" : " times");
        if (watching != null) b.append(", watched ").append(watching.ticks).append(" of ").append(watching.length).append(" ticks");
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
            case HANG, DROP -> {
                // hangs (and drops) where it is, at whoever asked, whether or not it is over them
                if (who == null || !huntable(who) || !body.laid() || dropping()) return false;
                dropCooldown = 0;
                startHang(who);
                if (s == DROP) startDrop(true);
            }
            case FALL -> {
                if (!body.laid() || dropping()) return false;
                startDrop(false);
            }
            default -> { return false; }
        }
        return true;
    }

    public void trace(int ticks) { trace = Math.max(0, ticks); }

    /**
     * For /remix giant watch: over the next {@code ticks}, how its body as drawn lies against the rock (its line and its
     * flanks, every 0.6 blocks along it), how its head rides, how far it goes and how often it turns to another face,
     * lets go or gives up a way, and its pace roaming and hunting, logged every minute and at the end. With
     * {@code roam}, it never rests (it crawls on to its next leg instead), for the most ground covered.
     */
    public void watch(int ticks, boolean roam) {
        watching = ticks > 0 ? new Watch(ticks, roam, slips, waysGivenUp, drops, landings) : null;
    }

    /** What /remix giant watch has seen so far. */
    private static final class Watch {
        final int length, slips0, given0, drops0, landings0;
        final boolean roam;
        final RockClearance rc = new RockClearance();
        final double[] q = new double[4];
        double[] arcs = new double[0], at = new double[0];
        int ticks, lineIn, lineTouch, flankIn, flips, jumps, hangs, roamTicks, huntTicks;
        double line = Double.MAX_VALUE, flank = Double.MAX_VALUE, gapLo = Double.MAX_VALUE, gapHi, crawled, most;
        double roamPace, huntPace, huntTop;
        @Nullable Direction side;
        @Nullable Vec3 foot;
        byte was = -1;

        Watch(int length, boolean roam, int slips0, int given0, int drops0, int landings0) {
            this.length = length;
            this.roam = roam;
            this.slips0 = slips0;
            this.given0 = given0;
            this.drops0 = drops0;
            this.landings0 = landings0;
        }
    }

    /** Server, after its body has moved: one tick of /remix giant watch. */
    private void watchTick() {
        Watch w = watching;
        if (w == null || level().isClientSide || !body.laid()) return;
        byte s = state();
        if (w.roam && s == REST && !dropping() && getTarget() == null) forceState(CRAWL, 0, null);
        if (s == HANG && w.was != HANG) w.hangs++;
        w.was = s;
        w.ticks++;
        // what is drawn: its body every 0.6 blocks along it, its line (BODY_LIFT out along its back) and its flanks
        int n = (int) Math.floor(body.length() / 0.6) + 1;
        if (w.arcs.length != n) {
            w.arcs = new double[n];
            for (int i = 0; i < n; i++) w.arcs[i] = i * 0.6;
            w.at = new double[6 * n];
        }
        bodyAt(w.arcs, 1.0F, w.at);
        LevelTerrain t = rock();
        double[] a = w.at, q = w.q;
        double line = Double.MAX_VALUE, flank = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int o = 6 * i;
            double cx = a[o] + a[o + 3] * BODY_LIFT, cy = a[o + 1] + a[o + 4] * BODY_LIFT, cz = a[o + 2] + a[o + 5] * BODY_LIFT;
            line = Math.min(line, w.rc.nearest(t, cx, cy, cz, BODY_LIFT + 0.5, q) ? q[0] : BODY_LIFT + 0.5);
            int f = 6 * Math.max(0, i - 1), b = 6 * Math.min(n - 1, i + 1);
            double tx = a[f] - a[b], ty = a[f + 1] - a[b + 1], tz = a[f + 2] - a[b + 2];
            double sx = ty * a[o + 5] - tz * a[o + 4], sy = tz * a[o + 3] - tx * a[o + 5], sz = tx * a[o + 4] - ty * a[o + 3];
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-6) continue;
            double half = BODY_HALF + (BODY_TAIL - BODY_HALF) * w.arcs[i] / Math.max(1.0, body.length());
            for (int side = -1; side <= 1; side += 2) {
                double k = half * side / sl;
                flank = Math.min(flank, w.rc.nearest(t, cx + sx * k, cy + sy * k, cz + sz * k, BODY_ROOM + 0.5, q) ? q[0] : BODY_ROOM + 0.5);
            }
        }
        w.line = Math.min(w.line, line);
        w.flank = Math.min(w.flank, flank);
        if (line < BODY_LIFT - 0.05) w.lineTouch++;
        if (line < BODY_LIFT - 0.3) w.lineIn++;
        if (flank <= 0.0) w.flankIn++;
        // its head: how it rides, how far it goes, and the face it is on
        boolean onRock = crawlSet && !dropping();
        if (onRock) {
            double g = crawl.gap();
            w.gapLo = Math.min(w.gapLo, g);
            w.gapHi = Math.max(w.gapHi, g);
        }
        Vec3 ft = footPoint;
        if (onRock && ft != null && w.foot != null) {
            double d = ft.distanceTo(w.foot);
            w.crawled += d;
            w.most = Math.max(w.most, d);
            if (d > 1.0) w.jumps++;
        }
        w.foot = onRock ? ft : null;
        Direction sd = surface();
        if (sd != null && w.side != null && sd != w.side) w.flips++;
        if (sd != null) w.side = sd;
        if (onRock) {
            if (s == HUNT) {
                w.huntTicks++;
                w.huntPace += crawl.speed;
                w.huntTop = Math.max(w.huntTop, crawl.speed);
            } else if (s == CRAWL || s == SEARCH || s == RETREAT) {
                w.roamTicks++;
                w.roamPace += crawl.speed;
            }
        }
        boolean done = w.ticks >= w.length;
        if (done || w.ticks % 1200 == 0) {
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT,
                    "[giant watch] %s %d ticks%s: body line worst %.2f of %.2f (touching rock %d ticks, in it %d), flanks worst %.2f (in rock %d ticks), "
                            + "head ride %.2f to %.2f, crawled %.1f blocks (most %.2f a tick, %d jumps over a block), %d turns to another face, "
                            + "%d slips off its rock, %d ways given up, roam pace %.3f (%d ticks), hunt pace %.3f (top %.3f, %d ticks), "
                            + "%d hangs, %d drops, %d landed on; now %s",
                    done ? "done" : "so far", w.ticks, w.roam ? " (roaming)" : "", w.line, BODY_LIFT, w.lineTouch, w.lineIn,
                    w.flank, w.flankIn, w.gapLo == Double.MAX_VALUE ? 0.0 : w.gapLo, w.gapHi, w.crawled, w.most, w.jumps, w.flips,
                    slips - w.slips0, waysGivenUp - w.given0, w.roamTicks == 0 ? 0.0 : w.roamPace / w.roamTicks, w.roamTicks,
                    w.huntTicks == 0 ? 0.0 : w.huntPace / w.huntTicks, w.huntTop, w.huntTicks, w.hangs, drops - w.drops0,
                    landings - w.landings0, describe()));
        }
        if (done) watching = null;
    }

    /**
     * For /remix giant hold (pictures of its strike): holds it {@code k} (0 to 1) through the strike's phase {@code s}
     * (REAR, LUNGE or RECOVER), striking at {@code aim}, until let go ({@link #letGo}). False for another state.
     */
    public boolean hold(byte s, float k, Vec3 aim) {
        if ((s != REAR && s != LUNGE && s != RECOVER) || dropping()) return false;
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
        if (dropping()) return;
        entityData.set(DATA_HELD, -1.0F);
        strikeCooldown = 0;
        dormant = false;
        startStrike(t);
    }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!level().isClientSide && dormantNow()) return;
        if (rockView != null) rockView.fresh();
        super.tick();
        boolean client = level().isClientSide;
        if (!client && isAlive()) {
            moveHead();
            movedLast = Math.sqrt(Mth.square(getX() - xo) + Mth.square(getY() - yo) + Mth.square(getZ() - zo));
            updateForward();
        }
        GiantRope r = rope;
        if (client && r != null && dropping() && !r.restful(ropeDaze)) r.tick(rock());
        followHead();
        if (client) clientTick();
        moveBody();
    }

    /** Server: whether it lies dormant this tick: resting on its rock, its body laid, unhurt, with no player near. */
    private boolean dormantNow() {
        if (state() != REST || !body.laid() || !isAlive() || hurtTime > 0 || isOnFire() || isInWater() || isInLava()
                || surface() == null || isPassenger() || trace > 0 || watching != null) return dormant = false;
        if ((tickCount + getId()) % WAKE_LOOK == 0) {
            boolean near = false;
            for (Player p : level().players()) if (p.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE) { near = true; break; }
            dormant = !near;
        }
        return dormant;
    }

    /**
     * The point under its head goes on its trail: on the server its crawl's, on the client found the same way from where
     * its box is and the way out the server sends (GiantCrawl#footAt). None while it drops: its body is the rope then.
     */
    private void followHead() {
        if (rope != null && dropping()) return;
        boolean client = level().isClientSide;
        Vec3 f = null, n = null;
        if (client) {
            Vec3 u = syncedUp();
            Vec3 c = position().add(0.0, getBbHeight() / 2.0, 0.0).add(u.scale(BOX_IN));
            if (crawl.footAt(rock(), c.x, c.y, c.z, u.x, u.y, u.z, footV, footN)) {
                f = new Vec3(footV.x, footV.y, footV.z);
                n = new Vec3(footN.x, footN.y, footN.z);
            } else {
                f = c.subtract(u.scale(GiantCrawl.RIDE));
                n = u;
            }
        } else if (crawlSet && crawl.foot(rock(), footV, footN)) {
            f = new Vec3(footV.x, footV.y, footV.z);
            n = new Vec3(footN.x, footN.y, footN.z);
        }
        if (f == null) {
            Vec3 u = up();
            f = position().add(0.0, getBbHeight() / 2.0, 0.0).add(u.scale(BOX_IN - GiantCrawl.RIDE));
            n = u;
        }
        footPoint = f;
        if (!body.laid()) {
            Vec3 fwd = client ? clientForward : forward;
            body.lay(f.x, f.y, f.z, -fwd.x, -fwd.y, -fwd.z, n.x, n.y, n.z);
            placeSections();
            if (client) poseHead(clientForward, n);
            return;
        }
        body.record(f.x, f.y, f.z, n.x, n.y, n.z);
    }

    /**
     * Its body comes on after its head, and is kept out of the rock everywhere along it, on the server (what can be hit)
     * and on each client (what is drawn) alike. Lying long still it is left as it lies.
     */
    private void moveBody() {
        if (!body.laid()) return;
        if (rope == null || !dropping()) {
            boolean still = state() == REST && (level().isClientSide ? clientStateTicks : stateTicks) > 100 && body.lastStep() < 1.0E-4;
            if (!still) {
                Vec3 fwd = level().isClientSide ? clientForward : forward;
                body.follow(fwd.x, fwd.y, fwd.z);
                body.keepOut(rock(), BODY_LIFT, BODY_HALF, BODY_TAIL, BODY_ROOM);
            }
        }
        placeSections();
        watchTick();
        if (trace > 0 && !level().isClientSide) {
            trace--;
            Vec3 f = footPoint == null ? Vec3.ZERO : footPoint;
            ExplorationRemix.LOGGER.info(String.format(Locale.ROOT, "[giant trace] %d %s on %s, foot %.2f %.2f %.2f, up %.2f %.2f %.2f, pace %.3f gap %.2f, step %.3f, pushed %d (most %.2f), %s",
                    tickCount, STATE_NAMES[Math.max(0, Math.min(STATE_NAMES.length - 1, state()))], surface() == null ? "nothing" : surface().getName(),
                    f.x, f.y, f.z, crawl.up.x, crawl.up.y, crawl.up.z, crawl.speed, crawl.gap(), body.lastStep(), body.pushedJoints(), body.pushedMost(), body.moves()));
        }
    }

    // ------------------------------------------------------------------------------------------------ client

    private void clientTick() {
        runO = run; legPhaseO = legPhase; glowO = glow; liftO = lift; jawO = jaw; rippleO = ripple; shudderO = shudder;
        System.arraycopy(feelerYaw, 0, feelerYawO, 0, 2);
        System.arraycopy(feelerPitch, 0, feelerPitchO, 0, 2);
        headOrientO.set(headOrient);
        clientStateTicks++;
        byte s = state();
        GiantRope r = rope;
        boolean falling = r != null && dropping() && !r.headDown(), dazed = r != null && dropping() && r.headDown();
        double moved = body.lastStep();
        // its legs flail as it falls
        boolean moving = moved > 0.02 || falling;
        run += ((moving ? 1.0F : 0.0F) - run) * 0.2F;
        // the legs' wave keeps pace with the ground it covers (each foot set down where it is carried back from)
        legPhase += (float) (falling ? 0.5 : moved * 2.1);
        float liftT = s == ALERT ? 1.0F : s == HUNT ? 0.35F : 0.0F;
        lift += (liftT - lift) * 0.12F;
        float jawT = switch (s) {
            case REAR, HANG -> 0.6F + 0.4F * (float) strikeProgress(0.0F);
            case LUNGE -> 1.0F;
            case RECOVER -> 0.3F;
            case HUNT, ALERT -> 0.2F + 0.1F * Mth.sin(tickCount * 0.3F);
            case DROP, FALL -> dazed ? 0.4F : 1.0F;
            default -> 0.05F;
        };
        jaw += (jawT - jaw) * 0.35F;
        if (s == REAR || s == HANG) shudder = Math.max(shudder, 0.2F + 0.6F * (float) strikeProgress(0.0F));
        else shudder *= 0.8F;
        // its tips glow steadily enough to show it across the rift, brighter as it hunts and brightest as it strikes or
        // hangs to drop; lying dazed where it landed, they dim
        float glowT = isDeadOrDying() ? 0.0F : switch (s) {
            case REAR, LUNGE, HANG -> 1.0F;
            case DROP, FALL -> dazed ? 0.55F : 0.9F;
            case ALERT, HUNT, RECOVER, SEARCH -> 0.9F;
            case CRAWL, RETREAT -> 0.8F;
            default -> 0.75F;
        };
        glow += (glowT - glow) * (glowT > glow ? 0.15F : dazed ? 0.2F : 0.03F);
        feelers(moving, s == HUNT || s == ALERT || s == SEARCH || s == HANG);
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

    /**
     * Its head's turn: belly to its rock (the way out the server sends), pointing the way the server says, turning
     * slowly; dropping, along the rope's head as it swings.
     */
    private void orientHead() {
        Vec3 upT, want;
        GiantRope r = rope;
        double ease = 0.25, turn = HEAD_TURN;
        if (r != null && dropping()) {
            double[] a = new double[12];
            r.sample(0.0, 1.0, a, 0);
            r.sample(GiantRope.SPACE, 1.0, a, 6);
            upT = new Vec3(a[3], a[4], a[5]);
            Vec3 f = new Vec3(a[0] - a[6], a[1] - a[7], a[2] - a[8]);
            want = f.lengthSqr() > 1.0E-6 ? f.normalize() : clientForward;
            ease = 0.5;
            turn = 0.5;
        } else {
            upT = syncedUp();
            Vector3f sf = entityData.get(DATA_FORWARD);
            want = new Vec3(sf.x, sf.y, sf.z);
        }
        if (clientUp.dot(upT) < -0.99) clientUp = clientUp.add(clientForward.cross(clientUp).scale(0.3));
        clientUp = clientUp.lerp(upT, ease);
        if (clientUp.lengthSqr() < 1.0E-4) clientUp = upT;
        clientUp = clientUp.normalize();
        if (clientForward.dot(want) < -0.99) clientForward = clientForward.add(clientUp.cross(clientForward).scale(0.3));
        clientForward = clientForward.lerp(want, turn);
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
