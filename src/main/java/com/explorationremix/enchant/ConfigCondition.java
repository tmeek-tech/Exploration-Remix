package com.explorationremix.enchant;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERConfig;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;

/** Recipe condition {@code {"type": "explorationremix:config", "key": "..."}}: true when that config switch is on. */
public record ConfigCondition(String key) implements ICondition {
    public static final ResourceLocation ID = new ResourceLocation(ExplorationRemix.MOD_ID, "config");

    @Override
    public ResourceLocation getID() { return ID; }

    @Override
    public boolean test(IContext context) {
        return switch (key) {
            case "disableEnchantingTable" -> ERConfig.INSTANCE.disableEnchantingTable.get();
            case "stripLootEnchantments" -> ERConfig.INSTANCE.stripLootEnchantments.get();
            case "worldEnabled" -> ERConfig.INSTANCE.worldEnabled.get();
            default -> {
                ExplorationRemix.LOGGER.warn("Unknown config condition key '{}', treating it as true", key);
                yield true;
            }
        };
    }

    public static final class Serializer implements IConditionSerializer<ConfigCondition> {
        public static final Serializer INSTANCE = new Serializer();
        private Serializer() {}

        @Override
        public void write(JsonObject json, ConfigCondition value) { json.addProperty("key", value.key()); }

        @Override
        public ConfigCondition read(JsonObject json) { return new ConfigCondition(GsonHelper.getAsString(json, "key")); }

        @Override
        public ResourceLocation getID() { return ID; }
    }
}
