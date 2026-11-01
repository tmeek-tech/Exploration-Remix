package com.explorationremix.world.terrain;

import java.util.List;

/**
 * Every number the deep world's shape depends on. Built from the config on a server, or from {@link #defaults()} in
 * the offline preview, so the terrain code never touches Forge.
 */
public record TerrainSettings(int bandThickness, int gateSpacing, double passageRadius, double landingRadius,
                              List<LayerSettings> layers) {

    public static final String[] LAYER_IDS = {"blue_reaches", "deep_gardens", "wasteland", "basalt_galleries"};

    /**
     * Four deep layers under the pack's floor at y -64: bands at -65, -273, -529 and -673, bedrock at -832. Since 0.5
     * the old 288-block bottom layer is the Wasteland (128) over a band and the Basalt Galleries (144), so the floor
     * stays where it was (Taran, 2026-11-01: "Keep bedrock at Y -832").
     */
    public static TerrainSettings defaults() {
        return new TerrainSettings(16, 600, 4.5, 14.0, List.of(
                new LayerSettings("blue_reaches", 192, 14, false, 80, 160, 30, 60),
                new LayerSettings("deep_gardens", 240, 16, false, 30, 100, 40, 200),
                new LayerSettings("wasteland", 128, 10, true, 15, 30, 15, 30),
                new LayerSettings("basalt_galleries", 144, 10, true, 50, 110, 60, 110)));
    }
}
