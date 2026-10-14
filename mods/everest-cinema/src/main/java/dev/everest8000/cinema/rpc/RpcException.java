package dev.everest8000.cinema.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.spec.SpecException;

/** A JSON-RPC error with one of the codes below and optional structured data. */
public final class RpcException extends RuntimeException {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL = -32603;
    /** No world loaded (still on the title screen, connecting, or disconnected). */
    public static final int NOT_IN_WORLD = -32000;
    /** A token is configured and this connection hasn't sent it with session.hello. */
    public static final int UNAUTHORIZED = -32001;
    /** A render or capture is running; only read-only calls are allowed until it ends. */
    public static final int BUSY = -32002;
    /** The operation didn't finish within its timeout. */
    public static final int TIMEOUT = -32003;
    /** A game command reported failure (data.feedback has the messages). */
    public static final int COMMAND_FAILED = -32004;
    /** Video capture failed (ffmpeg missing or crashed; data.log has the end of its output). */
    public static final int CAPTURE_FAILED = -32005;
    /** state.restore without a snapshot. */
    public static final int NO_SNAPSHOT = -32006;
    /** Interrupted by a stop call, a newer request, or the world closing. */
    public static final int CANCELLED = -32007;
    /** Needs a mod or feature that isn't there (e.g. shaders without Iris). */
    public static final int UNSUPPORTED = -32008;
    /** A target entity couldn't be found. */
    public static final int NOT_FOUND = -32009;
    /** The player didn't reach a walk target (blocked). */
    public static final int STUCK = -32010;

    private final int code;
    private final transient JsonElement data;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    public RpcException(int code, String message, JsonElement data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public JsonElement data() {
        return data;
    }

    public static RpcException invalidParams(SpecException e) {
        JsonObject d = new JsonObject();
        d.addProperty("path", e.path());
        d.addProperty("reason", e.reason());
        return new RpcException(INVALID_PARAMS, e.getMessage(), d);
    }

    public static RpcException notInWorld() {
        return new RpcException(NOT_IN_WORLD, "not in a world (title screen, connecting or disconnected)");
    }

    public static RpcException busy(String what) {
        return new RpcException(BUSY, "busy: " + what + " is running; only read-only calls are allowed until it ends");
    }
}
