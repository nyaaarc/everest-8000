package dev.everest8000.expedition.client;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/** Whether an Iris shader pack is on, read through Iris's public API without compiling against it. */
public final class Shaders {
    private static final Object API;
    private static final Method IN_USE;

    static {
        Object api = null;
        Method inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                EverestExpedition.LOG.warn("Couldn't reach the Iris API; whiteout fog will also draw under shaders", e);
                api = null;
                inUse = null;
            }
        }
        API = api;
        IN_USE = inUse;
    }

    private Shaders() {}

    public static boolean active() {
        if (IN_USE == null) return false;
        try {
            return (boolean) IN_USE.invoke(API);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
