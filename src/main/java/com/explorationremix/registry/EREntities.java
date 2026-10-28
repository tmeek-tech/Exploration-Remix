package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.entity.AmbusherEntity;
import com.explorationremix.creature.entity.ArmoredEntity;
import com.explorationremix.creature.entity.BurrowerEntity;
import com.explorationremix.creature.entity.ClimberEntity;
import com.explorationremix.creature.entity.FlockerEntity;
import com.explorationremix.creature.entity.SmasherEntity;
import com.explorationremix.creature.entity.SpitProjectile;
import com.explorationremix.creature.entity.SpitterEntity;
import com.explorationremix.creature.entity.SwarmerEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class EREntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ExplorationRemix.MOD_ID);
    private EREntities() {}

    private static <T extends Mob> RegistryObject<EntityType<T>> mob(String id, EntityType.EntityFactory<T> factory, MobCategory category,
                                                                      float w, float h, boolean fireImmune, int bg, int fg) {
        RegistryObject<EntityType<T>> ro = ENTITIES.register(id, () -> {
            EntityType.Builder<T> b = EntityType.Builder.of(factory, category).sized(w, h).clientTrackingRange(10);
            if (fireImmune) b = b.fireImmune();
            return b.build(id);
        });
        ERBlocks.ITEMS.register(id + "_spawn_egg", () -> new ForgeSpawnEggItem(ro, bg, fg, new Item.Properties()));
        return ro;
    }

    public static final RegistryObject<EntityType<FlockerEntity>> FLOCKER = mob("flocker", FlockerEntity::new, MobCategory.AMBIENT, 0.4F, 0.3F, false, 0x343242, 0xFFE678);
    public static final RegistryObject<EntityType<SwarmerEntity>> SWARMER = mob("swarmer", SwarmerEntity::new, MobCategory.MONSTER, 0.7F, 0.5F, false, 0xD6C4A0, 0x78603C);
    public static final RegistryObject<EntityType<ArmoredEntity>> ARMORED = mob("armored", ArmoredEntity::new, MobCategory.MONSTER, 1.3F, 1.0F, false, 0x545860, 0xFF963C);
    public static final RegistryObject<EntityType<SpitterEntity>> SPITTER = mob("spitter", SpitterEntity::new, MobCategory.MONSTER, 0.8F, 1.0F, false, 0x788C46, 0xFFF078);
    public static final RegistryObject<EntityType<ClimberEntity>> CLIMBER = mob("climber", ClimberEntity::new, MobCategory.MONSTER, 1.0F, 0.9F, false, 0x463428, 0xFFBE3C);
    public static final RegistryObject<EntityType<AmbusherEntity>> AMBUSHER = mob("ambusher", AmbusherEntity::new, MobCategory.MONSTER, 1.4F, 1.6F, false, 0x161A2C, 0x283C5A);
    public static final RegistryObject<EntityType<BurrowerEntity>> BURROWER = mob("burrower", BurrowerEntity::new, MobCategory.MONSTER, 0.9F, 0.9F, true, 0x783C28, 0xFF783C);
    public static final RegistryObject<EntityType<SmasherEntity>> SMASHER = mob("smasher", SmasherEntity::new, MobCategory.MONSTER, 2.0F, 1.9F, true, 0x28262A, 0xFF6E28);
    public static final RegistryObject<EntityType<SpitProjectile>> SPIT = ENTITIES.register("spit",
            () -> EntityType.Builder.<SpitProjectile>of(SpitProjectile::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10).build("spit"));

    public static void onAttributes(EntityAttributeCreationEvent e) {
        e.put(FLOCKER.get(), FlockerEntity.attributes().build());
        e.put(SWARMER.get(), SwarmerEntity.attributes().build());
        e.put(ARMORED.get(), ArmoredEntity.attributes().build());
        e.put(SPITTER.get(), SpitterEntity.attributes().build());
        e.put(CLIMBER.get(), ClimberEntity.attributes().build());
        e.put(AMBUSHER.get(), AmbusherEntity.attributes().build());
        e.put(BURROWER.get(), BurrowerEntity.attributes().build());
        e.put(SMASHER.get(), SmasherEntity.attributes().build());
    }
}
