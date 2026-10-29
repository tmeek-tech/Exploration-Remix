package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes the mod's glowing plants and crystals glow under Complementary shaders. A shader pack knows blocks only by the
 * ids its block.properties gives them, and lights every block it does not list like stone: under the shader a glowing
 * plant looked lit by a warm lamp instead of glowing in its own colour. Complementary keeps id 10028 for "modded light
 * sources", which glow in their texture's colour, and its block.properties invites mods' blocks into it. Oculus builds a
 * pack's block map each time a pack loads (WorldRenderingSettings.setBlockStateIds); a mixin hands the map here first.
 * Other shader packs give their ids other meanings, so their maps are left alone.
 */
public final class ShaderGlow {
    /** Complementary's id for modded light sources (block.10028 in its block.properties). */
    private static final int MODDED_LIGHT = 10028;
    private static Set<ResourceLocation> overlays;

    private ShaderGlow() {
    }

    /** Adds every state of the mod's blocks that gives light or draws a glow overlay, if the map is Complementary's. */
    public static void addTo(Object2IntMap<BlockState> ids) {
        if (!isComplementary(ids)) {
            return;
        }
        int added = 0;
        for (Block block : ForgeRegistries.BLOCKS) {
            ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null || !key.getNamespace().equals(ExplorationRemix.MOD_ID)) {
                continue;
            }
            boolean overlay = overlays().contains(key);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if ((overlay || state.getLightEmission() > 0) && !ids.containsKey(state)) {
                    ids.put(state, MODDED_LIGHT);
                    added++;
                }
            }
        }
        ExplorationRemix.LOGGER.info("Complementary shader pack: {} block states of the mod set to glow", added);
    }

    /** Complementary's own ids for two vanilla light sources; another pack giving both the same numbers is unlikely. */
    private static boolean isComplementary(Object2IntMap<BlockState> ids) {
        return ids.getInt(Blocks.SEA_LANTERN.defaultBlockState()) == 10448 && ids.getInt(Blocks.GLOW_LICHEN.defaultBlockState()) == 10544;
    }

    /** The blocks drawn with a glow overlay, listed by tools/gen_data.py. Some of them give no light (dusk vines). */
    private static Set<ResourceLocation> overlays() {
        if (overlays == null) {
            Set<ResourceLocation> out = new HashSet<>();
            try (InputStream in = ShaderGlow.class.getResourceAsStream("/assets/" + ExplorationRemix.MOD_ID + "/glowing_blocks.json")) {
                if (in != null) {
                    JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement e : json.getAsJsonArray("blocks")) {
                        out.add(new ResourceLocation(ExplorationRemix.MOD_ID, e.getAsString()));
                    }
                }
            } catch (Exception e) {
                ExplorationRemix.LOGGER.warn("Could not read the mod's list of glowing blocks", e);
            }
            overlays = out;
        }
        return overlays;
    }
}
