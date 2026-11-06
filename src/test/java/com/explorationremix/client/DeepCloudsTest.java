package com.explorationremix.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No clouds in the deep caves (DeepClouds): while the camera is under the pack floor in the overworld the clouds go far
 * over it, out of reach of Complementary's cloud march past the edge of the drawn world; everywhere else the height is
 * the dimension's own. The lifted height must survive the shader's arithmetic: an int, then a float times 64.
 */
class DeepCloudsTest {
    private static final float OVERWORLD_CLOUDS = 192F;
    /** Complementary's cloud altitude settings (CLOUD_ALT1, CLOUD_ALT2) run from -96 to 800; 192 is the default. */
    private static final int[] SHADER_ALTITUDES = {-96, 192, 288, 800};
    /** Its ray march stops this far out (horizontally), and its thickest cloud layer reaches this far under the altitude. */
    private static final double MARCH = 4000, THICKEST = 18;

    @Test
    void aboveTheFloorTheCloudsStay() {
        for (double y : new double[]{320, 192, 63.5, 0, -32, -63.99, -64}) {
            assertEquals(OVERWORLD_CLOUDS, DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, y), "camera at " + y);
            assertEquals(128F, DeepClouds.cloudHeight(128F, true, y), "a pack's own cloud height, camera at " + y);
        }
    }

    @Test
    void underTheFloorTheCloudsGoFarOverTheCamera() {
        for (double y : new double[]{-64.01, -65, -233.4, -545, -723.62, -822, -832, -2032}) {
            float h = DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, y);
            assertEquals(Math.floor(y) + DeepClouds.LIFT, h, 0, "camera at " + y);
            assertTrue(h - y > DeepClouds.LIFT - 1, "camera at " + y + ": clouds at " + h);
            // A pack that sets its own cloud height gets the same: the camera decides.
            assertEquals(h, DeepClouds.cloudHeight(250F, true, y), "camera at " + y);
        }
    }

    @Test
    void noCloudsStaysNoClouds() {
        for (double y : new double[]{100, -64, -700}) {
            assertTrue(Float.isNaN(DeepClouds.cloudHeight(Float.NaN, true, y)), "overworld, camera at " + y);
            assertTrue(Float.isNaN(DeepClouds.cloudHeight(Float.NaN, false, y)), "nether or end, camera at " + y);
        }
    }

    @Test
    void otherDimensionsKeepTheirClouds() {
        for (double y : new double[]{100, -63, -64.5, -700, -2000})
            for (float vanilla : new float[]{OVERWORLD_CLOUDS, 128F, -32F})
                assertEquals(vanilla, DeepClouds.cloudHeight(vanilla, false, y), "camera at " + y);
    }

    @Test
    void badCameraHeightsGiveSafeValues() {
        assertEquals(OVERWORLD_CLOUDS, DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, Double.NaN));
        assertEquals(OVERWORLD_CLOUDS, DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, Double.POSITIVE_INFINITY));
        float deepest = DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, Double.NEGATIVE_INFINITY);
        assertEquals(DeepClouds.LOWEST_CAMERA + DeepClouds.LIFT, deepest, 0);
        assertEquals(deepest, DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, -3.0E7));
        assertTrue(DeepClouds.LOWEST_CAMERA + DeepClouds.LIFT > 0, "always over the world");
    }

    /**
     * Over every camera height under the floor down to the deepest a world can be (-2032) and the lowest the lift follows:
     * the height is a whole number Complementary turns into an int without loss, its altitude times 64 (ModifyTracePos) is
     * an exact float, the clouds are met within the march only by rays climbing more than 88.8 degrees, and vanilla's and
     * Embeddium's cloud meshes there lie past the far plane even at render distance 32.
     */
    @Test
    void theLiftedHeightSurvivesTheShader() {
        int checked = 0;
        for (double y = -64.001; y >= DeepClouds.LOWEST_CAMERA - 10; y -= (y > -2100 ? 0.37 : 97.3)) {
            float h = DeepClouds.cloudHeight(OVERWORLD_CLOUDS, true, y);
            assertTrue(Float.isFinite(h) && h == Math.rint(h), "a whole number at " + y + ": " + h);
            for (int alt : SHADER_ALTITUDES) {
                // common.glsl: cloudHeightOffset = cloudHeight - 192.0; cloudAlt1i = int(CLOUD_ALT1 + cloudHeightOffset)
                float offset = h - 192F;
                int altitude = (int) (alt + offset);
                assertEquals((long) h - 192 + alt, altitude, "int altitude at " + y);
                // cloudCoord.glsl: tracePos.x += cloudAltitude * 64.0
                float times64 = (float) altitude * 64F;
                assertEquals((long) altitude * 64, (long) times64, "altitude x 64 exact at " + y);
                assertTrue(Math.abs(times64) < (1 << 24), "under 2^24 at " + y);
                // The lowest plane of the cloud layer, over the camera (as the shader works it out, in floats).
                float rise = (float) altitude - (float) THICKEST - (float) y;
                assertTrue(rise > 0, "over the camera at " + y);
                double steepest = Math.toDegrees(Math.atan2(rise, MARCH));
                assertTrue(steepest > 88.8, "met within " + MARCH + " blocks only climbing over " + steepest + " degrees, at " + y);
            }
            // LevelRenderer.renderClouds and Embeddium's CloudRenderer translate the mesh by h - camY + 0.33.
            assertTrue(h - (float) y + 0.33F > 32 * 16 * 4, "past the far plane at render distance 32, at " + y);
            checked++;
        }
        assertTrue(checked > 5000, "checked " + checked + " heights");
    }

    @Test
    void theMixinIsClientOnlyAndOptional() throws IOException {
        JsonObject config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/explorationremix.mixins.json")))
                .getAsJsonObject();
        JsonArray client = config.getAsJsonArray("client"), common = config.getAsJsonArray("mixins");
        assertTrue(client.toString().contains("\"client.DimensionSpecialEffectsMixin\""), "in the client list");
        assertFalse(common.toString().contains("DimensionSpecialEffectsMixin"), "not in the common list");
        String mixin = Files.readString(Path.of("src/main/java/com/explorationremix/mixin/client/DimensionSpecialEffectsMixin.java"));
        assertTrue(mixin.contains("@Mixin(DimensionSpecialEffects.class)"), "targets DimensionSpecialEffects");
        assertTrue(mixin.contains("method = \"getCloudHeight\""), "on getCloudHeight");
        assertTrue(mixin.contains("require = 0"), "skipped, not a crash, if the getter is missing");
        assertTrue(mixin.contains("DeepClouds.cloudHeight("), "decides through DeepClouds");
        String pure = Files.readString(Path.of("src/main/java/com/explorationremix/client/DeepClouds.java"));
        assertFalse(pure.contains("import net.minecraft") || pure.contains("import com.mojang"),
                "DeepClouds uses no game classes, so nothing of it touches the client's classes");
    }
}
