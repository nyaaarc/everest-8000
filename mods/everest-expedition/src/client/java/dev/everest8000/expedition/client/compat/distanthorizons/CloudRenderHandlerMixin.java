package dev.everest8000.expedition.client.compat.distanthorizons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import dev.everest8000.expedition.client.ClientSky;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Distant Horizons draws its own far clouds 100 blocks over the world's build limit (y 2132 here, above the summit),
 * ignoring the dimension's cloud height. Its lowest layer is moved to the weather's cloud base instead; the optional
 * upper layers stay up high as thin cirrus. Only seen without shaders and with Better Clouds off: Better Clouds turns
 * these off while it draws, and Complementary turns them off under Iris.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler", remap = false)
public abstract class CloudRenderHandlerMixin {
    /** Where Distant Horizons puts its lowest layer, captured as it works it out. */
    @Unique
    private int everest$lowestLayerY = Integer.MIN_VALUE;

    @WrapOperation(method = "preRender", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;getMaxHeight()I"))
    private int everest$captureLayerBase(ILevelWrapper level, Operation<Integer> original) {
        int maxHeight = original.call(level);
        everest$lowestLayerY = maxHeight + 100;
        return maxHeight;
    }

    @WrapOperation(method = "preRender", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/api/interfaces/render/IDhApiRenderableBoxGroup;setOriginBlockPos(Lcom/seibel/distanthorizons/api/objects/math/DhApiVec3d;)V"))
    private void everest$cloudDeck(IDhApiRenderableBoxGroup group, DhApiVec3d origin, Operation<Void> original) {
        if (ClientSky.active() && Math.abs(origin.y - everest$lowestLayerY) < 0.5) {
            origin = new DhApiVec3d(origin.x, ClientSky.cloudHeight(), origin.z);
        }
        original.call(group, origin);
    }
}
