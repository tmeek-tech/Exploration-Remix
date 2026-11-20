package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.FireflySwarm;
import com.explorationremix.creature.GiantSkeinEntity;
import com.explorationremix.creature.MossbackDen;
import com.explorationremix.creature.MossbackEntity;
import com.explorationremix.creature.SkeinEntity;
import com.explorationremix.creature.StiltMantisEntity;
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
 * Blue Reaches only (creature.BlueReachesSpawner), the stilt mantis herds in the Deep Gardens' groves: nothing lives in
 * the Basalt Galleries.
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

    // The giant Skein of the Deep Blue Rift (0.18.8): one to a rift, on its walls. Its head's box 2.4 wide and 1.2 tall
    // (3 x 2 x 3 cells to its climbing pathfinder; the 30-block body is its sections, along its trail); sent every
    // tick, and tracked from sixteen chunks off (as far as the server lets), so its line of lights shows across the rift.
    public static final RegistryObject<EntityType<GiantSkeinEntity>> GIANT_SKEIN = ENTITIES.register("giant_skein",
            () -> EntityType.Builder.of(GiantSkeinEntity::new, MobCategory.MONSTER).sized(2.4F, 1.2F).clientTrackingRange(16).updateInterval(1)
                    .build("giant_skein"));

    public static final RegistryObject<Item> GIANT_SKEIN_SPAWN_EGG = ERBlocks.ITEMS.register("giant_skein_spawn_egg",
            () -> new ForgeSpawnEggItem(GIANT_SKEIN, 0x7fa4d8, 0xdce9ff, new Item.Properties()));

    /** What the giant Skein leaves: its long spines (and Skein plates), kept for crafting later. */
    public static final RegistryObject<Item> GIANT_SKEIN_SPINE = ERBlocks.ITEMS.register("giant_skein_spine", () -> new Item(new Item.Properties()));

    // The stilt mantis of the Deep Gardens (0.20.0): a tall grazer on stilt legs, in herds in the groves of glow mats.
    // Its box is narrow and tall (1.2 wide, 2.9 to the top of its head; its legs reach a block and a half past it, drawn
    // from its body), so its paths keep to rooms two blocks wide and three high. MISC, as a villager: it counts against
    // no mob cap and vanilla never spawns it; the herds are set down with the world (world.feature.StiltMantisesFeature).
    public static final RegistryObject<EntityType<StiltMantisEntity>> STILT_MANTIS = ENTITIES.register("stilt_mantis",
            () -> EntityType.Builder.of(StiltMantisEntity::new, MobCategory.MISC).sized(1.2F, 2.9F).clientTrackingRange(10).build("stilt_mantis"));

    public static final RegistryObject<Item> STILT_MANTIS_SPAWN_EGG = ERBlocks.ITEMS.register("stilt_mantis_spawn_egg",
            () -> new ForgeSpawnEggItem(STILT_MANTIS, 0x2b2350, 0xb46cff, new Item.Properties()));

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
        e.put(GIANT_SKEIN.get(), GiantSkeinEntity.attributes().build());
        e.put(STILT_MANTIS.get(), StiltMantisEntity.attributes().build());
    }
}
