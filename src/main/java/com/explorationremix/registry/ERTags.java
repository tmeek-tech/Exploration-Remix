package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

public final class ERTags {
    private ERTags() {}

    public static final TagKey<Item> RUNE_HOLDERS = TagKey.create(Registries.ITEM, id("rune_holders"));
    public static final TagKey<Block> LAYER_STONE = TagKey.create(Registries.BLOCK, id("layer_stone"));
    public static final TagKey<Biome> IS_DEEP = TagKey.create(Registries.BIOME, id("is_deep"));
    public static final TagKey<Biome> IS_REFUGE = TagKey.create(Registries.BIOME, id("is_refuge"));
    public static final TagKey<Biome> IS_HUNTING = TagKey.create(Registries.BIOME, id("is_hunting"));
    public static final TagKey<Biome> IS_UPPER_CAVE = TagKey.create(Registries.BIOME, id("is_upper_cave"));
    public static final TagKey<EntityType<?>> HUNTERS = TagKey.create(Registries.ENTITY_TYPE, id("hunters"));
    public static final TagKey<EntityType<?>> NEST_CREATURES = TagKey.create(Registries.ENTITY_TYPE, id("nest_creatures"));
    public static final TagKey<EntityType<?>> REMIX_CREATURES = TagKey.create(Registries.ENTITY_TYPE, id("remix_creatures"));

    private static ResourceLocation id(String path) { return new ResourceLocation(ExplorationRemix.MOD_ID, path); }
}
