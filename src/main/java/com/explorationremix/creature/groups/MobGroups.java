package com.explorationremix.creature.groups;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.groups.MobGroupRules.Home;
import com.explorationremix.creature.groups.MobGroupRules.Kind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The groups in the world and how they hold together. A member carries its group in its saved data (the group's id, its
 * kind and the member's rank), so a group stays one through chunk unloads and restarts; while loaded, its members are
 * kept here. The leader is the loaded member of lowest rank (the first to come out), so when it dies the next one leads.
 *
 * <ul>
 *     <li>Roaming: the leader strolls as its kind does; a member further than {@link MobGroupRules#LEASH} from it walks
 *     back ({@link FollowGroupGoal}, for slimes {@link SlimeFollowGroupGoal}) until within {@link MobGroupRules#CLOSE}.</li>
 *     <li>Fighting: when one member turns on a player (it saw the player, or the player hit it), the members within
 *     {@link MobGroupRules#ALERT} of it with nothing else in mind turn on that player too.</li>
 *     <li>Despawning: a member with its group is never removed on its own; when vanilla removes the leader for being far
 *     from every player, the members with it go too, so a group never thins out one by one.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class MobGroups {
    private MobGroups() {}

    /** The member's saved data, in the entity's Forge data. */
    static final String TAG = ExplorationRemix.MOD_ID + ":group";
    /** A follower's goal comes this much before its own stroll; slimes have none to go by. */
    static final int SLIME_FOLLOW_PRIORITY = 2;
    /** How often stale alerts are let go (ticks). */
    static final int UPKEEP = 20;
    /** An alerted member lets its player go past this many times its follow range. */
    static final double ALERT_GIVE_UP = 1.25;

    /** One group's loaded members. */
    static final class Group {
        final UUID id;
        final Kind kind;
        final List<Mob> members = new ArrayList<>(6);

        Group(UUID id, Kind kind) { this.id = id; this.kind = kind; }

        /** The loaded, living member of lowest rank, or null. */
        @Nullable
        Mob leader() {
            Mob best = null;
            int bestRank = Integer.MAX_VALUE;
            for (Mob m : members) {
                if (!m.isAlive()) continue;
                Member mm = BY_MOB.get(m);
                int r = mm == null ? Integer.MAX_VALUE - 1 : mm.rank;
                if (best == null || r < bestRank) { best = m; bestRank = r; }
            }
            return best;
        }
    }

    record Member(Group group, int rank) {}

    private static final Map<UUID, Group> GROUPS = new HashMap<>();
    private static final Map<Mob, Member> BY_MOB = new IdentityHashMap<>();
    /** Members that took a player as their target from another member, and that player. */
    private static final Map<Mob, Player> ALERTED = new IdentityHashMap<>();
    /** The leader whose despawn check is running, from Forge's despawn event to the start of its tick. */
    @Nullable private static Mob despawning;
    private static boolean alerting;

    // what became of members since the server started, for /remix groups stats
    static final long[] LEFT = new long[Entity.RemovalReason.values().length];
    static long despawnedTogether, discardedWith, spared;
    static final Map<String, Integer> DEATHS = new java.util.TreeMap<>();

    // ------------------------------------------------------------------------------------------------ membership

    /** Marks a new mob as a member of group {@code id} before it is added to the world. */
    static void tag(Mob mob, UUID id, Kind kind, int rank) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("kind", kind.id());
        t.putInt("rank", rank);
        mob.getPersistentData().put(TAG, t);
    }

    /** The group a mob's saved data names, or null. */
    @Nullable
    private static Member read(Mob mob) {
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(TAG, Tag.TAG_COMPOUND)) return null;
        CompoundTag t = data.getCompound(TAG);
        if (!t.hasUUID("id")) return null;
        Kind kind;
        try {
            kind = Kind.valueOf(t.getString("kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        UUID id = t.getUUID("id");
        Group g = GROUPS.computeIfAbsent(id, k -> new Group(k, kind));
        return new Member(g, t.getInt("rank"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Mob mob) || BY_MOB.containsKey(mob)) return;
        Member m = read(mob);
        if (m == null) return;
        BY_MOB.put(mob, m);
        m.group.members.add(mob);
        addGoals(mob);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Mob mob)) return;
        ALERTED.remove(mob);
        boolean despawned = mob == despawning && mob.getRemovalReason() == Entity.RemovalReason.DISCARDED;
        if (mob == despawning) despawning = null;
        Member m = BY_MOB.remove(mob);
        if (m == null) return;
        Entity.RemovalReason why = mob.getRemovalReason();
        if (why != null) LEFT[why.ordinal()]++;
        Group g = m.group;
        g.members.remove(mob);
        if (despawned) despawnedTogether++;
        // vanilla removed the leader for being far from every player: the members with it go with it (any that vanilla
        // itself would keep, such as one within 32 blocks of a player, stays)
        if (despawned)
            for (Mob f : List.copyOf(g.members)) {
                if (!f.isAlive() || f.isPersistenceRequired() || f.requiresCustomPersistence() || f.distanceTo(mob) > MobGroupRules.KEEP) continue;
                Player near = f.level().getNearestPlayer(f, -1.0);
                if (near == null || (near.distanceTo(f) > MobGroupRules.KEEP && f.removeWhenFarAway(near.distanceToSqr(f)))) {
                    discardedWith++;
                    f.discard();
                }
            }
        if (g.members.isEmpty()) GROUPS.remove(g.id);
    }

    @SubscribeEvent
    public static void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Mob mob) || mob.level().isClientSide() || !BY_MOB.containsKey(mob)) return;
        Entity killer = e.getSource().getEntity();
        DEATHS.merge(e.getSource().getMsgId() + (killer == null ? "" : " by " + EntityType.getKey(killer.getType()).getPath()), 1, Integer::sum);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        java.util.Arrays.fill(LEFT, 0);
        despawnedTogether = discardedWith = spared = 0;
        DEATHS.clear();
        GROUPS.clear();
        BY_MOB.clear();
        ALERTED.clear();
        despawning = null;
        alerting = false;
        MobHomes.forget();
        MobGroupSpawner.resetCounts();
    }

    /** The leader of a member's group (itself when it leads), or null when it is in none. */
    @Nullable
    static Mob leaderOf(Mob mob) {
        Member m = BY_MOB.get(mob);
        return m == null ? null : m.group.leader();
    }

    @Nullable
    static Group groupOf(Mob mob) {
        Member m = BY_MOB.get(mob);
        return m == null ? null : m.group;
    }

    static int rankOf(Mob mob) {
        Member m = BY_MOB.get(mob);
        return m == null ? -1 : m.rank;
    }

    static Collection<Group> groups() { return GROUPS.values(); }

    /**
     * How many groups of each home have their leader within {@link MobGroupRules#COUNT_RADIUS} of {@code at} (a lone
     * member left of one is no group).
     */
    static int[] groupsRound(ServerLevel level, Vec3 at) {
        int[] n = new int[Home.values().length];
        double r2 = MobGroupRules.COUNT_RADIUS * MobGroupRules.COUNT_RADIUS;
        for (Group g : GROUPS.values()) {
            if (!MobGroupRules.stillAGroup(g.members.size())) continue;
            Mob l = g.leader();
            if (l != null && l.level() == level && l.position().distanceToSqr(at) <= r2) n[g.kind.home.ordinal()]++;
        }
        return n;
    }

    /** Adds the walk back to the leader, once, just before the mob's own stroll. */
    private static void addGoals(Mob mob) {
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals())
            if (w.getGoal() instanceof FollowGroupGoal || w.getGoal() instanceof SlimeFollowGroupGoal) return;
        if (mob instanceof Slime slime) {
            mob.goalSelector.addGoal(SLIME_FOLLOW_PRIORITY, new SlimeFollowGroupGoal(slime));
            return;
        }
        if (!(mob instanceof PathfinderMob pm)) return;
        int stroll = Integer.MAX_VALUE;
        for (WrappedGoal w : mob.goalSelector.getAvailableGoals())
            if (w.getGoal() instanceof RandomStrollGoal) stroll = Math.min(stroll, w.getPriority());
        mob.goalSelector.addGoal(stroll == Integer.MAX_VALUE ? 5 : Math.max(1, stroll - 1), new FollowGroupGoal(pm));
    }

    // ------------------------------------------------------------------------------------------------ fighting together

    /** Whether a mob may be set on {@code p}: a player in survival or adventure, alive, not on peaceful. */
    static boolean huntable(Player p) {
        return p.isAlive() && !p.isCreative() && !p.isSpectator() && p.level().getDifficulty() != Difficulty.PEACEFUL;
    }

    static boolean sameGroup(Mob a, Mob b) {
        Member ma = BY_MOB.get(a), mb = BY_MOB.get(b);
        return ma != null && mb != null && ma.group == mb.group;
    }

    /** A member's blows do not hurt its own group (a creeper's blast still does): see {@link MobGroupRules#spared}. */
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (!(e.getEntity() instanceof Mob victim) || victim.level().isClientSide() || !(e.getSource().getEntity() instanceof Mob attacker)
                || attacker == victim) return;
        if (MobGroupRules.spared(sameGroup(victim, attacker), e.getSource().is(DamageTypeTags.IS_EXPLOSION))) {
            spared++;
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getEntity() instanceof Mob mob) || mob.level().isClientSide()) return;
        // a member never turns on one of its own group (a zombie hit by a stray blast, or called by another zombie)
        if (e.getNewTarget() instanceof Mob other && sameGroup(mob, other)) {
            e.setCanceled(true);
            return;
        }
        if (alerting || !(e.getNewTarget() instanceof Player player) || !huntable(player)) return;
        Member m = BY_MOB.get(mob);
        if (m == null) return;
        alerting = true;
        try {
            double alert2 = MobGroupRules.ALERT * MobGroupRules.ALERT;
            for (Mob other : List.copyOf(m.group.members)) {
                if (other == mob || !other.isAlive() || other.getTarget() != null || other.isNoAi()) continue;
                if (other.distanceToSqr(mob) > alert2) continue;
                // only those that could follow the player at all (vanilla's path search reaches as far as this)
                double range = other.getAttributeValue(Attributes.FOLLOW_RANGE);
                if (other.distanceToSqr(player) > range * range) continue;
                other.setTarget(player);
                if (other.getTarget() == player) ALERTED.put(other, player);
            }
        } finally {
            alerting = false;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ALERTED.isEmpty() || e.getServer().getTickCount() % UPKEEP != 0) return;
        // a target set from outside the mob's own target goals is let go by nothing in vanilla: let it go here once
        // the player is out of reach, gone or no longer fair game
        for (Iterator<Map.Entry<Mob, Player>> it = ALERTED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Mob, Player> en = it.next();
            Mob mob = en.getKey();
            Player p = en.getValue();
            if (mob.isRemoved() || mob.getTarget() != p) { it.remove(); continue; }
            double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE) * ALERT_GIVE_UP;
            if (!huntable(p) || p.isRemoved() || p.level() != mob.level() || mob.distanceToSqr(p) > range * range) {
                it.remove();
                mob.setTarget(null);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ despawning together

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onAllowDespawn(MobSpawnEvent.AllowDespawn e) {
        if (e.getResult() != Event.Result.DEFAULT) return;
        Mob mob = e.getEntity();
        Member m = BY_MOB.get(mob);
        if (m == null) return;
        Mob leader = m.group.leader();
        if (leader == null) return;
        if (leader == mob) {
            // vanilla decides for the leader; if it removes it in this check, the members go with it (onLeave)
            despawning = mob;
            return;
        }
        boolean leaderMay = !leader.isPersistenceRequired() && !leader.requiresCustomPersistence();
        if (leader.level() == mob.level() && MobGroupRules.heldByLeader(false, mob.distanceTo(leader), leaderMay)) e.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent e) {
        // the despawn check is over once the leader's own tick starts: a later removal (a creeper going off, a zombie
        // turning into a drowned) is not a despawn
        if (despawning != null && e.getEntity() == despawning) despawning = null;
    }

    /** Whether {@code e} is a living member of any group (for the commands). */
    static boolean isMember(LivingEntity e) {
        return e instanceof Mob m && BY_MOB.containsKey(m);
    }
}
