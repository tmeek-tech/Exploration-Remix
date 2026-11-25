package com.explorationremix.creature.choir;

import com.explorationremix.combat.WindUp;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.terrain.ChoirSites;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
    public static final int PLACES = 6;
    /** How far out the tendrils are rooted, and how far out the crown's rim reaches. */
    public static final double TENDRIL_R = 8, CROWN_R = 2.25;
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
    /** Nobody within this far of the dais for this long and the fight is let go. */
    public static final double KEEP = 44;
    public static final int LEAVE_TICKS = 100;
    /**
     * How long after it is called the Choir waits before its first turn: time to see what has woken and get down
     * off the rim. Without it (0.21.9) the first slam came down on whoever set the third key 1.6 seconds later.
     */
    public static final int ENTER_TICKS = 80;
    /** How often it strikes dumb any shrieker that has come into range since ({@link ChoirSummon#hush}). */
    private static final int HUSH_EVERY = 100;
    /** What it gives up: about twenty levels' worth of experience. */
    public static final int XP = 1400;
    /** A slam's reach on the floor and what it costs to stand in it. */
    public static final double SLAM_R = 3.6;
    public static final float SLAM_HURT = 7;
    /** A toll's ring: what it costs where it passes, and how fast it runs out over the floor (blocks a tick). */
    public static final float TOLL_HURT = 5;

    // actions, which the client reads to animate: packed with the tendril in play in the top four bits
    public static final int ACT_IDLE = 0, ACT_WINDUP = 1, ACT_SLAM = 2, ACT_TOLL = 3, ACT_GRAB = 4,
            ACT_BLOOM = 5, ACT_FALL = 6, ACT_OPEN = 7, ACT_RISE = 8, ACT_STALL = 9;

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

    private final ServerBossEvent bar = new ServerBossEvent(
            Component.translatable("entity.explorationremix.hollow_choir"), BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS);

    /** The six tendrils' hit boxes, one per place; a place with no tendril standing is not pickable. */
    private final Tendril[] tendrils = new Tendril[PLACES];

    /** The bowl's cell, so the fight can find its arena again after a reload. */
    private int cellX, cellZ;
    /** How far each place's tendril has been cut through; -1 where no tendril stands. */
    private final float[] cut = new float[PLACES];
    private int phase = 1, openTicks, fallTicks, riseTicks, stallTicks, actTicks, awayTicks, mendTicks, actTimer;
    private int act = ACT_IDLE, actTendril;
    private double slamX, slamZ;
    private float spin;
    private boolean lost;

    // client animation
    public float drop, dropO, ring, ringO, glow, glowO, bob, bobO;

    public HollowChoirEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        for (int i = 0; i < PLACES; i++) tendrils[i] = new Tendril(this, i);
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
        setPos(site.x(), site.floorY() + CROWN_UP, site.z());
        growTendrils();
        actTimer = ENTER_TICKS;
    }

    public int phase() { return entityData.get(DATA_PHASE); }

    /** Client and server: the crown is down and the core open. */
    public boolean open() { return act() == ACT_OPEN || act() == ACT_FALL; }

    public int act() { return entityData.get(DATA_ACT) & 15; }

    public int actTendril() { return (entityData.get(DATA_ACT) >> 4) & 15; }

    public int actTick() { return entityData.get(DATA_ACT_TICK); }

    /** The ceiling the tendrils run up to. */
    public float top() { return entityData.get(DATA_TOP); }

    public float spin() { return entityData.get(DATA_SPIN); }

    /** True when a tendril stands in that place. */
    public boolean standing(int place) { return (entityData.get(DATA_STANDING) & (1 << place)) != 0; }

    /** Where a place's tendril is rooted on the floor. */
    public Vec3 tendrilFoot(int place) {
        double a = placeAngle(place);
        return new Vec3(getX() + TENDRIL_R * Math.cos(a), floorY(), getZ() + TENDRIL_R * Math.sin(a));
    }

    /** A place's angle round the dais. The client wants it too, so it is worked out from the id, not kept. */
    public double placeAngle(int place) {
        return place * 2 * Math.PI / PLACES + spin() * 0.0174532925;
    }

    /** The dais's middle: the crown's own column, which it never leaves. Right on the client too. */
    public double daisX() { return getX(); }

    public double daisZ() { return getZ(); }

    /** The bowl's floor. */
    public int floorY() { return Math.round(entityData.get(DATA_FLOOR)); }

    // ------------------------------------------------------------------------------------------- the body's parts

    @Override public boolean isMultipartEntity() { return true; }

    @Override public PartEntity<?>[] getParts() { return tendrils; }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (tendrils != null) for (int i = 0; i < tendrils.length; i++) if (tendrils[i] != null) tendrils[i].setId(id + i + 1);
    }

    /** The crown, the bell and six tendrils are drawn far outside the crown's own box. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return new AABB(getX() - TENDRIL_R - 4, floorY() - 2, getZ() - TENDRIL_R - 4,
                getX() + TENDRIL_R + 4, Math.max(top() + 2, getY() + 4), getZ() + TENDRIL_R + 4);
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

        if (letGo()) return;
        if (tickCount % HUSH_EVERY == 0) ChoirSummon.hush(this);
        mend();
        if (stallTicks > 0) { stallTicks--; setAct(ACT_STALL, 0, stallTicks); return; }

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
            actTimer = 20;
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
    }

    public int standingCount() {
        int n = 0;
        for (int i = 0; i < PLACES; i++) if (standing(i)) n++;
        return n;
    }

    // ---- choosing and running the turns

    private void chooseStep() {
        if (--actTimer > 0) { setAct(ACT_IDLE, 0, actTicks); return; }
        Player p = nearestFighter();
        if (p == null) { actTimer = 20; return; }
        List<Integer> roll = new ArrayList<>();
        roll.add(ACT_WINDUP);
        roll.add(ACT_WINDUP);
        roll.add(ACT_TOLL);
        if (phase >= 2) { roll.add(ACT_TOLL); roll.add(ACT_GRAB); }
        if (phase >= 3) { roll.add(ACT_BLOOM); roll.add(ACT_TOLL); }
        act = roll.get(random.nextInt(roll.size()));
        actTicks = 0;
        if (act == ACT_WINDUP) {
            actTendril = nearestStanding(p);
            if (actTendril < 0) { act = ACT_TOLL; actTendril = 0; }
            else {
                slamX = p.getX();
                slamZ = p.getZ();
                playOwn(ERSounds.CHOIR_WINDUP.get(), 0.9F, 1.0F);
            }
        }
        if (act == ACT_TOLL) playOwn(ERSounds.CHOIR_TOLL.get(), 1.3F, 1.0F);
        if (act == ACT_BLOOM) playOwn(ERSounds.CHOIR_BLOOM.get(), 1.1F, 1.0F);
    }

    /** The standing tendril nearest a player: the one that can reach them. */
    private int nearestStanding(Player p) {
        int best = -1;
        double bd = 1e9;
        for (int i = 0; i < PLACES; i++) {
            if (!standing(i)) continue;
            Vec3 f = tendrilFoot(i);
            double d = p.distanceToSqr(f.x, p.getY(), f.z);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** The wind-up: the tendril rears over the mark. Long enough to read, and a strong hit in it spoils the strike. */
    private void windUpStep() {
        if (actTicks < windUpLength()) return;
        act = ACT_SLAM;
        actTicks = 0;
    }

    private int windUpLength() { return phase >= 3 ? 18 : phase == 2 ? 22 : 26; }

    /** The slam itself: the tendril comes down on the mark it reared over, not on wherever the player is now. */
    private void slamStep() {
        if (actTicks == 6) {
            level().playSound(null, slamX, floorY() + 1, slamZ, ERSounds.CHOIR_SLAM.get(), SoundSource.HOSTILE, 1.2F, 1.0F);
            ChoirSummon.slam(this, slamX, slamZ, SLAM_R, SLAM_HURT);
        }
        if (actTicks > 16) { act = ACT_IDLE; actTicks = 0; actTimer = 26 - 4 * phase; }
    }

    private void tollStep() {
        // the ring leaves the crown and runs out over the floor; it passes a column once
        int reach = 4 + actTicks * 1;
        if (actTicks == 1) ChoirSummon.tollRing(this, 0);
        ChoirSummon.tollRing(this, reach);
        if (reach > ChoirSites.RIM_R) {
            act = ACT_IDLE;
            actTicks = 0;
            actTimer = 30 - 5 * phase;
        }
    }

    private void grabStep() {
        if (actTicks == 10) ChoirSummon.grab(this);
        if (actTicks > 22) { act = ACT_IDLE; actTicks = 0; actTimer = 30; }
    }

    private void bloomStep() {
        if (actTicks == 14) ChoirSummon.bloom(this);
        if (actTicks > 30) { act = ACT_IDLE; actTicks = 0; actTimer = 40; }
    }

    private void setAct(int a, int tendril, int ticks) {
        entityData.set(DATA_ACT, (byte) ((a & 15) | ((tendril & 15) << 4)));
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
        if (level().isClientSide || !standing(place) || cut[place] < 0) return false;
        float max = TENDRIL_CUT[Math.min(TENDRIL_CUT.length - 1, phase - 1)];
        cut[place] += amount;
        core(source, amount * TENDRIL_SHARE);
        if (cut[place] >= max) cutThrough(place);
        return true;
    }

    private void cutThrough(int place) {
        cut[place] = -1;
        entityData.set(DATA_STANDING, (byte) (entityData.get(DATA_STANDING) & ~(1 << place)));
        tendrils[place].refreshDimensions();
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
        stallTicks = INTERRUPT_STALL;
        if (standing(actTendril) && cut[actTendril] >= 0) {
            float max = TENDRIL_CUT[Math.min(TENDRIL_CUT.length - 1, phase - 1)];
            cut[actTendril] += INTERRUPT_CUT;
            if (cut[actTendril] >= max) cutThrough(actTendril);
        }
        playOwn(ERSounds.CHOIR_HURT.get(), 1.0F, 1.1F);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) ChoirSummon.give(this, true);
    }

    // --------------------------------------------------------------------------------------------------- the rest

    private void hover() {
        setDeltaMovement(Vec3.ZERO);
        if (open()) return;
        double y = floorY() + CROWN_UP + Math.sin(tickCount * 0.035) * 0.35;
        setPos(getX(), y, getZ());
    }

    /** Each standing tendril's box stands on its root; a cut one is pushed out of the way and cannot be hit. */
    private void placeTendrils() {
        for (int i = 0; i < PLACES; i++) {
            Tendril t = tendrils[i];
            Vec3 f = tendrilFoot(i);
            t.setPos(f.x, f.y, f.z);
            t.setOldPosAndRot();
        }
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
        cellX = tag.getInt("CellX");
        cellZ = tag.getInt("CellZ");
        phase = Math.max(1, tag.getInt("Phase"));
        entityData.set(DATA_PHASE, (byte) phase);
        entityData.set(DATA_TOP, tag.getFloat("Top"));
        entityData.set(DATA_STANDING, tag.getByte("Standing"));
        spin = tag.getFloat("Spin");
        net.minecraft.nbt.ListTag cuts = tag.getList("Cut", net.minecraft.nbt.Tag.TAG_FLOAT);
        for (int i = 0; i < Math.min(PLACES, cuts.size()); i++) cut[i] = cuts.getFloat(i);
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

    private void clientTick() {
        dropO = drop;
        ringO = ring;
        glowO = glow;
        bobO = bob;
        int a = act();
        float want = a == ACT_FALL ? Math.min(1, actTick() / (float) FALL_TICKS)
                : a == ACT_OPEN ? 1
                : a == ACT_RISE ? 1 - Math.min(1, actTick() / (float) RISE_TICKS) : 0;
        drop += (want - drop) * 0.25F;
        ring = a == ACT_TOLL ? Math.min(1, actTick() / 6F) : ring * 0.86F;
        glow = Math.min(1, glow + (open() ? 0.08F : -0.05F));
        glow = Math.max(0.15F, glow);
        bob += 0.035F;
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

        @Override public boolean isPickable() { return getParent().isAlive() && getParent().standing(place); }

        @Override public EntityDimensions getDimensions(Pose pose) { return getParent().standing(place) ? SIZE : GONE; }

        @Override public boolean is(Entity e) { return this == e || getParent() == e; }

        @Nullable @Override public ItemStack getPickResult() { return getParent().getPickResult(); }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return !isInvulnerableTo(source) && getParent().hurtTendril(place, source, amount);
        }
    }
}
