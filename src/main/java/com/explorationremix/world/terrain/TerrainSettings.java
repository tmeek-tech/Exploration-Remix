package com.explorationremix.world.terrain;

import java.util.List;

/**
 * Every number the deep world's shape depends on. Built from the config on a server, or from {@link #defaults()} in
 * the offline preview, so the terrain code never touches Forge.
 */
public record TerrainSettings(int bandThickness, int gateSpacing, double passageRadius, double landingRadius,
                              List<LayerSettings> layers) {

    public static final String[] LAYER_IDS = {"lantern_deeps", "blue_reaches", "ember_gardens", "basalt_galleries"};

    public static TerrainSettings defaults() {
        return new TerrainSettings(16, 600, 4.5, 14.0, List.of(
                new LayerSettings("lantern_deeps", 128, 18, false, 30, 60, 40, 100),
                new LayerSettings("blue_reaches", 160, 14, false, 80, 140, 30, 60),
                new LayerSettings("ember_gardens", 192, 16, false, 60, 100, 100, 200),
                new LayerSettings("basalt_galleries", 224, 10, true, 120, 180, 40, 90)));
    }
}
