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
 * its patch. It lives in a den ({@link MossbackDen}); killed, it leaves the den empty for a few days.
 */
public class MossbackEntity extends Monster implements ClimbMoveControl.Climber {
    // ------------------------------------------------------------------------------------------------ tuning
    public static final double HEALTH = 80.0, ARMOR = 4.0, SPEED = 0.2;
    static final double ROAM_SPEED = 0.5, SEARCH_SPEED = 0.85, CHASE_SPEED = 1.2;
    static final int TERRITORY = 24, LEASH = 64;
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
            EAT = 9, DROP = 10;
    private static final EntityDataAccessor<Byte> DATA_STATE = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    /** The side its rock is on (a Direction's 3D value), or -1 while it falls or swims. */
    private static final EntityDataAccessor<Byte> DATA_SURFACE = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_VARIANT = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_HELD = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.INT);
    /** The way its head points along a wall or ceiling (the server's {@code forward}), for drawing it there. */
    private static final EntityDataAccessor<Vector3f> DATA_FORWARD = SynchedEntityData.defineId(MossbackEntity.class, EntityDataSerializers.VECTOR3);

    public static final byte MOSS = 0, FROST = 1;

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
    private Vec3 forward = new Vec3(0, 0, 1);
    private long ignoreRivalsUntil;
    private float stepDist;
    private boolean throatHit;
    private boolean frozen;                  // /remix mossback freeze: holds still where it is, in the state it is in

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
    @Nullable public BlockPos den() { return den; }
    public void setDen(@Nullable BlockPos pos) { this.den = pos; if (pos != null) this.home = pos; }

    @Override public boolean onFloor() { return surface() == Direction.DOWN; }
    @Override public boolean canSteer() { byte s = state(); return s != LUNGE && s != DROP && hurtTime < 6; }
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
    }

    @Override
    protected void customServerAiStep() {
        if (frozen) {                                   // held in place for a picture (/remix mossback freeze)
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
            return;
        }
        updateSurface();
        if (home == null) home = blockPosition();
        stateTicks++;
        if (hunger > 0) hunger--;
        LivingEntity t = getTarget();
        if (t != null && state() == GRAB && t.getId() == heldId() && !t.isAlive()) {
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
            default -> toState(ROAM, 0);
        }
        if (den != null && tickCount % 100 == 0) MossbackDen.checkIn(this);
    }

    // --- roaming and waiting

    private void roam() {
        if (tickCount % 5 == 0 && spot(true)) return;
        PathNavigation nav = getNavigation();
        if (pauseTicks > 0) { pauseTicks--; return; }
        if (nav.isDone() || nav.isStuck()) {
            if (stateTicks > 40) {
                // a leg is done: sometimes settle in the plants to wait, otherwise pause and pick the next leg
                if (random.nextFloat() < 0.25F && findWaitSpot()) { toState(WAIT, 600 + random.nextInt(1200)); return; }
                pauseTicks = 20 + random.nextInt(50);
                stateTicks = 0;
                Vec3 to = roamTarget();
                if (to != null) nav.moveTo(to.x, to.y, to.z, ROAM_SPEED);
            } else if (stateTicks == 1) {
                Vec3 to = roamTarget();
                if (to != null) nav.moveTo(to.x, to.y, to.z, ROAM_SPEED);
            }
        }
    }

    /** A random walkable spot in its patch (towards home when it has strayed). */
    @Nullable
    private Vec3 roamTarget() {
        BlockPos h = home != null ? home : blockPosition();
        boolean strayed = blockPosition().distSqr(h) > (double) TERRITORY * TERRITORY;
        for (int i = 0; i < 12; i++) {
            BlockPos c = strayed ? h.offset(random.nextInt(9) - 4, random.nextInt(5) - 2, random.nextInt(9) - 4)
                    : h.offset(random.nextInt(2 * TERRITORY + 1) - TERRITORY, random.nextInt(17) - 8, random.nextInt(2 * TERRITORY + 1) - TERRITORY);
            if (!loadedAround(c)) continue;
            BlockPos n = nearestWalkable(c, 6);
            if (n == null || !level().isLoaded(n)) continue;
            if (isRefuge(n)) continue;
            return new Vec3(n.getX() + 1.0, n.getY(), n.getZ() + 1.0);
        }
        return null;
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
            if (n == null || isRefuge(n)) continue;
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
        if (waitSpot == null) { toState(ROAM, 0); return; }
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
                    if (!nav.moveTo(waitSpot.getX() + 1.0, waitSpot.getY(), waitSpot.getZ() + 1.0, ROAM_SPEED)) toState(ROAM, 0);
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
        AABB around = getBoundingBox().inflate(SIGHT);
        Player player = null;
        double best = Double.MAX_VALUE;
        for (Player p : level().players()) {
            if (!huntable(p) || !around.contains(p.position())) continue;
            double d = p.distanceToSqr(this);
            if (d < best && (d < TOUCH * TOUCH || canSee(p))) { best = d; player = p; }
        }
        if (player != null) return player;
        long now = level().getGameTime();
        if (now >= ignoreRivalsUntil && home != null) {
            for (MossbackEntity m : level().getEntitiesOfClass(MossbackEntity.class, getBoundingBox().inflate(12), m -> m != this && m.isAlive())) {
                if (m.home != null && !m.home.equals(home) && m.blockPosition().distSqr(home) < (double) TERRITORY * TERRITORY && canSee(m)) return m;
            }
        }
        if (hunger <= 0) {
            List<LivingEntity> prey = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(16), e -> e.getType().is(PREY) && e.isAlive());
            LivingEntity near = null;
            double nb = Double.MAX_VALUE;
            for (LivingEntity e : prey) {
                double d = e.distanceToSqr(this);
                if (d < nb && canSee(e)) { nb = d; near = e; }
            }
            return near;
        }
        return null;
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
        setTarget(t);
        lastSeen = t.position();
        lastSeenTime = level().getGameTime();
        chaseStart = lastSeenTime;
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
        if (home != null && blockPosition().distSqr(home) > (double) LEASH * LEASH && !sees) { giveUp(); return; }
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
            t.setPos(m.x, Math.max(t.getY(), m.y - t.getBbHeight() * 0.5), m.z);
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
        toState(EAT, 240);
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
        if (stateTicks >= stateLength) toState(ROAM, 0);
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
            if (attacker instanceof LivingEntity le && le != this && huntable(le) && state() != GRAB && getTarget() != le
                    && (getTarget() == null || !(getTarget() instanceof Player))) {
                setTarget(le);
                lastSeen = le.position();
                lastSeenTime = level().getGameTime();
                if (state() == ROAM || state() == WAIT || state() == EAT || state() == SEARCH) toState(CHASE, 0);
            }
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) {
            release(false);
            MossbackDen.lizardDied(this);
        }
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide) release(false);
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
    @Override public int getMaxHeadYRot() { return 50; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Variant", variant());
        if (den != null) tag.put("Den", NbtUtils.writeBlockPos(den));
        if (home != null) tag.put("Home", NbtUtils.writeBlockPos(home));
        tag.putInt("Hunger", hunger);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setVariant(tag.getByte("Variant"));
        den = tag.contains("Den") ? NbtUtils.readBlockPos(tag.getCompound("Den")) : null;
        home = tag.contains("Home") ? NbtUtils.readBlockPos(tag.getCompound("Home")) : den;
        hunger = tag.getInt("Hunger");
    }

    /** For /remix mossback: what it is doing. */
    public String describe() {
        String[] names = {"roam", "wait", "alert", "chase", "rear", "lunge", "grab", "recoil", "search", "eat", "drop"};
        byte s = state();
        Direction d = surface();
        return (s >= 0 && s < names.length ? names[s] : "?") + (s == WAIT ? (ambushing ? " (still)" : " (going)") : "")
                + " on " + (d == null ? "nothing" : d == Direction.DOWN ? "floor" : d == Direction.UP ? "ceiling" : "wall " + d.getName())
                + ", health " + Math.round(getHealth()) + ", target " + (getTarget() == null ? "none" : getTarget().getName().getString())
                + ", den " + (den == null ? "none" : den.toShortString()) + ", hunger " + hunger / 20 + "s";
    }

    public void forceState(byte s, int length) { toState(s, length); }

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
            default -> 0.0F;
        };
        jaw += (jawT - jaw) * 0.35F;
        float rearT = s == REAR ? 1.0F : s == ALERT ? 0.35F : 0.0F;
        rear += (rearT - rear) * 0.3F;
        float frillT = (s == ALERT || s == REAR || s == LUNGE || s == GRAB || s == CHASE || s == DROP) ? 1.0F : 0.0F;
        frill += (frillT - frill) * 0.25F;

        // orientation: its belly to its rock, its head the way it goes
        Direction d = surface();
        Vec3 upT = d == null ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(d.getOpposite().getNormal());
        if (clientUp.dot(upT) < -0.99) clientUp = clientUp.add(clientForward.scale(0.3));   // turn over through the side
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
