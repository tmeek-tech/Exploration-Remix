package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The ambient sound events the biomes play, the lava cycle's warnings, and the quakes and eruptions (sounds.json,
 * written by tools/sounds.py, says which files).
 */
public final class ERSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ExplorationRemix.MOD_ID);

    private ERSounds() {}

    /** The Basalt Galleries' lava cycle (client.LavaWarnings): a rumble through the rock before the rise, the rise's surge. */
    public static final RegistryObject<SoundEvent> LAVA_RUMBLE = register("lava.rumble");
    public static final RegistryObject<SoundEvent> LAVA_SURGE = register("lava.surge");
    /** 0.5.1: a quake's long rumble (client.QuakeEffects) and the thud of ceiling basalt landing (world.quake.Quakes). */
    public static final RegistryObject<SoundEvent> QUAKE_RUMBLE = register("quake.rumble");
    public static final RegistryObject<SoundEvent> QUAKE_DEBRIS = register("quake.debris");
    /** 0.5.1: an eruption's burst and its roar (client.EruptionEffects). */
    public static final RegistryObject<SoundEvent> ERUPTION_BOOM = register("eruption.boom");
    public static final RegistryObject<SoundEvent> ERUPTION_ROAR = register("eruption.roar");

    public static final RegistryObject<SoundEvent> UPPER_LOOP = register("ambient.upper.loop");
    public static final RegistryObject<SoundEvent> UPPER_ADDITIONS = register("ambient.upper.additions");
    public static final RegistryObject<SoundEvent> GARDENS_LOOP = register("ambient.gardens.loop");
    public static final RegistryObject<SoundEvent> GARDENS_ADDITIONS = register("ambient.gardens.additions");
    public static final RegistryObject<SoundEvent> SPRINGS_LOOP = register("ambient.springs.loop");
    public static final RegistryObject<SoundEvent> BLUE_LOOP = register("ambient.blue.loop");
    public static final RegistryObject<SoundEvent> BLUE_ADDITIONS = register("ambient.blue.additions");
    public static final RegistryObject<SoundEvent> BASALT_LOOP = register("ambient.basalt.loop");
    public static final RegistryObject<SoundEvent> BASALT_ADDITIONS = register("ambient.basalt.additions");
    // 0.5: the Ash Hollows, the Wasteland's refuge, hear the rock only from far off
    public static final RegistryObject<SoundEvent> HOLLOWS_LOOP = register("ambient.hollows.loop");
    public static final RegistryObject<SoundEvent> HOLLOWS_ADDITIONS = register("ambient.hollows.additions");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ExplorationRemix.MOD_ID, name)));
    }
}
