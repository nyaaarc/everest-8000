package dev.everest8000.expedition.client.wind;

import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.client.ClientWeather;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Snow the wind picks up around the player:
 * <ul>
 *   <li>spindrift streaming off crests and cornices (snow with a drop on its downwind side), as long horizontal veils;</li>
 *   <li>ground drift: low snakes of snow skimming the surface, in the bands and patches where gusts are passing;</li>
 *   <li>in hurricane-force wind, faint fast streaks of fine snow close to the eye.</li>
 * </ul>
 * All of it follows the gust field, so it surges and slackens with the push and the roar.
 */
public final class WindParticles {
    private static final ResourceLocation GRAIN = EverestExpedition.id("snow_grain");
    private static final ResourceLocation STREAK = EverestExpedition.id("snow_streak");
    private static final ResourceLocation[] WISPS = {
            EverestExpedition.id("snow_wisp_0"), EverestExpedition.id("snow_wisp_1"),
            EverestExpedition.id("snow_wisp_2"), EverestExpedition.id("snow_wisp_3")};

    /** Wind (km/h, gust included) where snow starts to lift off crests, and where it is fully streaming. */
    private static final float RIDGE_FROM = 40f;
    private static final float RIDGE_FULL = 110f;
    private static final float DRIFT_FROM = 32f;
    private static final float DRIFT_FULL = 95f;
    private static final float STREAKS_FROM = 80f;
    /** How far around the player crests are looked for. */
    private static final int EDGE_RADIUS = 36;

    /** Live particles: each one counts itself as it ticks, so the total heals itself after a world change. */
    static int counted;
    private static int live;

    private record Edge(int x, int top, int z, float score) {}

    private static final List<Edge> EDGES = new ArrayList<>();
    private static double scanX = Double.NaN, scanZ;
    private static float scanAngle;
    private static int scanAge;

    private WindParticles() {}

    public static int live() {
        return live;
    }

    /** For tests and debugging: is blown snow's layer drawn, what does the engine hold, how often is it drawn. */
    public static String diagnose() {
        var order = dev.everest8000.expedition.client.mixin.ParticleEngineAccessor.everest$renderOrder();
        var held = ((dev.everest8000.expedition.client.mixin.ParticleEngineAccessor) Minecraft.getInstance().particleEngine)
                .everest$particles().get(SnowFxRenderType.INSTANCE);
        return "inRenderOrder=" + order.contains(SnowFxRenderType.INSTANCE) + " order=" + order + " held="
                + (held == null ? 0 : held.size()) + " renders=" + SnowFxParticle.renders + " live=" + live;
    }

    public static void tick(Minecraft mc) {
        live = counted;
        counted = 0;
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused() || !ClientWeather.active()) return;
        float amount = WindFxConfig.windParticles;
        if (amount <= 0f) return;
        WeatherField field = ClientWeather.field();
        if (field == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        Vec3 cam = camera.getPosition();
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
        int budget = WindFxConfig.maxWindParticles - live;
        if (budget <= 0) return;

        RandomSource random = level.random;
        float windX = field.windX(), windZ = field.windZ();
        rescanEdges(level, cam, windX, windZ);
        budget -= ridges(level, atlas, random, windX, windZ, amount, budget);
        if (budget > 0) budget -= drift(level, atlas, random, cam, windX, windZ, amount, budget);
        if (budget > 0) streaks(mc, level, atlas, random, camera, windX, windZ, amount, budget);
    }

    // --- Crests -------------------------------------------------------------------------------------------------

    /** Finds snow with a drop on its downwind side near the player; redone as the player or the wind moves. */
    private static void rescanEdges(ClientLevel level, Vec3 cam, float windX, float windZ) {
        float angle = (float) Math.atan2(windZ, windX);
        scanAge++;
        boolean moved = Double.isNaN(scanX) || Mth.square(cam.x - scanX) + Mth.square(cam.z - scanZ) > 36.0;
        if (!moved && scanAge < 60 && Math.abs(Mth.wrapDegrees((angle - scanAngle) * Mth.RAD_TO_DEG)) < 12f) return;
        scanX = cam.x;
        scanZ = cam.z;
        scanAngle = angle;
        scanAge = 0;
        EDGES.clear();
        int cx = Mth.floor(cam.x), cz = Mth.floor(cam.z);
        for (int dz = -EDGE_RADIUS; dz <= EDGE_RADIUS; dz += 2) {
            for (int dx = -EDGE_RADIUS; dx <= EDGE_RADIUS; dx += 2) {
                if (dx * dx + dz * dz > EDGE_RADIUS * EDGE_RADIUS) continue;
                int x = cx + dx, z = cz + dz;
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (Math.abs(top - cam.y) > 24 || !snowy(level, x, top, z)) continue;
                int downNear = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x + windX * 2.5f), Mth.floor(z + windZ * 2.5f));
                int downFar = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x + windX * 5f), Mth.floor(z + windZ * 5f));
                int up = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x - windX * 4f), Mth.floor(z - windZ * 4f));
                float drop = top - Math.min(downNear, downFar);
                if (drop < 1.5f) continue;
                // Sheltered behind higher ground upwind, a crest stays quiet.
                float exposed = up > top + 1 ? 0.25f : 1f;
                float score = Mth.clamp((drop - 1f) / 5f, 0f, 1f) * exposed;
                if (score > 0.1f) EDGES.add(new Edge(x, top, z, score));
            }
        }
    }

    private static int ridges(ClientLevel level, TextureAtlas atlas, RandomSource random, float windX, float windZ,
                              float amount, int budget) {
        if (EDGES.isEmpty()) return 0;
        int spawned = 0;
        int attempts = Math.min(EDGES.size(), Math.round(30 * amount));
        for (int i = 0; i < attempts && spawned < budget; i++) {
            Edge e = EDGES.get(random.nextInt(EDGES.size()));
            float kmh = ClientWeather.windKmhAt(e.x + 0.5, e.top, e.z + 0.5);
            float wind = smooth(RIDGE_FROM, RIDGE_FULL, kmh);
            if (random.nextFloat() >= e.score * wind * 0.9f) continue;
            float speed = Gusts.blocksPerTick(kmh) * (0.75f + random.nextFloat() * 0.35f);
            double x = e.x + 0.5 + windX * 1.2 + (random.nextFloat() - 0.5) * 1.6;
            double z = e.z + 0.5 + windZ * 1.2 + (random.nextFloat() - 0.5) * 1.6;
            double y = surfaceY(level, e.x, e.z) + 0.1 + random.nextFloat() * 0.5;
            if (random.nextFloat() < 0.65f) {
                // The veil: long, soft, growing as it streams out over the drop.
                add(level, x, y + 0.3, z, windX * speed, 0.05, windZ * speed, sprite(atlas, WISPS[random.nextInt(WISPS.length)]),
                        SnowFxParticle.Kind.WISP, 2.6f + random.nextFloat() * 3.0f, 0.8f + random.nextFloat() * 1.0f,
                        (0.26f + random.nextFloat() * 0.22f) * (0.6f + 0.4f * wind), 30 + random.nextInt(26), 1.6f,
                        speed, 0.02f, 0.06f, -1f);
                spawned++;
            }
            int grains = 1 + random.nextInt(3);
            for (int g = 0; g < grains && spawned < budget; g++) {
                float s = speed * (0.85f + random.nextFloat() * 0.4f);
                add(level, x + (random.nextFloat() - 0.5) * 0.8, y + random.nextFloat() * 0.6, z + (random.nextFloat() - 0.5) * 0.8,
                        windX * s, 0.04 + random.nextFloat() * 0.08, windZ * s, sprite(atlas, GRAIN), SnowFxParticle.Kind.GRAIN,
                        s * 0.55f, 0.012f + random.nextFloat() * 0.013f, 0.35f + random.nextFloat() * 0.25f,
                        18 + random.nextInt(18), 0f, s, 0.025f, 0.12f, -1f);
                spawned++;
            }
        }
        return spawned;
    }

    // --- Ground drift -------------------------------------------------------------------------------------------

    private static int drift(ClientLevel level, TextureAtlas atlas, RandomSource random, Vec3 cam, float windX, float windZ,
                             float amount, int budget) {
        float wind = smooth(DRIFT_FROM, DRIFT_FULL, ClientWeather.windKmhAt(cam.y));
        if (wind <= 0f) return 0;
        int spawned = 0;
        int attempts = Math.round(30 * amount * wind);
        double t = level.getGameTime();
        for (int i = 0; i < attempts && spawned < budget; i++) {
            // Mostly upwind of the player, so the drift runs past and through them.
            double angle = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * 22;
            double x = cam.x + Math.cos(angle) * r - windX * 7;
            double z = cam.z + Math.sin(angle) * r - windZ * 7;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            if (Math.abs(top - cam.y) > 12 || !snowy(level, bx, top, bz)) continue;
            // Snakes: narrow bands along the wind, wandering slowly, and only where a gust is passing.
            double along = x * windX + z * windZ, across = -x * windZ + z * windX;
            double band = Gusts.Noise.at(across / 3.2, along / 26.0, t / 60.0);
            float gust = ClientWeather.windKmhAt(x, top, z) / Math.max(1f, ClientWeather.field().windKmhAt(top));
            float p = smooth(0.92f, 1.35f, gust) * (float) Mth.clamp((band + 0.05) * 3.0, 0.0, 1.0);
            if (random.nextFloat() >= p) continue;
            int up = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x - windX * 3), Mth.floor(z - windZ * 3));
            if (up > top + 1) continue;
            float kmh = ClientWeather.windKmhAt(x, top, z);
            float speed = Gusts.blocksPerTick(kmh) * (0.6f + random.nextFloat() * 0.3f);
            float hover = 0.12f + random.nextFloat() * 0.25f;
            double surface = surfaceY(level, bx, bz);
            add(level, x, surface + hover, z, windX * speed, 0, windZ * speed, sprite(atlas, WISPS[random.nextInt(WISPS.length)]),
                    SnowFxParticle.Kind.DRIFT, 3.0f + random.nextFloat() * 3.0f, 0.4f + random.nextFloat() * 0.45f,
                    0.3f + random.nextFloat() * 0.22f, 24 + random.nextInt(24), 0.5f, speed, 0f, 0.1f, hover);
            spawned++;
            if (random.nextFloat() < 0.7f && spawned < budget) {
                float s = speed * (1f + random.nextFloat() * 0.3f);
                float h = 0.05f + random.nextFloat() * 0.35f;
                add(level, x + (random.nextFloat() - 0.5), surface + h, z + (random.nextFloat() - 0.5), windX * s, 0, windZ * s,
                        sprite(atlas, GRAIN), SnowFxParticle.Kind.GRAIN, s * 0.5f, 0.012f + random.nextFloat() * 0.012f,
                        0.3f + random.nextFloat() * 0.25f, 16 + random.nextInt(16), 0f, s, 0f, 0.15f, h);
                spawned++;
            }
        }
        return spawned;
    }

    // --- Air streaks --------------------------------------------------------------------------------------------

    private static void streaks(Minecraft mc, ClientLevel level, TextureAtlas atlas, RandomSource random, Camera camera,
                                float windX, float windZ, float amount, int budget) {
        if (ClientWeather.underCover()) return;
        float kmh = ClientWeather.windKmh() * ClientWeather.windShare();
        if (kmh < STREAKS_FROM) return;
        float rate = (kmh - STREAKS_FROM) / 45f * 2.2f * amount;
        int n = (int) rate + (random.nextFloat() < rate - (int) rate ? 1 : 0);
        Vec3 cam = camera.getPosition();
        Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        float speed = Gusts.blocksPerTick(kmh) * 1.6f;
        for (int i = 0; i < Math.min(n, budget); i++) {
            // Inside the view, a few blocks out, started upwind so they cross the screen.
            Vec3 dir = look.xRot((random.nextFloat() - 0.5f) * 0.9f).yRot((random.nextFloat() - 0.5f) * 1.6f);
            double d = 2.5 + random.nextDouble() * 10;
            int life = 7 + random.nextInt(7);
            float s = speed * (0.8f + random.nextFloat() * 0.5f);
            double back = s * life * 0.5;
            double x = cam.x + dir.x * d - windX * back;
            double y = cam.y + dir.y * d + (random.nextFloat() - 0.5) * 1.5;
            double z = cam.z + dir.z * d - windZ * back;
            add(level, x, y, z, windX * s, (random.nextFloat() - 0.5f) * 0.03f, windZ * s, sprite(atlas, STREAK),
                    SnowFxParticle.Kind.STREAK, s * 2.4f, 0.02f + random.nextFloat() * 0.012f, 0.1f + random.nextFloat() * 0.14f,
                    life, 0f, s, 0f, 0.3f, -1f);
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    private static boolean snowy(ClientLevel level, int x, int top, int z) {
        BlockState surface = level.getBlockState(new BlockPos(x, top - 1, z));
        if (surface.is(BlockTags.SNOW)) return true;
        // A thin snow layer doesn't count toward the heightmap; it sits on top.
        return level.getBlockState(new BlockPos(x, top, z)).is(BlockTags.SNOW);
    }

    /** The real height of the snow's surface: a snow layer is only as tall as its layers. */
    static double surfaceY(ClientLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockState below = level.getBlockState(new BlockPos(x, top - 1, z));
        if (below.is(Blocks.SNOW)) return top - 1 + below.getValue(SnowLayerBlock.LAYERS) / 8.0;
        BlockState at = level.getBlockState(new BlockPos(x, top, z));
        if (at.is(Blocks.SNOW)) return top + at.getValue(SnowLayerBlock.LAYERS) / 8.0;
        return top;
    }

    private static TextureAtlasSprite sprite(TextureAtlas atlas, ResourceLocation id) {
        return atlas.getSprite(id);
    }

    private static void add(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                            TextureAtlasSprite sprite, SnowFxParticle.Kind kind, float length, float width, float alpha,
                            int lifetime, float growth, float speed, float lift, float couple, float hover) {
        Minecraft.getInstance().particleEngine.add(new SnowFxParticle(level, x, y, z, xd, yd, zd, sprite, kind, length,
                width, alpha, lifetime, growth, speed, lift, couple, hover));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
