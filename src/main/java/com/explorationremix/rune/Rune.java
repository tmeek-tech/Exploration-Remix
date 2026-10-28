package com.explorationremix.rune;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One rune as loaded from {@code data/<ns>/runes/<id>.json}. The behaviour lives in {@link RuneEffects}; the JSON
 * holds the numbers, so Taran can retune or delete any rune without touching code.
 */
public record Rune(ResourceLocation id, RuneKind kind, int cost, int tier, int weight, List<String> attunements,
                   Map<String, Double> params, String color) {

    public String translationKey() { return "rune." + id.getNamespace() + "." + id.getPath(); }
    public String descriptionKey() { return translationKey() + ".desc"; }

    public double param(String key, double fallback) { return params.getOrDefault(key, fallback); }

    public boolean fitsAttunement(String attunement) { return attunement.isEmpty() || attunements.isEmpty() || attunements.contains(attunement); }

    public ChatFormatting formatting() {
        return switch (kind) {
            case TRIGGER -> ChatFormatting.GOLD;
            case MODIFIER -> ChatFormatting.AQUA;
            case EFFECT -> ChatFormatting.LIGHT_PURPLE;
            case PASSIVE -> ChatFormatting.GREEN;
        };
    }

    public static Rune fromJson(ResourceLocation id, JsonObject json) {
        RuneKind kind = RuneKind.parse(json.get("kind").getAsString());
        int cost = json.has("cost") ? json.get("cost").getAsInt() : 20;
        int tier = json.has("tier") ? json.get("tier").getAsInt() : 0;
        int weight = json.has("weight") ? json.get("weight").getAsInt() : 10;
        List<String> attunements = json.has("attunements") ? json.getAsJsonArray("attunements").asList().stream().map(e -> e.getAsString()).toList() : List.of();
        Map<String, Double> params = new HashMap<>();
        if (json.has("params")) json.getAsJsonObject("params").entrySet().forEach(e -> params.put(e.getKey(), e.getValue().getAsDouble()));
        String color = json.has("color") ? json.get("color").getAsString() : "";
        return new Rune(id, kind, cost, tier, weight, attunements, Map.copyOf(params), color);
    }
}
