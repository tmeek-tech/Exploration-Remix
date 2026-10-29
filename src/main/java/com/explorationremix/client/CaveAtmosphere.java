package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.config.ERClientConfig;
import com.explorationremix.registry.ERParticles;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Each cave biome's air, on the client: fog that tints the distance in the biome's colour without hiding it, light
 * from glowing blocks tinted with the biome's colour (vanilla's is always warm), steam rising off hot pools, embers
 * and smoke off lava, pollen in daylight shafts, and drips falling from ceilings. All of it is worked out from the
 * biome and blocks around the camera, so every player on a server sees the same.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class CaveAtmosphere {
    /** How far the fog lets you see in a biome, as a share of the configured fog distance. */
    private static final Map<ResourceKey<Biome>, Float> FOG_REACH = new HashMap<>();
    private static final ResourceKey<Biome> ROOTVAULTS = key("rootvaults"), SILVER_FERN_RIFT = key("silver_fern_rift"),
            IVORY_ROOT_CAVERN = key("ivory_root_cavern"), LANTERN_LAKE = key("lantern_lake"), EMBER_GARDENS = key("ember_gardens"),
            RUST_SPRINGS = key("rust_springs"), DEEP_BLUE_RIFT = key("deep_blue_rift"), ASURINE_GEODES = key("asurine_geodes"),
            BASALT_GALLERIES = key("basalt_galleries"), OBSIDIAN_TUBES = key("obsidian_tubes");

    /** The colour light from glowing blocks takes in a biome, and how strongly: {r, g, b, strength}. */
    private static final Map<ResourceKey<Biome>, float[]> LIGHT_TINT = new HashMap<>();

    static {
        FOG_REACH.put(ROOTVAULTS, 1.3F);
        FOG_REACH.put(SILVER_FERN_RIFT, 1.3F);
        FOG_REACH.put(IVORY_ROOT_CAVERN, 1.3F);
        FOG_REACH.put(LANTERN_LAKE, 1.2F);
        FOG_REACH.put(EMBER_GARDENS, 1.0F);   // steam
        FOG_REACH.put(RUST_SPRINGS, 1.0F);    // steam
        FOG_REACH.put(DEEP_BLUE_RIFT, 1.2F);
        FOG_REACH.put(ASURINE_GEODES, 1.2F);
        FOG_REACH.put(BASALT_GALLERIES, 0.9F); // smoke
        FOG_REACH.put(OBSIDIAN_TUBES, 1.0F);
        // Cold blue in the Blue Reaches, red near the heat. The Deep Gardens and the upper caves keep vanilla's warm light.
        LIGHT_TINT.put(DEEP_BLUE_RIFT, new float[]{0.42F, 0.62F, 1.0F, 0.85F});
        LIGHT_TINT.put(ASURINE_GEODES, new float[]{0.45F, 0.66F, 1.0F, 0.85F});
        LIGHT_TINT.put(net.minecraft.world.level.biome.Biomes.DEEP_DARK, new float[]{0.45F, 0.72F, 1.0F, 0.6F});
        LIGHT_TINT.put(RUST_SPRINGS, new float[]{1.0F, 0.52F, 0.32F, 0.55F});
        LIGHT_TINT.put(BASALT_GALLERIES, new float[]{1.0F, 0.5F, 0.28F, 0.5F});
        LIGHT_TINT.put(OBSIDIAN_TUBES, new float[]{1.0F, 0.5F, 0.4F, 0.45F});
    }

    private static ResourceKey<Biome> key(String id) {
        return ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, id));
    }

    // Smoothed state, so the fog and light never jump at a biome border.
    private static float weight, reach = 1, r, g, b;
    private static volatile float tintR = 1, tintG = 1, tintB = 1, tintStrength;
    private static long lastFrame = -1, lastTintFrame = -1;

    private CaveAtmosphere() {}

    // ============================================================================================== fog

    /** Updates the smoothed fog target from the biomes around the camera. Called once per frame from the colour event. */
    private static void sample(ClientLevel level, Camera camera) {
        BlockPos cam = camera.getBlockPosition();
        float w = 0, rr = 0, gg = 0, bb = 0, reachSum = 0;
        int n = 0;
        for (int dx = -4; dx <= 4; dx += 4) for (int dy = -4; dy <= 4; dy += 4) for (int dz = -4; dz <= 4; dz += 4) {
            Holder<Biome> h = level.getBiome(cam.offset(dx, dy, dz));
            n++;
            Float k = h.unwrapKey().map(FOG_REACH::get).orElse(null);
            if (k == null) continue;
            int c = h.value().getFogColor();
            w += 1;
            rr += ((c >> 16) & 255) / 255F;
            gg += ((c >> 8) & 255) / 255F;
            bb += (c & 255) / 255F;
            reachSum += k;
        }
        float targetW = w / n;
        // Open sky above (a ravine or a daylight shaft) hands the fog back to the sky.
        float sky = level.getBrightness(LightLayer.SKY, cam) / 15F;
        targetW *= 1 - 0.85F * sky * sky;
        long frame = System.nanoTime() / 16_000_000L;
        float k = lastFrame < 0 ? 1 : Math.min(1, (frame - lastFrame) * 0.06F);
        lastFrame = frame;
        weight += (targetW - weight) * k;
        if (w > 0) {
            // Darker than the biome colour, and darker still where nothing glows: the fog is lit by glowing things,
            // not by daylight, so an unlit cave fades into dusk rather than into a bright haze.
            float glow = level.getBrightness(LightLayer.BLOCK, cam) / 15F;
            float dim = 0.14F + 0.34F * glow;
            r += (rr / w * dim - r) * k;
            g += (gg / w * dim - g) * k;
            b += (bb / w * dim - b) * k;
            reach += (reachSum / w - reach) * k;
        }
    }

    /** Updates the smoothed light tint from the biomes around the camera, once per frame. */
    private static void sampleTint(ClientLevel level, Camera camera) {
        float tr = 0, tg = 0, tb = 0, ts = 0;
        int n = 0;
        if (ERClientConfig.INSTANCE.caveLight.get()) {
            BlockPos cam = camera.getBlockPosition();
            for (int dx = -6; dx <= 6; dx += 6) for (int dy = -6; dy <= 6; dy += 6) for (int dz = -6; dz <= 6; dz += 6) {
                n++;
                float[] t = level.getBiome(cam.offset(dx, dy, dz)).unwrapKey().map(LIGHT_TINT::get).orElse(null);
                if (t == null) continue;
                tr += t[0] * t[3];
                tg += t[1] * t[3];
                tb += t[2] * t[3];
                ts += t[3];
            }
        }
        float targetS = n == 0 ? 0 : ts / n;
        long frame = System.nanoTime() / 16_000_000L;
        float k = lastTintFrame < 0 ? 1 : Math.min(1, (frame - lastTintFrame) * 0.04F);
        lastTintFrame = frame;
        tintStrength += (targetS - tintStrength) * k;
        if (ts > 0) {
            tintR += (tr / ts - tintR) * k;
            tintG += (tg / ts - tintG) * k;
            tintB += (tb / ts - tintB) * k;
        }
    }

    /**
     * The lightmap colour for block light level {@code block} and sky light level {@code sky}, tinted toward the
     * biome's light colour. Keeps the brightness, only turns the hue, and leaves daylight alone. ABGR in, ABGR out.
     */
    public static int tintLightmap(int block, int sky, int abgr) {
        float s = tintStrength;
        if (s < 0.01F || block == 0) return abgr;
        float share = s * (block / 15F) * (1 - 0.8F * sky / 15F);
        int rr = abgr & 255, gg = (abgr >> 8) & 255, bb = (abgr >> 16) & 255;
        float lum = 0.299F * rr + 0.587F * gg + 0.114F * bb;
        float tl = 0.299F * tintR + 0.587F * tintG + 0.114F * tintB;
        float scale = tl > 0 ? lum / tl : 0;
        int nr = Mth.clamp((int) Mth.lerp(share, rr, tintR * scale), 0, 255);
        int ng = Mth.clamp((int) Mth.lerp(share, gg, tintG * scale), 0, 255);
        int nb = Mth.clamp((int) Mth.lerp(share, bb, tintB * scale), 0, 255);
        return (abgr & 0xFF000000) | nb << 16 | ng << 8 | nr;
    }

    @SubscribeEvent
    public static void fogColour(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sampleTint(mc.level, event.getCamera());
        if (!ERClientConfig.INSTANCE.caveFog.get()) return;
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return;
        sample(mc.level, camera);
        if (weight < 0.01F) return;
        event.setRed(Mth.lerp(weight, event.getRed(), r));
        event.setGreen(Mth.lerp(weight, event.getGreen(), g));
        event.setBlue(Mth.lerp(weight, event.getBlue(), b));
    }

    @SubscribeEvent
    public static void fogDistance(ViewportEvent.RenderFog event) {
        if (!ERClientConfig.INSTANCE.caveFog.get() || weight < 0.01F) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) return;
        float far = (float) (ERClientConfig.INSTANCE.fogDistance.get() * reach);
        float vanillaFar = event.getFarPlaneDistance();
        if (far >= vanillaFar) return;
        float near = far * 0.3F;
        event.setFarPlaneDistance(Mth.lerp(weight, vanillaFar, far));
        event.setNearPlaneDistance(Mth.lerp(weight, event.getNearPlaneDistance(), near));
        event.setCanceled(true);
    }

    // ============================================================================================== particles

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ERClientConfig.INSTANCE.caveParticles.get()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || mc.isPaused()) return;
        RandomSource random = level.random;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
        for (int i = 0; i < 400; i++) {
            p.set(px + random.nextInt(49) - 24, py + random.nextInt(25) - 12, pz + random.nextInt(49) - 24);
            BlockState s = level.getBlockState(p);
            above.setWithOffset(p, 0, 1, 0);
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) {
                if (!level.getBlockState(above).isAir()) continue;
                Holder<Biome> h = level.getBiome(p);
                if (f.is(FluidTags.WATER) && (h.is(EMBER_GARDENS) || h.is(RUST_SPRINGS))) {
                    // Steam off every hot pool.
                    if (random.nextFloat() < 0.35F)
                        level.addParticle(ERParticles.STEAM.get(), p.getX() + random.nextDouble(), p.getY() + 0.9, p.getZ() + random.nextDouble(), 0, 0, 0);
                } else if (f.is(FluidTags.LAVA) && (h.is(BASALT_GALLERIES) || h.is(OBSIDIAN_TUBES))) {
                    // Embers, and now and then a column of smoke, off the lava.
                    if (random.nextFloat() < 0.3F)
                        level.addParticle(ERParticles.EMBER.get(), p.getX() + random.nextDouble(), p.getY() + 1.0, p.getZ() + random.nextDouble(), 0, 0, 0);
                    if (random.nextFloat() < 0.012F)
                        level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, p.getX() + random.nextDouble(), p.getY() + 1.2,
                                p.getZ() + random.nextDouble(), 0, 0.03, 0);
                }
                continue;
            }
            if (!s.isAir()) continue;
            BlockState up = level.getBlockState(above);
            if (up.isAir()) {
                // Pollen in a shaft of daylight in the Rootvaults (the biome is only underground, so no surface check).
                if (random.nextFloat() < 0.08F && level.getBrightness(LightLayer.SKY, p) >= 13 && level.getBiome(p).is(ROOTVAULTS))
                    level.addParticle(ERParticles.POLLEN.get(), p.getX() + random.nextDouble(), p.getY() + random.nextDouble(), p.getZ() + random.nextDouble(), 0, 0, 0);
                continue;
            }
            // Under a ceiling: water drips in the Deep Gardens and the upper caves.
            if (random.nextFloat() < 0.02F && up.isFaceSturdy(level, above, net.minecraft.core.Direction.DOWN)) {
                Holder<Biome> h = level.getBiome(p);
                if (h.is(LANTERN_LAKE) || h.is(EMBER_GARDENS) || h.is(RUST_SPRINGS) || h.is(IVORY_ROOT_CAVERN) || h.is(SILVER_FERN_RIFT) || h.is(ROOTVAULTS))
                    level.addParticle(ParticleTypes.DRIPPING_WATER, p.getX() + 0.2 + random.nextDouble() * 0.6, p.getY() + 0.95, p.getZ() + 0.2 + random.nextDouble() * 0.6, 0, 0, 0);
            }
        }
    }
}
