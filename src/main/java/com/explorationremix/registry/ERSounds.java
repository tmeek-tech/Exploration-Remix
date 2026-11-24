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
    /**
     * The quakes (client.QuakeEffects, 0.9.0; tools/synth_quake_sounds.py makes them, dry and with nothing under 80 Hz, so
     * they never sound like the lava's deep warning): a big quake's crack and long grinding of stone, a tremor's short
     * crackle, the crack overhead of a ceiling block about to drop, the creak of a build under a player, and the thud of
     * rock landing or breaking on someone.
     */
    public static final RegistryObject<SoundEvent> QUAKE_RUMBLE = register("quake.rumble");
    public static final RegistryObject<SoundEvent> QUAKE_TREMOR = register("quake.tremor");
    public static final RegistryObject<SoundEvent> QUAKE_CRACK = register("quake.crack");
    public static final RegistryObject<SoundEvent> QUAKE_CREAK = register("quake.creak");
    public static final RegistryObject<SoundEvent> QUAKE_DEBRIS = register("quake.debris");
    /**
     * The eruptions (client.EruptionEffects and client.BombEffects, 0.9.0; tools/synth_quake_sounds.py makes them): a
     * vent rumbling as it warns, the deep boom of its burst and the roar of its fountain; a lava bomb's whistle in
     * flight, its far blast, and lava hissing where it or a ceiling block falls into the lava.
     */
    public static final RegistryObject<SoundEvent> ERUPTION_RUMBLE = register("eruption.rumble");
    public static final RegistryObject<SoundEvent> ERUPTION_BOOM = register("eruption.boom");
    public static final RegistryObject<SoundEvent> ERUPTION_ROAR = register("eruption.roar");
    public static final RegistryObject<SoundEvent> LAVA_BOMB_WHISTLE = register("lava_bomb.whistle");
    public static final RegistryObject<SoundEvent> LAVA_BOMB_BURST = register("lava_bomb.burst");
    public static final RegistryObject<SoundEvent> LAVA_BOMB_FIZZ = register("lava_bomb.fizz");

    // Each layer's own ambience (0.20.9; tools/synth_layer_sounds.py makes the new files, tools/sounds.py says which).
    // The Upper Caves: crickets, played by the client (client.UpperCaveSounds) in the mod's upper caves, a stereo bed and a
    // cricket (two in a big cave) singing now and then on the rock near the player, which goes quiet as they come close;
    // the mod's upper caves' water drips (their biomes' additions).
    public static final RegistryObject<SoundEvent> UPPER_CRICKETS = register("ambient.upper.crickets");
    public static final RegistryObject<SoundEvent> UPPER_CRICKET = register("ambient.upper.cricket");
    public static final RegistryObject<SoundEvent> UPPER_ADDITIONS = register("ambient.upper.additions");
    // The frozen caves, cold and still: a thin draft, ice ticking and now and then singing
    public static final RegistryObject<SoundEvent> FROZEN_LOOP = register("ambient.frozen.loop");
    public static final RegistryObject<SoundEvent> FROZEN_ADDITIONS = register("ambient.frozen.additions");
    // The Deep Gardens' hush and a far vent's sigh; a live vent's simmer up close (block.VentCrownBlock)
    public static final RegistryObject<SoundEvent> GARDENS_LOOP = register("ambient.gardens.loop");
    public static final RegistryObject<SoundEvent> GARDENS_ADDITIONS = register("ambient.gardens.additions");
    public static final RegistryObject<SoundEvent> VENT_SIMMER = register("vent.simmer");
    public static final RegistryObject<SoundEvent> BLUE_LOOP = register("ambient.blue.loop");
    public static final RegistryObject<SoundEvent> BLUE_ADDITIONS = register("ambient.blue.additions");
    // The Galleries' low steady rumble and lava pops; the Wasteland hears the rumble fainter, with rolls far off
    public static final RegistryObject<SoundEvent> BASALT_LOOP = register("ambient.basalt.loop");
    public static final RegistryObject<SoundEvent> BASALT_ADDITIONS = register("ambient.basalt.additions");
    public static final RegistryObject<SoundEvent> WASTELAND_LOOP = register("ambient.wasteland.loop");
    public static final RegistryObject<SoundEvent> WASTELAND_ADDITIONS = register("ambient.wasteland.additions");
    // 0.5: the Ash Hollows, the Wasteland's refuge, hear the rock only from far off; 0.20.9: the Obsidian Tubes too
    public static final RegistryObject<SoundEvent> HOLLOWS_LOOP = register("ambient.hollows.loop");
    public static final RegistryObject<SoundEvent> HOLLOWS_ADDITIONS = register("ambient.hollows.additions");

    // The Mossback (creature.MossbackEntity): its call, heavy steps, the hiss when it sees you, the snap of its jaws,
    // the growl while it shakes its catch, hurt and death. The mod's own recordings (tools/synth_creature_sounds.py).
    public static final RegistryObject<SoundEvent> MOSSBACK_CALL = register("mossback.call");
    public static final RegistryObject<SoundEvent> MOSSBACK_STEP = register("mossback.step");
    public static final RegistryObject<SoundEvent> MOSSBACK_HISS = register("mossback.hiss");
    public static final RegistryObject<SoundEvent> MOSSBACK_BITE = register("mossback.bite");
    public static final RegistryObject<SoundEvent> MOSSBACK_SHAKE = register("mossback.shake");
    public static final RegistryObject<SoundEvent> MOSSBACK_HURT = register("mossback.hurt");
    public static final RegistryObject<SoundEvent> MOSSBACK_DEATH = register("mossback.death");

    // The Skein (creature.SkeinEntity): the dry patter of its many feet, the clicking chitter it makes lying still,
    // the rattling hiss of its plates as it rears, the crack of its mandibles, hurt and death. The mod's own recordings
    // (tools/synth_creature_sounds.py).
    public static final RegistryObject<SoundEvent> SKEIN_PATTER = register("skein.patter");
    public static final RegistryObject<SoundEvent> SKEIN_CHITTER = register("skein.chitter");
    public static final RegistryObject<SoundEvent> SKEIN_RATTLE = register("skein.rattle");
    public static final RegistryObject<SoundEvent> SKEIN_BITE = register("skein.bite");
    public static final RegistryObject<SoundEvent> SKEIN_HURT = register("skein.hurt");
    public static final RegistryObject<SoundEvent> SKEIN_DEATH = register("skein.death");

    // The blue fireflies (creature.FireflySwarm, 0.16.0): the soft flutter of many small wings as a landed swarm is
    // startled up off the rock. The mod's own recordings (tools/synth_creature_sounds.py).
    public static final RegistryObject<SoundEvent> FIREFLY_FLUTTER = register("firefly.flutter");

    // The timed swings (0.12.0, client.combat.SwingClient and combat.swing.Swordplay; tools/synth_swing_sounds.py makes
    // them): a light swing's quick whoosh, the chain's chop (heavier), a strong attack's thrust, the rising hum as a held
    // swing turns strong, and the quiet shimmer and note of a rune sword's ready light (client.combat.RuneGlow).
    public static final RegistryObject<SoundEvent> SWING_LIGHT = register("swing.light");
    public static final RegistryObject<SoundEvent> SWING_HEAVY = register("swing.heavy");
    public static final RegistryObject<SoundEvent> SWING_THRUST = register("swing.thrust");
    public static final RegistryObject<SoundEvent> SWING_CHARGE = register("swing.charge");
    public static final RegistryObject<SoundEvent> RUNES_READY = register("runes.ready");
    // The hunters' wind-ups (0.12.0): half a second that swells into the strike, so the ear can time it
    public static final RegistryObject<SoundEvent> MOSSBACK_WINDUP = register("mossback.windup");
    public static final RegistryObject<SoundEvent> SKEIN_WINDUP = register("skein.windup");

    // The giant Skein of the Deep Blue Rift (0.18.8, creature.GiantSkeinEntity): the Skein's own sounds slowed and
    // deepened (tools/sounds.py), carrying across the rift: its slow heavy tread, its chitter at rest, the deep click
    // as it notices someone, the rattle of its rear (the strike's wind-up), its bite, hurt and death.
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_STEP = register("giant_skein.step");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_CHITTER = register("giant_skein.chitter");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_NOTICE = register("giant_skein.notice");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_WINDUP = register("giant_skein.windup");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_BITE = register("giant_skein.bite");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_HURT = register("giant_skein.hurt");
    public static final RegistryObject<SoundEvent> GIANT_SKEIN_DEATH = register("giant_skein.death");

    // The stilt mantis of the Deep Gardens (0.20.0, creature.StiltMantisEntity): light and dry, so a herd reads as
    // gentle: its stilt feet's taps, its chirr while it grazes, its wings' rattle in its startle display, hurt, death.
    public static final RegistryObject<SoundEvent> STILT_MANTIS_STEP = register("stilt_mantis.step");
    public static final RegistryObject<SoundEvent> STILT_MANTIS_CHIRR = register("stilt_mantis.chirr");
    public static final RegistryObject<SoundEvent> STILT_MANTIS_RATTLE = register("stilt_mantis.rattle");
    public static final RegistryObject<SoundEvent> STILT_MANTIS_HURT = register("stilt_mantis.hurt");
    public static final RegistryObject<SoundEvent> STILT_MANTIS_DEATH = register("stilt_mantis.death");

    // The chimney lurker of the Deep Gardens (0.21.0, creature.ChimneyLurkerEntity): dry and hard, chitin on rock: its
    // hooked feet's ticks, its slow clicks while it stirs or hunts, the rattle as its glow flashes on, the hiss of its
    // crouch, its fangs' snap, hurt and death.
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_STEP = register("chimney_lurker.step");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_CLICK = register("chimney_lurker.click");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_RATTLE = register("chimney_lurker.rattle");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_HISS = register("chimney_lurker.hiss");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_BITE = register("chimney_lurker.bite");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_HURT = register("chimney_lurker.hurt");
    public static final RegistryObject<SoundEvent> CHIMNEY_LURKER_DEATH = register("chimney_lurker.death");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ExplorationRemix.MOD_ID, name)));
    }
}
