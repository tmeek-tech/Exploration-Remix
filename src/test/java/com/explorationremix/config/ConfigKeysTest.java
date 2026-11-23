package com.explorationremix.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 0.9.0 config keys of the quakes and eruptions (spec 6.2): the keys whose meaning or default changed since 0.8.0
 * have new names, so Forge's correction drops the old ones from a player's file and writes the pick's defaults; every
 * default is the pick's (spec 1.3), every alternative of 1.3 is a set of values inside its keys' ranges, and nothing says
 * the quakes or eruptions are not built any more.
 */
class ConfigKeysTest {
    private static final UnmodifiableConfig WORLD = ERConfig.SPEC.getSpec();
    private static final UnmodifiableConfig CLIENT = ERClientConfig.SPEC.getSpec();

    private static ForgeConfigSpec.ValueSpec spec(UnmodifiableConfig c, String path) {
        Object o = c.get(path);
        assertTrue(o instanceof ForgeConfigSpec.ValueSpec, "no key " + path);
        return (ForgeConfigSpec.ValueSpec) o;
    }

    /**
     * The Deep Gardens' vent field is built for 192 rows (round 3 of the layer, Taran: "Reduce the layer height"; 240
     * from 0.19.1): the world config takes no other height and puts one back to 192, since a taller layer read past the
     * vent field's tiles and crashed generation (0.19.4). The old 240 is refused like any other.
     */
    @Test
    void theDeepGardensHeightIsFixed() {
        ForgeConfigSpec.ValueSpec h = spec(WORLD, "world.deep_gardens.height");
        assertEquals(192, h.getDefault());
        assertTrue(h.test(192));
        for (int v : new int[]{64, 176, 191, 193, 208, 224, 240, 256, 512}) {
            assertFalse(h.test(v), "height " + v + " accepted");
            assertEquals(192, h.correct(v), "height " + v + " not put back to 192");
        }
    }

    @Test
    void droppedKeysAreGone() {
        for (String k : List.of("quakes.debrisPerSecond", "eruptions.activeMinMinutes", "eruptions.activeMaxMinutes", "eruptions.bombPower",
                "eruptions.fire")) {
            assertFalse(WORLD.contains(k), k + " is still in the config");
        }
    }

    @Test
    void theDefaultsAreThePicks() {
        Map<String, Object> want = new LinkedHashMap<>();
        // kept keys keep their 0.8.0 defaults, which are the pick's
        want.put("quakes.enabled", true);
        want.put("quakes.minMinutes", 3.0);
        want.put("quakes.maxMinutes", 7.0);
        want.put("quakes.seconds", 10.0);
        want.put("quakes.debrisRadius", 24);
        want.put("eruptions.enabled", true);
        want.put("eruptions.minMinutes", 3.0);
        want.put("eruptions.maxMinutes", 7.0);
        want.put("eruptions.bombs", 10);
        want.put("eruptions.range", 192);
        // new keys
        want.put("quakes.tremors", true);
        want.put("quakes.tremorMinSeconds", 30.0);
        want.put("quakes.tremorMaxSeconds", 60.0);
        want.put("quakes.climbTremors", false); // Taran's answer (2026-11-07): timed tremors only
        want.put("quakes.topple", true);
        want.put("quakes.supportDepth", 2);
        want.put("quakes.toppleAtOnce", false);
        want.put("quakes.toppleMax", 256);
        want.put("quakes.lowerTicks", 6);
        want.put("quakes.debrisPerQuake", 16);
        want.put("quakes.debrisNear", 0.3);
        want.put("quakes.debrisWarnTicks", 20);
        want.put("quakes.debrisDamage", 8);
        want.put("quakes.debrisMax", 128);
        want.put("eruptions.risingMinMinutes", 1.0);
        want.put("eruptions.risingMaxMinutes", 2.0);
        want.put("eruptions.wakeSeconds", 40);
        want.put("eruptions.warnSeconds", 4.0);
        want.put("eruptions.burstSeconds", 10.0);
        want.put("eruptions.bombsNear", 2);
        want.put("eruptions.nearMinBlocks", 3);
        want.put("eruptions.nearMaxBlocks", 8);
        want.put("eruptions.ventNearBias", 48);
        want.put("eruptions.blastPower", 3.0);
        want.put("eruptions.craterRock", 0.5);
        want.put("eruptions.bombDamage", 0.6);
        want.put("eruptions.bombKnockback", 1.0);
        want.put("eruptions.ventBurn", 4);
        want.put("eruptions.ejecta", 3);
        want.put("eruptions.craterMagma", 0.5);
        want.put("eruptions.risingScale", 1.5);
        want.put("eruptions.heardRange", 256);
        // the reset's keys are unchanged
        want.put("terrainReset.enabled", true);
        want.put("terrainReset.changesPerTick", 2048);
        for (Map.Entry<String, Object> e : want.entrySet()) {
            ForgeConfigSpec.ValueSpec v = spec(WORLD, e.getKey());
            assertEquals(e.getValue(), v.getDefault(), e.getKey());
            assertTrue(v.test(v.getDefault()), e.getKey() + "'s default is outside its range");
        }
        assertEquals(1.0, spec(CLIENT, "lavaCycle.shakeStrength").getDefault());
        assertEquals(ERClientConfig.TremorEffects.WHERE_BLOCKS_FALL, spec(CLIENT, "lavaCycle.tremorEffects").getDefault());
        assertEquals(true, spec(CLIENT, "lavaCycle.screenShake").getDefault());
        assertEquals(true, spec(CLIENT, "caves.caveParticles").getDefault());
    }

    @Test
    void everyAlternativeIsInsideItsKeysRanges() {
        List<Map<String, Object>> alternatives = new ArrayList<>();
        // choice 1: only the big quakes; fall at once, like sand
        alternatives.add(Map.of("quakes.tremors", false, "quakes.climbTremors", false));
        alternatives.add(Map.of("quakes.toppleAtOnce", true, "quakes.tremors", false, "quakes.climbTremors", false));
        // choice 2: deadly; mostly scenery
        alternatives.add(Map.of("quakes.debrisDamage", 20, "quakes.debrisPerQuake", 24, "quakes.debrisNear", 0.4, "quakes.lowerTicks", 0,
                "eruptions.bombDamage", 1.0, "eruptions.bombsNear", 4, "eruptions.nearMinBlocks", 0, "eruptions.nearMaxBlocks", 6));
        alternatives.add(Map.of("quakes.debrisDamage", 1, "eruptions.bombDamage", 0.1, "eruptions.bombKnockback", 0.25, "eruptions.ventBurn", 1,
                "eruptions.bombsNear", 0));
        // choice 3: one huge eruption per cycle as the lava rises; frequent and small
        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("eruptions.minMinutes", 0.0);
        huge.put("eruptions.wakeSeconds", 20);
        huge.put("eruptions.risingMinMinutes", 3.0);
        huge.put("eruptions.risingMaxMinutes", 3.0);
        huge.put("eruptions.bombs", 32);
        huge.put("eruptions.bombsNear", 4);
        huge.put("eruptions.burstSeconds", 30.0);
        huge.put("eruptions.blastPower", 4.0);
        huge.put("eruptions.risingScale", 2.0);
        huge.put("eruptions.range", 320);
        huge.put("eruptions.heardRange", 512);
        alternatives.add(huge);
        Map<String, Object> small = new LinkedHashMap<>();
        small.put("eruptions.minMinutes", 1.0);
        small.put("eruptions.maxMinutes", 2.0);
        small.put("eruptions.risingMinMinutes", 0.5);
        small.put("eruptions.risingMaxMinutes", 1.0);
        small.put("eruptions.bombs", 4);
        small.put("eruptions.bombsNear", 1);
        small.put("eruptions.burstSeconds", 6.0);
        small.put("eruptions.blastPower", 2.0);
        small.put("eruptions.risingScale", 1.0);
        alternatives.add(small);
        for (Map<String, Object> alt : alternatives) {
            for (Map.Entry<String, Object> e : alt.entrySet()) {
                ForgeConfigSpec.ValueSpec v = spec(WORLD, e.getKey());
                assertTrue(v.test(e.getValue()), e.getKey() + " = " + e.getValue() + " is outside its range");
                assertEquals(v.getDefault().getClass(), e.getValue().getClass(), e.getKey() + ": the alternative's value is of another type");
            }
        }
    }

    /**
     * The Upper Caves' crickets (0.20.9) default to Taran's picks: the chorus in every upper cave (card: "Every upper
     * cave", the recommended answer until one is given) and the crickets near you that go quiet as you walk up (card:
     * "Yes, it goes quiet"). Each setting takes only its own values.
     */
    @Test
    void theCricketsAreThePicks() {
        ForgeConfigSpec.ValueSpec where = spec(CLIENT, "sounds.crickets");
        assertEquals(ERClientConfig.Crickets.EVERY_UPPER_CAVE, where.getDefault());
        for (ERClientConfig.Crickets c : ERClientConfig.Crickets.values()) assertTrue(where.test(c), c + " refused");
        assertFalse(where.test("BIRDS"));
        ForgeConfigSpec.ValueSpec near = spec(CLIENT, "sounds.nearCrickets");
        assertEquals(true, near.getDefault());
        assertFalse(near.test(3));
    }

    @Test
    void nothingSaysNotBuilt() {
        List<String> comments = new ArrayList<>();
        collect(WORLD, comments);
        for (String section : List.of("quakes", "eruptions", "terrainReset")) comments.add(ERConfig.SPEC.getLevelComment(List.of(section)));
        for (String c : comments) {
            assertNotNull(c);
            String lc = c.toLowerCase(Locale.ROOT);
            assertFalse(lc.contains("not built") || lc.contains("once they are built"), "stale wording: " + c);
        }
    }

    private static void collect(UnmodifiableConfig c, List<String> out) {
        for (Object o : c.valueMap().values()) {
            if (o instanceof UnmodifiableConfig sub) collect(sub, out);
            else if (o instanceof ForgeConfigSpec.ValueSpec v && v.getComment() != null) out.add(v.getComment());
        }
    }
}
