package dev.everest8000.expedition.client.mixin;

import dev.everest8000.expedition.client.ClientWeather;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Above the snowline it snows, whatever biome the map painted there (some high ground uses warm or dry biomes). */
@Mixin(Biome.class)
public abstract class BiomePrecipitationMixin {
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void everest$snowline(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (pos.getY() > WeatherField.SNOWLINE_Y && ClientWeather.active()) cir.setReturnValue(Biome.Precipitation.SNOW);
    }
}
