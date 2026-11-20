package com.explorationremix.creature;

import com.explorationremix.block.GrazedGrassBlock;
import com.explorationremix.block.Neon;
import com.explorationremix.block.NeonBlock;
import com.explorationremix.creature.body.LevelTerrain;
import com.explorationremix.creature.body.MantisBody;
import com.explorationremix.creature.body.MantisRig;
import com.explorationremix.creature.body.TerrainPatch;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The stilt mantis of the Deep Gardens (0.20.0, Taran's ask: a tall, alien, mantis-like grazer on long skinny legs
 * that land on real blocks, in the glow mats' neon). Four stilt legs arched high over a slim body, two folded raptorial
 * arms it grazes with, a small triangular head that flicks to look. Gentle: it never attacks.
 *
 * <p>It lives in small herds of 2 to 4 ({@link MantisHerds}), one in most big groves of glow mats, each the colour of
 * the mats it grazes, lime only near a lime vent. A herd keeps to its grove: its members walk from patch to patch,
 * crouch and pick at the mat with their arms, and look about.
 *
 * <p>Wary of players (Taran's card): one in sight up to {@link #NOTICE} blocks off makes it stand tall and watch, its
 * head cocked; nearer than {@link #WARY} (half that for a player sneaking) it steps away; run at, or hit, it flares
 * its wings and raises its arms in a startle display and bolts on its stilts, and its herd bolts with it.
 *
 * <p>Its body is drawn on the client from {@link MantisBody} (feet planted on the blocks under them, the gait from how
 * fast it goes); the server moves only its box, which is narrow and tall (its legs reach well past it).
 */
public class StiltMantisEntity extends PathfinderMob {
    // ---- states (synced: the body poses from them)
    public static final byte CALM = 0, GRAZE = 1, ALERT = 2, DISPLAY = 3, FLEE = 4;

    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(StiltMantisEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_COLOUR = SynchedEntityData.defineId(StiltMantisEntity.class, EntityDataSerializers.BYTE);

    // ---- tuning
    /** A player in sight this near is watched. */
    public static final double NOTICE = 16.0;
    /** Nearer than this (a sneaking player: {@link #WARY_SNEAK}) it steps away. */
    public static final double WARY = 8.0, WARY_SNEAK = 4.0;
    /** A sprinting player coming at it from this near startles it. */
    public static final double RUN_AT = 11.0;
    /** Its herd bolts with it from this far. */
    static final double HERD_ALARM = 18.0;
    /** It grazes within this of its herd's home, and walks home from further than {@link #STRAY}. */
    public static final double HOME_RANGE = 11.0, STRAY = 22.0;
    /** Its speeds (times its movement speed): walking, stepping away, bolting. */
    static final double WALK = 0.7, STEP_AWAY = 0.85, BOLT = 1.25;
    /** How long its startle display lasts, and how long it bolts at the least and most (ticks). */
    static final int DISPLAY_TICKS = 13, FLEE_MIN = 70, FLEE_MAX = 140;
    /** After a scare it stays watchful this long once nothing is near (ticks). */
    static final int CALM_DOWN = 160;
    /** A player who keeps their distance this long (ticks) it gets used to, and grazes again. */
    static final int USED_TO = 240;
    /** How far it bolts (blocks), and how far it keeps from a player it steps away from. */
    static final int BOLT_REACH = 22, STEP_REACH = 9;
    /** Herd mates keep this far apart when they pick where to graze (their legs reach past their boxes). */
    static final double SPACING = 2.6;
    /**
     * With no player (of any kind) within this, a calm mantis lies dormant: its ticks are skipped (as the Skein's,
     * {@link SkeinEntity#WAKE_RANGE}). Herds set down with the world stand in groves far from anyone; this is further
     * than one is drawn at the default entity distance, so none is seen frozen. Players are looked for once a second.
     */
    public static final double WAKE_RANGE = 96.0;
    static final int WAKE_LOOK = 20;

    // ---- server
    /** Its herd ({@link MantisHerds.Home#id}), or -1 (an egg's, or a summoned one's). */
    private long herd = -1;
    @Nullable private BlockPos home;
    private int displayTicks, fleeTicks, stepTicks, idleTicks, calmTicks, lookTicks, boltIn, stare;
    @Nullable private Vec3 threat;
    @Nullable private Player watched;
    private double watchedDist = -1;
    private int sightTicks;
    private boolean sighted;
    private boolean dormant;

    // ---- client
    @Nullable private MantisBody body;
    @Nullable private MantisBody.Drive drive;
    @Nullable private TerrainPatch rock;
    @Nullable private LevelTerrain terrain;
    private int[] bounds;
    private int drawnAt = -1000, airTicks;
    private float flare, flareO;
    /** Client: its body is kept while drawn in the last this many ticks, or while a player is near enough to hear its feet. */
    static final int BODY_KEEP = 40;
    static final double BODY_HEAR = 20.0;

    public StiltMantisEntity(EntityType<? extends StiltMantisEntity> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
        xpReward = 3;
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STATE, CALM);
        entityData.define(DATA_COLOUR, (byte) -1);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanFloat(true);
        return nav;
    }

    public byte state() { return entityData.get(DATA_STATE); }

    private void setState(byte s) {
        if (state() != s) entityData.set(DATA_STATE, s);
    }

    /** Its colour: its grove's glow mats' (lime by a lime vent). */
    public Neon colour() {
        int c = entityData.get(DATA_COLOUR);
        return c < 0 || c > 3 ? Neon.VIOLET : Neon.ofCode(c);
    }

    private boolean coloured() { return entityData.get(DATA_COLOUR) >= 0; }

    public void setColour(Neon n) { entityData.set(DATA_COLOUR, (byte) n.ordinal()); }

    public long herd() { return herd; }
    @Nullable public BlockPos home() { return home; }
    public boolean dormant() { return dormant; }

    /** Makes it one of a herd set down with the world, at the herd's home. */
    public void joinHerd(MantisHerds.Home h) {
        herd = h.id();
        home = new BlockPos(h.x(), h.y(), h.z());
        if (h.colour() != null) setColour(h.colour());
        setPersistenceRequired();
    }

    // ------------------------------------------------------------------------------------------------ spawning, saving

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (!coloured()) setColour(colourHere(level, blockPosition()));
        if (home == null) home = blockPosition();
        return out;
    }

    /**
     * The colour one brought in by hand takes where it stands: lime by a live lime vent of the Deep Gardens (as the
     * herds), else the colour most glow mats round it are, else one of the mats' three at random.
     */
    private Neon colourHere(BlockGetter level, BlockPos at) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t != null && t.gardens != null) {
            int row = at.getY() - t.shape.bottom(Terrain.GARDENS);
            if (row >= 0 && row < t.shape.height(Terrain.GARDENS) && t.gardens.limeVentNear(at.getX() + 0.5, row, at.getZ() + 0.5, MantisHerds.LIME_REACH))
                return Neon.LIME;
        }
        int[] votes = new int[4];
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = -3; dy <= 1; dy++) {
                    BlockState s = level.getBlockState(p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz));
                    if ((s.is(ERBlocks.GLOW_MAT.get()) || s.is(ERBlocks.THIN_GLOW_MAT.get())) && s.hasProperty(NeonBlock.NEON))
                        votes[s.getValue(NeonBlock.NEON).ordinal()]++;
                }
        int best = -1;
        for (int c = 0; c < 4; c++) if (votes[c] > 0 && (best < 0 || votes[c] > votes[best])) best = c;
        if (best >= 0) return Neon.ofCode(best);
        Neon[] mats = {Neon.MAGENTA, Neon.VIOLET, Neon.LIGHT_BLUE};
        return mats[getRandom().nextInt(mats.length)];
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Colour", entityData.get(DATA_COLOUR));
        tag.putLong("Herd", herd);
        if (home != null) {
            tag.putInt("HomeX", home.getX());
            tag.putInt("HomeY", home.getY());
            tag.putInt("HomeZ", home.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Colour")) entityData.set(DATA_COLOUR, tag.getByte("Colour"));
        herd = tag.contains("Herd") ? tag.getLong("Herd") : -1;
        home = tag.contains("HomeX") ? new BlockPos(tag.getInt("HomeX"), tag.getInt("HomeY"), tag.getInt("HomeZ")) : null;
    }

    /** Set down with the world, it stays when no one is near, as the animals a world starts with do. */
    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean requiresCustomPersistence() { return true; }

    // ------------------------------------------------------------------------------------------------ the head and body

    /** It can look well round over its shoulder before its body turns after its head. */
    @Override
    public int getMaxHeadYRot() { return 95; }

    @Override
    public int getHeadRotSpeed() { return 24; }

    @Override
    public int getMaxHeadXRot() { return 60; }

    /** Its box is narrow; its legs reach a block and a half past it to each side. */
    @Override
    public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(2.2, 0.6, 2.2); }

    /** Its legs are long: a fall of a few blocks is a step down for it. */
    @Override
    protected int calculateFallDamage(float distance, float multiplier) {
        return super.calculateFallDamage(distance - 4.0F, multiplier);
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        byte s = state();
        return s == FLEE || s == DISPLAY ? null : ERSounds.STILT_MANTIS_CHIRR.get();
    }

    @Override
    public int getAmbientSoundInterval() { return 260; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.STILT_MANTIS_HURT.get(); }

    @Override
    protected SoundEvent getDeathSound() { return ERSounds.STILT_MANTIS_DEATH.get(); }

    @Override
    protected float getSoundVolume() { return 0.7F; }

    /** Its steps are its feet's own: each foot taps as it lands (see {@link #footfall}), not the box's walk. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    // ------------------------------------------------------------------------------------------------ being hurt

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            Entity by = source.getEntity();
            Vec3 from = by != null ? by.position() : source.getSourcePosition();
            if (from == null) from = position().add(getLookAngle());
            bolt(from, false);
            alarmHerd(from);
        }
        return hurt;
    }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!level().isClientSide && dormantNow()) return;
        super.tick();
        if (level().isClientSide) clientTick();
    }

    /**
     * Server: whether it lies dormant this tick ({@link #WAKE_RANGE}): calm, standing on the ground, unhurt, out of
     * water, lava and fire, going nowhere, with no player near.
     */
    private boolean dormantNow() {
        byte s = state();
        if ((s != CALM && s != GRAZE) || !isAlive() || hurtTime > 0 || !onGround() || isOnFire() || isInWater() || isInLava()
                || isPassenger() || isVehicle() || !getNavigation().isDone()) return dormant = false;
        if ((tickCount + getId()) % WAKE_LOOK == 0) dormant = !playerWithin(WAKE_RANGE);
        return dormant;
    }

    private boolean playerWithin(double range) {
        for (Player p : level().players()) if (p.distanceToSqr(this) < range * range) return true;
        return false;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!isAlive()) return;
        think();
    }

    // ------------------------------------------------------------------------------------------------ the mind (server)

    private void think() {
        Player near = nearestPlayer();
        if (boltIn > 0 && --boltIn == 0 && threat != null) { startFlee(); return; }
        byte s = state();                                    // after the look: a player running at it startles it
        switch (s) {
            case DISPLAY -> {
                getNavigation().stop();
                if (threat != null) getLookControl().setLookAt(threat.x, threat.y + 1.5, threat.z, 40.0F, 60.0F);
                if (--displayTicks <= 0) startFlee();
            }
            case FLEE -> {
                fleeTicks--;
                if (near != null && watchedDist < WARY + 4.0) {
                    // still close behind it: it keeps going
                    threat = near.position();
                    fleeTicks = Math.max(fleeTicks, 30);
                }
                if (getNavigation().isDone()) {
                    if (fleeTicks > 0) runFrom(threat);
                    else {
                        setState(ALERT);
                        calmTicks = CALM_DOWN;
                    }
                }
            }
            default -> {
                if (near != null) wary(near, s);
                else {
                    stare = Math.max(0, stare - 2);
                    if (s == ALERT) {
                        if (--calmTicks <= 0) { setState(CALM); idleTicks = 20 + getRandom().nextInt(40); }
                        else lookAbout();
                    } else graze(s);
                }
            }
        }
    }

    /**
     * The nearest player in sight within {@link #NOTICE} (not a spectator), with {@link #watchedDist} how far; a
     * sprinting player coming at it startles it here.
     */
    @Nullable
    private Player nearestPlayer() {
        Player best = null;
        double bestD = NOTICE * NOTICE;
        for (Player p : level().players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(this);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best == null) { watched = null; watchedDist = -1; return null; }
        // its sight is looked at four times a second (rock between it and the player hides them)
        if (best != watched || --sightTicks <= 0) {
            sighted = bestD < 9.0 || hasLineOfSight(best);
            sightTicks = 5;
        }
        double d = Math.sqrt(bestD);
        double closing = best == watched && watchedDist >= 0 ? watchedDist - d : 0.0;
        watched = best;
        watchedDist = d;
        if (!sighted) return null;
        // a sprinting player coming at it (closing faster than a walk) startles it: the display, then it bolts
        byte s = state();
        if (s != FLEE && s != DISPLAY && boltIn == 0 && best.isSprinting() && d < RUN_AT && closing > 0.12) startle(best.position());
        return best;
    }

    /**
     * A player near and in sight: it watches them, head cocked, and steps away if they come nearer than
     * {@link #WARY} (sneaking, {@link #WARY_SNEAK}). One who keeps their distance a while, it gets used to: it goes
     * back to grazing, looking up at them now and then.
     */
    private void wary(Player p, byte s) {
        double d = watchedDist;
        double keep = p.isShiftKeyDown() ? WARY_SNEAK : WARY;
        if (d < keep + 2.0 || p.isSprinting()) stare = 0;
        else stare++;
        if (d < keep) {
            calmTicks = CALM_DOWN;
            setState(ALERT);
            getLookControl().setLookAt(p, 30.0F, 60.0F);
            // step away, back toward its grove where it can
            if (getNavigation().isDone() || --stepTicks <= 0) {
                Vec3 to = away(p.position(), STEP_REACH, 3);
                if (to != null) getNavigation().moveTo(to.x, to.y, to.z, STEP_AWAY);
                stepTicks = 30;
            }
            return;
        }
        if (stare > USED_TO) {
            if (s == ALERT) { setState(CALM); idleTicks = 10 + getRandom().nextInt(30); }
            graze(state());
            if (getRandom().nextInt(50) == 0) getLookControl().setLookAt(p, 30.0F, 60.0F);
            return;
        }
        calmTicks = CALM_DOWN;
        getLookControl().setLookAt(p, 30.0F, 60.0F);
        if (s != ALERT) { setState(ALERT); getNavigation().stop(); }
    }

    /** It startles: wings flared and arms raised for a moment, its wings rattling, then it bolts; its herd bolts too. */
    private void startle(Vec3 from) {
        threat = from;
        setState(DISPLAY);
        displayTicks = DISPLAY_TICKS;
        getNavigation().stop();
        playSound(ERSounds.STILT_MANTIS_RATTLE.get(), 0.9F, 0.9F + getRandom().nextFloat() * 0.2F);
        alarmHerd(from);
    }

    /** It bolts from {@code from}: at once (hurt, or its herd bolting), or after its display. */
    void bolt(Vec3 from, boolean display) {
        threat = from;
        if (display) startle(from);
        else startFlee();
    }

    private void startFlee() {
        if (threat == null) threat = position().add(getLookAngle());
        boltIn = 0;
        stare = 0;
        if (state() != FLEE) fleeTicks = FLEE_MIN + getRandom().nextInt(FLEE_MAX - FLEE_MIN);
        setState(FLEE);
        runFrom(threat);
    }

    private void runFrom(Vec3 from) {
        Vec3 to = away(from, BOLT_REACH, 6);
        if (to != null) getNavigation().moveTo(to.x, to.y, to.z, BOLT);
        else fleeTicks = Math.min(fleeTicks, 10);
    }

    /** Its herd mates near bolt too, each a moment after the last (they see it go, not the danger). */
    private void alarmHerd(Vec3 from) {
        List<StiltMantisEntity> mates = level().getEntitiesOfClass(StiltMantisEntity.class, getBoundingBox().inflate(HERD_ALARM));
        for (StiltMantisEntity m : mates) {
            if (m == this || !m.isAlive()) continue;
            byte s = m.state();
            if (s == FLEE || s == DISPLAY || m.boltIn > 0) continue;
            m.threat = from;
            m.boltIn = 3 + m.getRandom().nextInt(8);
            m.dormant = false;
        }
    }

    /** A spot about {@code reach} blocks from {@code from}, leaning back toward its home where one is that way. */
    @Nullable
    private Vec3 away(Vec3 from, int reach, int up) {
        Vec3 best = null;
        double bestScore = -1e9;
        for (int i = 0; i < 6; i++) {
            Vec3 v = DefaultRandomPos.getPosAway(this, reach, up, from);
            if (v == null) continue;
            double score = v.distanceTo(from);
            if (home != null) score -= 0.5 * Math.max(0.0, Math.sqrt(home.distToCenterSqr(v)) - HOME_RANGE);
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return best;
    }

    /** Calm: it grazes, walks from patch to patch in its grove, and looks about. */
    private void graze(byte s) {
        BlockPos h = home != null ? home : blockPosition();
        if (!getNavigation().isDone()) {
            // walking to its next patch
            if (s == GRAZE) setState(CALM);
            lookAbout();
            return;
        }
        if (s == GRAZE) {
            // 0.20.2 (card pick: "Crops it short, it regrows"): each pick of its arms may crop the grass ahead to
            // stubble, which grows back in a few minutes; as a sheep's bite, only where mobs may change blocks
            if (tickCount % PICK_TICKS == 0 && getRandom().nextFloat() < CROP_CHANCE && ForgeEventFactory.getMobGriefingEvent(level(), this))
                cropAhead();
            if (--idleTicks <= 0) {
                setState(CALM);
                idleTicks = 15 + getRandom().nextInt(50);
            }
            return;
        }
        // standing
        if (home != null && Math.sqrt(home.distToCenterSqr(position())) > STRAY) {
            if (!walkTo(h, 4)) idleTicks = 40;
            return;
        }
        lookAbout();
        if (--idleTicks > 0) return;
        float r = getRandom().nextFloat();
        if (r < 0.55F && onGrazing()) {
            setState(GRAZE);
            idleTicks = 100 + getRandom().nextInt(180);
        } else if (r < 0.9F) {
            if (!walkTo(h, (int) HOME_RANGE)) idleTicks = 20 + getRandom().nextInt(30);
            else idleTicks = 20;
        } else idleTicks = 40 + getRandom().nextInt(60);
    }

    /** How often its arms pick while it grazes (ticks: two arms in turn, MantisBody.GRAZE_TICKS each); a pick's chance to crop. */
    static final int PICK_TICKS = 23;
    static final float CROP_CHANCE = 0.3F;

    /** The filament grass its arms reach crops to stubble (the first found of a few spots ahead of it, on its floor). */
    private void cropAhead() {
        double a = Math.toRadians(yBodyRot), fx = -Math.sin(a), fz = Math.cos(a);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 6; k++) {
            double ahead = 0.7 + getRandom().nextDouble() * 1.3, across = (getRandom().nextDouble() - 0.5) * 1.4;
            int x = Mth.floor(getX() + fx * ahead - fz * across), z = Mth.floor(getZ() + fz * ahead + fx * across);
            for (int dy = 1; dy >= -1; dy--) {
                p.set(x, getBlockY() + dy, z);
                if (!level().hasChunkAt(p)) return;
                if (GrazedGrassBlock.crop(level(), p, level().getBlockState(p))) return;
            }
        }
    }

    /** How much filament grass stands in the 3 x 3 columns round a block, at its row. */
    static int grassRound(BlockGetter level, BlockPos at) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (GrazedGrassBlock.croppable(level.getBlockState(p.set(at.getX() + dx, at.getY(), at.getZ() + dz)))) n++;
        return n;
    }

    /** Whether it stands over something to graze: glow mat or filament grass (or any floor, for one far from the Gardens). */
    private boolean onGrazing() {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int mats = 0, floors = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                BlockState st = level().getBlockState(p.set(getBlockX() + dx, getBlockY() - 1, getBlockZ() + dz));
                if (st.is(ERBlocks.GLOW_MAT.get()) || st.is(ERBlocks.THIN_GLOW_MAT.get())) mats++;
                if (!st.isAir()) floors++;
            }
        return mats > 0 || grassRound(level(), blockPosition()) > 0 || (home == null && floors > 4) || floors > 6;
    }

    /**
     * Walks to a patch within {@code range} of {@code around}, on a floor with room over it, apart from its herd mates:
     * best a glow mat with filament grass round it (0.20.2: it seeks out the grass), then either.
     */
    private boolean walkTo(BlockPos around, int range) {
        BlockPos best = null;
        int bestScore = -1;
        for (int i = 0; i < 10; i++) {
            int x = around.getX() + getRandom().nextInt(2 * range + 1) - range;
            int z = around.getZ() + getRandom().nextInt(2 * range + 1) - range;
            if ((x - around.getX()) * (x - around.getX()) + (z - around.getZ()) * (z - around.getZ()) > range * range) continue;
            BlockPos at = stand(level(), x, around.getY(), z, 5);
            if (at == null || crowded(at)) continue;
            int score = 1 + (onMat(level(), at) ? 3 : 0) + (grassRound(level(), at) >= 3 ? 3 : 0) + (at.distManhattan(blockPosition()) > 3 ? 1 : 0);
            if (score > bestScore) { bestScore = score; best = at; }
            if (bestScore >= 8) break;
        }
        if (best == null) return false;
        return getNavigation().moveTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5, WALK);
    }

    private boolean crowded(BlockPos at) {
        Vec3 c = Vec3.atBottomCenterOf(at);
        for (StiltMantisEntity m : level().getEntitiesOfClass(StiltMantisEntity.class, new AABB(at).inflate(SPACING + 1.0))) {
            if (m == this) continue;
            if (m.position().distanceToSqr(c) < SPACING * SPACING) return true;
            var path = m.getNavigation().getTargetPos();
            if (path != null && Vec3.atBottomCenterOf(path).distanceToSqr(c) < SPACING * SPACING) return true;
        }
        return false;
    }

    /** It looks about: its head flicks to a new spot every second or two. */
    private void lookAbout() {
        if (--lookTicks > 0) return;
        lookTicks = 15 + getRandom().nextInt(40);
        double a = getRandom().nextDouble() * Math.PI * 2.0, r = 4.0 + getRandom().nextDouble() * 6.0;
        double y = getEyeY() - 1.5 + getRandom().nextDouble() * 2.5;
        if (state() == ALERT && threat != null && getRandom().nextFloat() < 0.6F)
            getLookControl().setLookAt(threat.x, threat.y + 1.5, threat.z, 30.0F, 60.0F);
        else getLookControl().setLookAt(getX() + Math.cos(a) * r, y, getZ() + Math.sin(a) * r, 30.0F, 60.0F);
    }

    /**
     * Where one can stand in column (x, z) nearest {@code y}, up or down by at most {@code reach}: a floor whose top is
     * whole, with three open blocks over it (no fluid). Null if none.
     */
    @Nullable
    public static BlockPos stand(BlockGetter level, int x, int y, int z, int reach) {
        // never a column in a chunk that is not loaded (asking would make the server load or make it on the spot)
        if (level instanceof net.minecraft.world.level.LevelReader r && !r.hasChunkAt(new BlockPos(x, y, z))) return null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= 2 * reach; k++) {
            int yy = y + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
            if (standable(level, p, x, yy, z)) return new BlockPos(x, yy, z);
        }
        return null;
    }

    static boolean standable(BlockGetter level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState floor = level.getBlockState(p.set(x, y - 1, z));
        if (!floor.isFaceSturdy(level, p, net.minecraft.core.Direction.UP)) return false;
        for (int dy = 0; dy < 3; dy++) {
            BlockState s = level.getBlockState(p.set(x, y + dy, z));
            if (!s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    static boolean onMat(BlockGetter level, BlockPos at) {
        BlockState s = level.getBlockState(at.below());
        return s.is(ERBlocks.GLOW_MAT.get()) || s.is(ERBlocks.THIN_GLOW_MAT.get());
    }

    /** For /remix mantis: its state in words. */
    public String describe() {
        String s = switch (state()) {
            case GRAZE -> "grazing";
            case ALERT -> "watchful";
            case DISPLAY -> "startled";
            case FLEE -> "bolting";
            default -> getNavigation().isDone() ? "standing" : "walking";
        };
        StringBuilder b = new StringBuilder(colour().getSerializedName()).append(", ").append(s);
        if (herd >= 0) b.append(", herd ").append(Long.toHexString(herd));
        if (home != null) b.append(", ").append((int) Math.sqrt(home.distToCenterSqr(position()))).append(" blocks from home");
        if (dormant) b.append(", dormant");
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ client

    private void clientTick() {
        flareO = flare;
        float flareT = state() == DISPLAY ? 1.0F : 0.0F;
        flare += (flareT - flare) * (flareT > flare ? 0.5F : 0.15F);
        airTicks = onGround() || isInWater() ? 0 : airTicks + 1;
        boolean drawn = tickCount - drawnAt <= BODY_KEEP;
        if (body != null && !drawn && level().getNearestPlayer(this, BODY_HEAR) == null) body = null;
        else if (body != null || level().getNearestPlayer(this, BODY_HEAR) != null) tickBody();
    }

    /** Client: its body for drawing this frame, laid out on the spot if it has none (just come into sight). */
    public MantisBody drawnBody() {
        drawnAt = tickCount;
        if (body == null) tickBody();
        return body;
    }

    private void tickBody() {
        if (drive == null) {
            drive = new MantisBody.Drive();
            rock = new TerrainPatch();
            terrain = new LevelTerrain(level());
            bounds = new int[6];
        }
        if (body == null) {
            body = new MantisBody(MantisRig.shared().spec);
            body.setSeed(getUUID().getLeastSignificantBits());
            body.setFootfalls(this::footfall);
        }
        MantisBody.Drive in = drive;
        in.x = getX(); in.y = getY(); in.z = getZ();
        in.bodyYaw = Math.toRadians(yBodyRot);
        in.headYaw = Math.toRadians(yHeadRot);
        in.headPitch = Math.toRadians(getXRot());
        byte s = state();
        if (isDeadOrDying()) in.mode = MantisBody.Mode.DEAD;
        else if (airTicks > 4 && Math.abs(getY() - yo) > 0.05) in.mode = MantisBody.Mode.AIR;
        else in.mode = MantisBody.Mode.GROUND;
        in.crouch = 0.0;
        in.alert = s == ALERT ? 1.0 : s == DISPLAY ? 1.0 : s == FLEE ? 0.4 : 0.0;
        in.hurry = s == FLEE;
        in.graze = s == GRAZE;
        in.display = s == DISPLAY;
        in.watch = s == ALERT;
        body.bounds(in, bounds);
        rock.build(terrain, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        body.tick(rock, in);
    }

    /** Client: a foot lands: a light tap, a little louder running, the hind feet a touch deeper. */
    private void footfall(MantisBody.Foot foot, boolean running, double speed) {
        if (isSilent()) return;
        float volume = running ? 0.32F : 0.2F;
        float pitch = (foot.mid ? 1.05F : 0.92F) * (0.9F + random.nextFloat() * 0.2F);
        level().playLocalSound(foot.at.x, foot.at.y, foot.at.z, ERSounds.STILT_MANTIS_STEP.get(), getSoundSource(), volume, pitch, false);
    }

    /** Client: how bright its neon flares in its startle display (0 to 1). */
    public float flare(float pt) { return Mth.lerp(pt, flareO, flare); }
}
