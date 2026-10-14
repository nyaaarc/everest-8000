package dev.everest8000.expedition.weather;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Keeps the weather model in the world save (data/everest_weather.dat), so a restart doesn't reset a storm. */
public final class WeatherState extends SavedData {
    public static final String NAME = "everest_weather";
    public static final Factory<WeatherState> FACTORY =
            new Factory<>(() -> new WeatherState(new WeatherModel()), WeatherState::load, null);

    private final WeatherModel model;

    private WeatherState(WeatherModel model) {
        this.model = model;
    }

    public WeatherModel model() {
        return model;
    }

    private static WeatherState load(CompoundTag tag, HolderLookup.Provider registries) {
        return new WeatherState(WeatherModel.load(tag));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return model.save(tag);
    }
}
