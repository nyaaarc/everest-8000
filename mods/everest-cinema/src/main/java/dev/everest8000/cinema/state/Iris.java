package dev.everest8000.cinema.state;

import dev.everest8000.cinema.rpc.RpcException;

/** Iris shader toggling through its public API, by reflection so the mod runs without Iris. */
public final class Iris {
    private Iris() {}

    private static Object api() throws ReflectiveOperationException {
        Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        return c.getMethod("getInstance").invoke(null);
    }

    public static boolean present() {
        try {
            api();
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** Whether shaders are switched on in Iris's config, or null without Iris. */
    public static Boolean enabled() {
        try {
            Object api = api();
            Object config = api.getClass().getMethod("getConfig").invoke(api);
            return (Boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Whether a shader pack is rendering right now, or null without Iris. */
    public static Boolean inUse() {
        try {
            Object api = api();
            return (Boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Turns shaders on or off and reloads them (this takes a few seconds on the render thread). */
    public static void set(boolean on) {
        try {
            Object api = api();
            Object config = api.getClass().getMethod("getConfig").invoke(api);
            config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, on);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new RpcException(RpcException.UNSUPPORTED, "Iris isn't available: " + e);
        }
    }
}
