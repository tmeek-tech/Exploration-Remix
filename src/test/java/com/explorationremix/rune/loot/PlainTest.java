package com.explorationremix.rune.loot;

import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.RuneKind;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an enchanted book becomes (0.22.6; a chest's a plain book since 0.24.5), and which collect runes each layer gives. */
class PlainTest {
    @Test
    void aBookIsAnyRuneButACollectOneWeightedByWhereItIsFound() {
        Map<RuneId, Integer> got = new EnumMap<>(RuneId.class);
        int total = Plain.total(0, 2);
        for (int roll = 0; roll < total; roll++) got.merge(Plain.pick(roll, 0, 2), 1, Integer::sum);
        for (RuneId r : RuneId.runes()) {
            if (r.isCollect()) assertFalse(got.containsKey(r), r.id + ": collect runes come from each layer's creatures");
            else assertEquals(Plain.LAYER_WEIGHT[r.layer], got.get(r), r.id + " comes as often as its layer's weight");
        }
        assertTrue(Plain.LAYER_WEIGHT[0] > Plain.LAYER_WEIGHT[1] && Plain.LAYER_WEIGHT[1] > Plain.LAYER_WEIGHT[2], "deeper runes are rarer");
    }

    @Test
    void aTradeSellsRunesOfOneLayer() {
        for (int layer = 0; layer <= 2; layer++) {
            List<RuneId> runes = Plain.bookRunes(layer, layer);
            assertFalse(runes.isEmpty());
            for (int roll = 0; roll < Plain.total(layer, layer); roll++) assertEquals(layer, Plain.pick(roll, layer, layer).layer);
        }
        assertTrue(Plain.bookRunes(0, 0).contains(RuneId.HASTE), "tool runes are sold too");
        assertTrue(Plain.bookRunes(1, 1).contains(RuneId.GLIDE), "and armor's effect runes");
    }

    @Test
    void aChestIsKnownByItsTablesPath() {
        for (String chest : List.of("chests/simple_dungeon", "chests/stronghold_library", "chests/village/village_weaponsmith",
                "chests/ancient_city", "structures/chests/tower_top"))
            assertTrue(PlainLoot.chestPath(chest), chest + " fills a chest: its book comes plain");
        for (String other : List.of("gameplay/fishing", "gameplay/piglin_bartering", "entities/zombie", "blocks/chest", "chestnut/tree"))
            assertFalse(PlainLoot.chestPath(other), other + " is no chest's: its book is still a rune");
    }

    @Test
    void eachLayerHasTwoKindsOfCollectRune() {
        assertEquals(List.of(RuneId.MEND, RuneId.VIGOR), Plain.collects(0));
        assertEquals(List.of(RuneId.FLEET, RuneId.THIRST), Plain.collects(1));
        assertEquals(List.of(RuneId.WARD, RuneId.WISDOM), Plain.collects(2));
        assertEquals(6, RuneId.ofKind(RuneKind.COLLECT).size());
    }
}
