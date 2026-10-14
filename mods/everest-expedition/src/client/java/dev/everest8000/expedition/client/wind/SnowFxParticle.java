package dev.everest8000.expedition.client.wind;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.everest8000.expedition.client.ClientWeather;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One piece of blown snow. Drawn as a quad stretched along its motion and turned to face the camera, so a fast grain
 * reads as a short streak and a wisp of spindrift as a long veil, instead of the vanilla snowflake's cross.
 */
public final class SnowFxParticle extends TextureSheetParticle {
    public enum Kind {
        /** A visible grain, a little motion-blurred. */
        GRAIN,
        /** A faint, fast streak of fine snow passing close to the eye. */
        STREAK,
        /** A soft veil of spindrift streaming off a crest. */
        WISP,
        /** Snow snaking along the surface: hugs the ground until it blows off an edge. */
        DRIFT
    }

    private final Kind kind;
    private final float length;
    private final float width;
    private final float peakAlpha;
    private final float growth;
    private final float speed;
    private final float lift;
    private final float couple;
    private float hover;
    private boolean airborne;
    private float gust = 1f;

    /**
     * @param speed   blocks per tick it tries to move downwind at
     * @param lift    upward velocity it keeps adding (snow thrown up over an edge), decaying with age
     * @param hover   for DRIFT and skimming grains: height above the surface; negative for free-flying snow
     */
    public SnowFxParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                          TextureAtlasSprite sprite, Kind kind, float length, float width, float alpha, int lifetime,
                          float growth, float speed, float lift, float couple, float hover) {
        super(level, x, y, z);
        this.kind = kind;
        this.length = length;
        this.width = width;
        this.peakAlpha = alpha;
        this.growth = growth;
        this.speed = speed;
        this.lift = lift;
        this.couple = couple;
        this.hover = hover;
        this.airborne = hover < 0f;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.lifetime = lifetime;
        this.hasPhysics = false;
        this.gravity = 0f;
        this.friction = 1f;
        this.alpha = 0f;
        // Not pure white: Complementary treats pure-white translucent particles as another mod's snow. The veils are a
        // touch cooler than the snow they blow over, as blowing snow is (it is in its own shade); pure white on white
        // snow doesn't show at all.
        boolean veil = kind == Kind.WISP || kind == Kind.DRIFT;
        this.rCol = veil ? 0.84f : 0.95f;
        this.gCol = veil ? 0.88f : 0.965f;
        this.bCol = veil ? 0.96f : 0.99f;
        setSprite(sprite);
        // The box only matters for culling; make it cover the stretched quad.
        float extent = Math.min(4f, Math.max(Math.max(width, length), 0.2f));
        setSize(extent, extent);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return SnowFxRenderType.INSTANCE;
    }

    @Override
    public void tick() {
        WindParticles.counted++;
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        WeatherField field = ClientWeather.field();
        if (field != null) {
            // Ease toward the wind for this spot, so a lull or a change of direction carries the snow with it.
            if ((age & 3) == 1) gust = Mth.clamp(ClientWeather.windKmhAt(x, y, z) / Math.max(1f, field.windKmhAt(y)), 0.5f, 1.8f);
            float target = speed * gust;
            xd += (field.windX() * target - xd) * couple;
            zd += (field.windZ() * target - zd) * couple;
        }
        float lifeLeft = 1f - age / (float) lifetime;
        yd += lift * lifeLeft * 0.25f;
        if (kind == Kind.DRIFT || (!airborne && hover >= 0f)) followGround();
        else yd -= kind == Kind.GRAIN ? 0.004f : 0.0008f;
        yd *= 0.92;
        x += xd;
        y += yd;
        z += zd;
        setPos(x, y, z);
    }

    /** Skims the surface; past an edge the snow keeps going and falls away like spindrift. */
    private void followGround() {
        double want = WindParticles.surfaceY(level, Mth.floor(x + xd), Mth.floor(z + zd)) + hover;
        if (want > y + 2.5) {
            // A wall: it piles up there.
            remove();
            return;
        }
        if (want < y - 1.2) {
            airborne = true;
            hover = -1f;
            return;
        }
        yd = (want - y) * 0.35;
    }

    /** Render calls so far, for diagnostics. */
    static long renders;

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        renders++;
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partialTick, xo, x) - cam.x);
        float py = (float) (Mth.lerp(partialTick, yo, y) - cam.y);
        float pz = (float) (Mth.lerp(partialTick, zo, z) - cam.z);
        float dist = Mth.sqrt(px * px + py * py + pz * pz);
        if (dist < 0.05f) return;

        float t = (age + partialTick) / lifetime;
        float a = peakAlpha * Mth.clamp(t / 0.15f, 0f, 1f) * Mth.clamp((1f - t) / 0.4f, 0f, 1f);
        // Big soft veils right in the face read as a smear: they thin out up close.
        float nearFade = switch (kind) {
            case WISP, DRIFT -> smooth(1.0f, 4.0f, dist);
            case STREAK -> smooth(0.8f, 2.0f, dist);
            case GRAIN -> smooth(0.6f, 1.5f, dist);
        };
        a *= nearFade;
        if (a < 0.003f) return;

        // Along the motion, across both the motion and the line of sight.
        float ax = (float) xd, ay = (float) yd, az = (float) zd;
        float alen = Mth.sqrt(ax * ax + ay * ay + az * az);
        if (alen < 1e-5f) {
            ax = 1f;
            ay = 0f;
            az = 0f;
            alen = 1f;
        }
        ax /= alen;
        ay /= alen;
        az /= alen;
        float vx = px / dist, vy = py / dist, vz = pz / dist;
        float sx = ay * vz - az * vy, sy = az * vx - ax * vz, sz = ax * vy - ay * vx;
        float slen = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (slen < 1e-4f) return;
        sx /= slen;
        sy /= slen;
        sz /= slen;
        // A grain or streak coming straight at the eye (or going straight away) is a speck, not a bright dot: a few
        // pixels of a round sprite rasterise as a plus sign, so these fade out end-on.
        if (kind == Kind.GRAIN || kind == Kind.STREAK) {
            a *= smooth(0.2f, 0.6f, slen);
            if (a < 0.003f) return;
        }
        // Seen end-on a streak shrinks to a dot rather than a long sliver pointing at the eye.
        float grow = 1f + growth * t;
        float along = 0.5f * Math.max(width, length * slen) * grow;
        float across = 0.5f * width * grow;

        float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1();
        int light = getLightColor(partialTick);
        // Counter-clockwise as seen from the camera, like vanilla's particle quads: the other way round they are
        // back faces and culled.
        vertex(buffer, px + ax * along - sx * across, py + ay * along - sy * across, pz + az * along - sz * across, u1, v1, a, light);
        vertex(buffer, px + ax * along + sx * across, py + ay * along + sy * across, pz + az * along + sz * across, u1, v0, a, light);
        vertex(buffer, px - ax * along + sx * across, py - ay * along + sy * across, pz - az * along + sz * across, u0, v0, a, light);
        vertex(buffer, px - ax * along - sx * across, py - ay * along - sy * across, pz - az * along - sz * across, u0, v1, a, light);
    }

    /** Light from just above as well: snow skimming the surface sits inside snow-layer blocks, which read as dark. */
    @Override
    protected int getLightColor(float partialTick) {
        int here = super.getLightColor(partialTick);
        net.minecraft.core.BlockPos above = net.minecraft.core.BlockPos.containing(x, y + 0.8, z);
        int up = level.hasChunkAt(above) ? net.minecraft.client.renderer.LevelRenderer.getLightColor(level, above) : 0;
        return Math.max(here & 0xFFFF, up & 0xFFFF) | Math.max(here >>> 16, up >>> 16) << 16;
    }

    private void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, float a, int light) {
        if (WindFxConfig.debugColour) {
            buffer.addVertex(x, y, z).setUv(u, v).setColor(1f, 0.1f, 0.1f, Math.max(a, 0.9f)).setLight(0xF000F0);
            return;
        }
        buffer.addVertex(x, y, z).setUv(u, v).setColor(rCol, gCol, bCol, a).setLight(light);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
