package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.combat.WindUp;
import com.explorationremix.creature.body.LurkerBody;
import com.explorationremix.creature.body.LurkerCrawl;
import com.explorationremix.creature.body.LurkerRig;
import com.explorationremix.creature.body.TerrainPatch;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GardensField;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The vent urchin of the Deep Gardens (0.21.3, Taran: "make the vent spiders more sea urchin-like than spider-like and
 * have them straddle the vents that they live in"; the 0.21.0 chimney lurker, its id kept so the ones already set down
 * in a world stay). Taran's cards set its look: a round black test about a block and a half across, sixteen walking
 * spines each bending once at a knee close to the test, a dense coat of short spines, five teeth under it; it sits half
 * sunk in its vent's mouth, its spines spread over the rim all round; neon bands down every spine glow in its vent's
 * colour once it sees someone; it glides any way and never turns; its coat points at footsteps, then at you; it strikes
 * with a crouch and a leap, flipping to bite with its five-toothed mouth; its spines prick only what bumps into it. The
 * 0.21.0 cards it keeps: mostly black while it waits, the flash with a dry rattle as it sees someone, a rush down its
 * vent, a short chase and back up, about twelve hearts, about half the live vents, a new one moving in days after one
 * is killed, a strike at the stilt mantises now and then.
 *
 * <p>On its vent it moves as the spider did: its body's middle carried over the rock itself by a crawler
 * ({@link LurkerCrawl}: it reads the rock round it each tick, so a vent a player has dug into is climbed as it now is),
 * its box with it, the game's own movement off; between its seat and its rim it glides. On the floor it is a mob like
 * any other (it climbs walls in its way, as a spider does). Its body is drawn on the client from {@link LurkerBody}:
 * sixteen spines' points set on the blocks under them.
 *
 * <p>Its senses while it waits: it looks out from its crown ({@link LurkerVents.Home#peek}), so a player in sight within
 * {@link #SIGHT} blocks (one sneaking, half that) brings it out; its spines over its rim feel round it; footsteps round
 * its vent's foot stir it (its coat turns to them, its spines tap on the rim) and, kept up, bring it out unseen; a
 * player within {@link #TOUCH} of it, or a blow, at once.
 */
public class ChimneyLurkerEntity extends Monster implements WindUp {
    // ---- states (synced: its body and its glow pose from them)
    public static final byte WAIT = 0, STIR = 1, EMERGE = 2, HUNT = 3, CROUCH = 4, LEAP = 5, RECOIL = 6, RETURN = 7, CLIMB = 8, SETTLE = 9;

    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(ChimneyLurkerEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_COLOUR = SynchedEntityData.defineId(ChimneyLurkerEntity.class, EntityDataSerializers.BYTE);
    /** The way it heads while it clings to its vent (zero on the floor, where its body's yaw says). */
    private static final EntityDataAccessor<Vector3f> DATA_HEADING = SynchedEntityData.defineId(ChimneyLurkerEntity.class, EntityDataSerializers.VECTOR3);
    /** Sat in its vent's mouth, or gliding between there and its rim: its body held upright. */
    private static final EntityDataAccessor<Boolean> DATA_UPRIGHT = SynchedEntityData.defineId(ChimneyLurkerEntity.class, EntityDataSerializers.BOOLEAN);
    /** What its coat points at: the one whose footsteps it feels, then its quarry (an entity's id; -1 none). */
    private static final EntityDataAccessor<Integer> DATA_FOCUS = SynchedEntityData.defineId(ChimneyLurkerEntity.class, EntityDataSerializers.INT);

    // ---- tuning
    /** About twelve hearts (card), and its bite (difficulty scales it: 4 on easy, 9 on hard). */
    public static final float HEALTH = 24.0F, BITE = 6.0F;
    static final double SPEED = 0.3;
    /** Its speeds on the floor (times its movement speed): hunting (about a sprint's), going home. */
    static final double HUNT_SPEED = 1.2, HOME_SPEED = 0.75;
    /** Its speeds over its vent's rock (blocks a tick): rushing out headfirst, climbing home. */
    public static final double EMERGE_CRAWL = 0.27, HOME_CRAWL = 0.13;
    /** It sees a player this far from over its rim (one sneaking, half that); one this near it notices at once. */
    public static final double SIGHT = 20.0, SIGHT_SNEAK = 10.0, TOUCH = 3.5;
    /**
     * Waiting, its spines over its rim feel round it: a player (or a mantis) this far from its rim's outer edge on their
     * side, in sight of that edge, it notices (one sneaking, half that).
     */
    public static final double RIM_SIGHT = 12.0;
    /** Footsteps this far (across) from its vent's axis, under its rim, carry up the rock to it. */
    public static final double TREMOR = 15.0;
    /** Footsteps felt (a look every four ticks; a sprint counts three) this long stir it, and this long bring it out. */
    static final int STIR_AT = 5, STIR_OUT = 22;
    /** It chases no further than this from its vent's axis (across), no longer than this (ticks), and gives up on a quarry out of sight this long. */
    public static final double LEASH = 24.0;
    static final int CHASE_MAX = 600, MANTIS_CHASE = 200, LOST_SIGHT = 80;
    /** The flash and rattle before it rushes out, the crouch before its leap, the rest after a leap (ticks). */
    static final int NOTICE_TICKS = 6, CROUCH_TICKS = 10, LEAP_REST = 40;
    /** How far it leaps (blocks, across), and how much higher or lower its quarry may be. */
    static final double LEAP_MIN = 1.2, LEAP_MAX = 5.5, LEAP_UP = 2.5, LEAP_DOWN = 3.5;
    /** Its rise out of its mouth and over its rim, and its glide back down into its seat (ticks). */
    public static final int RISE_TICKS = 10, SETTLE_TICKS = 26;
    /** What bumps into it is pricked this hard (Taran's card: "Only if you bump into it"), at most this often (ticks). */
    static final float PRICK = 2.0F;
    static final int PRICK_EVERY = 10;
    /** Ticks on level rock, well under its rim, before its way out is done and it runs on the floor. */
    public static final int FLOOR_TICKS = 5;
    /** A player it gave up on is let be this long (unless they come at it). */
    static final int LET_BE = 200;
    /** Mantis strikes: only after waiting this long (ticks), a roll each second; a leap at one misses this often. */
    static final int STRIKE_WAIT = 1200, STRIKE_ROLL = 45;
    static final double STRIKE_REACH = 18.0, STRIKE_MISS = 0.8;
    /** Waiting with no player within this, its ticks are skipped (as the mantis's); players are looked for once a second. */
    public static final double WAKE_RANGE = 96.0;
    static final int WAKE_LOOK = 20;

    // ---- server
    @Nullable private LurkerVents.Home home;
    /** Where its last way out came down to the floor: where it climbs home from (null until known). */
    @Nullable private Vec3 foot;
    /** With no vent (one from an egg far from any): where it lurks on the floor. */
    @Nullable private BlockPos lair;
    @Nullable private LurkerCrawl crawl;
    /** How its way out over its rim (or home into its mouth) goes, judged tick by tick while it crawls. */
    @Nullable private LurkerVents.Out outJudge;
    @Nullable private LurkerVents.In inJudge;
    @Nullable private com.explorationremix.creature.body.Terrain rockOf;
    private int stateTicks, stateLength, stir, waitTicks, rest, chaseTicks, seenAgo, flatTicks, sightTicks, climbTries, pathTicks, crackTicks;
    private boolean sighted, dormant;
    private final Vector3d progressFrom = new Vector3d();
    private int progressTicks;
    @Nullable private UUID letBe;
    private int letBeTicks;
    /** A quarry set by /remix lurker hunt (any creature, for tests), hunted as a player would be. */
    @Nullable private UUID commanded;
    @Nullable private Vec3 leapFrom;
    /** For /remix lurker: the last thing that went wrong on its vent's rock (its way lost, its hold lost), and when. */
    @Nullable private String trouble;
    /** Sat on its seat in its vent's mouth (its box put there, the game's movement off). */
    private boolean onSeat;
    /** Gliding between its seat and its rim: from, to, how far along (ticks) and how long. */
    @Nullable private Vec3 glideFrom, glideTo;
    private int glideT, glideN;
    /** Its home is a 0.21.0 lurker's spot, to be read again as a seat once its vent's blocks are loaded. */
    private boolean reseat;
    private int prickTicks;

    // ---- client
    @Nullable private LurkerBody body;
    @Nullable private LurkerBody.Drive drive;
    @Nullable private TerrainPatch rock;
    @Nullable private com.explorationremix.creature.body.LevelTerrain terrain;
    private int[] bounds;
    private int drawnAt = -1000, airTicks, lastStep, climbFade;
    private byte seenState = WAIT;
    private float glow, glowO, flash, flashO;
    /** Client: its body is kept while drawn in the last this many ticks, or while a player is near enough to hear its feet. */
    static final int BODY_KEEP = 40;
    static final double BODY_HEAR = 20.0;

    public ChimneyLurkerEntity(EntityType<? extends ChimneyLurkerEntity> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
        xpReward = 10;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.MOVEMENT_SPEED, SPEED)
                .add(Attributes.ATTACK_DAMAGE, BITE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STATE, WAIT);
        entityData.define(DATA_COLOUR, (byte) -1);
        entityData.define(DATA_HEADING, new Vector3f());
        entityData.define(DATA_UPRIGHT, false);
        entityData.define(DATA_FOCUS, -1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    /** It climbs walls in its way, as a spider does (its body clings to them). */
    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WallClimberNavigation(this, level);
    }

    public byte state() { return entityData.get(DATA_STATE); }

    private void setState(byte s) {
        if (state() != s) entityData.set(DATA_STATE, s);
        stateTicks = 0;
    }

    private void toState(byte s, int length) {
        setState(s);
        stateLength = length;
    }

    /** Its colour: its vent's. */
    public Neon colour() {
        int c = entityData.get(DATA_COLOUR);
        return c < 0 || c > 3 ? Neon.VIOLET : Neon.ofCode(c);
    }

    public void setColour(Neon n) { entityData.set(DATA_COLOUR, (byte) n.ordinal()); }

    @Nullable public LurkerVents.Home home() { return home; }
    public boolean dormant() { return dormant; }

    /** Its body's middle: its box's feet and its ride over the rock. */
    private Vec3 centre() { return position().add(0, ride(), 0); }

    private static double ride() { return LurkerRig.shared().spec.ride; }

    /** Makes it the urchin of a vent, sat in its mouth. */
    public void settleAt(LurkerVents.Home h) {
        home = h;
        setColour(h.colour());
        setPersistenceRequired();
        moveTo(h.waitX(), h.boxY(), h.waitZ(), (float) Math.toDegrees(h.azimuth()) - 90.0F, 0.0F);
        sitDown();
        toState(WAIT, 0);
    }

    /** Onto its seat in its vent's mouth: its box there, upright, the game's movement off. */
    private void sitDown() {
        crawl = null;
        glideFrom = glideTo = null;
        setPos(home.waitX(), home.boxY(), home.waitZ());
        setDeltaMovement(Vec3.ZERO);
        noPhysics = true;
        setNoGravity(true);
        onSeat = true;
        entityData.set(DATA_HEADING, new Vector3f(0, 1, 0));
        entityData.set(DATA_UPRIGHT, true);
    }

    // ------------------------------------------------------------------------------------------------ spawning, saving

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (home == null && reason != MobSpawnType.CHUNK_GENERATION) adoptVent(level);
        if (home == null) {
            lair = blockPosition();
            if (entityData.get(DATA_COLOUR) < 0) setColour(Neon.VIOLET);
            toState(WAIT, 0);
        }
        return out;
    }

    /** One from an egg or a command takes the nearest live vent with no urchin of its own within 40 blocks, and goes home to it. */
    private void adoptVent(ServerLevelAccessor level) {
        com.explorationremix.world.terrain.Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null) return;
        int bottom = t.shape.bottom(com.explorationremix.world.terrain.Terrain.GARDENS);
        // nearest first: the first with room for it and a way out and back is its (each search a few trial crawls)
        List<GardensField.LiveVent> vents = new java.util.ArrayList<>(t.gardens.liveVents(getX() - 40, getZ() - 40, getX() + 40, getZ() + 40));
        vents.sort(java.util.Comparator.comparingDouble(v -> (v.x() - getX()) * (v.x() - getX()) + (v.z() - getZ()) * (v.z() - getZ())));
        LurkerVents.Home best = null;
        for (GardensField.LiveVent v : vents) {
            double d = (v.x() - getX()) * (v.x() - getX()) + (v.z() - getZ()) * (v.z() - getZ());
            if (d >= 40.0 * 40.0 || !LurkerVents.fits(v)) continue;
            if (!level.getEntitiesOfClass(ChimneyLurkerEntity.class, new AABB(v.x() - 24, getY() - 48, v.z() - 24, v.x() + 24, getY() + 48, v.z() + 24),
                    e -> e != this && e.home != null && e.home.key() == v.key()).isEmpty()) continue;
            best = LurkerVents.find(level, v, bottom, (x, z) -> level.hasChunk(x >> 4, z >> 4));
            if (best != null) break;
        }
        if (best == null) return;
        home = best;
        setColour(best.colour());
        setPersistenceRequired();
        toState(RETURN, 0);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Colour", entityData.get(DATA_COLOUR));
        tag.putByte("State", state());
        if (home != null) tag.put("Home", home.save());
        if (foot != null) {
            tag.putDouble("FootX", foot.x);
            tag.putDouble("FootY", foot.y);
            tag.putDouble("FootZ", foot.z);
        }
        if (lair != null) {
            tag.putInt("LairX", lair.getX());
            tag.putInt("LairY", lair.getY());
            tag.putInt("LairZ", lair.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Colour")) entityData.set(DATA_COLOUR, tag.getByte("Colour"));
        home = tag.contains("Home") ? LurkerVents.Home.load(tag.getCompound("Home")) : null;
        // a 0.21.0 lurker's home (a spot on its flue's wall): read again as a seat in its mouth once it ticks
        reseat = home != null && LurkerVents.Home.old(tag.getCompound("Home"));
        foot = tag.contains("FootX") ? new Vec3(tag.getDouble("FootX"), tag.getDouble("FootY"), tag.getDouble("FootZ")) : null;
        lair = tag.contains("LairX") ? new BlockPos(tag.getInt("LairX"), tag.getInt("LairY"), tag.getInt("LairZ")) : null;
        // back where it was: sat in its mouth (put on its seat as it next ticks), or (anything under way) going home
        byte s = tag.getByte("State");
        toState(s == WAIT || s == STIR || s == SETTLE ? WAIT : RETURN, 0);
        if (state() == RETURN) setNoGravity(false);
    }

    /** Set down with the world, it stays when no one is near, as the animals a world starts with do. */
    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean requiresCustomPersistence() { return true; }

    /** In Peaceful it stays (it never notices a player there: it waits on in its vent). */
    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    // ------------------------------------------------------------------------------------------------ the box

    /** Its box is its test's, standing on its spines; its spines reach some three blocks round it. */
    @Override
    public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(3.7, 2.2, 3.7); }

    /** It sees from its crown. */
    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions size) { return 1.6F; }

    /** On its vent nothing moves it but its own legs. */
    @Override
    public boolean isPushable() { return !clinging() && super.isPushable(); }

    @Override
    public void knockback(double strength, double x, double z) {
        if (!clinging()) super.knockback(strength, x, z);
    }

    /** Rock round its box is what it holds to, never what smothers it. */
    @Override
    public boolean isInWall() { return false; }

    /** Its long spines take any fall. */
    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    /** It climbs walls as a spider does: wherever it runs into one. */
    @Override
    public boolean onClimbable() { return !clinging() && horizontalCollision; }

    /** Whether it clings to its vent now (sat in its mouth, gliding, or its body carried over the rock: the game's movement off). */
    private boolean clinging() {
        return crawl != null || onSeat || glideTo != null;
    }

    @Override
    public void travel(Vec3 input) {
        if (clinging()) {
            calculateEntityAnimation(false);
            return;
        }
        super.travel(input);
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        byte s = state();
        return s == HUNT || s == RETURN ? ERSounds.CHIMNEY_LURKER_CLICK.get() : null;
    }

    @Override
    public int getAmbientSoundInterval() { return 140; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.CHIMNEY_LURKER_HURT.get(); }

    @Override
    protected SoundEvent getDeathSound() { return ERSounds.CHIMNEY_LURKER_DEATH.get(); }

    /** Its steps are its feet's own (see {@link #footfall}). */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    // ------------------------------------------------------------------------------------------------ being hurt

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            Entity by = source.getEntity();
            if (by instanceof LivingEntity le && le != this && huntable(le, true)) {
                letBe = null;
                byte s = state();
                if (s == SETTLE) { sitDown(); toState(WAIT, 0); s = WAIT; }
                if (s == WAIT || s == STIR) notice(le);
                else {
                    if (getTarget() != le) { setTarget(le); chaseTicks = 0; }
                    seenAgo = 0;
                    if (s == RETURN) toState(HUNT, 0);
                    else if (s == CLIMB) {
                        // hit on its way home: it turns and comes out again
                        setTarget(le);
                        toState(EMERGE, 0);
                        stateTicks = NOTICE_TICKS;
                        crawlToward(le);
                    }
                }
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide && home != null && level() instanceof ServerLevel l) LurkerRefill.died(l, home.key());
        super.die(source);
    }

    // ------------------------------------------------------------------------------------------------ WindUp

    @Override
    public boolean windingUp() { return !level().isClientSide && state() == CROUCH; }

    /** A strong attack caught it crouched: the leap is dropped and it shrinks back a moment. */
    @Override
    public void interrupt(LivingEntity by) {
        toState(RECOIL, 14);
        rest = LEAP_REST;
        playSound(ERSounds.CHIMNEY_LURKER_HURT.get(), 1.2F, 1.25F);
    }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!level().isClientSide && dormantNow()) return;
        super.tick();
        if (level().isClientSide) clientTick();
    }

    /** Server: waiting in its vent with no player near, its ticks are skipped ({@link #WAKE_RANGE}). */
    private boolean dormantNow() {
        byte s = state();
        if ((s != WAIT && s != STIR) || home == null || !isAlive() || hurtTime > 0 || isOnFire()) return dormant = false;
        if ((tickCount + getId()) % WAKE_LOOK == 0) {
            boolean near = false;
            for (Player p : level().players()) if (p.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE) { near = true; break; }
            dormant = !near;
        }
        return dormant;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!isAlive()) return;
        stateTicks++;
        if (rest > 0) rest--;
        if (letBeTicks > 0 && --letBeTicks == 0) letBe = null;
        switch (state()) {
            case WAIT, STIR -> waiting();
            case EMERGE -> emerge();
            case HUNT -> hunt();
            case CROUCH -> crouch();
            case LEAP -> leap();
            case RECOIL -> recoil();
            case RETURN -> goingHome();
            case CLIMB -> climb();
            case SETTLE -> settling();
            default -> toState(RETURN, 0);
        }
        if (!isAlive()) return;
        pricks();
        // its coat on its quarry (sat waiting, on the footsteps it feels: set as it feels them)
        byte s = state();
        if (s != WAIT && s != STIR) {
            LivingEntity t = getTarget();
            entityData.set(DATA_FOCUS, t != null && t.isAlive() ? t.getId() : -1);
        }
    }

    // ------------------------------------------------------------------------------------------------ on its vent

    private com.explorationremix.creature.body.Terrain rock() {
        if (rockOf == null) rockOf = LurkerVents.loaded(level());
        return rockOf;
    }

    /** Onto its vent's rock from where its body is, going {@code s}: the game's movement off. */
    private void startCrawl(LurkerCrawl.Sense s) {
        onSeat = false;
        glideFrom = glideTo = null;
        entityData.set(DATA_UPRIGHT, false);
        if (crawl == null) crawl = new LurkerCrawl(ride());
        double ax = home != null ? home.x() : getX(), az = home != null ? home.z() : getZ();
        Vec3 c = centre();
        crawl.start(new Vector3d(c.x, c.y, c.z), ax, az, s);
        noPhysics = true;
        setNoGravity(true);
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        flatTicks = 0;
        progressFrom.set(c.x, c.y, c.z);
        progressTicks = 0;
        outJudge = null;
        inJudge = null;
    }

    /**
     * Its way along its vent's rock toward {@code t} from where it is (turning round on the rock if it was on it), and
     * the judge of its way out from here.
     */
    private void crawlToward(LivingEntity t) {
        LurkerCrawl.Sense s = wayTo(t);
        if (crawl == null) startCrawl(s);
        else {
            crawl.turn(s);
            flatTicks = 0;
            progressFrom.set(crawl.pos);
            progressTicks = 0;
            inJudge = null;
        }
        outJudge = s == LurkerCrawl.Sense.OUT && home != null ? new LurkerVents.Out(home, crawl.pos) : null;
    }

    /** Off its vent: the game's movement back on, its yaw the way it went. */
    private void dropToFloor() {
        onSeat = false;
        glideFrom = glideTo = null;
        entityData.set(DATA_UPRIGHT, false);
        if (crawl != null) {
            Vector3d h = crawl.heading;
            if (h.x * h.x + h.z * h.z > 1.0E-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-h.x, h.z));
                setYRot(yaw);
                yBodyRot = yaw;
                yHeadRot = yaw;
            }
        }
        crawl = null;
        noPhysics = false;
        setNoGravity(false);
        entityData.set(DATA_HEADING, new Vector3f());
    }

    /** One tick over the rock: false (and it is off the rock, falling) when it lost its hold. */
    private boolean crawlTick(double speed) {
        if (crawl == null) return false;
        if (!crawl.step(rock(), speed)) {
            dropToFloor();
            return false;
        }
        moveBody();
        return true;
    }

    /** Its box with its body's middle; its heading told to the client when it turns. */
    private void moveBody() {
        Vector3d p = crawl.pos;
        setPos(p.x, p.y - ride(), p.z);
        setDeltaMovement(Vec3.ZERO);
        Vector3d h = crawl.heading;
        Vector3f was = entityData.get(DATA_HEADING);
        if (was.lengthSquared() < 0.5 || was.x * h.x + was.y * h.y + was.z * h.z < 0.995)
            entityData.set(DATA_HEADING, new Vector3f((float) h.x, (float) h.y, (float) h.z));
        if (h.x * h.x + h.z * h.z > 0.04) {
            float yaw = (float) Math.toDegrees(Math.atan2(-h.x, h.z));
            setYRot(yaw);
            yBodyRot = yaw;
            yHeadRot = yaw;
        }
        fallDistance = 0;
    }

    /** Whether it has made headway lately (half a block in 30 ticks): false once it has stuck. */
    private boolean moving() {
        if (crawl == null) return true;
        if (++progressTicks < 30) return true;
        boolean moved = progressFrom.distanceSquared(crawl.pos) > 0.25;
        progressFrom.set(crawl.pos);
        progressTicks = 0;
        return moved;
    }

    /** Whether a point lies inside its vent (its flue or den, under its rim). */
    private boolean insideVent(Vec3 p) {
        if (home == null) return false;
        return home.across(p.x, p.z) < home.mouth() + 0.6 && p.y < home.top() - 0.5;
    }

    // ------------------------------------------------------------------------------------------------ gliding

    /** Glides its body's middle from {@code from} to {@code to} over {@code ticks} ticks, held upright (the game's movement off). */
    private void glide(Vec3 from, Vec3 to, int ticks) {
        crawl = null;
        onSeat = false;
        glideFrom = from;
        glideTo = to;
        glideT = 0;
        glideN = ticks;
        noPhysics = true;
        setNoGravity(true);
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        entityData.set(DATA_UPRIGHT, true);
        entityData.set(DATA_HEADING, new Vector3f(0, 1, 0));
    }

    /** One tick of its glide (eased, rising over the straight way between); true once it is there. */
    private boolean glideTick() {
        if (glideTo == null) return true;
        double t = Math.min(1.0, ++glideT / (double) glideN), e = t * t * (3.0 - 2.0 * t);
        Vec3 p = glideFrom.add(glideTo.subtract(glideFrom).scale(e)).add(0, LurkerVents.GLIDE_LIFT * Math.sin(Math.PI * t), 0);
        setPos(p.x, p.y - ride(), p.z);
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0;
        return t >= 1.0;
    }

    // ------------------------------------------------------------------------------------------------ waiting

    private void waiting() {
        if (home == null) { lurk(); return; }
        if (reseat) reseat();
        // just loaded or come home: onto its seat in its mouth
        if (!onSeat) sitDown();
        setDeltaMovement(Vec3.ZERO);
        // now and then it feels for its rim again: rock dug out from round its mouth brings it down
        if ((tickCount + getId()) % 20 == 0 && !holds()) { lostHold(); return; }
        waitTicks++;
        if ((tickCount + getId()) % 4 != 0) return;
        LivingEntity p = sensePlayer(true);
        if (p != null) { notice(p); return; }
        if (stir >= STIR_AT && state() == WAIT) {
            setState(STIR);
            playSound(ERSounds.CHIMNEY_LURKER_CLICK.get(), 0.5F, 1.1F + random.nextFloat() * 0.2F);
        } else if (stir == 0 && state() == STIR) setState(WAIT);
        // stirred, its coat turns to the footsteps it feels
        entityData.set(DATA_FOCUS, state() == STIR && felt != null ? felt.getId() : -1);
        if (state() == STIR && random.nextInt(6) == 0) playSound(ERSounds.CHIMNEY_LURKER_CLICK.get(), 0.35F, 1.0F + random.nextFloat() * 0.3F);
        // now and then a stilt mantis grazing by its vent's foot
        if (waitTicks > STRIKE_WAIT && (tickCount + getId()) % 20 == 0 && random.nextInt(STRIKE_ROLL) == 0) {
            StiltMantisEntity m = mantisInSight();
            if (m != null) notice(m);
        }
    }

    /** Whether the rim round its mouth still holds its spines: rock under most of the ring they rest on. */
    private boolean holds() {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = Mth.floor(home.top()), held = 0;
        for (int k = 0; k < 12; k++) {
            double a = k * Math.PI / 6.0, r = home.mouth() + 0.7;
            int bx = Mth.floor(home.x() + Math.cos(a) * r), bz = Mth.floor(home.z() + Math.sin(a) * r);
            if (!level().hasChunkAt(m.set(bx, top, bz))) return true;
            for (int y = top - 3; y <= top + 2; y++) if (LurkerVents.solid(level(), m.set(bx, y, bz))) { held++; break; }
        }
        return held >= 7;
    }

    /**
     * A 0.21.0 lurker's home (a spot on its flue's inner wall), read again as a seat in its vent's mouth; with its vent
     * gone or no seat in it, it sits over its mouth's middle at its old rim's top as best it can.
     */
    private void reseat() {
        reseat = false;
        if (!(level() instanceof ServerLevel l)) return;
        com.explorationremix.world.terrain.Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t != null && t.gardens != null) {
            int bottom = t.shape.bottom(com.explorationremix.world.terrain.Terrain.GARDENS);
            for (GardensField.LiveVent v : t.gardens.liveVents(home.x() - 2, home.z() - 2, home.x() + 2, home.z() + 2)) {
                if (v.key() != home.key()) continue;
                LurkerVents.Home h = LurkerVents.find(l, v, bottom, (x, z) -> l.hasChunk(x >> 4, z >> 4));
                if (h != null) { home = h; return; }
            }
        }
        double seat = LurkerRig.shared().spec.seat;
        home = new LurkerVents.Home(home.key(), home.x(), home.z(), home.top(), home.azimuth(), home.x(), home.top() + seat, home.z(), home.mouth(),
                home.wall(), home.colour());
    }

    /** Notes what went wrong on its vent's rock, for /remix lurker. */
    private void trouble(String what) {
        Vec3 c = centre();
        trouble = String.format(java.util.Locale.ROOT, "%s at %.1f %.1f %.1f, tick %d", what, c.x, c.y, c.z, tickCount);
    }

    /** Its vent's rim is gone from round its mouth: it drops, and goes looking for its way home. */
    private void lostHold() {
        trouble("lost its hold waiting");
        dropToFloor();
        toState(RETURN, 0);
    }

    /** One with no vent waits low on the floor where it was set down, and goes back there after a chase. */
    private void lurk() {
        if (lair == null) lair = blockPosition();
        getNavigation().stop();
        if ((tickCount + getId()) % 4 != 0) return;
        LivingEntity p = sensePlayer(false);
        if (p != null) notice(p);
    }

    /**
     * A player it notices: one within {@link #TOUCH} of it; one in sight from its crown (or from its eyes, off its vent)
     * within {@link #SIGHT} ({@link #SIGHT_SNEAK} sneaking); one its spines over its rim feel; or, waiting, the one whose
     * footsteps round its vent's foot have gone on long enough ({@link #STIR_OUT}). Footsteps felt stir it ({@link #stir}).
     */
    @Nullable
    private LivingEntity sensePlayer(boolean waiting) {
        if (level().getDifficulty() == Difficulty.PEACEFUL) return null;
        Vec3 c = centre();
        Vec3 from = waiting && home != null ? toVec(home.peek(new Vector3d())) : getEyePosition();
        Player felt = null;
        boolean anyFelt = false;
        for (Player p : level().players()) {
            if (!huntable(p, false)) continue;
            if (p.getUUID().equals(letBe) && p.distanceToSqr(c) > TOUCH * TOUCH * 4) continue;
            if (p.distanceToSqr(c) < TOUCH * TOUCH) return p;
            double range = p.isShiftKeyDown() ? SIGHT_SNEAK : SIGHT;
            if (p.isInvisible()) range *= 0.25;
            Vec3 eye = p.getEyePosition();
            if (eye.distanceToSqr(from) < range * range && clear(from, eye)) return p;
            if (waiting && home != null && seenFromRim(eye, range * (RIM_SIGHT / SIGHT))) return p;
            if (waiting && home != null && p.onGround() && !p.isShiftKeyDown() && home.across(p.getX(), p.getZ()) < TREMOR
                    && p.getY() < home.top() && p.getY() > home.top() - 48) {
                double v = p.getDeltaMovement().horizontalDistanceSqr();
                if (v > 0.002) {
                    stir += p.isSprinting() ? 3 : 1;
                    anyFelt = true;
                    felt = p;
                }
            }
        }
        if (!anyFelt) stir = Math.max(0, stir - 1);
        this.felt = felt;
        if (felt != null && stir >= STIR_OUT) return felt;
        return null;
    }

    /** Waiting: the player whose footsteps it felt at its last look (null with none). */
    @Nullable private Player felt;

    /** A stilt mantis near its vent's foot, in sight from over its rim. */
    @Nullable
    private StiltMantisEntity mantisInSight() {
        if (home == null) return null;
        Vec3 from = toVec(home.peek(new Vector3d()));
        List<StiltMantisEntity> near = level().getEntitiesOfClass(StiltMantisEntity.class,
                new AABB(home.x() - STRIKE_REACH, home.top() - 48, home.z() - STRIKE_REACH, home.x() + STRIKE_REACH, home.top() + 4, home.z() + STRIKE_REACH),
                m -> m.isAlive() && home.across(m.getX(), m.getZ()) < STRIKE_REACH);
        for (StiltMantisEntity m : near) if (clear(from, m.getEyePosition()) || seenFromRim(m.getEyePosition(), RIM_SIGHT)) return m;
        return null;
    }

    /**
     * Whether {@code eye} is within {@code range} of its rim's outer edge on that side, and in sight of it: its spines
     * over its rim feel round it, though its body sits down in its mouth.
     */
    private boolean seenFromRim(Vec3 eye, double range) {
        if (home.across(eye.x, eye.z) > home.wall() + 4.0 + range || Math.abs(eye.y - home.top()) > range + 2.0) return false;
        Vec3 edge = rimEdgeToward(eye.x, eye.z);
        return edge != null && edge.distanceToSqr(eye) < range * range && clear(edge, eye);
    }

    /**
     * A little over its rim's outer edge on the side toward (x, z): out from its axis along the rock under the rim's top
     * (or a row or two lower where the rim dips) to where the rock ends, a little to either side where a crack notches
     * the rim; null with no rim found there, or unloaded blocks.
     */
    @Nullable
    private Vec3 rimEdgeToward(double x, double z) {
        double dx = x - home.x(), dz = z - home.z(), d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-3) return null;
        double reach = Math.min(d, home.wall() + 4.0), toward = Math.atan2(dz, dx);
        int top = Mth.floor(home.top());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (double turn : RIM_TURNS) {
            double ux = Math.cos(toward + turn), uz = Math.sin(toward + turn);
            for (int row = top - 1; row >= top - 3; row--) {
                boolean inRock = false;
                for (double r = 0.5; r <= reach; r += 0.5) {
                    m.set(Mth.floor(home.x() + ux * r), row, Mth.floor(home.z() + uz * r));
                    if (!level().hasChunkAt(m)) return null;
                    if (LurkerVents.solid(level(), m)) inRock = true;
                    else if (inRock) return new Vec3(home.x() + ux * (r + 0.25), row + 1.6, home.z() + uz * (r + 0.25));
                }
            }
        }
        return null;
    }

    /** The ways (radians, off the way toward what it looks for) it looks along for its rim's edge. */
    private static final double[] RIM_TURNS = {0.0, 0.3, -0.3};

    /** It notices {@code t}: its glow flashes on with a dry rattle, and a moment later it rushes out. */
    private void notice(LivingEntity t) {
        setTarget(t);
        entityData.set(DATA_FOCUS, t.getId());
        chaseTicks = 0;
        seenAgo = 0;
        stir = 0;
        crackTicks = 0;
        playSound(ERSounds.CHIMNEY_LURKER_RATTLE.get(), 1.6F, 0.92F + random.nextFloat() * 0.16F);
        // a mantis hears the rattle: it startles and bolts, and its herd with it (they usually get away)
        if (t instanceof StiltMantisEntity m) m.bolt(position(), true);
        // on its vent's rock: back down after a quarry below, else up and out over its rim (from its seat it rises first)
        if (crawl != null && home != null) crawlToward(t);
        toState(EMERGE, 0);
    }

    /** The way along its vent's rock toward {@code t}: in (down into its vent) for a quarry inside it below, else out. */
    private LurkerCrawl.Sense wayTo(LivingEntity t) {
        return insideVent(t.position()) && t.getY() < getY() - 1.0 ? LurkerCrawl.Sense.IN : LurkerCrawl.Sense.OUT;
    }

    // ------------------------------------------------------------------------------------------------ out and after it

    private void emerge() {
        LivingEntity t = getTarget();
        if (!valid(t)) { giveUp(); return; }
        getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (stateTicks < NOTICE_TICKS) {
            // the flash and the rattle: it holds still a moment
            if (crawl != null) moveBody();
            return;
        }
        if (glideTo != null) {
            // rising out of its mouth and over its rim: then onto the rock there, and down
            if (glideTick()) offRim(t);
            return;
        }
        if (crawl == null) {
            if (onSeat && home != null) { rise(t); return; }
            if (home == null || !clingingNow()) { toFloorHunt(); return; }
            crawlToward(t);
        }
        if (canLeapAt(t)) { startCrouch(t); return; }
        if (!crawlTick(EMERGE_CRAWL)) { trouble("lost the rock coming out"); toFloorHunt(); return; }
        if (crawl.sense() == LurkerCrawl.Sense.IN) {
            // down into its vent after a quarry in there: off the rock once it is on the floor
            flatTicks = crawl.facingUp() > 0.85 ? flatTicks + 1 : 0;
            if (flatTicks >= 2) { toFloorHunt(); return; }
        } else if (outJudge != null) {
            LurkerVents.Verdict j = outJudge.judge(crawl);
            if (j == LurkerVents.Verdict.FLOOR) {
                foot = centre();
                toFloorHunt();
                return;
            }
            // lost its way out (round its axis, back down inside, up something over its rim): it drops and runs
            if (j == LurkerVents.Verdict.LOST) { trouble("lost its way out (" + outJudge.why() + ")"); toFloorHunt(); return; }
        } else {
            flatTicks = crawl.facingUp() > 0.85 && crawl.pos.y < crawl.highest - 3.0 ? flatTicks + 1 : 0;
            if (flatTicks >= FLOOR_TICKS) { toFloorHunt(); return; }
        }
        if (!moving() || stateTicks > 400) { trouble("stuck coming out"); toFloorHunt(); }
    }

    /**
     * It rises out of its mouth toward {@code t}: up and over its rim on their side (or its own side, should the rim
     * there not be found), gliding; a quarry down inside its vent it drops on, straight down its flue.
     */
    private void rise(LivingEntity t) {
        if (insideVent(t.position()) && t.getY() < home.top() - 1.0) { toFloorHunt(); return; }
        double th = Math.atan2(t.getZ() - home.z(), t.getX() - home.x());
        Vector3d c = LurkerVents.crest(level(), home, th);
        if (c == null) c = LurkerVents.crest(level(), home, th = home.azimuth());
        if (c == null) { trouble("no rim to rise over"); toFloorHunt(); return; }
        glide(centre(), new Vec3(c.x, c.y, c.z), RISE_TICKS);
    }

    /** Over its rim: onto the rock there, and on down its vent's outside after {@code t}. */
    private void offRim(LivingEntity t) {
        startCrawl(LurkerCrawl.Sense.OUT);
        if (!crawl.read(rock())) { trouble("no rock over its rim"); toFloorHunt(); return; }
        crawl.settle();
        moveBody();
        outJudge = new LurkerVents.Out(home, crawl.pos);
    }

    /** Whether it is on its vent's rock (sat, gliding or crawling). */
    private boolean clingingNow() {
        return crawl != null || onSeat || glideTo != null || state() == EMERGE;
    }

    private void toFloorHunt() {
        dropToFloor();
        toState(HUNT, 0);
        pathTicks = 0;
    }

    private void hunt() {
        LivingEntity t = getTarget();
        if (!valid(t)) { giveUp(); return; }
        chaseTicks++;
        if (--sightTicks <= 0) {
            sighted = hasLineOfSight(t);
            sightTicks = 5;
        }
        seenAgo = sighted ? 0 : seenAgo + 1;
        boolean mantis = t instanceof StiltMantisEntity;
        if (seenAgo > LOST_SIGHT || chaseTicks > (mantis ? MANTIS_CHASE : CHASE_MAX) || tooFar(t, mantis)) { giveUp(); return; }
        getLookControl().setLookAt(t, 30.0F, 30.0F);
        // into its vent through a crack: it is too big to follow, and waits at the crack a moment
        if (insideVent(t.position()) && !insideVent(position())) {
            getNavigation().stop();
            if (++crackTicks > 60) giveUp();
            return;
        }
        crackTicks = 0;
        if (canLeapAt(t)) { startCrouch(t); return; }
        if (getNavigation().isDone() || --pathTicks <= 0) {
            getNavigation().moveTo(t, HUNT_SPEED);
            pathTicks = 10;
        }
    }

    /** Past its leash (from its vent, or its lair): its quarry, or itself. */
    private boolean tooFar(LivingEntity t, boolean mantis) {
        double leash = mantis ? 16.0 : LEASH;
        if (home != null) return home.across(t.getX(), t.getZ()) > leash + 4.0 || home.across(getX(), getZ()) > leash + 8.0;
        if (lair != null) return Math.sqrt(lair.distToCenterSqr(t.position())) > leash + 4.0;
        return false;
    }

    /** It gives up: lets the player be a while, and goes home. */
    private void giveUp() {
        LivingEntity t = getTarget();
        if (t instanceof Player p) { letBe = p.getUUID(); letBeTicks = LET_BE; }
        commanded = null;
        setTarget(null);
        entityData.set(DATA_FOCUS, -1);
        if (onSeat && home != null) {
            // it never left its seat (its quarry gone as it flashed): it settles again
            toState(WAIT, 0);
            return;
        }
        if (crawl != null || glideTo != null) dropToFloor();
        toState(RETURN, 0);
        pathTicks = 0;
        climbTries = 0;
    }

    private boolean valid(@Nullable LivingEntity t) {
        return t != null && t.isAlive() && t.level() == level() && huntable(t, true) && t.distanceToSqr(this) < 64.0 * 64.0;
    }

    /** A player it may hunt (none in Peaceful, creative or spectating); a mantis only once it has picked one. */
    private boolean huntable(LivingEntity e, boolean picked) {
        if (picked && commanded != null && e.getUUID().equals(commanded)) return !(e instanceof Player p && p.isSpectator());
        if (e instanceof Player p) return !p.isSpectator() && !p.isCreative() && p.isAlive() && level().getDifficulty() != Difficulty.PEACEFUL;
        return picked && e instanceof StiltMantisEntity;
    }

    // ------------------------------------------------------------------------------------------------ the strike

    /** Its quarry within a leap: across and up or down, in sight, rested, from rock it can spring off. */
    private boolean canLeapAt(LivingEntity t) {
        if (rest > 0) return false;
        Vec3 c = centre();
        double dx = t.getX() - c.x, dz = t.getZ() - c.z, across = Math.sqrt(dx * dx + dz * dz);
        double dy = t.getY() + t.getBbHeight() * 0.5 - c.y;
        if (across < LEAP_MIN || across > LEAP_MAX || dy > LEAP_UP || dy < -LEAP_DOWN) return false;
        // from its vent: never down into its flue (it crawls down there), only out at what is round its rim
        if (crawl != null && (insideVent(t.position()) || home != null && crawl.facingOut() < -0.5 && t.getY() < home.top() - 1.0)) return false;
        if (crawl == null && !onGround() && !onClimbable()) return false;
        return clear(getEyePosition(), t.getEyePosition());
    }

    /** It crouches to leap: half a second, seen and heard (a hiss, a glint at its eyes, its glow surging). */
    private void startCrouch(LivingEntity t) {
        toState(CROUCH, CROUCH_TICKS);
        getNavigation().stop();
        playSound(ERSounds.CHIMNEY_LURKER_HISS.get(), 1.3F, 0.95F + random.nextFloat() * 0.1F);
        if (level() instanceof ServerLevel l) {
            Vec3 to = t.getEyePosition().subtract(getEyePosition());
            RuneFx.windup(l, getEyePosition(), to.lengthSqr() > 1.0E-4 ? to.normalize() : getLookAngle(), 1.2F);
        }
    }

    private void crouch() {
        LivingEntity t = getTarget();
        if (!valid(t)) { giveUp(); return; }
        getLookControl().setLookAt(t, 60.0F, 60.0F);
        if (crawl != null) moveBody();
        else {
            Vec3 dm = getDeltaMovement();
            setDeltaMovement(dm.x * 0.3, dm.y, dm.z * 0.3);
        }
        if (stateTicks >= stateLength) spring(t);
    }

    /**
     * It springs at its quarry: a ballistic leap to where it will be, the game's gravity and drag on it. At a mantis it
     * mostly misses (card: "they usually escape"): its leap lands short or wide.
     */
    private void spring(LivingEntity t) {
        Vec3 c = centre();
        Vec3 aim = t.position().add(t.getDeltaMovement().multiply(5.0, 0.0, 5.0)).add(0, t.getBbHeight() * 0.35, 0);
        if (t instanceof StiltMantisEntity && random.nextDouble() < STRIKE_MISS) {
            Vec3 side = new Vec3(-(aim.z - c.z), 0, aim.x - c.x).normalize().scale((random.nextBoolean() ? 1 : -1) * (2.0 + random.nextDouble()));
            aim = aim.add(side);
        }
        double dx = aim.x - c.x, dz = aim.z - c.z, across = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
        double dy = aim.y - c.y;
        // its flight: about half a block a tick across, the time it takes, and the rise that lands it there
        double vh = Mth.clamp(across / 9.0, 0.3, 0.62);
        int ticks = Math.max(3, (int) Math.round(across / vh));
        double drag = (1.0 - Math.pow(0.98, ticks)) / 0.02;
        vh = across / drag;
        double vy = Mth.clamp((dy + 0.04 * ticks * ticks) / drag, 0.22, 0.8);
        if (crawl != null) dropToFloor();
        setDeltaMovement(dx / across * vh, vy, dz / across * vh);
        hasImpulse = true;
        leapFrom = c;
        toState(LEAP, 40);
        playSound(ERSounds.CHIMNEY_LURKER_BITE.get(), 1.1F, 1.25F + random.nextFloat() * 0.1F);
    }

    private void leap() {
        LivingEntity t = getTarget();
        if (t != null && t.isAlive()) {
            // its mouth, flipped to the front of it as it flies, reaches its quarry
            Vec3 dm = getDeltaMovement();
            Vec3 ahead = dm.horizontalDistanceSqr() > 1.0E-4 ? new Vec3(dm.x, 0, dm.z).normalize() : getLookAngle();
            Vec3 jaws = centre().add(ahead.scale(0.9));
            if (new AABB(jaws, jaws).inflate(0.85).intersects(t.getBoundingBox()) || getBoundingBox().inflate(0.15).intersects(t.getBoundingBox())) {
                bite(t);
                return;
            }
        }
        if (stateTicks > 3 && (onGround() || isInWater())) {
            rest = LEAP_REST;
            toState(RECOIL, 8);
            return;
        }
        if (stateTicks >= stateLength) toState(HUNT, 0);
    }

    /** Its five teeth close on its quarry: a shield turns them aside (and is knocked out of the player's guard a moment). */
    private void bite(LivingEntity t) {
        DamageSource src = damageSources().mobAttack(this);
        Vec3 dm = getDeltaMovement();
        rest = LEAP_REST;
        if (t instanceof Player p && p.isBlocking() && p.isDamageSourceBlocked(src)) {
            p.disableShield(true);
            p.knockback(0.6, getX() - p.getX(), getZ() - p.getZ());
            p.hurtMarked = true;
            level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, getSoundSource(), 1.0F, 0.8F + random.nextFloat() * 0.3F);
            setDeltaMovement(dm.x * -0.35, Math.max(dm.y, 0.15), dm.z * -0.35);
            toState(RECOIL, 25);
            return;
        }
        if (t.hurt(src, (float) getAttributeValue(Attributes.ATTACK_DAMAGE))) {
            playSound(ERSounds.CHIMNEY_LURKER_BITE.get(), 1.3F, 0.85F + random.nextFloat() * 0.15F);
            doEnchantDamageEffects(this, t);
        }
        setDeltaMovement(dm.x * -0.2, Math.max(dm.y * 0.5, 0.1), dm.z * -0.2);
        toState(RECOIL, 14);
    }

    private void recoil() {
        getNavigation().stop();
        if (crawl != null) moveBody();
        if (stateTicks < stateLength) return;
        LivingEntity t = getTarget();
        if (crawl != null) {
            // stopped on its vent's rock: on out after its quarry, or home
            if (valid(t)) { toState(EMERGE, 0); stateTicks = NOTICE_TICKS; }
            else giveUp();
            return;
        }
        if (valid(t) && !(t instanceof StiltMantisEntity && chaseTicks > 40)) toState(HUNT, 0);
        else giveUp();
    }

    // ------------------------------------------------------------------------------------------------ home

    private void goingHome() {
        if (home == null) {
            // no vent: back to its lair, and waits there
            if (lair == null) lair = blockPosition();
            if (lair.distToCenterSqr(position()) < 2.5 * 2.5) { getNavigation().stop(); toState(WAIT, 0); return; }
            if (getNavigation().isDone() || --pathTicks <= 0) {
                getNavigation().moveTo(lair.getX() + 0.5, lair.getY(), lair.getZ() + 0.5, HOME_SPEED);
                pathTicks = 20;
            }
            if (renotice()) return;
            return;
        }
        setNoGravity(false);
        if (renotice()) return;
        Vec3 c = centre();
        // inside its vent (down in its flue or den): up its inside from here
        if (insideVent(c)) { startClimb(LurkerCrawl.Sense.OUT); return; }
        if (foot == null && stateTicks == 1 && level() instanceof ServerLevel l) {
            Vector3d f = LurkerVents.footOf(l, home);
            if (f != null) foot = new Vec3(f.x, f.y, f.z);
        }
        Vec3 to = foot != null ? foot : new Vec3(home.x() + Math.cos(home.azimuth()) * 8.0, getY(), home.z() + Math.sin(home.azimuth()) * 8.0);
        double toAxis = home.across(c.x, c.z), footAxis = home.across(to.x, to.z);
        double d = Math.hypot(to.x - c.x, to.z - c.z);
        // at its climbing spot, or against its vent's own wall already: up from here
        if ((d < 1.6 && Math.abs(to.y - c.y) < 3.0) || (toAxis < footAxis - 0.8 && against(c, home))) { startClimb(LurkerCrawl.Sense.IN); return; }
        if (getNavigation().isDone() || --pathTicks <= 0) {
            if (!getNavigation().moveTo(to.x, to.y - ride(), to.z, HOME_SPEED) && ++climbTries > 3 && toAxis < 18.0) startClimb(LurkerCrawl.Sense.IN);
            pathTicks = 20;
        }
        if (stateTicks > 1600 && toAxis < 20.0) startClimb(LurkerCrawl.Sense.IN);
    }

    /** Whether rock lies close on the way from {@code c} toward its vent's axis (it is at the vent's foot). */
    private boolean against(Vec3 c, LurkerVents.Home h) {
        double dx = h.x() - c.x, dz = h.z() - c.z, l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1.0E-3) return true;
        Vec3 to = c.add(dx / l * 1.6, 0, dz / l * 1.6);
        return !clear(c, to);
    }

    /** Going home, a player in sight near (or one who comes close) brings it back out after them. */
    private boolean renotice() {
        if ((tickCount + getId()) % 4 != 0) return false;
        LivingEntity p = sensePlayer(false);
        if (p == null || p.distanceToSqr(this) > 14.0 * 14.0) return false;
        setTarget(p);
        chaseTicks = 0;
        seenAgo = 0;
        playSound(ERSounds.CHIMNEY_LURKER_RATTLE.get(), 1.4F, 1.0F + random.nextFloat() * 0.1F);
        if (crawl != null) {
            toState(EMERGE, 0);
            stateTicks = NOTICE_TICKS;
            crawlToward(p);
        } else toState(HUNT, 0);
        return true;
    }

    private void startClimb(LurkerCrawl.Sense s) {
        getNavigation().stop();
        startCrawl(s);
        if (s == LurkerCrawl.Sense.IN && home != null) inJudge = new LurkerVents.In(home);
        toState(CLIMB, 0);
    }

    private void climb() {
        if (home == null || crawl == null) { if (crawl != null) dropToFloor(); toState(RETURN, 0); return; }
        if (renotice()) return;
        if (!crawlTick(HOME_CRAWL)) { trouble("lost the rock climbing home"); failedClimb(); return; }
        if (crawl.sense() == LurkerCrawl.Sense.IN && inJudge != null) {
            // up its vent's outside and over its rim to its mouth's edge: then it glides down into its seat
            LurkerVents.Verdict j = inJudge.judge(crawl);
            if (j == LurkerVents.Verdict.HOME) { settleIn(); return; }
            if (j == LurkerVents.Verdict.LOST) { trouble("lost its way home (" + inJudge.why() + ")"); failedClimb(); return; }
        } else {
            // up its inside from down in its vent (never across its axis): by its rim, it glides onto its seat
            if (home.across(crawl.pos.x, crawl.pos.z) < 0.6) { trouble("round its axis climbing up inside"); failedClimb(); return; }
            if (crawl.pos.y >= home.top() - 0.5) { settleIn(); return; }
        }
        if (!moving() || stateTicks > 900) { trouble("stuck climbing home"); failedClimb(); }
    }

    /** Its climb went wrong (it fell, or stuck): it tries again from another side, a little round its vent. */
    private void failedClimb() {
        if (crawl != null) dropToFloor();
        climbTries++;
        if (home != null && foot != null) {
            double a = home.azimuth() + (climbTries % 2 == 0 ? 1 : -1) * 0.6 * climbTries;
            double r = home.across(foot.x, foot.z);
            foot = new Vec3(home.x() + Math.cos(a) * r, foot.y, home.z() + Math.sin(a) * r);
        }
        toState(RETURN, 0);
        pathTicks = 0;
    }

    /** Home by its mouth: it glides down into its seat. */
    private void settleIn() {
        glide(centre(), new Vec3(home.waitX(), home.waitY(), home.waitZ()), SETTLE_TICKS);
        toState(SETTLE, SETTLE_TICKS);
    }

    /** Gliding down into its seat: sat, it waits again. */
    private void settling() {
        if (home == null) { dropToFloor(); toState(RETURN, 0); return; }
        if (glideTo == null) glide(centre(), new Vec3(home.waitX(), home.waitY(), home.waitZ()), SETTLE_TICKS);
        if (!glideTick()) return;
        sitDown();
        waitTicks = 0;
        stir = 0;
        climbTries = 0;
        toState(WAIT, 0);
    }

    // ------------------------------------------------------------------------------------------------ its spines

    /** Its spines prick what bumps into it (Taran's card: "Only if you bump into it"): never a player in creative or spectating. */
    private void pricks() {
        if (prickTicks > 0) { prickTicks--; return; }
        AABB box = getBoundingBox().inflate(0.15);
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, box, o -> o != this && o.isAlive() && !(o instanceof ChimneyLurkerEntity))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (e.hurt(damageSources().thorns(this), PRICK)) {
                prickTicks = PRICK_EVERY;
                playSound(ERSounds.CHIMNEY_LURKER_CLICK.get(), 0.8F, 1.5F + random.nextFloat() * 0.2F);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ small things

    private boolean clear(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    private static Vec3 toVec(Vector3d v) { return new Vec3(v.x, v.y, v.z); }

    /** For /remix lurker: its state in words. */
    public String describe() {
        String s = switch (state()) {
            case WAIT -> home != null ? "sat in its vent's mouth" : "lurking";
            case STIR -> "stirring (footsteps)";
            case EMERGE -> "coming out";
            case HUNT -> "hunting";
            case CROUCH -> "crouched to leap";
            case LEAP -> "leaping";
            case RECOIL -> "recoiling";
            case RETURN -> "going home";
            case CLIMB -> "climbing home";
            case SETTLE -> "settling into its mouth";
            default -> "?";
        };
        StringBuilder b = new StringBuilder(colour().getSerializedName()).append(", ").append(s);
        LivingEntity t = getTarget();
        if (t != null) b.append(" (").append(t.getName().getString()).append(")");
        if (home != null) b.append(String.format(java.util.Locale.ROOT, ", vent at %.0f %.0f (rim %.0f, its mouth's edge %.1f out), %.0f blocks from its axis", home.x(), home.z(),
                home.top(), home.mouth(), home.across(getX(), getZ())));
        else b.append(", no vent");
        if (foot != null) b.append(String.format(java.util.Locale.ROOT, ", climbs from %.0f %.0f %.0f", foot.x, foot.y, foot.z));
        if (stir > 0) b.append(", stir ").append(stir);
        if (dormant) b.append(", dormant");
        if (trouble != null) b.append(", last trouble: ").append(trouble);
        b.append(String.format(java.util.Locale.ROOT, ", %.0f/%.0f hp", getHealth(), getMaxHealth()));
        return b.toString();
    }

    /** For /remix lurker sight: how it would see {@code p} sat in its vent's mouth (from its crown, from its rim's edge). */
    public String sightReport(Player p) {
        if (home == null) return p.getName().getString() + ": it has no vent";
        Vec3 eye = p.getEyePosition();
        Vec3 from = toVec(home.peek(new Vector3d()));
        double range = p.isShiftKeyDown() ? SIGHT_SNEAK : SIGHT;
        StringBuilder b = new StringBuilder(p.getName().getString());
        b.append(String.format(java.util.Locale.ROOT, " (%.1f off its axis, %.1f under its rim): from its crown %.1f off of %.0f, %s", home.across(eye.x, eye.z),
                home.top() - eye.y, Math.sqrt(eye.distanceToSqr(from)), range, sightLine(from, eye)));
        Vec3 edge = rimEdgeToward(eye.x, eye.z);
        double rim = range * (RIM_SIGHT / SIGHT);
        if (edge == null) b.append("; no rim edge found toward them");
        else b.append(String.format(java.util.Locale.ROOT, "; its rim's edge at %.1f %.1f %.1f, %.1f off of %.0f, %s", edge.x, edge.y, edge.z,
                Math.sqrt(edge.distanceToSqr(eye)), rim, sightLine(edge, eye)));
        b.append(seenFromRim(eye, rim) ? " (seen)" : " (not seen)");
        b.append(String.format(java.util.Locale.ROOT, "; huntable %s, invisible %s, let be %s; it has waited %d ticks (tick %d, id %d), %s",
                huntable(p, false), p.isInvisible(), p.getUUID().equals(letBe), waitTicks, tickCount, getId(), dormant ? "dormant" : "awake"));
        return b.toString();
    }

    private String sightLine(Vec3 from, Vec3 to) {
        net.minecraft.world.phys.BlockHitResult r = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        if (r.getType() == HitResult.Type.MISS) return "clear";
        BlockPos at = r.getBlockPos();
        return "blocked at " + at.toShortString() + " by " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level().getBlockState(at).getBlock()).getPath();
    }

    /** For /remix lurker hunt: it notices {@code t} now, wherever it is. */
    public void huntNow(LivingEntity t) {
        letBe = null;
        commanded = t.getUUID();
        rest = 0;
        dormant = false;
        byte s = state();
        if (s == SETTLE) { sitDown(); toState(WAIT, 0); s = WAIT; }
        if (s == WAIT || s == STIR || s == CLIMB) {
            notice(t);
        } else {
            setTarget(t);
            chaseTicks = 0;
            seenAgo = 0;
            if (crawl == null) toState(HUNT, 0);
        }
    }

    /** For /remix lurker strike: waiting, it strikes at a stilt mantis it sees by its vent now; false if it sees none. */
    public boolean strikeNow() {
        byte s = state();
        if ((s != WAIT && s != STIR) || home == null) return false;
        dormant = false;
        StiltMantisEntity m = mantisInSight();
        if (m == null) return false;
        notice(m);
        return true;
    }

    /** For /remix lurker home: it gives up what it is doing and goes home. */
    public void sendHome() {
        letBe = null;
        setTarget(null);
        entityData.set(DATA_FOCUS, -1);
        if (onSeat && home != null) { toState(WAIT, 0); return; }
        if (crawl != null || glideTo != null) dropToFloor();
        toState(RETURN, 0);
    }

    // ------------------------------------------------------------------------------------------------ client

    private void clientTick() {
        glowO = glow;
        flashO = flash;
        byte s = state();
        if (s != seenState) {
            // it sees someone: its glow flashes on
            if ((seenState == WAIT || seenState == STIR) && s == EMERGE) { glow = 1.0F; flash = 1.0F; }
            if (s == CLIMB) climbFade = 0;
            seenState = s;
        }
        float t = tickCount;
        float target = switch (s) {
            case WAIT, STIR -> 0.0F;
            case EMERGE, HUNT, LEAP, RECOIL -> 0.78F + 0.22F * Mth.sin(t * 0.32F);
            case CROUCH -> 1.0F;
            case RETURN -> 0.5F + 0.1F * Mth.sin(t * 0.12F);
            case CLIMB -> 0.55F * Math.max(0.0F, 1.0F - climbFade++ / 40.0F);
            default -> 0.0F;   // settling into its mouth, its bands go dark
        };
        glow += (target - glow) * (target > glow ? 0.5F : 0.1F);
        flash *= 0.8F;
        if (s == CROUCH) flash = Math.max(flash, 0.35F + 0.15F * Mth.sin(t * 1.3F));
        airTicks = onGround() || isInWater() ? 0 : airTicks + 1;
        boolean drawn = tickCount - drawnAt <= BODY_KEEP;
        if (body != null && !drawn && level().getNearestPlayer(this, BODY_HEAR) == null) body = null;
        else if (body != null || level().getNearestPlayer(this, BODY_HEAR) != null) tickBody();
    }

    /** Client: whether it clings to its vent's rock now (its heading is told to the client only then). */
    public boolean clingingForDraw() { return entityData.get(DATA_HEADING).lengthSquared() > 0.25F; }

    /** Client: how bright its markings glow (0 dark in its vent, 1 hunting). */
    public float glow(float pt) { return Mth.lerp(pt, glowO, glow); }

    /** Client: its glow's flash as it notices someone, and its surge as it crouches to leap. */
    public float flash(float pt) { return Mth.lerp(pt, flashO, flash); }

    /** Client: its body for drawing this frame, laid out on the spot if it has none (just come into sight). */
    public LurkerBody drawnBody() {
        drawnAt = tickCount;
        if (body == null) tickBody();
        return body;
    }

    private void tickBody() {
        if (drive == null) {
            drive = new LurkerBody.Drive();
            rock = new TerrainPatch();
            terrain = new com.explorationremix.creature.body.LevelTerrain(level());
            bounds = new int[6];
        }
        if (body == null) {
            body = new LurkerBody(LurkerRig.shared().spec);
            body.setSeed(getUUID().getLeastSignificantBits());
            body.setFootfalls(this::footfall);
        }
        LurkerBody.Drive in = drive;
        double ride = ride();
        in.x = getX();
        in.y = getY() + ride;
        in.z = getZ();
        byte s = state();
        Vector3f h = entityData.get(DATA_HEADING);
        double vy = getY() - yo, vx = getX() - xo, vz = getZ() - zo;
        if (h.lengthSquared() > 0.25) in.heading.set(h.x, h.y, h.z);
        else if (Math.abs(vy) > 0.06 && vx * vx + vz * vz < vy * vy * 0.5 && s != LEAP && airTicks < 3) in.heading.set(0, Math.signum(vy), 0);
        else {
            double a = Math.toRadians(yBodyRot);
            in.heading.set(-Math.sin(a), 0, Math.cos(a));
        }
        in.up.set(0, 0, 0);
        in.velocity.set(vx, vy, vz);
        if (isDeadOrDying()) in.mode = LurkerBody.Mode.DEAD;
        else if (s == LEAP || (airTicks > 4 && vy < -0.1 && h.lengthSquared() < 0.25)) in.mode = LurkerBody.Mode.AIR;
        else in.mode = LurkerBody.Mode.SURFACE;
        in.crouch = s == CROUCH ? 1.0 : (s == WAIT && home == null ? 0.35 : 0.0);
        in.mouth = s == CROUCH ? 0.45 : s == LEAP ? 1.0 : s == HUNT || s == RECOIL ? 0.12 : 0.0;
        in.tense = s == STIR ? 1.0 : 0.0;
        in.hurry = s == EMERGE || s == HUNT || s == LEAP;
        in.upright = entityData.get(DATA_UPRIGHT);
        // its coat points at the footsteps it feels, then at its quarry
        Entity at = entityData.get(DATA_FOCUS) >= 0 ? level().getEntity(entityData.get(DATA_FOCUS)) : null;
        if (at != null) {
            in.focus.set(at.getX(), at.getY() + at.getBbHeight() * 0.6, at.getZ());
            in.focusAmount = s == STIR || s == WAIT ? 0.65 : s == RETURN || s == CLIMB || s == SETTLE ? 0.0 : 1.0;
        } else in.focusAmount = 0.0;
        body.bounds(in, bounds);
        rock.build(terrain, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        body.tick(rock, in);
    }

    /** Client: a spine's point lands: a light dry tap (a run's crowd of them thinned, so it patters rather than roars). */
    private void footfall(LurkerBody.Foot foot, boolean running, double speed) {
        if (isSilent() || tickCount - lastStep < (running ? 2 : 3)) return;
        lastStep = tickCount;
        float volume = running ? 0.3F : 0.18F;
        float pitch = (foot.index % 4 == 0 ? 1.1F : 0.95F) * (0.9F + random.nextFloat() * 0.2F);
        level().playLocalSound(foot.at.x, foot.at.y, foot.at.z, ERSounds.CHIMNEY_LURKER_STEP.get(), getSoundSource(), volume, pitch, false);
    }
}
