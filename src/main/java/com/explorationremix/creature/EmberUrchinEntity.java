package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.body.EmberBody;
import com.explorationremix.creature.body.EmberPose;
import com.explorationremix.creature.body.EmberRig;
import com.explorationremix.creature.body.EmberRock;
import com.explorationremix.creature.body.EmberSpec;
import com.explorationremix.creature.body.EmberWalk;
import com.explorationremix.creature.body.LevelTerrain;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ember urchin of the Wasteland (0.23.7, Taran: "Can you add another urchin-like monster to the Wastelands? This one
 * should be more of a ball than a disk, be black with orange, ember-like details, and straddle the cave walls. It should
 * also walk around and be more of an obstacle to avoid than something to fight").
 *
 * <p>A black ball a block and a quarter across, plated, with embers in its seams, held in a tunnel's middle by thirty
 * long springy spines braced on its floor, walls and roof (its body, {@link EmberBody}, which bows each spine where it
 * presses on the rock). It goes its own way round the tunnels at a quarter of a player's walk ({@link EmberWalk}),
 * never after anyone and never turning (it has no front); in a cave it walks the floor on its lower spines. It keeps to
 * the Wasteland and out of the Ash Hollows.
 *
 * <p>It is an obstacle, not a fight (the cards):
 * <ul>
 *     <li>Its spines, wherever they are as it walks (worked out on the server too, so a prick is where its spines are
 *     drawn), prick anything that touches them: about two hearts, a moment alight, and a shove back out of them. A
 *     player in creative or spectating is never touched.</li>
 *     <li>It never walks into anyone: someone in its spines' way ahead holds it where it is, and held long, it turns
 *     back the way it came.</li>
 *     <li>About forty hearts, fireproof (it wades through lava), unmoved by blows; every blow struck at it by hand
 *     pricks the striker back. Arrows work, slowly.</li>
 *     <li>Its embers smoulder like breathing coals and flare as someone comes near (with a hiss), its spines turning to
 *     them; it casts a dim light on the rock round it (a light block it carries, put back as it goes).</li>
 * </ul>
 * In Peaceful it stays, and only shoves.
 */
public class EmberUrchinEntity extends Monster {
    /** Its spines' prick (data/explorationremix/damage_type/ember_urchin.json: scaled by difficulty, none in Peaceful). */
    public static final ResourceKey<DamageType> PRICK_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(ExplorationRemix.MOD_ID, "ember_urchin"));

    /** The player its spines turn to (-1 none). */
    private static final EntityDataAccessor<Integer> DATA_FOCUS = SynchedEntityData.defineId(EmberUrchinEntity.class, EntityDataSerializers.INT);
    /** Flared: someone near, or hurt. */
    private static final EntityDataAccessor<Boolean> DATA_FLARED = SynchedEntityData.defineId(EmberUrchinEntity.class, EntityDataSerializers.BOOLEAN);

    // ------------------------------------------------------------------------------------------------ tuning
    /** About forty hearts (the cards' "Very tough, and blows prick back"). */
    public static final float HEALTH = 80.0F;
    /** A prick: about two hearts on Normal (the cards' "Prick, burn and push you out"), and a blow's prick back. */
    public static final float PRICK = 4.0F, PRICK_BACK = 3.0F;
    /** Seconds alight after a prick, and after a prick back. */
    public static final int BURN = 3, BURN_BACK = 2;
    /** The shove out of its spines (blocks a tick, along the floor and up). */
    public static final double SHOVE = 0.55, SHOVE_UP = 0.28, SHOVE_BACK = 0.45;
    /** One thing is pricked at most this often (ticks). */
    public static final int PRICK_EVERY = 16;
    /** Its spines turn to a player this near that it can see; it flares at one this near. */
    public static final double NOTICE = 12.0, FLARE_NEAR = 6.0;
    /** Hurt, it flares this long (ticks); pricking something, at least this long. */
    static final int HURT_FLARE = 80, PRICK_FLARE = 30;
    /** No player this near (looked at every WAKE_LOOK ticks), its ticks are skipped. */
    public static final double WAKE_RANGE = 48.0;
    static final int WAKE_LOOK = 20;
    /** The server works out its spines while a player is this near (or anything is within their reach). */
    static final double BODY_NEAR = 24.0;
    /**
     * The light it casts: calm, and flared. Enough to light the rock round it a few blocks off, so its black spines stand
     * out against it (at 6 only the ember points showed in the dark Wasteland, its ball and spines lost in the rock).
     */
    public static final int LIGHT_CALM = 8, LIGHT_FLARED = 11;
    /** Its embers' breath (ticks a breath). */
    public static final double BREATH = 90.0;
    /** Its ball's middle over its box's foot (its box is its ball). */
    public static final double HALF = 0.625;

    // ------------------------------------------------------------------------------------------------ server
    private final EmberWalk walk = new EmberWalk();
    @Nullable private EmberSpots.LevelGround ground;
    private final List<Vector3d> others = new ArrayList<>();
    private final Vector3d mid = new Vector3d(), touched = new Vector3d();
    private boolean dormant, walkSeeded;
    private int hurtFlare, othersIn;
    /** When each thing it pricked was pricked (by entity id: tick). */
    private final Map<Integer, Integer> pricked = new HashMap<>();
    /** The server's own working of its spines (for its pricks), kept while anyone is near. */
    @Nullable private EmberBody hold;
    @Nullable private EmberBody.Drive holdDrive;
    @Nullable private EmberRock holdRock;
    @Nullable private LevelTerrain holdTerrain;
    private int holdIdle;
    /** The light it carries: where, at what level, and what it stands in for (air, or the deep world's cave air). */
    @Nullable private BlockPos lightPos;
    private int lightLevel;
    private BlockState lightWas = Blocks.CAVE_AIR.defaultBlockState();
    /** A light left where it was when its chunk was put away (from its save): taken up as it next ticks. */
    @Nullable private BlockPos strayLight;
    private boolean strayCave;
    /** For /remix ember: things pricked, blows pricked back. */
    public int pricks, pricksBack;

    // ------------------------------------------------------------------------------------------------ client
    @Nullable private EmberBody body;
    @Nullable private EmberBody.Drive drive;
    @Nullable private EmberRock rock;
    @Nullable private LevelTerrain terrain;
    private int drawnAt = -1000, lastStep;
    private float flare, flareO;
    private final Embers embers = new Embers();
    /** Client: its body is kept while drawn in the last this many ticks, or while a player is near enough to hear its spines. */
    static final int BODY_KEEP = 40;
    static final double BODY_HEAR = 20.0;

    public EmberUrchinEntity(EntityType<? extends EmberUrchinEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        xpReward = 8;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.MOVEMENT_SPEED, EmberWalk.SPEED)
                .add(Attributes.ATTACK_DAMAGE, PRICK)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, NOTICE);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_FOCUS, -1);
        entityData.define(DATA_FLARED, false);
    }

    @Override
    protected void registerGoals() {}

    public static EmberSpec spec() { return EmberRig.shared().spec; }

    /** Its ball's middle now. */
    public Vec3 middle() { return new Vec3(getX(), getY() + HALF, getZ()); }

    /** Sets it down with its middle at (x, y, z), going the way {@code heading} (radians round from +x). */
    public void setDown(double x, double y, double z, double heading) {
        moveTo(x, y - HALF, z, 0.0F, 0.0F);
        walk.face(Math.cos(heading), Math.sin(heading));
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData data, @Nullable CompoundTag tag) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (reason != MobSpawnType.CHUNK_GENERATION && reason != MobSpawnType.NATURAL) {
            double a = random.nextDouble() * Math.PI * 2.0;
            walk.face(Math.cos(a), Math.sin(a));
        }
        return out;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("HeadingX", walk.heading.x);
        tag.putDouble("HeadingZ", walk.heading.z);
        BlockPos l = lightPos != null ? lightPos : strayLight;
        if (l != null) {
            tag.put("Light", NbtUtils.writeBlockPos(l));
            tag.putBoolean("LightCave", lightPos != null ? lightWas.is(Blocks.CAVE_AIR) : strayCave);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("HeadingX")) walk.face(tag.getDouble("HeadingX"), tag.getDouble("HeadingZ"));
        if (tag.contains("Light")) {
            strayLight = NbtUtils.readBlockPos(tag.getCompound("Light"));
            strayCave = tag.getBoolean("LightCave");
        }
        setNoGravity(true);
    }

    /** Set down with the world, it stays when no one is near, as the animals a world starts with do. */
    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean requiresCustomPersistence() { return true; }

    /** In Peaceful it stays (its pricks do nothing there: it only shoves). */
    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    // ------------------------------------------------------------------------------------------------ the box

    /** Its box is its ball; its spines reach some three and a half blocks round it. */
    @Override
    public AABB getBoundingBoxForCulling() { return getBoundingBox().inflate(3.9); }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions size) { return size.height * 0.5F; }

    /** Nothing moves it but its own spines. */
    @Override
    public boolean isPushable() { return false; }

    @Override
    public void knockback(double strength, double x, double z) {}

    /** Rock its ball touches is what it braces against, never what smothers it. */
    @Override
    public boolean isInWall() { return false; }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }

    /** It walks on its spines (see {@link #customServerAiStep}); dead, it falls. */
    @Override
    public void travel(Vec3 input) {
        if (isAlive()) {
            calculateEntityAnimation(false);
            return;
        }
        super.travel(input);
    }

    // ------------------------------------------------------------------------------------------------ sounds

    @Override
    protected SoundEvent getAmbientSound() { return ERSounds.EMBER_URCHIN_CRACKLE.get(); }

    @Override
    public int getAmbientSoundInterval() { return 70; }

    @Override
    protected float getSoundVolume() { return 0.8F; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return ERSounds.EMBER_URCHIN_HURT.get(); }

    @Override
    protected SoundEvent getDeathSound() { return ERSounds.EMBER_URCHIN_DEATH.get(); }

    /** Its steps are its spines' own taps (see {@link #footfall}). */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    // ------------------------------------------------------------------------------------------------ being hurt

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(PRICK_DAMAGE)) return false;
        boolean hurt = super.hurt(source, amount);
        if (!level().isClientSide && isAlive()) {
            if (hurt) hurtFlare = HURT_FLARE;
            Entity by = source.getEntity();
            // a blow struck by hand: its spines prick the striker back (the cards' "Every sword blow pricks you back")
            if (hurt && by instanceof LivingEntity le && source.getDirectEntity() == by && !(le instanceof EmberUrchinEntity)) prickBack(le);
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        if (!level().isClientSide) clearLight();
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason != RemovalReason.UNLOADED_TO_CHUNK && reason != RemovalReason.UNLOADED_WITH_PLAYER) clearLight();
        super.remove(reason);
    }

    // ------------------------------------------------------------------------------------------------ tick

    @Override
    public void tick() {
        if (!level().isClientSide && dormantNow()) return;
        super.tick();
        if (level().isClientSide) clientTick();
    }

    /**
     * Server: with no player near ({@link #WAKE_RANGE}), its ticks are skipped. The level counts its {@code tickCount}
     * on before each tick, asleep or not (counting it again here once left half of them, those whose count and id summed
     * to an odd number, never looking again: asleep for good beside a player).
     */
    private boolean dormantNow() {
        if (!isAlive() || hurtTime > 0) return dormant = false;
        if ((tickCount + getId()) % WAKE_LOOK == 0 || tickCount < 2) {
            boolean near = false;
            for (Player p : level().players()) if (!p.isSpectator() && p.distanceToSqr(this) < WAKE_RANGE * WAKE_RANGE) { near = true; break; }
            dormant = !near;
        }
        return dormant;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!isAlive() || !(level() instanceof ServerLevel sl)) return;
        if (ground == null) ground = new EmberSpots.LevelGround(sl);
        if (!walkSeeded) {
            walk.setSeed(getUUID().getLeastSignificantBits());
            walkSeeded = true;
            Vec3 m = middle();
            ground.keepToWasteland = EmberSpots.inLayer(m.y);
            ground.inHollows = ground.keepToWasteland && !EmberSpots.inWasteland(m.x, m.y, m.z);
        }
        ground.fresh();
        mid.set(getX(), getY() + HALF, getZ());
        EmberSpec spec = spec();
        double reach = spec.span();
        List<LivingEntity> near = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach + 1.5),
                e -> e != this && e.isAlive() && !e.isSpectator() && !(e instanceof EmberUrchinEntity));
        boolean blocked = blockedBy(near, reach);
        if (--othersIn <= 0) {
            othersIn = 12;
            others.clear();
            for (EmberUrchinEntity o : level().getEntitiesOfClass(EmberUrchinEntity.class, getBoundingBox().inflate(16.0), o -> o != this && o.isAlive()))
                others.add(new Vector3d(o.getX(), o.getY() + HALF, o.getZ()));
        }
        Vector3d v = walk.tick(ground, mid, others, blocked);
        setDeltaMovement(Vec3.ZERO);
        if (v.lengthSquared() > 1.0E-8) move(MoverType.SELF, new Vec3(v.x, v.y, v.z));
        mid.set(getX(), getY() + HALF, getZ());

        // its spines turn to the nearest player it can see; it flares at one near, or hurt
        Player nearest = null;
        double best = NOTICE * NOTICE;
        for (Player p : level().players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(mid.x, mid.y, mid.z);
            if (d < best) { best = d; nearest = p; }
        }
        if ((tickCount + getId()) % 10 == 0) {
            int focus = nearest != null && sees(nearest) ? nearest.getId() : -1;
            if (entityData.get(DATA_FOCUS) != focus) entityData.set(DATA_FOCUS, focus);
        }
        if (hurtFlare > 0) hurtFlare--;
        boolean flared = hurtFlare > 0 || (nearest != null && best < FLARE_NEAR * FLARE_NEAR);
        if (flared != entityData.get(DATA_FLARED)) {
            entityData.set(DATA_FLARED, flared);
            if (flared) playSound(ERSounds.EMBER_URCHIN_HISS.get(), 0.9F, 0.9F + random.nextFloat() * 0.2F);
        }
        holdTick(near);
        if ((tickCount & 3) == 0 || strayLight != null) lightTick(sl, flared);
        if ((tickCount % 200) == 0) {
            for (Iterator<Map.Entry<Integer, Integer>> it = pricked.entrySet().iterator(); it.hasNext(); )
                if (tickCount - it.next().getValue() > PRICK_EVERY * 4) it.remove();
        }
    }

    /** Whether something stands in its spines' way ahead (the way it goes), near enough that it would walk its spines into it. */
    private boolean blockedBy(List<LivingEntity> near, double reach) {
        Vector3d h = walk.heading;
        for (LivingEntity e : near) {
            if (e instanceof AmbientCreature) continue;
            double dx = e.getX() - mid.x, dz = e.getZ() - mid.z, dy = e.getY() + e.getBbHeight() * 0.5 - mid.y;
            if (Math.abs(dy) > 3.2) continue;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d - e.getBbWidth() * 0.5 > reach + 0.6) continue;
            if (d < 1.0E-3 || (dx * h.x + dz * h.z) / d > 0.3) return true;
        }
        return false;
    }

    private boolean sees(Entity e) {
        Vec3 from = middle(), to = e.getEyePosition();
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------------------------------------ its spines

    /** Server: its spines worked out as the client draws them (kept while anyone is near), then what they touch pricked. */
    private void holdTick(List<LivingEntity> near) {
        boolean wanted = !near.isEmpty();
        if (!wanted) for (Player p : level().players()) if (!p.isSpectator() && p.distanceToSqr(this) < BODY_NEAR * BODY_NEAR) { wanted = true; break; }
        if (!wanted) {
            if (hold != null && ++holdIdle > 100) hold = null;
            return;
        }
        holdIdle = 0;
        if (hold == null) {
            hold = new EmberBody(spec());
            hold.setSeed(getUUID().getLeastSignificantBits());
            holdDrive = new EmberBody.Drive();
            holdRock = new EmberRock();
            holdTerrain = new LevelTerrain(level(), true);
        }
        fill(holdDrive, entityData.get(DATA_FLARED) ? 1.0 : 0.0);
        holdTerrain.fresh();
        hold.tick(holdRock.around(hold, holdDrive, holdTerrain), holdDrive);
        for (LivingEntity e : near) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Integer last = pricked.get(e.getId());
            if (last != null && tickCount - last < PRICK_EVERY) continue;
            AABB b = e.getBoundingBox();
            if (hold.touches(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, touched) == -2) continue;
            prick(e);
        }
    }

    /** Its spines prick something touching them: about two hearts, a moment alight and a shove back out of them. */
    private void prick(LivingEntity e) {
        pricked.put(e.getId(), tickCount);
        Vec3 was = e.getDeltaMovement();
        boolean hurt = e.hurt(prickSource(), PRICK);
        if (hurt && !(e instanceof Player && level().getDifficulty() == Difficulty.PEACEFUL)) e.setSecondsOnFire(BURN);
        shove(e, was, SHOVE);
        hurtFlare = Math.max(hurtFlare, PRICK_FLARE);
        playSound(ERSounds.EMBER_URCHIN_STEP.get(), 1.0F, 1.55F + random.nextFloat() * 0.2F);
        pricks++;
    }

    /** A blow struck at it by hand: the striker is pricked back, set alight a moment and shoved off. */
    private void prickBack(LivingEntity by) {
        if (by instanceof Player p && (p.isCreative() || p.isSpectator())) return;
        pricked.put(by.getId(), tickCount);
        Vec3 was = by.getDeltaMovement();
        boolean hurt = by.hurt(prickSource(), PRICK_BACK);
        if (hurt && !(by instanceof Player && level().getDifficulty() == Difficulty.PEACEFUL)) by.setSecondsOnFire(BURN_BACK);
        shove(by, was, SHOVE_BACK);
        playSound(ERSounds.EMBER_URCHIN_STEP.get(), 1.0F, 1.7F);
        pricksBack++;
    }

    private DamageSource prickSource() {
        return new DamageSource(level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(PRICK_DAMAGE), this);
    }

    /**
     * Shoves {@code e} straight out from its middle, at once: a player's push is sent to them now (a push left for the
     * game to send arrives a tick late and half spent: see ChoirSummon's fling), {@code was} how they moved before the prick.
     */
    private void shove(LivingEntity e, Vec3 was, double strength) {
        double dx = e.getX() - mid.x, dz = e.getZ() - mid.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-3) { dx = walk.heading.x; dz = walk.heading.z; d = 1.0; }
        double x = dx / d * strength, z = dz / d * strength;
        if (e instanceof ServerPlayer sp) {
            sp.setDeltaMovement(was);
            sp.hurtMarked = false;
            if (!sp.isAlive() || sp.connection == null) return;
            double share = sp.onGround() ? 1.0 : 0.6;
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp.getId(), new Vec3(x * share, SHOVE_UP, z * share)));
        } else {
            e.setDeltaMovement(x, Math.max(SHOVE_UP, was.y), z);
            e.hurtMarked = true;
        }
    }

    /** What moves its body this tick (client and server alike). */
    private void fill(EmberBody.Drive in, double flareNow) {
        in.x = getX();
        in.y = getY() + HALF;
        in.z = getZ();
        in.mode = isDeadOrDying() ? EmberBody.Mode.DEAD : EmberBody.Mode.LIVE;
        in.flare = flareNow;
        int focus = entityData.get(DATA_FOCUS);
        Entity at = focus >= 0 ? level().getEntity(focus) : null;
        if (at != null && at.isAlive()) {
            in.focus.set(at.getX(), at.getY() + at.getBbHeight() * 0.6, at.getZ());
            in.focusAmount = 1.0;
        } else in.focusAmount = 0.0;
    }

    // ------------------------------------------------------------------------------------------------ its light

    /**
     * Its dim light on the rock (the cards' "A dim glow on the rock"): a light block at its middle, brighter flared,
     * moved as it goes and put back as it was (air or cave air) when it moves on, dies or is taken away. A chunk put
     * away with it keeps its light; the save remembers where, and it takes it up as it next ticks.
     */
    private void lightTick(ServerLevel level, boolean flared) {
        if (strayLight != null) {
            takeUp(level, strayLight, strayCave ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.AIR.defaultBlockState());
            strayLight = null;
        }
        BlockPos at = BlockPos.containing(getX(), getY() + HALF, getZ());
        int want = flared ? LIGHT_FLARED : LIGHT_CALM;
        if (lightPos != null && (!lightPos.equals(at) || lightLevel != want)) {
            if (lightPos.equals(at) && level.getBlockState(at).is(Blocks.LIGHT)) {
                level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, want), Block.UPDATE_CLIENTS);
                lightLevel = want;
                return;
            }
            clearLight();
        }
        if (lightPos != null || !level.hasChunkAt(at)) return;
        BlockState here = level.getBlockState(at);
        if (!here.is(Blocks.AIR) && !here.is(Blocks.CAVE_AIR)) return;
        lightWas = here;
        level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, want), Block.UPDATE_CLIENTS);
        lightPos = at.immutable();
        lightLevel = want;
    }

    private void clearLight() {
        if (lightPos == null || !(level() instanceof ServerLevel l)) return;
        takeUp(l, lightPos, lightWas);
        lightPos = null;
    }

    private static void takeUp(ServerLevel l, BlockPos p, BlockState was) {
        if (l.hasChunkAt(p) && l.getBlockState(p).is(Blocks.LIGHT)) l.setBlock(p, was, Block.UPDATE_CLIENTS);
    }

    // ------------------------------------------------------------------------------------------------ for commands

    /** For /remix ember: what it is doing, in words. */
    public String describe() {
        String spines = hold != null && hold.laid() ? hold.plantedCount() + " of " + hold.legs.length + " spines braced" : "spines not worked out (no one near)";
        String light = lightPos != null ? String.format(Locale.ROOT, "light %d at %d %d %d", lightLevel, lightPos.getX(), lightPos.getY(), lightPos.getZ())
                : "no light";
        return String.format(Locale.ROOT, "%s; %s; %s%s; %s; %.0f/%.0f health; pricked %d, pricked back %d",
                walk.describe(), spines, entityData.get(DATA_FLARED) ? "flared" : "smouldering", dormant ? ", asleep" : "", light,
                getHealth(), getMaxHealth(), pricks, pricksBack);
    }

    /** For /remix ember flare: flares as if hurt, for {@code ticks}. */
    public void flareFor(int ticks) { hurtFlare = Math.max(hurtFlare, ticks); }

    public EmberWalk walk() { return walk; }

    @Nullable
    public EmberBody serverBody() { return hold; }

    // ------------------------------------------------------------------------------------------------ client

    private void clientTick() {
        flareO = flare;
        boolean f = entityData.get(DATA_FLARED) && !isDeadOrDying();
        flare += ((f ? 1.0F : 0.0F) - flare) * (f ? 0.3F : 0.035F);
        boolean drawn = tickCount - drawnAt <= BODY_KEEP;
        Player hearer = level().getNearestPlayer(this, BODY_HEAR);
        if (body != null && !drawn && hearer == null) body = null;
        else if (body != null || hearer != null) tickBody();
        sparks();
    }

    /** Client: now and then a spark drifts up off its ball or a spine's hot point; many more flared. */
    private void sparks() {
        if (isDeadOrDying() || isInvisible()) return;
        if (random.nextFloat() < 0.035F + 0.22F * flare) {
            double a = random.nextDouble() * Math.PI * 2.0, y = 0.1 + random.nextDouble() * 0.9, r = Math.sqrt(1.0 - y * y);
            double s = spec().radius * 1.03;
            level().addParticle(ERParticles.EMBER.get(), getX() + Math.cos(a) * r * s, getY() + HALF + y * s, getZ() + Math.sin(a) * r * s, 0, 0, 0);
        }
        if (body != null && random.nextFloat() < 0.02F + 0.08F * flare) {
            EmberBody.Leg lg = body.legs[random.nextInt(body.legs.length)];
            level().addParticle(ERParticles.EMBER.get(), lg.at.x, lg.at.y + 0.05, lg.at.z, 0, 0, 0);
        }
    }

    /** Client: its body for drawing this frame, laid out on the spot if it has none (just come into sight). */
    @Nullable
    public EmberBody drawnBody() {
        drawnAt = tickCount;
        if (body == null) tickBody();
        return body;
    }

    private void tickBody() {
        if (drive == null) {
            drive = new EmberBody.Drive();
            rock = new EmberRock();
            terrain = new LevelTerrain(level());
        }
        if (body == null) {
            body = new EmberBody(spec());
            body.setSeed(getUUID().getLeastSignificantBits());
            body.setFootfalls(this::footfall);
        }
        fill(drive, flare);
        body.tick(rock.around(body, drive, terrain), drive);
    }

    /** Client: a spine's point sets down on the rock: a light tick of its needle (thinned, so many tick rather than roar). */
    private void footfall(EmberBody.Leg leg, double speed) {
        if (isSilent() || tickCount - lastStep < 3) return;
        lastStep = tickCount;
        float pitch = (leg.index % 3 == 0 ? 1.1F : 0.95F) * (0.9F + random.nextFloat() * 0.2F);
        level().playLocalSound(leg.at.x, leg.at.y, leg.at.z, ERSounds.EMBER_URCHIN_STEP.get(), getSoundSource(), 0.22F, pitch, false);
    }

    /** Client: how flared it is (0 smouldering, 1 flared). */
    public float flare(float pt) { return Mth.lerp(pt, flareO, flare); }

    /** Client: its breath, -1 to 1 (its embers brighten and its ball swells a little toward 1). */
    public double breath(float pt) {
        return Math.sin(((tickCount + pt) / BREATH + (getId() * 0.37 % 1.0)) * Math.PI * 2.0);
    }

    /** Client: how its glow is shaded over it this frame: the breath rolling over its ball, its points' own flicker. */
    public EmberPose.Shade embers(float pt) {
        embers.time = tickCount + pt + getId() * 0.37 * BREATH;
        embers.flare = flare(pt);
        return embers;
    }

    /**
     * Its embers: a slow breath rolling over its ball from one side to the other (each seam brightening as it passes,
     * the cards' "Smoulders like breathing coals"), its spines' hot points flickering each to its own beat, the coat's
     * longer spines dim with the breath; all of it full, flared.
     */
    public static final class Embers implements EmberPose.Shade {
        public double time;
        public float flare;

        @Override
        public float at(int kind, float along, float phase) {
            if (kind == EmberRig.BALL) {
                double w = 0.5 + 0.5 * Math.sin((time / BREATH - phase * 0.8) * Math.PI * 2.0);
                float calm = (float) (0.55 + 0.45 * w * w);
                return calm + (1.0F - calm) * flare;
            }
            if (kind == EmberRig.SPINE) {
                double f = 0.8 + 0.12 * Math.sin(time * 0.61 + phase * 37.0) + 0.08 * Math.sin(time * 1.43 + phase * 11.0);
                return (float) Math.min(1.0, f + 0.25 * flare);
            }
            double w = 0.5 + 0.5 * Math.sin((time / BREATH - phase * 0.3) * Math.PI * 2.0);
            return (float) Math.min(1.0, 0.35 + 0.35 * w + 0.4 * flare);
        }
    }
}
