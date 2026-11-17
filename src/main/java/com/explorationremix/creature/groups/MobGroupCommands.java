package com.explorationremix.creature.groups;

import com.explorationremix.creature.groups.MobGroupRules.Home;
import com.explorationremix.creature.groups.MobGroupRules.Kind;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix groups: looking at vanilla's hostile mobs in groups while testing (operators only).
 * <ul>
 *     <li>{@code /remix groups list [radius]}: the groups with a member within radius (96) of you, each with its leader
 *     and how far each member is from it</li>
 *     <li>{@code /remix groups here}: the home of the ground under you, of the cave you stand in and of the water you
 *     are in, the groups of each home round you, the endermen round you, and whether monsters may come out</li>
 *     <li>{@code /remix groups spawn <kind>}: a group of that kind near you, at the first place of its home that takes
 *     it (dark enough, room, at least 24 blocks off), whatever the counts</li>
 *     <li>{@code /remix groups stats}: what the spawner has done since the server started</li>
 * </ul>
 */
public final class MobGroupCommands {
    private MobGroupCommands() {}

    private static final String[] KINDS = Arrays.stream(Kind.values()).map(Kind::id).toArray(String[]::new);

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("groups")
                .then(Commands.literal("list").executes(c -> list(c, (int) MobGroupRules.COUNT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 512)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("here").executes(MobGroupCommands::here))
                .then(Commands.literal("spawn").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(KINDS, b))
                        .executes(c -> spawn(c, StringArgumentType.getString(c, "kind")))))
                .then(Commands.literal("stats").executes(MobGroupCommands::stats));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static String at(Vec3 p) {
        return (int) Math.floor(p.x) + " " + (int) Math.floor(p.y) + " " + (int) Math.floor(p.z);
    }

    private static String name(Mob m) {
        return net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(m.getType()).getPath();
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 me = c.getSource().getPosition();
        List<MobGroups.Group> near = new ArrayList<>();
        for (MobGroups.Group g : MobGroups.groups())
            for (Mob m : g.members)
                if (m.level() == level && m.position().distanceTo(me) <= radius) { near.add(g); break; }
        if (near.isEmpty()) { say(c, "No groups within " + radius + " blocks."); return 0; }
        near.sort(Comparator.comparingDouble(g -> { Mob l = g.leader(); return l == null ? Double.MAX_VALUE : l.position().distanceTo(me); }));
        say(c, near.size() + " group" + (near.size() == 1 ? "" : "s") + " within " + radius + " blocks:");
        for (MobGroups.Group g : near) {
            Mob l = g.leader();
            if (l == null) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(g.kind.id()).append(" (").append(g.kind.home.name().toLowerCase(Locale.ROOT)).append("), ")
                    .append(g.members.size()).append(": ").append(name(l)).append(" leads at ").append(at(l.position()))
                    .append(", ").append((int) l.position().distanceTo(me)).append(" off");
            if (l.getTarget() != null) sb.append(", after ").append(l.getTarget().getName().getString());
            for (Mob m : g.members) {
                if (m == l) continue;
                sb.append("; ").append(name(m)).append(' ').append(String.format(Locale.ROOT, "%.0f", m.distanceTo(l)));
                if (m.getTarget() != null) sb.append(" (fighting)");
            }
            say(c, sb.toString());
        }
        return near.size();
    }

    private static int here(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 me = c.getSource().getPosition();
        BlockPos pos = BlockPos.containing(me);
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("?");
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        BlockPos ground = MobGroupSpawner.surfaceGround(level, pos.getX(), pos.getZ());
        if (ground == null) say(c, "Ground here: none to stand on (water, a trunk, or no ground under the column's top at y " + top + ")");
        else {
            String groundBiome = level.getBiome(ground).unwrapKey().map(k -> k.location().toString()).orElse("?");
            Home surface = MobHomes.surfaceHome(level, ground, MobGroupSpawner.woodsCanopy());
            say(c, String.format(Locale.ROOT, "Ground here (y %d, %s): %s; trees cover %.0f%% round it (woods need %.0f%%)", ground.getY(), groundBiome,
                    surface == null ? "no group's home" : surface.name().toLowerCase(Locale.ROOT), 100 * MobHomes.canopy(level, ground), 100 * MobGroupSpawner.woodsCanopy()));
        }
        if (pos.getY() < top - 1 && !level.canSeeSky(pos)) {
            Home cave = MobHomes.caveHome(level, pos);
            say(c, "Cave here (" + biome + "): " + (cave == null ? "no group's home" : cave.name().toLowerCase(Locale.ROOT)));
        }
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            int depth = 0;
            BlockPos waterTop = new BlockPos(pos.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1, pos.getZ());
            while (depth < MobGroupSpawner.WATER_SCAN && level.getFluidState(waterTop.below(depth)).is(FluidTags.WATER)) depth++;
            Home water = MobHomes.waterHome(level, waterTop, depth);
            say(c, "Water here (" + depth + " deep): " + (water == null ? "no group's home" : water.name().toLowerCase(Locale.ROOT)));
        }
        int[] n = MobGroups.groupsRound(level, me);
        StringBuilder sb = new StringBuilder("Groups within " + (int) MobGroupRules.COUNT_RADIUS + " blocks:");
        for (Home h : Home.values()) sb.append(' ').append(h.name().toLowerCase(Locale.ROOT)).append(' ').append(n[h.ordinal()]);
        sb.append("; endermen within ").append((int) MobGroupRules.DESPAWN).append(": ").append(MobGroupSpawner.endermenRound(level, me));
        say(c, sb.toString());
        // monsters round you that are in no group: vanilla's own spawns (none should come any more), spawners' and others'
        java.util.Map<String, Integer> loose = new java.util.TreeMap<>();
        double r = MobGroupRules.DESPAWN;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(me, me).inflate(r),
                m -> m instanceof net.minecraft.world.entity.monster.Enemy && m.isAlive() && m.position().distanceToSqr(me) <= r * r && !MobGroups.isMember(m)))
            loose.merge(name(m), 1, Integer::sum);
        say(c, "Monsters in no group within " + (int) r + ": " + (loose.isEmpty() ? "none" : loose.toString()));
        say(c, "Monsters come out: " + (!MobGroupSpawner.enabled() ? "no, groups are off in the config (vanilla's own spawning)"
                : MobGroupSpawner.spawningOn(level) ? "yes" : "no (peaceful, doMobSpawning off or the server's spawn-monsters off)"));
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String kindName) throws CommandSyntaxException {
        Kind kind;
        try {
            kind = Kind.valueOf(kindName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("No kind " + kindName + "; one of " + String.join(", ", KINDS)));
            return 0;
        }
        ServerPlayer p = c.getSource().getPlayerOrException();
        Mob leader = MobGroupSpawner.spawnFor(p.serverLevel(), p, kind);
        if (leader == null) {
            c.getSource().sendFailure(Component.literal("No place of " + kind.home.name().toLowerCase(Locale.ROOT) + " near you took a group of "
                    + kind.id() + " (" + MobGroupSpawner.COMMAND_TRIES + " tries): it must be dark enough, with room, 24 to 128 blocks off."));
            return 0;
        }
        MobGroups.Group g = MobGroups.groupOf(leader);
        int n = g == null ? 1 : g.members.size();
        say(c, "A group of " + kind.id() + ", " + n + " strong, came out at " + at(leader.position()) + ", "
                + (int) leader.position().distanceTo(p.position()) + " blocks off.");
        return n;
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        StringBuilder homes = new StringBuilder();
        for (Home h : Home.values()) homes.append(' ').append(h.name().toLowerCase(Locale.ROOT)).append(' ').append(MobGroupSpawner.ANCHORS_BY_HOME[h.ordinal()]);
        StringBuilder kinds = new StringBuilder();
        for (Kind k : Kind.values()) kinds.append(' ').append(k.id()).append(' ').append(MobGroupSpawner.SPAWNED_BY_KIND[k.ordinal()]);
        say(c, "Looks " + MobGroupSpawner.looks + ", places in a home " + MobGroupSpawner.anchors + " (" + homes.toString().trim() + "), wanted "
                + MobGroupSpawner.wanted + ", came out " + MobGroupSpawner.spawned + ", did not take " + MobGroupSpawner.failed
                + " (the leader's spot " + MobGroupSpawner.failedLeader + ", too few found room " + (MobGroupSpawner.failed - MobGroupSpawner.failedLeader)
                + "), endermen " + MobGroupSpawner.endermen);
        say(c, "Groups by kind:" + kinds);
        int loaded = 0, members = 0;
        for (MobGroups.Group g : MobGroups.groups()) { loaded++; members += g.members.size(); }
        say(c, "Loaded now: " + loaded + " groups, " + members + " members");
        StringBuilder left = new StringBuilder();
        for (net.minecraft.world.entity.Entity.RemovalReason r : net.minecraft.world.entity.Entity.RemovalReason.values())
            left.append(' ').append(r.name().toLowerCase(Locale.ROOT)).append(' ').append(MobGroups.LEFT[r.ordinal()]);
        say(c, "Members gone:" + left + "; groups despawned with their leader " + MobGroups.despawnedTogether + " (members taken with it "
                + MobGroups.discardedWith + "); deaths " + (MobGroups.DEATHS.isEmpty() ? "none" : MobGroups.DEATHS.toString())
                + "; blows held back between members of one group " + MobGroups.spared);
        return 1;
    }
}
