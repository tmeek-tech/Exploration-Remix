package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.entity.LavaBomb;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's entities: the Caldera's lava bombs (0.5.1). No creatures: nothing lives in the Basalt Galleries. */
public final class EREntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExplorationRemix.MOD_ID);

    private EREntities() {}

    /** A lava bomb thrown by an eruption (world.eruption.Eruptions): bursts where it lands. Seen from 10 chunks away. */
    public static final RegistryObject<EntityType<LavaBomb>> LAVA_BOMB = ENTITIES.register("lava_bomb", () ->
            EntityType.Builder.<LavaBomb>of(LavaBomb::new, MobCategory.MISC).sized(0.6F, 0.6F).clientTrackingRange(10).updateInterval(2)
                    .fireImmune().build(ExplorationRemix.MOD_ID + ":lava_bomb"));
}
