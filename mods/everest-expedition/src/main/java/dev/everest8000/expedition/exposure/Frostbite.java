package dev.everest8000.expedition.exposure;

import dev.everest8000.expedition.gear.EverestGear;
import dev.everest8000.expedition.weather.WeatherDirector;
import dev.everest8000.expedition.weather.WeatherField;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cold exposure, built on vanilla freezing so it looks native: the frost overlay creeps in over a minute or more
 * and players slow down. Once frozen through they lose half a heart every few seconds and can't heal (so peaceful
 * regeneration doesn't cancel it), until a tent, hut or lit campfire thaws them out.
 *
 * <p>Two kinds of cold: storms and wind, which can hit anywhere high up, and the plain cold of altitude above Camp 1,
 * which freezes you even on a clear day. The down suit keeps out all of the altitude cold and most of the weather.
 * Nights are colder.
 */
public final class Frostbite {
    /** Vanilla thaws everyone by 2 ticks of frost per tick; exposure has to beat that to build up. */
    private static final int VANILLA_THAW = 2;
    /** Time to freeze through in the worst storm; milder cold takes proportionally longer. */
    private static final float FULL_FREEZE_SECONDS = 90f;
    /** Half a heart this often at the worst cold, and this often at the mildest cold that still hurts. */
    private static final int DAMAGE_EVERY_TICKS_WORST = 80;
    private static final int DAMAGE_EVERY_TICKS_MILD = 200;
    /** Altitude cold starts at Camp 1, right at the level where frost starts to build. */
    private static final float ALTITUDE_COLD_FROM_Y = 540f;
    private static final float ALTITUDE_COLD_AT_CAMP_1 = 0.3f;
    /** At the summit on a clear day: frozen through in about three minutes without the suit. */
    private static final float ALTITUDE_COLD_AT_SUMMIT = 0.65f;
    /** The full down suit cuts storm and wind chill by this much; each piece its share of it. */
    private static final float SUIT_WEATHER_CUT = 0.6f;
    /** Altitude cold at midnight relative to the day, and storm and wind chill likewise. */
    private static final float NIGHT_ALTITUDE_COLD = 1.5f;
    private static final float NIGHT_WEATHER_CHILL = 1.25f;
    /** Valley showers only chill at night, and only a heavy one enough to start frost. */
    private static final float NIGHT_SHOWER_CHILL = 0.35f;
    private static final Map<UUID, Boolean> NEAR_HEAT = new HashMap<>();
    private static final Map<UUID, Integer> SINCE_DAMAGE = new HashMap<>();
    private static final Set<UUID> FREEZING = new HashSet<>();

    private Frostbite() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Frostbite::tick);
    }

    /**
     * 0 = fine, 1 = lethal. The worse of the weather (storm snow plus wind chill, which grows with height) and the
     * altitude cold. {@code warmth} is how much of the down suit is worn (0..1), {@code night} 0 by day and 1 at midnight.
     */
    public static float chill(WeatherField field, double y, float windShare, boolean underCover, float warmth, float night) {
        float storm = underCover ? 0f : field.stormAt(y);
        float shower = underCover ? 0f : field.showerAt(y) * NIGHT_SHOWER_CHILL * night;
        float high = Mth.clamp(((float) y - 700f) / 1300f, 0f, 1f);
        float windChill = Mth.clamp((field.windKmhAt(y) * windShare - 50f) / 130f, 0f, 1f) * (0.3f + 0.7f * high);
        float weather = (storm * 0.9f + windChill * 0.6f + shower) * Mth.lerp(night, 1f, NIGHT_WEATHER_CHILL)
                * (1f - SUIT_WEATHER_CUT * warmth);
        float altitude = underCover ? 0f : altitudeCold(y, night) * (1f - warmth);
        // The worse of the two rather than the sum, so storms hit an unsuited climber as hard as they always did.
        return Mth.clamp(Math.max(weather, altitude), 0f, 1f);
    }

    /** The cold of altitude alone, above Camp 1, as a chill level. */
    public static float altitudeCold(double y, float night) {
        if (y < ALTITUDE_COLD_FROM_Y) return 0f;
        float t = Mth.clamp(((float) y - ALTITUDE_COLD_FROM_Y) / (WeatherField.SUMMIT_Y - ALTITUDE_COLD_FROM_Y), 0f, 1f);
        return Mth.lerp(t, ALTITUDE_COLD_AT_CAMP_1, ALTITUDE_COLD_AT_SUMMIT) * Mth.lerp(night, 1f, NIGHT_ALTITUDE_COLD);
    }

    /** 0 all day, rising after sunset to 1 at midnight. */
    public static float night(Level level) {
        return Math.max(0f, -Mth.cos(level.getTimeOfDay(1f) * Mth.TWO_PI));
    }

    /** Frozen through and still out in it: healing is blocked (see LivingEntityHealMixin). */
    public static boolean isFreezing(ServerPlayer player) {
        return FREEZING.contains(player.getUUID());
    }

    private static void tick(MinecraftServer server) {
        boolean recheckHeat = server.getTickCount() % 10 == 0;
        WeatherField field = WeatherDirector.current();
        Level overworld = server.overworld();
        float night = night(overworld);
        FREEZING.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (player.isCreative() || player.isSpectator() || !player.isAlive() || player.level() != overworld) continue;
            if (recheckHeat) NEAR_HEAT.put(id, nearHeat(player));
            boolean warm = NEAR_HEAT.getOrDefault(id, false);
            double eyeY = player.getEyeY();
            boolean covered = Exposure.underCover(overworld, player.getX(), eyeY, player.getZ());
            float share = Exposure.windShare(overworld, field, player.getX(), player.getY(), player.getZ(), eyeY);
            float chill = warm ? 0f : chill(field, player.getY(), share, covered, EverestGear.warmth(player), night);

            int need = player.getTicksRequiredToFreeze();
            int frozen = player.getTicksFrozen();
            if (warm || covered) {
                frozen = Math.max(0, frozen - 3);
            } else if (chill > 0.3f) {
                float gain = (chill - 0.3f) / 0.7f * need / (FULL_FREEZE_SECONDS * 20f);
                // Held one tick short of vanilla's "fully frozen" so its own fast freeze damage never kicks in.
                frozen = Math.min(need - 1, frozen + VANILLA_THAW + (player.getRandom().nextFloat() < gain ? 1 : 0));
            }
            player.setTicksFrozen(frozen);

            boolean frozenThrough = frozen >= need - 5 && chill > 0.4f && !warm && !covered;
            if (!frozenThrough) {
                SINCE_DAMAGE.remove(id);
                continue;
            }
            FREEZING.add(id);
            int since = SINCE_DAMAGE.getOrDefault(id, 0) + 1;
            int every = Math.round(Mth.lerp((chill - 0.4f) / 0.6f, DAMAGE_EVERY_TICKS_MILD, DAMAGE_EVERY_TICKS_WORST));
            if (since >= every) {
                player.hurt(player.damageSources().freeze(), 1f);
                since = 0;
            }
            SINCE_DAMAGE.put(id, since);
        }
        if (recheckHeat) {
            NEAR_HEAT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            SINCE_DAMAGE.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
    }

    private static boolean nearHeat(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, -1, -3), center.offset(3, 2, 3))) {
            BlockState state = player.level().getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.is(Blocks.LAVA)) return true;
            if (state.is(BlockTags.CAMPFIRES) && state.getOptionalValue(BlockStateProperties.LIT).orElse(false)) return true;
        }
        return false;
    }
}
