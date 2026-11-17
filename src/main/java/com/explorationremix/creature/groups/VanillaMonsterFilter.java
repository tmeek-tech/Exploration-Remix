package com.explorationremix.creature.groups;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Takes vanilla's own scattered monster spawns off in the overworld, so its hostile mobs come out only in the groups of
 * {@link MobGroupSpawner}: each spawn try's list loses vanilla's monsters that come from the biome's own list
 * ({@link MobGroupRules#takenOff}). A structure's own spawns (witch huts, ocean monuments, pillager outposts) are other
 * entries and stay, as do other mods' monsters; the Nether and the End are untouched, and so are spawners, patrols,
 * phantoms and sieges, which do not go through these lists.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class VanillaMonsterFilter {
    private VanillaMonsterFilter() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns e) {
        if (e.getMobCategory() != MobCategory.MONSTER || e.getSpawnerDataList().isEmpty()) return;
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !MobGroupSpawner.enabled()) return;
        List<MobSpawnSettings.SpawnerData> own = level.getBiome(e.getPos()).value().getMobSettings().getMobs(MobCategory.MONSTER).unwrap();
        if (own.isEmpty()) return;
        for (MobSpawnSettings.SpawnerData d : List.copyOf(e.getSpawnerDataList())) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(d.type);
            if (id != null && MobGroupRules.takenOff(id.getNamespace(), d.type.getCategory() == MobCategory.MONSTER, containsSame(own, d))) e.removeSpawnerData(d);
        }
    }

    /**
     * Monsters that still came out in the overworld by a natural spawn other than the groups' own since the server
     * started, by kind and biome, for {@code /remix groups stats}: a structure's own (a witch hut's witches) and other
     * mods' are expected, any of vanilla's own scattered ones would be a gap in the filter.
     */
    static final Map<String, Integer> OTHERS = new TreeMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn e) {
        if (e.getSpawnType() != MobSpawnType.NATURAL || MobGroupSpawner.placing || e.isSpawnCancelled() || !(e.getEntity() instanceof Enemy)) return;
        ServerLevel level = e.getLevel().getLevel();
        if (level.dimension() != Level.OVERWORLD) return;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType());
        String biome = level.getBiome(e.getEntity().blockPosition()).unwrapKey().map(k -> k.location().getPath()).orElse("?");
        OTHERS.merge((id == null ? "?" : id.getPath()) + " in " + biome, 1, Integer::sum);
    }

    /** Whether {@code list} holds this very entry (the biome's own), not just one like it. */
    private static boolean containsSame(List<MobSpawnSettings.SpawnerData> list, MobSpawnSettings.SpawnerData d) {
        for (MobSpawnSettings.SpawnerData o : list) if (o == d) return true;
        return false;
    }
}
