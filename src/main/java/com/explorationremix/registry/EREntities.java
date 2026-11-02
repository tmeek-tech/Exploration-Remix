package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.MossbackDen;
import com.explorationremix.creature.MossbackEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's creatures, their spawn eggs and drops, and the dens that keep them. */
public final class EREntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExplorationRemix.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ExplorationRemix.MOD_ID);

    private EREntities() {}

    /** The Upper Caves' lizard: a box 1.4 wide (too wide for a one-block gap) and 0.9 tall. */
    public static final RegistryObject<EntityType<MossbackEntity>> MOSSBACK = ENTITIES.register("mossback",
            () -> EntityType.Builder.of(MossbackEntity::new, MobCategory.MONSTER).sized(1.4F, 0.9F).clientTrackingRange(10).build("mossback"));

    public static final RegistryObject<Item> MOSSBACK_SPAWN_EGG = ERBlocks.ITEMS.register("mossback_spawn_egg",
            () -> new ForgeSpawnEggItem(MOSSBACK, 0x2e3e20, 0xc4ec50, new Item.Properties()));

    /** What a Mossback leaves: its hide, kept for crafting later. */
    public static final RegistryObject<Item> MOSSBACK_HIDE = ERBlocks.ITEMS.register("mossback_hide", () -> new Item(new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<MossbackDen.Tile>> DEN_TILE = BLOCK_ENTITIES.register("mossback_den",
            () -> BlockEntityType.Builder.of(MossbackDen.Tile::new, ERBlocks.MOSSBACK_DEN.get()).build(null));

    public static void onAttributes(EntityAttributeCreationEvent e) {
        e.put(MOSSBACK.get(), MossbackEntity.attributes().build());
    }
}
