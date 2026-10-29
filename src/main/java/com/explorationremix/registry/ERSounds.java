package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The ambient sound events the biomes play (sounds.json, written by tools/sounds.py, says which files). */
public final class ERSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ExplorationRemix.MOD_ID);

    private ERSounds() {}

    public static final RegistryObject<SoundEvent> UPPER_LOOP = register("ambient.upper.loop");
    public static final RegistryObject<SoundEvent> UPPER_ADDITIONS = register("ambient.upper.additions");
    public static final RegistryObject<SoundEvent> GARDENS_LOOP = register("ambient.gardens.loop");
    public static final RegistryObject<SoundEvent> GARDENS_ADDITIONS = register("ambient.gardens.additions");
    public static final RegistryObject<SoundEvent> SPRINGS_LOOP = register("ambient.springs.loop");
    public static final RegistryObject<SoundEvent> BLUE_LOOP = register("ambient.blue.loop");
    public static final RegistryObject<SoundEvent> BLUE_ADDITIONS = register("ambient.blue.additions");
    public static final RegistryObject<SoundEvent> BASALT_LOOP = register("ambient.basalt.loop");
    public static final RegistryObject<SoundEvent> BASALT_ADDITIONS = register("ambient.basalt.additions");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ExplorationRemix.MOD_ID, name)));
    }
}
