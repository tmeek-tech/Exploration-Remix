package com.explorationremix.creature;

import com.explorationremix.registry.EREntities;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix mossback: looking after the Mossbacks while testing (operators only).
 * <ul>
 *     <li>{@code /remix mossback spawn [moss|frost]}: one on the floor where you look (no den)</li>
 *     <li>{@code /remix mossback list [radius]}: the Mossbacks round you and what each is doing</li>
 *     <li>{@code /remix mossback state <name> [ticks]}: sets the nearest one's state (roam, wait, chase, search...)</li>
 *     <li>{@code /remix mossback dens [chunks]}: the dens in the chunks round you, nearest first, and how each stands</li>
 *     <li>{@code /remix mossback den}: takes you to the nearest den within 8 chunks</li>
 * </ul>
 */
public final class MossbackCommands {
    private MossbackCommands() {}

    private static final String[] STATES = {"roam", "wait", "alert", "chase", "rear", "lunge", "grab", "recoil", "search", "eat", "drop"};

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("mossback")
                .then(Commands.literal("spawn").executes(c -> spawn(c, MossbackEntity.MOSS))
                        .then(Commands.literal("moss").executes(c -> spawn(c, MossbackEntity.MOSS)))
                        .then(Commands.literal("frost").executes(c -> spawn(c, MossbackEntity.FROST))))
                .then(Commands.literal("list").executes(c -> list(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("state").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(STATES, b))
                        .executes(c -> state(c, StringArgumentType.getString(c, "name"), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                                .executes(c -> state(c, StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "ticks"))))))
                .then(Commands.literal("dens").executes(c -> dens(c, 6, false))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 16)).executes(c -> dens(c, IntegerArgumentType.getInteger(c, "chunks"), false))))
                .then(Commands.literal("den").executes(c -> dens(c, 8, true)));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int spawn(CommandContext<CommandSourceStack> c, byte variant) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
        if (c.getSource().getEntity() != null) eye = c.getSource().getEntity().getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(24)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(6)) : hit.getLocation().subtract(look.scale(1.2));
        MossbackEntity m = EREntities.MOSSBACK.get().create(level);
        if (m == null) return 0;
        m.moveTo(at.x, at.y, at.z, c.getSource().getRotation().y + 180.0F, 0.0F);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.COMMAND, null, null);
        m.setVariant(variant);
        level.addFreshEntity(m);
        say(c, "Mossback (" + (variant == MossbackEntity.FROST ? "frost" : "moss") + ") at " + BlockPos.containing(at).toShortString());
        return 1;
    }

    private static List<MossbackEntity> near(CommandContext<CommandSourceStack> c, double radius) {
        Vec3 p = c.getSource().getPosition();
        List<MossbackEntity> all = c.getSource().getLevel().getEntitiesOfClass(MossbackEntity.class, new AABB(p, p).inflate(radius));
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        List<MossbackEntity> all = near(c, radius);
        if (all.isEmpty()) { say(c, "No Mossback within " + radius + " blocks."); return 0; }
        Vec3 p = c.getSource().getPosition();
        for (MossbackEntity m : all)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks: %s", m.blockPosition().toShortString(), Math.sqrt(m.distanceToSqr(p)), m.describe()));
        return all.size();
    }

    private static int state(CommandContext<CommandSourceStack> c, String name, int ticks) {
        int s = Arrays.asList(STATES).indexOf(name.toLowerCase(Locale.ROOT));
        if (s < 0) { c.getSource().sendFailure(Component.literal("No state " + name + "; one of " + String.join(", ", STATES))); return 0; }
        List<MossbackEntity> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Mossback within 64 blocks.")); return 0; }
        MossbackEntity m = all.get(0);
        if (s == MossbackEntity.CHASE || s == MossbackEntity.ALERT || s == MossbackEntity.SEARCH) {
            if (c.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity me) m.setTarget(me);
        }
        m.forceState((byte) s, ticks);
        say(c, "Now: " + m.describe());
        return 1;
    }

    private static int dens(CommandContext<CommandSourceStack> c, int radius, boolean go) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 p = c.getSource().getPosition();
        ChunkPos centre = new ChunkPos(BlockPos.containing(p));
        record Found(BlockPos pos, MossbackDen.Tile tile) {}
        java.util.ArrayList<Found> found = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (!(level.getChunk(centre.x + dx, centre.z + dz, ChunkStatus.FULL, true) instanceof LevelChunk chunk)) continue;
                for (BlockEntity be : chunk.getBlockEntities().values())
                    if (be instanceof MossbackDen.Tile t) found.add(new Found(be.getBlockPos(), t));
            }
        found.sort(Comparator.comparingDouble(f -> f.pos.distToCenterSqr(p)));
        if (found.isEmpty()) { say(c, "No den within " + radius + " chunks."); return 0; }
        long now = level.getGameTime();
        if (go) {
            Found f = found.get(0);
            BlockPos at = f.pos;
            if (c.getSource().getEntity() != null)
                c.getSource().getEntity().teleportTo(at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5);
            say(c, "Den at " + at.toShortString() + " (" + level.getBiome(at).unwrapKey().map(k -> k.location().getPath()).orElse("?") + "): " + f.tile.describe(now));
            return 1;
        }
        for (Found f : found)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks, %s: %s", f.pos.toShortString(), Math.sqrt(f.pos.distToCenterSqr(p)),
                    level.getBiome(f.pos).unwrapKey().map(k -> k.location().getPath()).orElse("?"), f.tile.describe(now)));
        return found.size();
    }
}
