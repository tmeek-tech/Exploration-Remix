package com.explorationremix.client;

/**
 * Keeps the clouds out of the deep caves (0.8.0). Under the pack floor nothing is open to the sky, but past the edge of the
 * drawn world (at a short render distance, or wherever the caves run longer than it) the game shows the sky, and
 * Complementary Reimagined draws its own clouds on it: pale blocky clouds past the far end of the Blue Reaches' caverns and
 * the Galleries' halls. It puts them at the height the dimension's effects give (Oculus hands it {@code getCloudHeight()}
 * every frame as the {@code cloudHeight} uniform), and its ray march gives up past 4000 blocks out. So while the camera is
 * under the floor in the overworld the clouds are put {@link #LIFT} blocks over it: a ray meets them within 4000 blocks
 * only when it climbs more than 88.8 degrees, and the sky shows that high only straight up a shaft taller than the render
 * distance. Vanilla's, Embeddium's and Oculus's own cloud meshes read the same height and lie far past the far plane there.
 * Above the floor, in other dimensions and in dimensions without clouds (NaN) the height is left as it is.
 *
 * <p>A pure function, with no game classes, so the dedicated server never loads anything for it; the client's
 * {@code DimensionSpecialEffectsMixin} hands it the camera.
 */
public final class DeepClouds {
    /** The pack's old floor: under it nothing is open to the sky. */
    public static final double FLOOR_Y = -64;
    /**
     * How far over the camera the clouds go while it is under the floor. Complementary turns the height into an int and
     * multiplies it by 64 as a float (ModifyTracePos), so the height is kept a whole number under 2^24 / 64 = 262144 with
     * room for its own cloud altitude settings (at most 608 over the default).
     */
    public static final int LIFT = 200_000;
    /** The lowest camera height the lift follows; lower (only ever a bad value) the clouds stay at this camera's lift. */
    public static final int LOWEST_CAMERA = -65_536;

    private DeepClouds() {}

    /**
     * The cloud height to use.
     *
     * @param vanilla   the dimension effects' own height (NaN: the dimension has no clouds)
     * @param overworld whether the client is in the overworld
     * @param cameraY   the camera's height
     */
    public static float cloudHeight(float vanilla, boolean overworld, double cameraY) {
        if (!overworld || Float.isNaN(vanilla) || !(cameraY < FLOOR_Y)) return vanilla;
        return (float) (Math.max(Math.floor(cameraY), LOWEST_CAMERA) + LIFT);
    }
}
