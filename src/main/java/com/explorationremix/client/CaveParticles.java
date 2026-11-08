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

    /**
     * A small grey pebble of grit falling (0.9.0): the dust a quake shakes out of the rock, the trickle from a ceiling
     * block about to come down and the patter where it will land, a build's crumbs as it creaks. It takes the spawn's
     * velocity, falls as a falling block does (gravity 1, 2 % drag a tick), stops on what it lands on and is gone a second
     * after, or after up to 6 seconds in the air. Opaque, off the smoke's translucent sheet; lit by the world, but never
     * darker than block light 6, so it shows in the dark halls.
     */
    public static final class Grit extends TextureSheetParticle {
        /** The least block light it is drawn with. */
        static final int LIGHT_FLOOR = 6;
        private int grounded;

        Grit(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = 1.0F;
            this.friction = 0.98F;
            this.hasPhysics = true;
            this.lifetime = 60 + random.nextInt(61);
            this.quadSize = 0.06F + 0.05F * random.nextFloat();
            float g = 0.85F + 0.15F * random.nextFloat();
            this.setColor(g, g * 0.98F, g * 0.95F);
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (!removed && onGround && ++grounded > 20) remove();
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), LIGHT_FLOOR), LightTexture.sky(own));
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Grit(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A gob of glowing lava (0.9.0): thrown up by an eruption's fountain, hopping in a vent's crater while it warns, off a
     * lava bomb in flight, and splashed up where something falls into the lava. It takes the spawn's velocity, flies
     * under {@link Fountain#SPATTER_GRAVITY} of the particles' gravity with {@link Fountain#SPATTER_FRICTION} drag, lands
     * on what it meets and stops there, shrinking and darkening as it cools, and is gone after 30 to 60 ticks. It never
     * fades its alpha and is always full-bright, so the shader and the cave fog do not drop it at a distance; drawn on
     * the opaque sheet (the vanilla lava's sprite), away from the long-lived smoke on the translucent one. Unlike the
     * game's own lava particle it leaves no smoke behind it each tick.
     */
    public static final class Spatter extends TextureSheetParticle {
        /** Once landed it keeps this share of its size and of its green and blue each tick. */
        static final float COOL_SIZE = 0.93F, COOL_COLOUR = 0.9F;

        Spatter(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = (float) Fountain.SPATTER_GRAVITY;
            this.friction = (float) Fountain.SPATTER_FRICTION;
            this.hasPhysics = true;
            this.lifetime = 30 + random.nextInt(31);
            this.quadSize = 0.07F + 0.09F * random.nextFloat();
            this.setColor(1.0F, 0.75F + 0.25F * random.nextFloat(), 0.55F + 0.3F * random.nextFloat());
            this.pickSprite(sprites);
        }

        /** Drawn {@code k} times its size (a far fountain's spatter keeps more of its size); its box for landing stays the same. */
        void grow(float k) { this.quadSize *= k; }

        @Override
        public void tick() {
            super.tick();
            if (removed || !onGround) return;
            // cooling where it landed: smaller and redder, never fainter
            this.quadSize *= COOL_SIZE;
            this.gCol *= COOL_COLOUR;
            this.bCol *= COOL_COLOUR * COOL_COLOUR;
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Spatter(level, x, y, z, dx, dy, dz, sprites);
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
