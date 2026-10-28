package com.explorationremix.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/** The three cave particles' looks and motion. */
public final class CaveParticles {
    private CaveParticles() {}

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
