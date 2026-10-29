package com.explorationremix.devtools;

import com.explorationremix.registry.ERTags;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /remix census [radius]}: how overgrown and how lit the caves around you are, per biome, to tune the plant
 * counts in tools/worldgen.py against the targets of the vegetation design. It looks at the loaded chunks within radius
 * chunks of you (3 if not given) through your whole layer (48 blocks up and down above the first band), at the dry
 * open blocks: air, or a plant tagged {@code explorationremix:plants}. Of those it counts
 * <ul>
 *     <li>the floor (on a solid top that is not a plant) and the share of it holding a plant,</li>
 *     <li>the ceiling (under a solid underside that is not a plant) and the share with something hanging from it,</li>
 *     <li>the share of the floor with block light 4 or more, and 8 or more,</li>
 *     <li>the light sources among them, per thousand.</li>
 * </ul>
 * Water, lava and waterlogged plants are left out, so a lake bed counts neither as bare nor as overgrown floor. Biomes
 * with fewer than 200 open blocks in range are not listed.
 */
public final class PlantCensus {
    private PlantCensus() {}

    private static final int FLOOR = 0, FLOOR_PLANT = 1, CEILING = 2, CEILING_PLANT = 3, LIT_4 = 4, LIT_8 = 5, OPEN = 6, LIGHTS = 7;

    /** The command, which RemixCommands adds under /remix. */
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("census").executes(c -> census(c, 3))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8)).executes(c -> census(c, IntegerArgumentType.getInteger(c, "radius"))));
    }

    private static int census(CommandContext<CommandSourceStack> c, int radius) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 at = c.getSource().getPosition();
        int px = (int) Math.floor(at.x), py = (int) Math.floor(at.y), pz = (int) Math.floor(at.z);
        int y0 = py - 48, y1 = py + 48;
        Terrain t = DeepWorld.terrain();
        int li = t != null && DeepWorld.installed() ? t.shape.layerAt(py) : -1;
        if (li >= 0) {
            y0 = Math.min(t.shape.top(li), t.shape.bottom(li));
            y1 = Math.max(t.shape.top(li), t.shape.bottom(li));
        }
        y0 = Math.max(y0, level.getMinBuildHeight() + 1);
        y1 = Math.min(y1, level.getMaxBuildHeight() - 2);
        Map<String, long[]> stats = new TreeMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int chunks = 0;
        for (int cx = (px >> 4) - radius; cx <= (px >> 4) + radius; cx++) {
            for (int cz = (pz >> 4) - radius; cz <= (pz >> 4) + radius; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                chunks++;
                for (int x = cx << 4; x < (cx << 4) + 16; x++) for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                    for (int y = y0; y <= y1; y++) {
                        BlockState s = chunk.getBlockState(m.set(x, y, z));
                        boolean plant = s.is(ERTags.PLANTS);
                        if (!plant && !s.isAir() || !s.getFluidState().isEmpty()) continue;
                        String biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
                                .unwrapKey().map(k -> k.location().getPath()).orElse("?");
                        long[] a = stats.computeIfAbsent(biome, k -> new long[8]);
                        a[OPEN]++;
                        if (s.getLightEmission(chunk, m) > 0) a[LIGHTS]++;
                        BlockState below = chunk.getBlockState(m.set(x, y - 1, z));
                        if (!below.is(ERTags.PLANTS) && below.isFaceSturdy(chunk, m, Direction.UP)) {
                            a[FLOOR]++;
                            if (plant) a[FLOOR_PLANT]++;
                            int light = level.getBrightness(LightLayer.BLOCK, m.set(x, y, z));
                            if (light >= 4) a[LIT_4]++;
                            if (light >= 8) a[LIT_8]++;
                        }
                        BlockState above = chunk.getBlockState(m.set(x, y + 1, z));
                        if (!above.is(ERTags.PLANTS) && above.isFaceSturdy(chunk, m, Direction.DOWN)) {
                            a[CEILING]++;
                            if (plant) a[CEILING_PLANT]++;
                        }
                    }
                }
            }
        }
        say(c, String.format(Locale.ROOT, "Census of %d loaded chunks, y %d to %d:", chunks, y0, y1));
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            long[] a = e.getValue();
            if (a[OPEN] < 200) continue;
            say(c, String.format(Locale.ROOT, "  %-18s floor %6d: %3.0f%% plants, lit 4+ %3.0f%%, 8+ %3.0f%% | ceiling %6d: %3.0f%% hung | %.1f lights per 1000 open",
                    e.getKey(), a[FLOOR], pct(a[FLOOR_PLANT], a[FLOOR]), pct(a[LIT_4], a[FLOOR]), pct(a[LIT_8], a[FLOOR]),
                    a[CEILING], pct(a[CEILING_PLANT], a[CEILING]), 1000.0 * a[LIGHTS] / a[OPEN]));
        }
        return 1;
    }

    private static double pct(long part, long whole) { return whole == 0 ? 0 : 100.0 * part / whole; }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
