package com.explorationremix.creature.choir;

import com.explorationremix.combat.WindUp;
import com.explorationremix.combat.swing.Swordplay;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.feature.ChoirArena;
import com.explorationremix.world.terrain.ChoirSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.entity.PartEntity;

import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Hollow Choir (0.21.9): the Deep Dark's boss, called into its own arena ({@link ChoirSites}) by three keys taken
 * from three ancient cities. A crown of bone plates hangs in the air over the dais with a bell inside it, held up by
 * six great sculk tendrils that run from the cavern's ceiling past it to roots on the floor. Nothing of it is the
 * Warden's: it does not listen, it does not come looking, and it never leaves its bowl.
 *
 * <p>The loop, which the whole fight is built on (Taran's card picks): while the crown is up it cannot be hurt at all
 * - a blow on it rings the bell and does nothing - so the way in is to cut the tendrils, which hold it. Damage to a
 * tendril both wears that tendril through and tells on the Choir itself, so the bar moves while you work. Cut the last
 * standing tendril and the crown has nothing left to hang from: it falls, the bell opens, and for
 * {@link #OPEN_TICKS} ticks the core inside takes everything you have. Then it climbs back up and grows new tendrils,
 * fewer and tougher each phase.
 *
 * <p>Three phases, and the arena changes with them: the braziers go out one ring at a time and the sculk comes further
 * out over the floor ({@link ChoirSummon#dress}), so the last phase is fought in the Choir's own light on ground that
 * is nearly all sculk. Lose, and nothing is spent: the Choir goes, the arena is put back as it was and the three keys
 * stay in their sockets, so the next try costs nothing.
 *
 * <p>Every blow it strikes shows where it will land and counts down to when (0.22.3): the clock of each is in
 * {@link ChoirActs}, which the fight here and the drawing both read, and the tendrils' lines are in {@link ChoirPose}.
 * A slam leaves an opening too: the tendril's head lies where it struck for a second, and a blow on it there cuts
 * the tendril half again as fast as a blow on its root. This class runs the turns on the server and keeps, for the
 * player's own game, the little state the drawing eases between ticks: how far each tendril has grown, reared and
 * struck, and what in the bowl is lit.
 */
public class HollowChoirEntity extends Monster implements WindUp {
    // ------------------------------------------------------------------------------------------------------ numbers
    /**
     * The core's own health: what the bar shows. Tuned for diamond gear and a few runes, about five minutes.
     *
     * <p>How the budget works, since the three numbers below only make sense together. Nearly all of a player's
     * damage goes into tendrils, and only {@link #TENDRIL_SHARE} of that reaches the core, so the most damage the
     * fight can ever ask for is {@code HEALTH / TENDRIL_SHARE}: 2700 here, which at a diamond sword's rate, with the
     * time spent dodging and the time the crown is simply unhittable, comes out near five minutes.
     *
     * <p>The other half of the budget is that cutting a whole phase's tendrils has to come to <em>less</em> than one
     * phase's slice of the health (180 here): 6 x 120 x 0.2 = 144, 5 x 150 x 0.2 = 150, 4 x 190 x 0.2 = 152. That is
     * what makes the crown fall because you cut the last tendril, which is the whole loop. Were it more, the health
     * border would always come first and cut the rest for you, and the tendrils would be scenery.
     */
    public static final float HEALTH = 540;
    /** Phase borders, as a share of the health. */
    public static final float PHASE2 = 2 / 3F, PHASE3 = 1 / 3F;
    /** The tendrils: how many stand in each phase, and what it takes to cut one through. */
    public static final int[] TENDRILS = {6, 5, 4};
    public static final float[] TENDRIL_CUT = {120, 150, 190};
    /** Six places a tendril can stand, so a phase's tendrils are spread evenly round the dais. */
    public static final int PLACES = ChoirArena.ROOTS;
    /** How far out the tendrils are rooted, and how far out the crown's rim reaches. */
    public static final double TENDRIL_R = ChoirArena.ROOT_R, CROWN_R = 2.25;
    /** The crown hangs this far over the bowl's floor, and lies this far over it once it has fallen. */
    public static final double CROWN_UP = 6, CROWN_DOWN = 1.0;
    /** How long the core stays open after a fall, and how long the crown takes to fall and to climb back. */
    public static final int OPEN_TICKS = 180, FALL_TICKS = 24, RISE_TICKS = 50;
    /** A tendril's reachable part: a box this wide, standing this tall off the floor. */
    public static final float TENDRIL_W = 1.6F, TENDRIL_H = 5.0F;
    /** Of the damage a tendril takes, this share reaches the Choir itself (see {@link #HEALTH} for why it is low). */
    public static final float TENDRIL_SHARE = 0.2F;
    /** A strong attack in a slam's wind-up spoils it: the tendril loses this much, and the Choir stalls this long. */
    public static final float INTERRUPT_CUT = 20;
    public static final int INTERRUPT_STALL = 40;
    /** The sprouts: each mends the Choir this often and takes this share off a blow, up to this much in all. */
    public static final int SPROUT_MEND_EVERY = 40;
    public static final float SPROUT_WARD = 0.04F, SPROUT_WARD_MAX = 0.25F;
    /**
     * Nobody within this far of the dais for this long and the fight is let go: the bowl, its rim walk and six blocks
     * of the cavern round it. It was 44 (0.22.2), twenty blocks past the rim, which left a wide ring of cavern to
     * stand in with a bow where nothing the Choir does could reach; the grab pulls from this whole distance now.
     */
    public static final double KEEP = ChoirSites.RIM_R + 6;
    public static final int LEAVE_TICKS = 100;
    /** How often it strikes dumb any shrieker that has come into range since ({@link ChoirSummon#hush}). */
    private static final int HUSH_EVERY = 100;
    /** What it gives up: about twenty levels' worth of experience. */
    public static final int XP = 1400;
    /** What it costs to stand in a slam, to be passed by a toll's ring, and to be on the sculk when it blooms. */
    public static final float SLAM_HURT = 7, TOLL_HURT = 5, BLOOM_HURT = 4;
    /** How long its death takes: the crown flares and comes apart before it is gone. */
    public static final int DEATH_TICKS = 40;

    // actions, which the client reads to animate: packed with the tendril in play in the top four bits
    public static final int ACT_IDLE = 0, ACT_WINDUP = 1, ACT_SLAM = 2, ACT_TOLL = 3, ACT_GRAB = 4,
            ACT_BLOOM = 5, ACT_FALL = 6, ACT_OPEN = 7, ACT_RISE = 8, ACT_STALL = 9, ACT_ENTER = 10;
    /** How finely a tendril's wear is told to the player's game: this many steps from whole to cut through. */
    public static final int WEAR_STEPS = 31;

    private static final EntityDataAccessor<Byte> DATA_PHASE =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_STANDING =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_ACT =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> DATA_ACT_TICK =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_TOP =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPIN =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.FLOAT);
    /** The bowl's floor, which everything else is measured from. Synched, so the client can place the tendrils. */
    private static final EntityDataAccessor<Float> DATA_FLOOR =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.FLOAT);
    /** The arena's own turn, which the roots are laid out by ({@link ChoirArena#rootAngle}). */
    private static final EntityDataAccessor<Float> DATA_TURN =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.FLOAT);
    /** A slam's mark, measured from the dais's middle at the bowl's floor: where the tendril in play will come down. */
    private static final EntityDataAccessor<Vector3f> DATA_MARK =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.VECTOR3);
    /** How far each tendril is cut through, five bits a place, so its light can die as it is worn. */
    private static final EntityDataAccessor<Integer> DATA_WEAR =
            SynchedEntityData.defineId(HollowChoirEntity.class, EntityDataSerializers.INT);

    private final ServerBossEvent bar = new ServerBossEvent(
            Component.translatable("entity.explorationremix.hollow_choir"), BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS);

    /** The six tendrils' hit boxes, one per place; a place with no tendril standing is not pickable. */
    private final Tendril[] tendrils = new Tendril[PLACES];
    /** The head of the tendril that has just struck, lying on its mark; nowhere and nothing the rest of the time. */
    private final Head head;
    /** All of them, as the game asks for them: the six tendrils, then the head. */
    private final PartEntity<?>[] parts = new PartEntity<?>[PLACES + 1];
    private boolean headWas;

    /** The bowl's cell, so the fight can find its arena again after a reload. */
    private int cellX, cellZ;
    /** How far each place's tendril has been cut through; -1 where no tendril stands. */
    private final float[] cut = new float[PLACES];
    private int phase = 1, openTicks, fallTicks, riseTicks, stallTicks, enterTicks, actTicks, awayTicks, mendTicks, actTimer;
    private int act = ACT_IDLE, actTendril, lastAct = ACT_IDLE;
    private double markX, markY, markZ;
    private float spin;
    private boolean lost;
    /**
     * Dev tools: the fight held still, at one tick of a blow's clock or between blows, so a picture can be taken or
     * one blow staged at a time; whether it is to be held again once the blow in play is over; and a blow that is to
     * begin in a while, and how many ticks from now.
     */
    private boolean frozen, holdAfter;
    private int devKind, devIn;
    /** The tick of its life from which each place's tendril has reached its root and can be struck. */
    private final int[] rootedAt = new int[PLACES];
    /**
     * What stands in a toll's way: for each slice of the floor round the dais, how far out the nearest solid thing is
     * ({@link ChoirSummon#tollWalls}). Both sides work it out from the blocks as the ring lands, so the ring a player
     * sees stop at a pillar is the ring that does not reach them behind it.
     */
    public final float[] tollWall = new float[ChoirActs.TOLL_SLICES];

    { java.util.Arrays.fill(tollWall, Float.MAX_VALUE); }
    /** Who each ring of the toll in play has struck already, so it strikes nobody twice. */
    private final List<Set<UUID>> tollStruck = List.of(new HashSet<>(), new HashSet<>());

    // ---- what the player's game keeps for the drawing (nothing here is the server's)
    public float drop, dropO, ring, ringO, glow, glowO, bob, bobO;
    /** The act's own clock as it was last heard, and a tick before, for the drawing to ease between. */
    public float actTime, actTimeO;
    /** Each tendril: how far it has grown down from the ceiling, reared over its mark, and struck. */
    public final float[] grow = new float[PLACES], growO = new float[PLACES], reach = new float[PLACES],
            reachO = new float[PLACES], plunge = new float[PLACES], plungeO = new float[PLACES];
    /** Each tendril after it is cut: how far its loose end has whipped back up (0 not cut, 1 gone). */
    public final float[] gone = new float[PLACES], goneO = new float[PLACES];
    /** Each tendril: the flash a blow leaves on it, and the mark its head last hung over. */
    public final float[] hit = new float[PLACES], hitO = new float[PLACES];
    public final double[] tipX = new double[PLACES], tipY = new double[PLACES], tipZ = new double[PLACES];
    /** The flash a slam leaves on the floor, and where. */
    public float flash, flashO;
    public double flashX, flashY, flashZ;
    /** How stalled it is (a spoiled slam): its light gutters. */
    public float stun, stunO;
    /** The sculk lit by the bloom in play and the sprouts feeding the crown, as the player's game last found them. */
    public final List<BlockPos> bloomTiles = new ArrayList<>(), sproutsSeen = new ArrayList<>();
    private final boolean[] was = new boolean[PLACES];
    private final int[] wait = new int[PLACES], wearSeen = new int[PLACES];
    private int actSeen = -1;
    private boolean seen, flashed, wallsFound, tilesFound;

    public HollowChoirEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        for (int i = 0; i < PLACES; i++) parts[i] = tendrils[i] = new Tendril(this, i);
        parts[PLACES] = head = new Head(this);
        setId(getId());
        xpReward = XP;
        setNoGravity(true);
        setPersistenceRequired();
        bar.setDarkenScreen(false);
        bar.setCreateWorldFog(false);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, HEALTH)
                .add(Attributes.ARMOR, 6)
                .add(Attributes.MOVEMENT_SPEED, 0)
                .add(Attributes.ATTACK_DAMAGE, 8)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_PHASE, (byte) 1);
        entityData.define(DATA_STANDING, (byte) 0x3F);
        entityData.define(DATA_ACT, (byte) 0);
        entityData.define(DATA_ACT_TICK, 0);
        entityData.define(DATA_TOP, 0F);
        entityData.define(DATA_SPIN, 0F);
        entityData.define(DATA_FLOOR, 0F);
        entityData.define(DATA_TURN, 0F);
        entityData.define(DATA_MARK, new Vector3f());
        entityData.define(DATA_WEAR, 0);
    }

    @Override
    protected void registerGoals() {
        // the Choir has no goals: it never moves from its dais and its turns are its own (customServerAiStep)
    }

    // ------------------------------------------------------------------------------------------------ what it is

    /** Sets the fight up on an arena's dais: the middle, the bowl's floor, the ceiling the tendrils rise to. */
    public void settle(ChoirSites.Site site, double ceil) {
        cellX = site.cellX();
        cellZ = site.cellZ();
        entityData.set(DATA_TOP, (float) ceil);
        entityData.set(DATA_FLOOR, (float) site.floorY());
        entityData.set(DATA_TURN, (float) site.turn());
        // it comes down out of the dark over the dais, and its tendrils grow down to their roots as it comes
        enterTicks = ChoirActs.ENTER_TICKS;
        setPos(site.x(), site.floorY() + CROWN_UP + enterDrop(), site.z());
        setAct(ACT_ENTER, 0, 0);
        growTendrils();
    }

    /** How far over its hanging height the crown starts when it is called: high, but under the cavern's ceiling. */
    private double enterDrop() { return Math.max(0, Math.min(18, top() - (floorY() + CROWN_UP) - 3)); }

    public int phase() { return entityData.get(DATA_PHASE); }

    /** Client and server: the crown is down and the core open. */
    public boolean open() { return act() == ACT_OPEN || act() == ACT_FALL; }

    public int act() { return entityData.get(DATA_ACT) & 15; }

    public int actTendril() { return (entityData.get(DATA_ACT) >> 4) & 7; }
    /** True while the dev tools hold the act still at one tick of its clock, for a picture ({@link #devPose}). */
    public boolean held() { return (entityData.get(DATA_ACT) & 0x80) != 0; }

    public int actTick() { return entityData.get(DATA_ACT_TICK); }

    /** The ceiling the tendrils run up to. */
    public float top() { return entityData.get(DATA_TOP); }

    public float spin() { return entityData.get(DATA_SPIN); }

    /** True when a tendril stands in that place. */
    public boolean standing(int place) { return (entityData.get(DATA_STANDING) & (1 << place)) != 0; }

    /**
     * True when that place's tendril has reached its root and can be struck: a tendril still growing down from the
     * ceiling is not there to hit yet, on either side.
     */
    public boolean rooted(int place) {
        if (!standing(place)) return false;
        return level().isClientSide ? grow[place] >= 0.95F : tickCount >= rootedAt[place];
    }

    /** A slam's mark in the world: where the tendril in play comes down. */
    public Vec3 mark() {
        Vector3f m = entityData.get(DATA_MARK);
        return new Vec3(getX() + m.x, floorY() + m.y, getZ() + m.z);
    }

    /**
     * True while the tendril that has just struck lies with its head on its mark, where it can be hit: from the tick
     * the blow lands until the head lifts. The same on both sides, from what is synced.
     */
    public boolean headDown() {
        return act() == ACT_SLAM && actTick() >= ChoirActs.SLAM_AT && standing(actTendril());
    }

    /** How far a place's tendril is cut through, 0 whole to 1 parting. */
    public float wear(int place) { return wearLevel(place) / (float) WEAR_STEPS; }

    private int wearLevel(int place) { return (entityData.get(DATA_WEAR) >> (place * 5)) & 31; }

    /** Where a place's tendril is rooted on the floor. */
    public Vec3 tendrilFoot(int place) {
        double a = placeAngle(place);
        return new Vec3(getX() + TENDRIL_R * Math.cos(a), floorY(), getZ() + TENDRIL_R * Math.sin(a));
    }

    /**
     * A place's angle round the dais. The roots stand still (0.22.2): they turned with the crown before, a third of a
     * block a second, which made a tendril a thing to chase rather than a thing to cut, dragged its box through the
     * bowl's pillars, and left nothing fixed for a strike to rear from. The crown still turns; what holds it does not.
     */
    public double placeAngle(int place) { return ChoirArena.rootAngle(entityData.get(DATA_TURN), place); }

    /** The arena's own turn: the way its first socket lies. */
    public float turn() { return entityData.get(DATA_TURN); }

    /** The dais's middle: the crown's own column, which it never leaves. Right on the client too. */
    public double daisX() { return getX(); }

    public double daisZ() { return getZ(); }

    /** The bowl's floor. */
    public int floorY() { return Math.round(entityData.get(DATA_FLOOR)); }

    // ------------------------------------------------------------------------------------------- the body's parts

    @Override public boolean isMultipartEntity() { return true; }

    @Override public PartEntity<?>[] getParts() { return parts; }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (parts != null) for (int i = 0; i < parts.length; i++) if (parts[i] != null) parts[i].setId(id + i + 1);
    }

    /** The crown, the bell and six tendrils are drawn far outside the crown's own box. */
    @Override
    public AABB getBoundingBoxForCulling() {
        // and its signs are drawn over the whole bowl: a toll's ring to the seats, the grab's ripples from the rim
        double r = ChoirSites.RIM_R + 2;
        return new AABB(getX() - r, floorY() - 2, getZ() - r, getX() + r, Math.max(top() + 4, getY() + 4), getZ() + r);
    }

    @Override public boolean isPushable() { return false; }

    @Override protected void doPush(Entity e) { }

    @Override public boolean canBeCollidedWith() { return false; }

    @Override public boolean isNoGravity() { return true; }

    @Override protected boolean canRide(Entity e) { return false; }

    @Override public boolean canChangeDimensions() { return false; }

    @Override public boolean removeWhenFarAway(double d) { return false; }

    /**
     * The Choir stays on Peaceful. Vanilla takes every {@link Monster} off the world the tick the difficulty goes to
     * Peaceful, which for a summoned boss means the three keys are spent and the fight is gone with them. The mod is
     * mostly peaceful exploration and this is the one thing in it a player goes looking for a fight with, so it is
     * theirs to start and theirs to finish whatever the difficulty says. (It is also how the test rig sees it at all:
     * a fresh server starts on Peaceful.) Vanilla still takes the Choir's blows down to nothing on Peaceful, as it does
     * every mob's, so the fight there is a walk-through rather than a fight; that is the difficulty's own promise and
     * not ours to break. Standing there instead of vanishing is.
     */
    @Override protected boolean shouldDespawnInPeaceful() { return false; }

    // --------------------------------------------------------------------------------------------------- the fight

    @Override
    public void tick() {
        super.tick();
        // on both sides: a player's own game picks what a swing strikes from where the parts stand in its own world,
        // and nothing but this tells it where a tendril is. Left to the server alone (0.21.9) the six boxes sat at
        // the world's origin on every client, and no sword could find one.
        placeTendrils();
        if (level().isClientSide) {
            clientTick();
            return;
        }
        hover();
    }

    /** The player's game hears which tendrils stand: their boxes follow, as the server's do when it cuts or grows one. */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_STANDING.equals(key) && tendrils != null)
            for (Tendril t : tendrils) if (t != null) t.refreshDimensions();
    }

    @Override
    protected void customServerAiStep() {
        if (entityData.get(DATA_TOP) == 0) { discard(); return; }
        bar.setProgress(getHealth() / getMaxHealth());

        if (enterTicks > 0) { enter(); return; }
        // dev tools: a staged blow's time has come; and the fight held still (whoever is or is not in the bowl)
        if (devIn > 0 && --devIn == 0) devAct(devKind);
        if (frozen) { setAct(act, actTendril, actTicks); return; }
        if (letGo()) return;
        if (tickCount % HUSH_EVERY == 0) ChoirSummon.hush(this);
        mend();
        if (stallTicks > 0) {
            stallTicks--;
            setAct(ACT_STALL, actTendril, INTERRUPT_STALL - stallTicks);
            return;
        }

        if (fallTicks > 0) { fall(); return; }
        if (openTicks > 0) { openCore(); return; }
        if (riseTicks > 0) { rise(); return; }
        if (standingCount() == 0) { beginFall(); return; }

        actTicks++;
        switch (act) {
            case ACT_WINDUP -> windUpStep();
            case ACT_SLAM -> slamStep();
            case ACT_TOLL -> tollStep();
            case ACT_GRAB -> grabStep();
            case ACT_BLOOM -> bloomStep();
            default -> chooseStep();
        }
        setAct(act, actTendril, actTicks);
        turnCrown();
    }

    private void turnCrown() {
        spin = (spin + (open() ? 0.05F : 0.18F)) % 360;
        entityData.set(DATA_SPIN, spin);
    }

    /** Nobody left in the bowl, or everybody dead: put the arena back and go, with the keys still in their sockets. */
    private boolean letGo() {
        Player near = nearestFighter();
        awayTicks = near == null ? awayTicks + 1 : 0;
        if (awayTicks < LEAVE_TICKS) return false;
        lost = true;
        ChoirSummon.give(this, false);
        discard();
        return true;
    }

    /**
     * The player nearest the dais who is in the fight, or null when nobody is: this is both who the Choir aims at and
     * whether there is anyone left to fight at all. A spectator is nobody, but a creative player counts, so that a
     * fight called with the dev tools stands long enough to be watched (nothing the Choir does can hurt them anyway).
     */
    @Nullable
    private Player nearestFighter() {
        Player best = null;
        double bd = KEEP * KEEP;
        for (Player p : level().players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(getX(), floorY() + 2, getZ());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** The sprouts standing in the bowl mend the Choir; cut them and it stops. */
    private void mend() {
        if (++mendTicks < SPROUT_MEND_EVERY) return;
        mendTicks = 0;
        int n = sprouts();
        if (n > 0 && getHealth() < getMaxHealth()) heal(n);
    }

    /** How many of the Choir's sprouts still stand in the bowl (its ward and its mending both ride on this). */
    public int sprouts() {
        if (!(level() instanceof ServerLevel sl)) return 0;
        int n = 0;
        BlockState sprout = ERBlocks.CHOIR_SPROUT.get().defaultBlockState();
        for (BlockPos p : ChoirSummon.sproutSpots(this))
            if (sl.getBlockState(p).is(sprout.getBlock())) n++;
        return n;
    }

    // ---- coming down when it is called

    private void enter() {
        int t = ChoirActs.ENTER_TICKS - enterTicks;
        enterTicks--;
        double u = (t + 1) / (double) ChoirActs.ENTER_TICKS, left = (1 - u) * (1 - u) * (1 - u);
        setPos(getX(), floorY() + CROWN_UP + enterDrop() * left, getZ());
        for (int i = 0; i < PLACES; i++)
            if (standing(i) && t == ChoirActs.growStart(i) + ChoirActs.GROW_TICKS) ChoirSummon.rootFx(this, i);
        setAct(ACT_ENTER, 0, t + 1);
        turnCrown();
        if (enterTicks == 0) {
            act = ACT_IDLE;
            actTicks = 0;
            actTimer = 10;
        }
    }

    // ---- the crown falling, the core open, the crown climbing back

    private void beginFall() {
        fallTicks = FALL_TICKS;
        act = ACT_FALL;
        actTicks = 0;
        playOwn(ERSounds.CHOIR_FALL.get(), 1.1F, 0.95F);
    }

    private void fall() {
        fallTicks--;
        double t = 1 - fallTicks / (double) FALL_TICKS;
        setPos(getX(), floorY() + CROWN_UP + (CROWN_DOWN - CROWN_UP) * t * t, getZ());
        setAct(ACT_FALL, 0, FALL_TICKS - fallTicks);
        if (fallTicks == 0) {
            openTicks = OPEN_TICKS;
            setPos(getX(), floorY() + CROWN_DOWN, getZ());
        }
    }

    private void openCore() {
        openTicks--;
        setAct(ACT_OPEN, 0, OPEN_TICKS - openTicks);
        if (openTicks == 0) {
            riseTicks = RISE_TICKS;
            playOwn(ERSounds.CHOIR_RISE.get(), 1.0F, 1.0F);
        }
    }

    private void rise() {
        riseTicks--;
        double t = 1 - riseTicks / (double) RISE_TICKS;
        setPos(getX(), floorY() + CROWN_DOWN + (CROWN_UP - CROWN_DOWN) * t, getZ());
        setAct(ACT_RISE, 0, RISE_TICKS - riseTicks);
        if (riseTicks == 0) {
            growTendrils();
            act = ACT_IDLE;
            actTicks = 0;
            actTimer = ChoirActs.GROW_TICKS + 12;
        }
    }

    /** New tendrils for this phase: as many as the phase allows, spread over the six places, each whole again. */
    private void growTendrils() {
        int want = TENDRILS[Math.min(TENDRILS.length - 1, phase - 1)];
        int mask = 0;
        java.util.Arrays.fill(cut, -1);
        // spread them: every PLACES/want-th place, so a phase of four leaves two gaps opposite each other
        for (int i = 0; i < want; i++) {
            int place = (int) Math.round(i * PLACES / (double) want) % PLACES;
            while ((mask & (1 << place)) != 0) place = (place + 1) % PLACES;
            mask |= 1 << place;
            cut[place] = 0;
        }
        entityData.set(DATA_STANDING, (byte) mask);
        for (Tendril t : tendrils) t.refreshDimensions();
        // none can be struck until it has grown down to its root: one after another as it is called, together after
        boolean entering = enterTicks > 0;
        for (int i = 0; i < PLACES; i++)
            rootedAt[i] = tickCount + ChoirActs.GROW_TICKS + (entering ? ChoirActs.growStart(i) : i * 2);
        tellWear();
    }

    /** Tells the player's game how worn each tendril is. */
    private void tellWear() {
        float max = TENDRIL_CUT[Math.min(TENDRIL_CUT.length - 1, phase - 1)];
        int packed = 0;
        for (int i = 0; i < PLACES; i++) {
            int level = cut[i] <= 0 ? 0 : Math.min(WEAR_STEPS, Math.round(cut[i] / max * WEAR_STEPS));
            packed |= level << (i * 5);
        }
        if (packed != entityData.get(DATA_WEAR)) entityData.set(DATA_WEAR, packed);
    }

    public int standingCount() {
        int n = 0;
        for (int i = 0; i < PLACES; i++) if (standing(i)) n++;
        return n;
    }

    // ---- choosing and running the turns

    /**
     * Picks the next blow by where the nearest fighter stands. On the floor it is slams for the most part, with the
     * toll, then the grab from the second phase and the bloom in the third. Up on the seats, the ramps or the rim,
     * where a toll's ring is spent before it reaches, it is the grab, which brings them back down. No blow but a slam
     * comes twice running.
     */
    private void chooseStep() {
        // dev tools: the one blow that was staged is over, so the fight is held again
        if (holdAfter) { holdAfter = false; frozen = true; return; }
        if (--actTimer > 0) return;
        Player p = nearestFighter();
        if (p == null) { actTimer = 20; return; }
        double d = Math.hypot(p.getX() - getX(), p.getZ() - getZ());
        boolean far = d > ChoirSites.SEAT_R0 + 0.5;
        int with = slamTendril(p);
        List<Integer> roll = new ArrayList<>();
        if (far) {
            roll.add(ACT_GRAB);
            roll.add(ACT_GRAB);
            if (with >= 0) roll.add(ACT_WINDUP);
        } else {
            if (with >= 0) { roll.add(ACT_WINDUP); roll.add(ACT_WINDUP); }
            roll.add(ACT_TOLL);
            if (phase >= 2) { roll.add(ACT_TOLL); roll.add(ACT_GRAB); }
            if (phase >= 3) { roll.add(ACT_BLOOM); roll.add(ACT_BLOOM); }
        }
        List<Integer> fresh = new ArrayList<>(roll);
        fresh.removeIf(k -> k != ACT_WINDUP && k == lastAct);
        if (!fresh.isEmpty()) roll = fresh;
        begin(roll.get(random.nextInt(roll.size())), p, with);
    }

    /** Starts a blow: its sound, and for a slam the tendril and the mark. */
    private void begin(int pick, @Nullable Player p, int with) {
        act = pick;
        lastAct = pick;
        actTicks = 0;
        switch (pick) {
            case ACT_WINDUP -> {
                if (with < 0 || p == null) { act = ACT_IDLE; actTimer = 10; return; }
                actTendril = with;
                // the mark is where they stand now and does not follow them: it is theirs to walk out of. It lies
                // on the ground under them, so one called on a player in the middle of a jump is not left in the air
                markAt(p.getX(), groundUnder(p), p.getZ());
                // the lifting sound is as long as the last phase's wind-up; slowed, it lasts out the longer ones
                // too, so in every phase it stops dead the tick the tendril starts down
                Vec3 f = tendrilFoot(with);
                level().playSound(null, f.x, f.y + 3, f.z, ERSounds.CHOIR_WINDUP.get(), SoundSource.HOSTILE, 1.1F,
                        ChoirActs.windUp(3) / (float) ChoirActs.windUp(phase));
            }
            case ACT_TOLL -> {
                playOwn(ERSounds.CHOIR_TOLL.get(), 1.4F, 1.0F);
                for (Set<UUID> ring : tollStruck) ring.clear();
            }
            case ACT_GRAB -> playOwn(ERSounds.CHOIR_GRAB.get(), 1.3F, 1.0F);
            case ACT_BLOOM -> playOwn(ERSounds.CHOIR_BLOOM.get(), 1.2F, 1.0F);
            default -> { }
        }
        setAct(act, actTendril, 0);
    }

    /** The top of whatever a player stands on or is in the air over, a few blocks down at most. */
    private double groundUnder(Player p) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int from = Mth.floor(p.getY() - 0.01);
        for (int i = 0; i < 6; i++) {
            at.set(Mth.floor(p.getX()), from - i, Mth.floor(p.getZ()));
            VoxelShape shape = level().getBlockState(at).getCollisionShape(level(), at);
            if (!shape.isEmpty()) return at.getY() + shape.max(Direction.Axis.Y);
        }
        return p.getY();
    }

    private void markAt(double x, double y, double z) {
        markX = x;
        markY = y;
        markZ = z;
        entityData.set(DATA_MARK, new Vector3f((float) (x - getX()), (float) (y - floorY()), (float) (z - getZ())));
    }

    /**
     * The tendril that can bring a slam down on a player: the nearest one that is rooted, if its root is within
     * {@link ChoirActs#SLAM_REACH} of them, and -1 if none is.
     */
    private int slamTendril(Player p) {
        int best = -1;
        double bd = ChoirActs.SLAM_REACH * ChoirActs.SLAM_REACH;
        for (int i = 0; i < PLACES; i++) {
            if (!rooted(i)) continue;
            Vec3 f = tendrilFoot(i);
            double d = p.distanceToSqr(f.x, p.getY(), f.z);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** The wind-up: the tendril rears over the mark. Long enough to read, and a strong hit in it spoils the strike. */
    private void windUpStep() {
        if (actTicks < ChoirActs.windUp(phase)) return;
        act = ACT_SLAM;
        actTicks = 0;
    }

    /** The slam itself: the tendril comes down on the mark it reared over, not on wherever the player is now. */
    private void slamStep() {
        if (actTicks == ChoirActs.SLAM_AT) {
            level().playSound(null, markX, markY, markZ, ERSounds.CHOIR_SLAM.get(), SoundSource.HOSTILE, 1.2F, 1.0F);
            Vec3 root = tendrilFoot(actTendril);
            ChoirSummon.slam(this, markX, markY, markZ, root.x, root.z);
        }
        if (actTicks >= ChoirActs.SLAM_END) rest(18 - 3 * phase);
    }

    /** The toll: the ring drops from the crown, lands, and runs out over the floor; each ring strikes a player once. */
    private void tollStep() {
        if (actTicks == ChoirActs.TOLL_TELL) ChoirSummon.tollWalls(level(), getX(), floorY(), getZ(), tollWall);
        for (int k = 0; k < ChoirActs.tollRings(phase); k++) {
            // every ring after the first leaves the bell with a stroke of its own, a little higher, so the ear can
            // count the rings before the eye has to
            if (k > 0 && actTicks == k * ChoirActs.TOLL_GAP) playOwn(ERSounds.CHOIR_TOLL.get(), 1.2F, 1.12F);
            double r = ChoirActs.tollRadius(phase, k, actTicks);
            if (!Double.isNaN(r)) ChoirSummon.tollRing(this, r, tollStruck.get(k));
        }
        if (actTicks >= ChoirActs.tollEnd(phase)) rest(30 - 5 * phase);
    }

    private void grabStep() {
        if (actTicks == ChoirActs.GRAB_TELL) ChoirSummon.grab(this);
        if (actTicks >= ChoirActs.GRAB_END) rest(24);
    }

    private void bloomStep() {
        if (actTicks == ChoirActs.BLOOM_TELL) ChoirSummon.bloom(this);
        if (actTicks >= ChoirActs.BLOOM_END) rest(30);
    }

    /** A blow is done: nothing for a while. */
    private void rest(int ticks) {
        act = ACT_IDLE;
        actTicks = 0;
        actTimer = ticks;
    }

    private void setAct(int a, int tendril, int ticks) {
        entityData.set(DATA_ACT, (byte) ((a & 15) | ((tendril & 7) << 4) | (frozen ? 0x80 : 0)));
        entityData.set(DATA_ACT_TICK, ticks);
    }

    // --------------------------------------------------------------------------------------------------- the hurt

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        if (isInvulnerableTo(source)) return false;
        // while the crown is up it cannot be touched: the blow rings the bell and the tendrils must be cut instead
        if (!open()) {
            if (source.getEntity() instanceof Player p) {
                playOwn(ERSounds.CHOIR_TOLL_FAR.get(), 0.6F, 1.5F);
                ChoirSummon.clang(this, p);
            }
            return false;
        }
        return core(source, amount);
    }

    /** A blow on the open core, or the Choir's share of one on a tendril: what the bar reads. */
    private boolean core(DamageSource source, float amount) {
        float ward = Math.min(SPROUT_WARD_MAX, SPROUT_WARD * sprouts());
        boolean did = super.hurt(source, amount * (1 - ward));
        if (did) checkPhase();
        return did;
    }

    /**
     * A hit on a tendril: it wears that tendril through, and {@link #TENDRIL_SHARE} of it tells on the Choir. The
     * sprouts' ward is taken off the Choir's share only, not off the cutting, so a bowl full of sprouts slows the bar
     * but never the work on the tendrils.
     *
     * <p>It always reports the blow as landed. The cut takes every hit, even one the Choir's own hurt cooldown eats
     * the share of, so a tendril never feels like swinging at air.
     */
    boolean hurtTendril(int place, DamageSource source, float amount) {
        if (level().isClientSide || !rooted(place) || cut[place] < 0) return false;
        // a strong attack on a tendril while one rears spoils the slam. The combat layer does this too, from the
        // Choir's own share of the blow, but that share can be eaten by its hurt cooldown and the spoiling with it
        if (windingUp() && source.getEntity() instanceof ServerPlayer sp) {
            Swordplay.Landing swing = Swordplay.landing(sp);
            if (swing != null && swing.strong()) interrupt(sp);
        }
        float max = TENDRIL_CUT[Math.min(TENDRIL_CUT.length - 1, phase - 1)];
        if (cut[place] < 0) return true;
        cut[place] += amount;
        core(source, amount * TENDRIL_SHARE);
        if (cut[place] >= max) cutThrough(place);
        tellWear();
        return true;
    }

    /**
     * A hit on the head of the tendril that has just struck, while it lies on its mark: the opening a slam leaves. It
     * is that tendril's own flesh, and softer there: the blow counts {@link ChoirActs#HEAD_BONUS} times over.
     */
    boolean hurtHead(DamageSource source, float amount) {
        if (level().isClientSide || !headDown()) return false;
        return hurtTendril(actTendril, source, amount * ChoirActs.HEAD_BONUS);
    }

    private void cutThrough(int place) {
        cut[place] = -1;
        entityData.set(DATA_STANDING, (byte) (entityData.get(DATA_STANDING) & ~(1 << place)));
        tendrils[place].refreshDimensions();
        // a tendril cut through while it rears, strikes or lies where it struck takes the rest of its slam with it
        if (place == actTendril && (act == ACT_WINDUP || act == ACT_SLAM)) rest(16);
        Vec3 f = tendrilFoot(place);
        level().playSound(null, f.x, f.y + 2, f.z, ERSounds.CHOIR_TENDRIL_CUT.get(), SoundSource.HOSTILE, 1.1F, 1.0F);
        ChoirSummon.tendrilCutFx(this, place);
    }

    /**
     * Dev tools ({@code /remix choir cut}): every standing tendril parts at once, as if each had been cut through, so
     * the crown falls and the open core can be tested without the minutes of cutting that lead to it.
     */
    public int devCutAll() {
        int n = 0;
        for (int i = 0; i < PLACES; i++) if (standing(i)) { cutThrough(i); n++; }
        return n;
    }

    /** Health crossing a phase border: the arena darkens, the sculk comes out, and it drops at once. */
    private void checkPhase() {
        float f = getHealth() / getMaxHealth();
        int want = f <= PHASE3 ? 3 : f <= PHASE2 ? 2 : 1;
        if (want <= phase) return;
        phase = want;
        entityData.set(DATA_PHASE, (byte) phase);
        ChoirSummon.dress(this, phase);
        // the phase takes the tendrils with it: whatever stands is cut, so the crown comes down for the change
        for (int i = 0; i < PLACES; i++) if (standing(i)) cutThrough(i);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // nothing in the world but a fighter can touch it: no fall, no drowning, no fire, no suffocation
        if (source.getEntity() == null && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        return super.isInvulnerableTo(source);
    }

    @Override public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance e) {
        return !e.getEffect().equals(net.minecraft.world.effect.MobEffects.POISON)
                && !e.getEffect().equals(net.minecraft.world.effect.MobEffects.WITHER)
                && super.canBeAffected(e);
    }

    @Override public boolean windingUp() { return act == ACT_WINDUP; }

    @Override
    public void interrupt(LivingEntity by) {
        if (act != ACT_WINDUP) return;
        act = ACT_IDLE;
        actTicks = 0;
        actTimer = 10;
        stallTicks = INTERRUPT_STALL;
        if (standing(actTendril) && cut[actTendril] >= 0) {
            float max = TENDRIL_CUT[Math.min(TENDRIL_CUT.length - 1, phase - 1)];
            cut[actTendril] += INTERRUPT_CUT;
            if (cut[actTendril] >= max) cutThrough(actTendril);
            tellWear();
        }
        playOwn(ERSounds.CHOIR_HURT.get(), 1.0F, 1.1F);
    }

    /** Its death is not a puff of smoke: the crown flares and comes apart over two seconds, then it is gone. */
    @Override
    protected void tickDeath() {
        if (++deathTime >= DEATH_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level().isClientSide) return;
        // whatever still holds it lets go, quietly: its death has a sound of its own
        java.util.Arrays.fill(cut, -1);
        entityData.set(DATA_STANDING, (byte) 0);
        for (Tendril t : tendrils) t.refreshDimensions();
        ChoirSummon.give(this, true);
    }

    // --------------------------------------------------------------------------------------------- the dev tools

    /**
     * Dev tools ({@code /remix choir act}): the next blow is this one, now. False when it cannot be struck now. If
     * the fight was being held ({@link #devHold}), it is held again when this one blow is over.
     */
    public boolean devAct(int kind) {
        if (level().isClientSide || !isAlive() || enterTicks > 0 || open() || riseTicks > 0) return false;
        holdAfter = frozen || holdAfter;
        frozen = false;
        Player p = nearestFighter();
        begin(kind, p, p == null ? -1 : slamTendril(p));
        return act == kind;
    }

    /**
     * Dev tools ({@code /remix choir act ... in}): that blow, begun in {@code ticks} ticks. A script can then be in
     * place and ready when it starts, where a command's own reply takes longer to come back than a blow's warning
     * lasts.
     */
    public boolean devActIn(int kind, int ticks) {
        if (level().isClientSide || !isAlive()) return false;
        if (ticks <= 0) return devAct(kind);
        devKind = kind;
        devIn = ticks;
        return true;
    }

    /**
     * Dev tools ({@code /remix choir pose hold}): the fight held as it stands, between blows or in the middle of
     * one, until {@code pose off} or a staged blow. Nothing strikes, mends or leaves while it is held.
     */
    public boolean devHold() {
        if (level().isClientSide || !isAlive() || enterTicks > 0) return false;
        frozen = true;
        holdAfter = false;
        setAct(act, actTendril, actTicks);
        return true;
    }

    /**
     * Dev tools ({@code /remix choir pose}): a blow held still at one tick of its clock for a picture to be taken of
     * it, or let go again with a kind under 0. A slam is posed by its wind-up ({@link #ACT_WINDUP}) or by its stroke
     * ({@link #ACT_SLAM}). Nothing strikes while it is held.
     */
    public boolean devPose(int kind, int tick) {
        if (level().isClientSide || !isAlive() || enterTicks > 0 || open() || riseTicks > 0) return false;
        if (kind < 0) {
            frozen = holdAfter = false;
            devIn = 0;
            rest(20);
            return true;
        }
        Player p = nearestFighter();
        // a slam already held, and nobody in the bowl to aim a new one at: the same slam at another tick, so a
        // picture of it can be taken from where no fighter stands
        boolean slam = kind == ACT_WINDUP || kind == ACT_SLAM;
        boolean same = frozen && slam && p == null && (act == ACT_WINDUP || act == ACT_SLAM) && standing(actTendril);
        if (!same) {
            begin(kind == ACT_SLAM ? ACT_WINDUP : kind, p, p == null ? -1 : slamTendril(p));
            if (act == ACT_IDLE) return false;
        }
        act = kind;
        actTicks = Math.max(0, tick);
        frozen = true;
        if (kind == ACT_TOLL) ChoirSummon.tollWalls(level(), getX(), floorY(), getZ(), tollWall);
        setAct(act, actTendril, actTicks);
        return true;
    }

    /**
     * Dev tools ({@code /remix choir phase}): the fight as it stands at the start of a phase, tendrils regrown. A
     * fight that was being held ({@link #devHold}) is still held, between blows.
     */
    public void devPhase(int want) {
        if (level().isClientSide || !isAlive()) return;
        phase = Mth.clamp(want, 1, 3);
        entityData.set(DATA_PHASE, (byte) phase);
        setHealth(getMaxHealth() * (phase == 3 ? PHASE3 : phase == 2 ? PHASE2 : 1F));
        ChoirSummon.dress(this, phase);
        holdAfter = false;
        devIn = 0;
        enterTicks = fallTicks = openTicks = riseTicks = stallTicks = 0;
        setPos(getX(), floorY() + CROWN_UP, getZ());
        growTendrils();
        rest(ChoirActs.GROW_TICKS + 12);
        setAct(act, actTendril, actTicks);
    }

    // --------------------------------------------------------------------------------------------------- the rest

    private void hover() {
        setDeltaMovement(Vec3.ZERO);
        // the fall, the open core and the climb back each set the crown's height themselves. The climb was left out of
        // this (0.21.9), so the crown was put straight back up at its first tick and never seen to climb at all.
        if (fallTicks > 0 || openTicks > 0 || riseTicks > 0 || enterTicks > 0 || isDeadOrDying()) return;
        double y = floorY() + CROWN_UP + Math.sin(tickCount * 0.035) * 0.35;
        setPos(getX(), y, getZ());
    }

    /**
     * Each standing tendril's box stands on its root (a cut one has no size and cannot be hit), and the head's lies
     * on the mark while a tendril lies where it struck.
     */
    private void placeTendrils() {
        for (int i = 0; i < PLACES; i++) {
            Tendril t = tendrils[i];
            Vec3 f = tendrilFoot(i);
            t.setPos(f.x, f.y, f.z);
            t.setOldPosAndRot();
        }
        boolean down = headDown();
        if (down != headWas) {
            headWas = down;
            head.refreshDimensions();
        }
        Vec3 m = down ? mark() : new Vec3(getX(), floorY() - 4, getZ());
        head.setPos(m.x, m.y, m.z);
        head.setOldPosAndRot();
    }

    private void playOwn(SoundEvent sound, float volume, float pitch) {
        level().playSound(null, getX(), floorY() + 3, getZ(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    @Override protected SoundEvent getHurtSound(DamageSource s) { return ERSounds.CHOIR_HURT.get(); }

    @Override protected SoundEvent getDeathSound() { return ERSounds.CHOIR_DEATH.get(); }

    @Override protected SoundEvent getAmbientSound() { return ERSounds.CHOIR_IDLE.get(); }

    @Override public int getAmbientSoundInterval() { return 160; }

    @Override protected float getSoundVolume() { return 0.8F; }

    @Override
    public void startSeenByPlayer(ServerPlayer p) {
        super.startSeenByPlayer(p);
        bar.addPlayer(p);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer p) {
        super.stopSeenByPlayer(p);
        bar.removePlayer(p);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("FloorY", entityData.get(DATA_FLOOR));
        tag.putFloat("Turn", entityData.get(DATA_TURN));
        tag.putInt("CellX", cellX);
        tag.putInt("CellZ", cellZ);
        tag.putInt("Phase", phase);
        tag.putFloat("Top", top());
        tag.putByte("Standing", entityData.get(DATA_STANDING));
        tag.putFloat("Spin", spin);
        float[] c = new float[PLACES];
        System.arraycopy(cut, 0, c, 0, PLACES);
        net.minecraft.nbt.ListTag cuts = new net.minecraft.nbt.ListTag();
        for (float v : c) cuts.add(net.minecraft.nbt.FloatTag.valueOf(v));
        tag.put("Cut", cuts);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_FLOOR, tag.getFloat("FloorY"));
        entityData.set(DATA_TURN, tag.getFloat("Turn"));
        cellX = tag.getInt("CellX");
        cellZ = tag.getInt("CellZ");
        phase = Math.max(1, tag.getInt("Phase"));
        entityData.set(DATA_PHASE, (byte) phase);
        entityData.set(DATA_TOP, tag.getFloat("Top"));
        entityData.set(DATA_STANDING, tag.getByte("Standing"));
        spin = tag.getFloat("Spin");
        net.minecraft.nbt.ListTag cuts = tag.getList("Cut", net.minecraft.nbt.Tag.TAG_FLOAT);
        for (int i = 0; i < Math.min(PLACES, cuts.size()); i++) cut[i] = cuts.getFloat(i);
        tellWear();
        if (hasCustomName()) bar.setName(getDisplayName());
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        bar.setName(getDisplayName());
    }

    public int cellX() { return cellX; }

    public int cellZ() { return cellZ; }

    /** True when the fight was let go rather than won. */
    public boolean lost() { return lost; }

    // ------------------------------------------------------------------------------------------- client animation

    /**
     * How far ahead of what was last heard from the server the signs are drawn, in ticks. A picture is always a tick
     * behind the game's own state, and the state a message's trip behind the server's; drawn that late, a ring would
     * reach a player on the screen a moment after it had struck them. Early is the fair side to be wrong on.
     */
    public static final float LEAD = 1.5F;
    /**
     * How long a tendril takes to lift its head back off the floor after it has struck, and then to go back to
     * standing; and the most it rears in a tick, when it has a new mark to catch up with.
     */
    private static final int LIFT_TICKS = 6, RETURN_TICKS = 14;
    private static final float REAR_MOST = 0.2F;
    /** How much of a slam's flash on the floor goes each tick. */
    private static final float FLASH_FADE = 0.09F;

    /** The clock of the act in play as the drawing reads it: eased between ticks, and {@link #LEAD} ahead. */
    public float clock(float partial) { return Mth.lerp(partial, actTimeO, actTime) + LEAD; }

    private void clientTick() {
        dropO = drop;
        ringO = ring;
        glowO = glow;
        bobO = bob;
        actTimeO = actTime;
        flashO = flash;
        stunO = stun;
        int a = act(), t = actTick(), with = actTendril(), ph = phase();

        // the act's own clock. It runs on by itself and is only nudged by what is heard, so a message that comes
        // late, or two that come together, do not make a ring stutter on its way over the floor
        if (a != actSeen || t + 4 < actTime) {
            actSeen = a;
            actTime = actTimeO = t;
            flashed = wallsFound = tilesFound = false;
            if (a != ACT_BLOOM) bloomTiles.clear();
        } else if (held()) {
            // held still for a picture: drawn at the very tick that was asked for
            actTime = actTimeO = t - LEAD;
        } else {
            actTime += 1;
            float off = t - actTime;
            actTime += Math.abs(off) > 3 ? off : off * 0.3F;
        }
        float now = actTime + LEAD;

        float want = a == ACT_FALL ? Math.min(1, t / (float) FALL_TICKS)
                : a == ACT_OPEN ? 1
                : a == ACT_RISE ? 1 - Math.min(1, t / (float) RISE_TICKS) : 0;
        drop += (want - drop) * 0.25F;
        glow = Mth.clamp(glow + (open() ? 0.08F : -0.05F), 0.15F, 1);
        bob += 0.035F;
        stun += ((a == ACT_STALL ? 1 : 0) - stun) * 0.3F;

        // the bells jolt as each ring of a toll leaves them, and when a slam lands
        float jolt = 0;
        if (a == ACT_TOLL)
            for (int k = 0; k < ChoirActs.tollRings(ph); k++) {
                float since = now - k * ChoirActs.TOLL_GAP;
                if (since >= 0) jolt = Math.max(jolt, 1 - since / 12F);
            }
        ring = Math.max(jolt, ring * 0.86F);

        boolean entering = a == ACT_ENTER;
        for (int i = 0; i < PLACES; i++) {
            growO[i] = grow[i];
            reachO[i] = reach[i];
            plungeO[i] = plunge[i];
            goneO[i] = gone[i];
            hitO[i] = hit[i];
            boolean stands = standing(i);
            if (!seen) {
                // first sight of it: whatever stands has stood a while, unless it is only now coming down
                was[i] = stands;
                grow[i] = growO[i] = stands && !entering ? 1 : 0;
                gone[i] = goneO[i] = 1;
                wearSeen[i] = wearLevel(i);
            }
            if (stands && !was[i]) {
                grow[i] = growO[i] = 0;
                reach[i] = reachO[i] = plunge[i] = plungeO[i] = 0;
                wait[i] = i * 2;
            }
            if (!stands && was[i]) gone[i] = goneO[i] = 0;
            was[i] = stands;

            if (!stands) gone[i] = Math.min(1, gone[i] + 1F / ChoirActs.PART_TICKS);
            else if (entering) grow[i] = Mth.clamp((now - ChoirActs.growStart(i)) / ChoirActs.GROW_TICKS, 0, 1);
            else if (wait[i] > 0) wait[i]--;
            else grow[i] = Math.min(1, grow[i] + 1F / ChoirActs.GROW_TICKS);

            // the tendril in play rears over the mark and comes down on it; any other goes back to standing
            boolean mine = stands && with == i && (a == ACT_WINDUP || a == ACT_SLAM);
            Vec3 m = mine ? mark() : null;
            // a new mark while it is still bent over its last one: it stands up from that one first, and quickly
            boolean moved = mine && reach[i] > 0 && (tipX[i] != m.x || tipY[i] != m.y || tipZ[i] != m.z);
            if (mine && !moved) {
                tipX[i] = m.x;
                tipY[i] = m.y;
                tipZ[i] = m.z;
                reach[i] = Math.min(a == ACT_SLAM ? 1 : ChoirActs.rear(ph, now), reach[i] + REAR_MOST);
                plunge[i] = a == ACT_SLAM ? ChoirActs.plunge(now) : 0;
            } else {
                // it lifts its head off the floor first and stands up after, so it uncurls as it curled
                plunge[i] = Math.max(0, plunge[i] - 1F / LIFT_TICKS);
                if (plunge[i] <= 0) reach[i] = Math.max(0, reach[i] - (moved ? 3F : 1F) / RETURN_TICKS);
            }

            int worn = wearLevel(i);
            hit[i] = worn > wearSeen[i] ? 1 : Math.max(0, hit[i] - 0.2F);
            wearSeen[i] = worn;
        }

        // a slam landing leaves a flash on the floor, which fades by the act's own clock
        if (a == ACT_SLAM && now >= ChoirActs.SLAM_AT) {
            if (!flashed) {
                flashed = true;
                Vec3 m = mark();
                flashX = m.x;
                flashY = m.y;
                flashZ = m.z;
                ring = Math.max(ring, 0.5F);
            }
            flash = Math.max(0, 1 - (now - ChoirActs.SLAM_AT) * FLASH_FADE);
        } else flash = Math.max(0, flash - FLASH_FADE);

        // what stands in a toll's way, found from the blocks as the first ring lands (as the server finds it)
        if (a == ACT_TOLL && !wallsFound && now >= ChoirActs.TOLL_TELL - 1) {
            wallsFound = true;
            ChoirSummon.tollWalls(level(), getX(), floorY(), getZ(), tollWall);
        }
        if (a == ACT_BLOOM && !tilesFound) {
            tilesFound = true;
            findBloomTiles();
        }
        if (!seen || tickCount % 20 == 0) findSprouts();
        seen = true;
    }

    /** The sculk the bloom lights: every block of it in the bowl with open air over it. */
    private void findBloomTiles() {
        bloomTiles.clear();
        int r = (int) Math.ceil(ChoirSites.RIM_R), fy = floorY(), cx = Mth.floor(getX()), cz = Mth.floor(getZ());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                if (Math.hypot(x + 0.5 - getX(), z + 0.5 - getZ()) > ChoirSites.RIM_R) continue;
                for (int y = fy + (int) ChoirSites.DEPTH; y >= fy; y--) {
                    if (!level().getBlockState(p.set(x, y, z)).is(Blocks.SCULK)) continue;
                    if (!level().getBlockState(p.set(x, y + 1, z)).isSolidRender(level(), p))
                        bloomTiles.add(new BlockPos(x, y, z));
                    break;
                }
            }
    }

    /** The sprouts standing on the bowl's floor, which are drawn feeding the crown. */
    private void findSprouts() {
        sproutsSeen.clear();
        if (phase() < 2) return;
        int r = (int) Math.ceil(ChoirSites.FLOOR_R), y = floorY() + 1, cx = Mth.floor(getX()), cz = Mth.floor(getZ());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++)
                if (level().getBlockState(p.set(x, y, z)).is(ERBlocks.CHOIR_SPROUT.get())) sproutsSeen.add(p.immutable());
    }

    /** One of the six tendrils: the part of it a player can reach and cut. */
    public static final class Tendril extends PartEntity<HollowChoirEntity> {
        public final int place;
        private static final EntityDimensions SIZE = EntityDimensions.scalable(TENDRIL_W, TENDRIL_H);
        private static final EntityDimensions GONE = EntityDimensions.scalable(0.01F, 0.01F);

        Tendril(HollowChoirEntity parent, int place) {
            super(parent);
            this.place = place;
            refreshDimensions();
        }

        @Override protected void defineSynchedData() { }

        @Override protected void readAdditionalSaveData(CompoundTag tag) { }

        @Override protected void addAdditionalSaveData(CompoundTag tag) { }

        @Override public boolean shouldBeSaved() { return false; }

        @Override public boolean isPickable() { return getParent().isAlive() && getParent().rooted(place); }

        @Override public EntityDimensions getDimensions(Pose pose) { return getParent().standing(place) ? SIZE : GONE; }

        @Override public boolean is(Entity e) { return this == e || getParent() == e; }

        @Nullable @Override public ItemStack getPickResult() { return getParent().getPickResult(); }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return !isInvulnerableTo(source) && getParent().hurtTendril(place, source, amount);
        }
    }

    /**
     * The head of the tendril that has just struck, while it lies on its mark: a second box of that tendril's, and
     * the softer place to hit it. It has no size and cannot be picked the rest of the time.
     */
    public static final class Head extends PartEntity<HollowChoirEntity> {
        private static final EntityDimensions SIZE = EntityDimensions.scalable(ChoirActs.HEAD_W, ChoirActs.HEAD_H);
        private static final EntityDimensions GONE = EntityDimensions.scalable(0.01F, 0.01F);

        Head(HollowChoirEntity parent) {
            super(parent);
            refreshDimensions();
        }

        @Override protected void defineSynchedData() { }

        @Override protected void readAdditionalSaveData(CompoundTag tag) { }

        @Override protected void addAdditionalSaveData(CompoundTag tag) { }

        @Override public boolean shouldBeSaved() { return false; }

        @Override public boolean isPickable() { return getParent().isAlive() && getParent().headDown(); }

        @Override public EntityDimensions getDimensions(Pose pose) { return getParent().headDown() ? SIZE : GONE; }

        @Override public boolean is(Entity e) { return this == e || getParent() == e; }

        @Nullable @Override public ItemStack getPickResult() { return getParent().getPickResult(); }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return !isInvulnerableTo(source) && getParent().hurtHead(source, amount);
        }
    }
}
