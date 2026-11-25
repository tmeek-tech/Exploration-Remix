package com.explorationremix.devtools;

import com.explorationremix.block.KeySocketBlock;
import com.explorationremix.creature.choir.ChoirSummon;
import com.explorationremix.creature.choir.HollowChoirEntity;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERItems;
import com.explorationremix.world.feature.ChoirArena;
import com.explorationremix.world.feature.ChoirArenaFeature;
import com.explorationremix.world.feature.ChoirReliquaryFeature;
import com.explorationremix.world.terrain.ChoirSites;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The dev tools for the Hollow Choir (0.21.9, {@code /remix choir}): what the arena near you is and how it stands,
 * the way to it and to the nearest ancient city's heart, the keys in hand, the summoning, and the arena dressed for
 * any phase so the look of each can be checked without fighting through to it.
 *
 * <p>These are the only way to reach a boss fight quickly in a test world, so the in-pack rig leans on them.
 * {@code choir}, {@code choir summon} and {@code choir phase} ask only where the command came from, so the rig can
 * drive a whole fight from the server console with {@code execute positioned <x> <y> <z> run remix choir ...} and no
 * player on the server at all. Only {@code tp}, {@code city} and {@code keys} need one, since they move a player or
 * put something in their hands. {@code choir cut} (0.22.0) parts every standing tendril, which is how the rig gets to
 * the open core and through the phases in a scripted fight.
 *
 * <p>For the fight's warnings (0.22.3): {@code choir act <blow>} has the Choir strike that blow next, and
 * {@code choir pose <blow> <tick>} holds a blow still at one tick of its clock, so a picture can be taken of exactly
 * what a player is shown at that moment ({@code choir pose off} lets it go). {@code choir phase} sets a Choir that is
 * up to the start of that phase as well as dressing its arena, so the later phases' blows can be seen without the
 * minutes of fighting that lead to them.
 */
public final class ChoirCommands {
    /** How many cells out an arena or a city is looked for (each is 600 blocks across). */
    private static final int CELLS = 2;

    private ChoirCommands() {}

    /** The {@code choir} branch of /remix. */
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("choir")
                .executes(ChoirCommands::report)
                .then(Commands.literal("tp").executes(ChoirCommands::tp))
                .then(Commands.literal("city").executes(ChoirCommands::tpCity))
                .then(Commands.literal("keys").executes(ChoirCommands::keys))
                .then(Commands.literal("summon").executes(ChoirCommands::summon))
                .then(Commands.literal("cut").executes(ChoirCommands::cut))
                .then(blows(Commands.literal("act"), false))
                .then(blows(Commands.literal("pose"), true)
                        .then(Commands.literal("hold").executes(ChoirCommands::hold))
                        .then(Commands.literal("off").executes(c -> pose(c, -1, 0))))
                .then(Commands.literal("phase").then(Commands.argument("phase", IntegerArgumentType.integer(0, 3))
                        .executes(c -> phase(c, IntegerArgumentType.getInteger(c, "phase")))));
    }

    /** The blows by name, each to be struck now ({@code act}) or held at a tick of its clock ({@code pose}). */
    private static LiteralArgumentBuilder<CommandSourceStack> blows(LiteralArgumentBuilder<CommandSourceStack> on, boolean held) {
        String[] names = {"windup", "slam", "toll", "grab", "bloom"};
        int[] kinds = {HollowChoirEntity.ACT_WINDUP, HollowChoirEntity.ACT_SLAM, HollowChoirEntity.ACT_TOLL,
                HollowChoirEntity.ACT_GRAB, HollowChoirEntity.ACT_BLOOM};
        for (int i = 0; i < names.length; i++) {
            int kind = kinds[i];
            if (held) on.then(Commands.literal(names[i]).then(Commands.argument("tick", IntegerArgumentType.integer(0, 200))
                    .executes(c -> pose(c, kind, IntegerArgumentType.getInteger(c, "tick")))));
            else if (kind != HollowChoirEntity.ACT_SLAM) on.then(Commands.literal(names[i]).executes(c -> act(c, kind, 0))
                    .then(Commands.literal("in").then(Commands.argument("ticks", IntegerArgumentType.integer(1, 600))
                            .executes(c -> act(c, kind, IntegerArgumentType.getInteger(c, "ticks"))))));
        }
        return on;
    }

    // -------------------------------------------------------------------------------------------------- the lookups

    @Nullable
    private static Terrain terrain(CommandContext<CommandSourceStack> c) {
        Terrain t = com.explorationremix.world.DeepWorld.terrain();
        if (t == null || !com.explorationremix.world.DeepWorld.installed()) {
            c.getSource().sendFailure(Component.literal("The deep world is not installed in this world (see the server log)."));
            return null;
        }
        return t;
    }

    /**
     * Where the command is asked from: the player's place, or, from the server console or a command block, whatever
     * {@code execute positioned} put the source at. The reports and the dressing ask only for this, so the in-pack
     * rig can drive the whole arena from the console without a player on the server.
     */
    private static net.minecraft.world.phys.Vec3 at(CommandContext<CommandSourceStack> c) {
        return c.getSource().getPosition();
    }

    /** The level the arena is in: always the overworld, whichever level the source is in. */
    @Nullable
    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        return c.getSource().getServer().getLevel(Level.OVERWORLD);
    }

    /** The arena nearest the player, searched out to {@link #CELLS} cells, or null. */
    @Nullable
    private static ChoirSites.Site nearest(Terrain t, double x, double z) {
        int cx = t.plans.cellOf(x), cz = t.plans.cellOf(z);
        ChoirSites.Site best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -CELLS; dx <= CELLS; dx++)
            for (int dz = -CELLS; dz <= CELLS; dz++) {
                ChoirSites.Site s = t.network.choirSite(cx + dx, cz + dz);
                if (s == null) continue;
                double d = Math.hypot(s.x() - x, s.z() - z);
                if (d < bd) { bd = d; best = s; }
            }
        return best;
    }

    // -------------------------------------------------------------------------------------------------- the reports

    private static int report(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        net.minecraft.world.phys.Vec3 from = at(c);
        ChoirSites.Site s = nearest(t, from.x, from.z);
        if (s == null) {
            c.getSource().sendSuccess(() -> Component.literal("No Hollow Choir arena within " + CELLS + " cells. About one"
                    + " Blue Reaches cell in " + 3 + " has one; try /remix choir tp from somewhere else."), false);
            return 0;
        }
        ServerLevel level = overworld(c);
        int filled = level == null ? -1 : ChoirSummon.filled(level, s);
        HollowChoirEntity fighting = level == null ? null : ChoirSummon.fighting(level, s);
        double d = Math.hypot(s.x() - from.x, s.z() - from.z);
        StringBuilder out = new StringBuilder();
        out.append(String.format("Arena of cell %d, %d: middle %.0f, %.0f, bowl floor %.0f, rim %.0f, ceiling %.0f",
                s.cellX(), s.cellZ(), s.x(), s.z(), s.floorY(), s.rimY(), s.ceil()));
        out.append(String.format("%n  tier %d, %.0f blocks from here, first socket at %.0f degrees",
                s.tier(), d, Math.toDegrees(s.turn())));
        out.append(String.format("%n  floor radius %.0f, rim radius %.0f, %d tiers of seats %.0f deep in all",
                ChoirSites.FLOOR_R, ChoirSites.RIM_R, ChoirSites.STEPS, ChoirSites.DEPTH));
        out.append("\n  sockets filled: ").append(filled).append(" of 3");
        if (fighting != null)
            out.append(String.format("%n  the Choir is up: phase %d, %d tendrils standing, %.0f of %.0f health, %d sprouts",
                    fighting.phase(), fighting.standingCount(), fighting.getHealth(), fighting.getMaxHealth(), fighting.sprouts()));
        for (int[] sk : ChoirArena.sockets(s)) out.append(String.format("%n  socket at %d, %.0f, %d", sk[0], s.rimY() + 1, sk[1]));
        // what generation has actually built since the server started, which is how a missing casket is told from a
        // city that was never generated
        out.append(String.format("%n  built so far: %d chunks of arena, %d city hearts generated, %d reliquaries set",
                ChoirArenaFeature.CHUNKS.get(), ChoirReliquaryFeature.HEARTS.get(), ChoirReliquaryFeature.SET.get()));
        String text = out.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // ----------------------------------------------------------------------------------------------- getting there

    private static int tp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerPlayer pl = c.getSource().getPlayerOrException();
        ChoirSites.Site s = nearest(t, pl.getX(), pl.getZ());
        if (s == null) {
            c.getSource().sendFailure(Component.literal("No Hollow Choir arena within " + CELLS + " cells."));
            return 0;
        }
        // on the rim walk, a sixth of a turn off the first socket, looking in across the bowl
        double a = s.turn() + Math.PI / 6;
        double x = s.x() + (ChoirSites.RIM_R - 1) * Math.cos(a), z = s.z() + (ChoirSites.RIM_R - 1) * Math.sin(a);
        float yaw = (float) (Math.toDegrees(Math.atan2(s.z() - z, s.x() - x)) - 90);
        pl.teleportTo(pl.serverLevel(), x, s.rimY() + 1, z, yaw, 10);
        c.getSource().sendSuccess(() -> Component.literal(String.format("On the rim of the arena of cell %d, %d.",
                s.cellX(), s.cellZ())), false);
        return 1;
    }

    private static int tpCity(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerPlayer pl = c.getSource().getPlayerOrException();
        int cx = t.plans.cellOf(pl.getX()), cz = t.plans.cellOf(pl.getZ());
        double[] best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -CELLS; dx <= CELLS; dx++)
            for (int dz = -CELLS; dz <= CELLS; dz++)
                for (double[] h : t.network.cityHearts(cx + dx, cz + dz)) {
                    double d = Math.hypot(h[0] - pl.getX(), h[1] - pl.getZ());
                    if (d < bd) { bd = d; best = h; }
                }
        if (best == null) {
            c.getSource().sendFailure(Component.literal("No planned ancient city within " + CELLS + " cells."));
            return 0;
        }
        double[] h = best;
        pl.teleportTo(pl.serverLevel(), h[0], h[2] + 2, h[1], pl.getYRot(), pl.getXRot());
        c.getSource().sendSuccess(() -> Component.literal(String.format(
                "At the heart of the city planned at %.0f, %.0f, %.0f. Its reliquary holds one choir key.",
                h[0], h[2], h[1])), false);
        return 1;
    }

    // ----------------------------------------------------------------------------------------------- the fight

    private static int keys(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        ItemStack keys = new ItemStack(ERItems.CHOIR_KEY.get(), 3);
        if (!pl.addItem(keys)) pl.drop(keys, false);
        c.getSource().sendSuccess(() -> Component.literal("Three choir keys."), false);
        return 1;
    }

    private static int summon(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerLevel level = overworld(c);
        net.minecraft.world.phys.Vec3 from = at(c);
        ChoirSites.Site s = level == null ? null : nearest(t, from.x, from.z);
        if (s == null) {
            c.getSource().sendFailure(Component.literal("No Hollow Choir arena within " + CELLS + " cells."));
            return 0;
        }
        if (ChoirSummon.fighting(level, s) != null) {
            c.getSource().sendFailure(Component.literal("The Choir of that arena is already up."));
            return 0;
        }
        // the three sockets filled as a player would fill them, then called
        for (int[] sk : ChoirArena.sockets(s)) {
            BlockPos p = new BlockPos(sk[0], (int) s.rimY() + 1, sk[1]);
            BlockState st = level.getBlockState(p);
            if (st.is(ERBlocks.KEY_SOCKET.get())) level.setBlock(p, st.setValue(KeySocketBlock.FILLED, true), 3);
        }
        ChoirSummon.summon(level, s, c.getSource().getEntity() instanceof ServerPlayer pl ? pl : null);
        c.getSource().sendSuccess(() -> Component.literal("Called the Hollow Choir of the arena of cell "
                + s.cellX() + ", " + s.cellZ() + "."), false);
        return 1;
    }

    /** Parts every tendril the nearest arena's Choir has standing, so its crown falls and its core opens. */
    private static int cut(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerLevel level = overworld(c);
        net.minecraft.world.phys.Vec3 from = at(c);
        ChoirSites.Site s = level == null ? null : nearest(t, from.x, from.z);
        HollowChoirEntity choir = s == null ? null : ChoirSummon.fighting(level, s);
        if (choir == null) {
            c.getSource().sendFailure(Component.literal("No Hollow Choir is up near here."));
            return 0;
        }
        int n = choir.devCutAll();
        c.getSource().sendSuccess(() -> Component.literal(n == 0 ? "It has no tendril standing: the crown is down or on its way."
                : "Cut " + n + " tendrils: the crown falls and the core opens."), false);
        return n;
    }

    /** The Choir that is up in the arena nearest the command, or null with the failure already said. */
    @Nullable
    private static HollowChoirEntity up(CommandContext<CommandSourceStack> c) {
        Terrain t = terrain(c);
        if (t == null) return null;
        ServerLevel level = overworld(c);
        net.minecraft.world.phys.Vec3 from = at(c);
        ChoirSites.Site s = level == null ? null : nearest(t, from.x, from.z);
        HollowChoirEntity choir = s == null ? null : ChoirSummon.fighting(level, s);
        if (choir == null) c.getSource().sendFailure(Component.literal("No Hollow Choir is up near here."));
        return choir;
    }

    /** Has the Choir strike a blow now: a slam (by its wind-up) at whoever is nearest, a toll, a grab or a bloom. */
    private static int act(CommandContext<CommandSourceStack> c, int kind, int in) {
        HollowChoirEntity choir = up(c);
        if (choir == null) return 0;
        if (!choir.devActIn(kind, in)) {
            c.getSource().sendFailure(Component.literal("It cannot strike that now: it is coming down, open or climbing,"
                    + " or (for a slam) nobody stands within a rooted tendril's reach."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(in > 0 ? "The Choir strikes in " + in + " ticks." : "The Choir strikes."), false);
        return 1;
    }

    /** Holds the fight as it stands, so one blow at a time can be staged. */
    private static int hold(CommandContext<CommandSourceStack> c) {
        HollowChoirEntity choir = up(c);
        if (choir == null) return 0;
        if (!choir.devHold()) {
            c.getSource().sendFailure(Component.literal("It cannot be held while it is still coming down."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("The Choir is held."), false);
        return 1;
    }

    /** Holds a blow still at a tick of its clock, or lets it go. */
    private static int pose(CommandContext<CommandSourceStack> c, int kind, int tick) {
        HollowChoirEntity choir = up(c);
        if (choir == null) return 0;
        if (!choir.devPose(kind, tick)) {
            c.getSource().sendFailure(Component.literal("It cannot be held in that now: it is coming down, open or"
                    + " climbing, or (for a slam) nobody stands within a rooted tendril's reach."));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(kind < 0 ? "The Choir is let go."
                : "The Choir is held at tick " + tick + " of that blow."), false);
        return 1;
    }

    private static int phase(CommandContext<CommandSourceStack> c, int phase) {
        Terrain t = terrain(c);
        if (t == null) return 0;
        ServerLevel level = overworld(c);
        net.minecraft.world.phys.Vec3 from = at(c);
        ChoirSites.Site s = level == null ? null : nearest(t, from.x, from.z);
        if (s == null) {
            c.getSource().sendFailure(Component.literal("No Hollow Choir arena within " + CELLS + " cells."));
            return 0;
        }
        ChoirSummon.dress(level, s, phase);
        // a Choir that is up goes to the start of that phase with it, its tendrils grown again
        HollowChoirEntity choir = ChoirSummon.fighting(level, s);
        if (choir != null && phase >= 1) choir.devPhase(phase);
        c.getSource().sendSuccess(() -> Component.literal(phase == 0
                ? "The arena is dressed as it is at rest: braziers lit, the sculk back to the dais, no sprouts."
                : "The arena is dressed for phase " + phase + "."), false);
        return 1;
    }
}
