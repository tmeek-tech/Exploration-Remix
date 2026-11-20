package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.StiltMantisesFeature;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix mantis: looking after the Deep Gardens' stilt mantis herds while testing (operators only).
 * <ul>
 *     <li>{@code /remix mantis spawn [colour]}: one where you look (its colour from the mats round it, or the one named:
 *     magenta, violet, lime or light_blue)</li>
 *     <li>{@code /remix mantis list [radius]}: the mantises round you, nearest first: where, colour, what each is doing,
 *     its herd and how far from home</li>
 *     <li>{@code /remix mantis homes [radius]}: the herd homes planned round you (the vent field's plan), nearest first:
 *     where, colour, size, how many of the herd are near home now; and the herds set down with the world since the game
 *     started, and the refill's counts</li>
 *     <li>{@code /remix mantis tp [n] [distance]}: to the nth nearest herd home (from 1), standing a little way off
 *     (14 blocks, or the distance given), facing it</li>
 *     <li>{@code /remix mantis tpcolour <colour> [distance]}: the same, to the nearest herd of that colour</li>
 *     <li>{@code /remix mantis scare}: the mantises within 24 blocks startle from you, as if you had run at them</li>
 *     <li>{@code /remix mantis refill}: every short herd round you gets one mantis back now (unseen spots only)</li>
 * </ul>
 */
public final class MantisCommands {
    private MantisCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("mantis")
                .then(Commands.literal("spawn").executes(c -> spawn(c, null))
                        .then(Commands.argument("colour", StringArgumentType.word()).executes(c -> spawn(c, StringArgumentType.getString(c, "colour")))))
                .then(Commands.literal("list").executes(c -> list(c, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("homes").executes(c -> homes(c, 160))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512)).executes(c -> homes(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("tp").executes(c -> tp(c, 1, 14, null))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), 14, null))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 40))
                                        .executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), IntegerArgumentType.getInteger(c, "distance"), null)))))
                .then(Commands.literal("tpcolour")
                        .then(Commands.argument("colour", StringArgumentType.word()).executes(c -> tp(c, 1, 14, StringArgumentType.getString(c, "colour")))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 40))
                                        .executes(c -> tp(c, 1, IntegerArgumentType.getInteger(c, "distance"), StringArgumentType.getString(c, "colour"))))))
                .then(Commands.literal("scare").executes(MantisCommands::scare))
                .then(Commands.literal("refill").executes(MantisCommands::refill));
    }

    private static int spawn(CommandContext<CommandSourceStack> c, String colour) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 eye = src.getPosition().add(0, 1.6, 0);
        Vec3 look = src.getEntity() != null ? src.getEntity().getLookAngle() : new Vec3(0, 0, 1);
        Vec3 at = eye.add(look.x * 4, 0, look.z * 4);
        BlockPos spot = StiltMantisEntity.stand(level, (int) Math.floor(at.x), (int) Math.floor(src.getPosition().y), (int) Math.floor(at.z), 6);
        if (spot == null) {
            src.sendFailure(Component.literal("No floor with three blocks of room over it there"));
            return 0;
        }
        StiltMantisEntity m = EREntities.STILT_MANTIS.get().create(level);
        if (m == null) return 0;
        if (colour != null) {
            Neon n = null;
            for (Neon k : Neon.values()) if (k.getSerializedName().equals(colour.toLowerCase(Locale.ROOT))) n = k;
            if (n == null) {
                src.sendFailure(Component.literal("Colours: magenta, violet, lime, light_blue"));
                return 0;
            }
            m.setColour(n);
        }
        float yaw = (float) (Math.atan2(-(src.getPosition().x - spot.getX()), src.getPosition().z - spot.getZ()) * 180.0 / Math.PI);
        m.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0.0F);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(m);
        src.sendSuccess(() -> Component.literal("A " + m.colour().getSerializedName() + " stilt mantis at " + spot.toShortString()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        List<StiltMantisEntity> all = new ArrayList<>(src.getLevel().getEntitiesOfClass(StiltMantisEntity.class, new AABB(p, p).inflate(radius),
                m -> m.isAlive() && m.position().distanceTo(p) <= radius));
        all.sort(Comparator.comparingDouble(m -> m.position().distanceToSqr(p)));
        StringBuilder b = new StringBuilder(all.size() + " stilt mantis(es) within " + radius + " blocks");
        for (StiltMantisEntity m : all.subList(0, Math.min(all.size(), 16)))
            b.append(String.format(Locale.ROOT, "\n %d %d %d (%d off): %s", m.getBlockX(), m.getBlockY(), m.getBlockZ(),
                    (int) m.position().distanceTo(p), m.describe()));
        src.sendSuccess(() -> Component.literal(b.toString()), false);
        return all.size();
    }

    private record Found(MantisHerds.Home home, double dist) {}

    private static List<Found> nearHomes(Terrain t, Vec3 p, int radius) {
        List<Found> out = new ArrayList<>();
        int r = (int) Math.ceil(radius / (double) MantisHerds.CELL);
        int pcx = MantisHerds.cell((int) Math.floor(p.x)), pcz = MantisHerds.cell((int) Math.floor(p.z));
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++)
                for (MantisHerds.Home h : MantisHerds.homes(t, cx, cz)) {
                    double d = p.distanceTo(new Vec3(h.x() + 0.5, h.y(), h.z() + 0.5));
                    if (d <= radius) out.add(new Found(h, d));
                }
        out.sort(Comparator.comparingDouble(Found::dist));
        return out;
    }

    private static int homes(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null) {
            src.sendFailure(Component.literal("The deep world is not in this level"));
            return 0;
        }
        List<Found> found = nearHomes(t, src.getPosition(), radius);
        int[] colours = new int[4];
        for (Found f : found) colours[f.home.colour().ordinal()]++;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%d herd home(s) within %d blocks (magenta %d, violet %d, lime %d, light blue %d)",
                found.size(), radius, colours[0], colours[1], colours[2], colours[3]));
        for (Found f : found.subList(0, Math.min(found.size(), 12))) {
            MantisHerds.Home h = f.home;
            int members = MantisRefill.members(src.getLevel(), h);
            b.append(String.format(Locale.ROOT, "\n %d %d %d (%d off): %s, %d of %d near home, grove of %d mats", h.x(), h.y(), h.z(), (int) f.dist,
                    h.colour().getSerializedName(), members, h.size(), h.mats()));
        }
        b.append(String.format(Locale.ROOT, "\nSet down with the world since the game started: %d herds met, %d mantises. Refill: %d looks at short herds, %d rolls, %d moved in.",
                StiltMantisesFeature.HERDS.get(), StiltMantisesFeature.PLACED.get(), MantisRefill.LOOKS, MantisRefill.ROLLS, MantisRefill.MOVED_IN));
        src.sendSuccess(() -> Component.literal(b.toString()), false);
        return found.size();
    }

    private static int tp(CommandContext<CommandSourceStack> c, int n, int distance, String colour) {
        CommandSourceStack src = c.getSource();
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null || !(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Needs a player in a level with the deep world"));
            return 0;
        }
        List<Found> found = nearHomes(t, src.getPosition(), colour == null ? 512 : 1024);
        if (colour != null) found.removeIf(f -> !f.home.colour().getSerializedName().equals(colour.toLowerCase(Locale.ROOT)));
        if (found.size() < n) {
            src.sendFailure(Component.literal("Only " + found.size() + " such herd home(s) near"));
            return 0;
        }
        MantisHerds.Home h = found.get(n - 1).home;
        ServerLevel level = src.getLevel();
        // stand off where there is floor, facing the home (the first way round with floor, from the seed's own turn)
        double a0 = (h.id() & 0xFF) / 256.0 * Math.PI * 2.0;
        for (int k = 0; k < 24; k++) {
            double a = a0 + k * Math.PI * 2.0 / 24.0;
            int x = h.x() + (int) Math.round(Math.cos(a) * distance), z = h.z() + (int) Math.round(Math.sin(a) * distance);
            level.getChunk(x >> 4, z >> 4);
            BlockPos at = StiltMantisEntity.stand(level, x, h.y(), z, 6);
            if (at == null) continue;
            double dx = h.x() - at.getX(), dz = h.z() - at.getZ(), dy = h.y() + 1.5 - (at.getY() + 1.62);
            float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
            float pitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
            player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, pitch);
            src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "At the %s herd's home %d %d %d (size %d), from %s",
                    h.colour().getSerializedName(), h.x(), h.y(), h.z(), h.size(), at.toShortString())), true);
            return 1;
        }
        player.teleportTo(level, h.x() + 0.5, h.y(), h.z() + 0.5, player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.literal("At the herd's home " + h.x() + " " + h.y() + " " + h.z()), true);
        return 1;
    }

    private static int scare(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        int n = 0;
        StiltMantisEntity first = null;
        for (StiltMantisEntity m : src.getLevel().getEntitiesOfClass(StiltMantisEntity.class, new AABB(p, p).inflate(24))) {
            if (!m.isAlive()) continue;
            if (first == null || m.position().distanceToSqr(p) < first.position().distanceToSqr(p)) first = m;
            n++;
        }
        if (first != null) first.bolt(p, true);
        int count = n;
        src.sendSuccess(() -> Component.literal(count + " stilt mantis(es) within 24 blocks; the nearest startles, its herd bolts after it"), false);
        return n;
    }

    private static int refill(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) return 0;
        int in = MantisRefill.lookRound(src.getLevel(), p, true);
        src.sendSuccess(() -> Component.literal(in + " mantis(es) moved back into short herds 48 to 128 blocks off, out of sight"), false);
        return in;
    }
}
