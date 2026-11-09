package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.FireflySwarm;
import com.explorationremix.creature.MossbackDen;
import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.creature.SkeinEntity;
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
 * The mod's entities: the creatures, their spawn eggs and drops, and the dens that keep the Mossbacks; and the
 * Caldera's lava bombs (0.5.1). The Mossbacks live in the Upper Caves' dens only, the Skein and the blue fireflies in the
 * Blue Reaches only (creature.BlueReachesSpawner): nothing lives in the Basalt Galleries.
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

    // The Skein, the Blue Reaches' crawler: its head's box 1.6 wide and 0.7 tall (the body is its sections, along its
    // trail); sent every tick, so the long body pours along its path on the client without a stutter.
    public static final RegistryObject<EntityType<SkeinEntity>> SKEIN = ENTITIES.register("skein",
            () -> EntityType.Builder.of(SkeinEntity::new, MobCategory.MONSTER).sized(1.6F, 0.7F).clientTrackingRange(10).updateInterval(1).build("skein"));

    public static final RegistryObject<Item> SKEIN_SPAWN_EGG = ERBlocks.ITEMS.register("skein_spawn_egg",
            () -> new ForgeSpawnEggItem(SKEIN, 0x1d3d96, 0x8fe8ff, new Item.Properties()));

    /** What a Skein leaves: a plate of its shell, kept for crafting later. */
    public static final RegistryObject<Item> SKEIN_PLATE = ERBlocks.ITEMS.register("skein_plate", () -> new Item(new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<MossbackDen.Tile>> DEN_TILE = BLOCK_ENTITIES.register("mossback_den",
            () -> BlockEntityType.Builder.of(MossbackDen.Tile::new, ERBlocks.MOSSBACK_DEN.get()).build(null));

    // ---- the blue fireflies (creature.FireflySwarm): one entity is a whole swarm, and no mob, so MISC (not AMBIENT,
    // and never a monster): it counts against no mob cap, so it takes no place from the bats of its own caves, and
    // vanilla asks no attributes of it. It despawns by its own rules (FireflySwarm.checkDespawn). Its box is only what
    // a swing can hit (it blocks nothing); clients track it from eight chunks off, as its lights show far.
    public static final RegistryObject<EntityType<FireflySwarm>> FIREFLY_SWARM = ENTITIES.register("firefly_swarm",
            () -> EntityType.Builder.<FireflySwarm>of(FireflySwarm::new, MobCategory.MISC).sized(0.6F, 0.6F).clientTrackingRange(8).build("firefly_swarm"));

    /**
     * A lava bomb thrown by an eruption (world.eruption.Eruptions): bursts where it lands. Seen from 10 chunks away, and
     * never saved: one in a chunk that unloads is gone, never reloaded to burst after a reset.
     */
    public static final RegistryObject<EntityType<LavaBomb>> LAVA_BOMB = ENTITIES.register("lava_bomb", () ->
            EntityType.Builder.<LavaBomb>of(LavaBomb::new, MobCategory.MISC).sized(0.6F, 0.6F).clientTrackingRange(10).updateInterval(2)
                    .fireImmune().noSave().build(ExplorationRemix.MOD_ID + ":lava_bomb"));

    public static void onAttributes(EntityAttributeCreationEvent e) {
        e.put(MOSSBACK.get(), MossbackEntity.attributes().build());
        // the Skein
        e.put(SKEIN.get(), SkeinEntity.attributes().build());
    }
}
