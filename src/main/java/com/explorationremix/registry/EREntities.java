package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.MossbackDen;
import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.entity.LavaBomb;
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

/**
 * The mod's entities: the Upper Caves' creatures, their spawn eggs and drops, and the dens that keep them; and the
 * Caldera's lava bombs (0.5.1). The creatures live in the Upper Caves' dens only: nothing lives in the Basalt Galleries.
 */
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

    /** A lava bomb thrown by an eruption (world.eruption.Eruptions): bursts where it lands. Seen from 10 chunks away. */
    public static final RegistryObject<EntityType<LavaBomb>> LAVA_BOMB = ENTITIES.register("lava_bomb", () ->
            EntityType.Builder.<LavaBomb>of(LavaBomb::new, MobCategory.MISC).sized(0.6F, 0.6F).clientTrackingRange(10).updateInterval(2)
                    .fireImmune().build(ExplorationRemix.MOD_ID + ":lava_bomb"));

    public static void onAttributes(EntityAttributeCreationEvent e) {
        e.put(MOSSBACK.get(), MossbackEntity.attributes().build());
    }
}
