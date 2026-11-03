package com.explorationremix.client;

import com.explorationremix.block.GlowBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/** The cave particles' looks and motion. */
public final class CaveParticles {
    private CaveParticles() {}

    /**
     * A puff of volcanic smoke off the lava of the Galleries and the Wasteland, built like {@link Steam} but long-lived:
     * it rises 40 to 100 blocks over 30 to 60 seconds (the plumes through the openings about 170), slowing as it cools,
     * swells from two blocks or so across to seven to nine, thickens and thins away, and spreads under the rock it meets
     * rather than stopping dead ({@link SmokePuff} has the motion). It takes the lava's light from below
     * ({@link SmokePuff#lightFromBelow}), so it is never a black blot in mid-air.
     *
     * <p>The spawner passes the rise it should make as the x speed and the height over the lava it starts at as the y
     * speed (0, 0, 0 makes a plume of a random rise starting at the lava).
     */
    public static final class VolcanicSmoke extends TextureSheetParticle {
        private final SmokePuff puff;
        private final float spin;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        VolcanicSmoke(ClientLevel level, double x, double y, double z, double reach, double above, SpriteSet sprites) {
            super(level, x, y, z);
            if (!(reach > 0)) reach = SmokePuff.REACH_MIN + (SmokePuff.REACH_MAX - SmokePuff.REACH_MIN) * random.nextDouble();
            this.puff = new SmokePuff(x, y, z, y - Math.max(0, above), reach, random::nextDouble);
            this.lifetime = puff.life;
            this.hasPhysics = false;   // the puff meets the rock itself (Particle's collision stops a puff dead under a vault)
            this.gravity = 0;
            this.friction = 1;
            this.xd = this.yd = this.zd = 0;
            this.quadSize = puff.size();
            float g = 0.5F + 0.12F * random.nextFloat();
            this.setColor(g * 1.03F, g, g * 0.94F);   // a warm grey; the lava's light below does the rest
            this.alpha = 0;
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.spin = (0.0015F + 0.0025F * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
            this.pickSprite(sprites);
            SmokeBudget.add(level.getGameTime() + lifetime);
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            this.oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            puff.tick(this::rock, random::nextDouble);
            this.setPos(puff.x, puff.y, puff.z);
            this.roll += spin;
            this.quadSize = puff.size();
            this.alpha = puff.alpha();
        }

        /** Rock for the smoke: any block but air, the glow and fluids (a puff rises past a lava fall). */
        private boolean rock(double x, double y, double z) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            return !GlowBlock.open(s) && s.getFluidState().isEmpty();
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            int below = SmokePuff.lightFromBelow(y - puff.base);
            return LightTexture.pack(Math.max(LightTexture.block(own), below), LightTexture.sky(own));
        }

        /** Never culled by its point-sized box: the quad is up to nine blocks across and would pop at the screen's edge. */
        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new VolcanicSmoke(level, x, y, z, dx, dy, sprites);
            }
        }
    }

    /** A soft puff of steam that rises off a hot pool, swells and fades. */
    public static final class Steam extends TextureSheetParticle {
        private final SpriteSet sprites;

        Steam(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.lifetime = 50 + random.nextInt(50);
            this.gravity = -0.012F;
            this.friction = 0.96F;
            this.xd = (random.nextDouble() - 0.5) * 0.012;
            this.yd = 0.025 + random.nextDouble() * 0.02;
            this.zd = (random.nextDouble() - 0.5) * 0.012;
            this.quadSize = 0.35F + random.nextFloat() * 0.35F;
            float g = 0.9F + random.nextFloat() * 0.08F;
            this.setColor(g, g, g);
            this.alpha = 0;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            float t = (float) age / lifetime;
            this.alpha = 0.32F * Mth.clamp(t * 5, 0, 1) * (1 - t);
            this.quadSize *= 1.012F;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Steam(level, x, y, z, sprites);
            }
        }
    }

    /** A spark from the lava that drifts up, flickers and goes out. Glows in the dark. */
    public static final class Ember extends TextureSheetParticle {
        Ember(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 40 + random.nextInt(60);
            this.gravity = -0.004F;
            this.friction = 0.97F;
            this.xd = (random.nextDouble() - 0.5) * 0.03;
            this.yd = 0.03 + random.nextDouble() * 0.05;
            this.zd = (random.nextDouble() - 0.5) * 0.03;
            this.quadSize = 0.04F + random.nextFloat() * 0.05F;
            this.setColor(1.0F, 0.55F + random.nextFloat() * 0.25F, 0.15F);
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            this.xd += (random.nextDouble() - 0.5) * 0.006;
            this.zd += (random.nextDouble() - 0.5) * 0.006;
            float t = (float) age / lifetime;
            this.alpha = (1 - t * t) * (0.75F + 0.25F * random.nextFloat());
            this.gCol = Math.max(0.2F, this.gCol - 0.006F);
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Ember(level, x, y, z, sprites);
            }
        }
    }

    /** A mote of pollen hanging in a shaft of daylight, drifting slowly and glinting. */
    public static final class Pollen extends TextureSheetParticle {
        Pollen(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 120 + random.nextInt(120);
            this.gravity = 0.0015F;
            this.friction = 0.98F;
            this.xd = (random.nextDouble() - 0.5) * 0.01;
            this.yd = -0.002;
            this.zd = (random.nextDouble() - 0.5) * 0.01;
            this.quadSize = 0.025F + random.nextFloat() * 0.025F;
            this.setColor(1.0F, 0.9F, 0.55F);
            this.alpha = 0;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            this.xd += (random.nextDouble() - 0.5) * 0.002;
            this.zd += (random.nextDouble() - 0.5) * 0.002;
            float t = (float) age / lifetime;
            this.alpha = 0.85F * Mth.clamp(t * 6, 0, 1) * Mth.clamp((1 - t) * 4, 0, 1);
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Pollen(level, x, y, z, sprites);
            }
        }
    }
}
