package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.entity.RuneBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ERBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ExplorationRemix.MOD_ID);

    private ERBlockEntities() {}

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<RuneBlockEntity>> RUNE_BLOCK = BLOCK_ENTITIES.register("rune_block",
            () -> BlockEntityType.Builder.of(RuneBlockEntity::new, ERBlocks.RUNE_BLOCK.get()).build(null));
}
