package dev.everest8000.expedition.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
import dev.everest8000.expedition.client.Shaders;
import dev.everest8000.expedition.client.Whiteout;

import java.awt.Color;

/**
 * Carries the whiteout into Distant Horizons' far terrain, which has its own fog. The fog is made linear from the
 * camera to the same visibility distance the vanilla fog uses, so near and far fade as one. Shader packs fog the
 * LODs themselves. Only loaded when Distant Horizons is installed.
 */
public final class DistantHorizonsFog extends DhApiBeforeFogRenderEvent {
    private DistantHorizonsFog() {}

    public static void register() {
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new DistantHorizonsFog());
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
        float lodRange = lodRangeBlocks();
        Whiteout.setLodRange(lodRange);
        float w = Whiteout.strength();
        if (w <= 0f || lodRange <= 0f || Shaders.active()) return;
        float visibility = Whiteout.visibility(lodRange);
        DhApiMutableFogRenderParam fog = event.value.getFogRenderParam();
        fog.setFarFogFalloff(EDhApiFogFalloff.LINEAR);
        fog.setFarFogStartPercent(0f);
        fog.setFarFogEndPercent(Math.min(1f, visibility / lodRange));
        fog.setFarFogMinThickness(0f);
        fog.setFarFogMaxThickness(1f);
        fog.setFarFogDensity(1f);
        fog.setFogColor(new Color(Whiteout.red(), Whiteout.green(), Whiteout.blue()));
    }

    private static float lodRangeBlocks() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs == null) return 0f;
        Integer chunks = configs.graphics().chunkRenderDistance().getValue();
        return chunks == null ? 0f : chunks * 16f;
    }
}
