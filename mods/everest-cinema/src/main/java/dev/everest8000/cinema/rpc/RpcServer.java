package dev.everest8000.cinema.rpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import dev.everest8000.cinema.CinemaClient;
import dev.everest8000.cinema.spec.JObj;
import dev.everest8000.cinema.spec.SpecException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC 2.0 over TCP on 127.0.0.1, one JSON object per line in each direction (UTF-8, '\n' terminated). Requests on
 * one connection run in order; open more connections to poll status while a long call runs. Batches are rejected.
 */
public final class RpcServer {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final long MAX_WAIT_MS = TimeUnit.HOURS.toMillis(12);

    /** A method: parses params on the connection thread (pure) and returns the eventual result. */
    public interface Handler {
        CompletableFuture<JsonElement> call(JObj params, Session session);
    }

    public static final class Session {
        public final int id;
        volatile boolean authenticated;

        Session(int id, boolean authenticated) {
            this.id = id;
            this.authenticated = authenticated;
        }

        public boolean authenticated() {
            return authenticated;
        }
    }

    private final int port;
    private final String token;
    private final Map<String, Handler> methods;
    private final AtomicInteger sessions = new AtomicInteger();
    private ServerSocket server;

    public RpcServer(int port, String token, Map<String, Handler> methods) {
        this.port = port;
        this.token = token == null ? "" : token;
        this.methods = methods;
    }

    public void start() throws IOException {
        server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "Cinema RPC accept");
        t.setDaemon(true);
        t.start();
        CinemaClient.LOG.info("Cinema API listening on 127.0.0.1:{}{}", port, token.isEmpty() ? "" : " (token required)");
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Session session = new Session(sessions.incrementAndGet(), token.isEmpty());
                Thread t = new Thread(() -> serve(s, session), "Cinema RPC #" + session.id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) CinemaClient.LOG.warn("Cinema API accept failed", e);
            }
        }
    }

    private void serve(Socket socket, Session session) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject response = handle(line, session);
                if (response != null) {
                    out.write((GSON.toJson(response) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException e) {
            // Client went away.
        }
    }

    private JsonObject handle(String line, Session session) {
        JsonElement req;
        try {
            req = JsonParser.parseString(line);
        } catch (JsonSyntaxException e) {
            return error(JsonNull.INSTANCE, new RpcException(RpcException.PARSE_ERROR, "parse error: " + e.getMessage()));
        }
        if (req.isJsonArray()) {
            return error(JsonNull.INSTANCE, new RpcException(RpcException.INVALID_REQUEST, "batch requests are not supported"));
        }
        if (!req.isJsonObject()) return error(JsonNull.INSTANCE, new RpcException(RpcException.INVALID_REQUEST, "request must be an object"));
        JsonObject r = req.getAsJsonObject();
        JsonElement id = r.has("id") ? r.get("id") : null;
        boolean notification = id == null;
        if (id != null && !(id.isJsonNull() || id.isJsonPrimitive() && (id.getAsJsonPrimitive().isNumber() || id.getAsJsonPrimitive().isString()))) {
            return error(JsonNull.INSTANCE, new RpcException(RpcException.INVALID_REQUEST, "id must be a string, number or null"));
        }
        JsonElement respId = id == null ? JsonNull.INSTANCE : id;
        if (!r.has("jsonrpc") || !new JsonPrimitive("2.0").equals(r.get("jsonrpc"))) {
            return error(respId, new RpcException(RpcException.INVALID_REQUEST, "jsonrpc must be \"2.0\""));
        }
        if (!r.has("method") || !r.get("method").isJsonPrimitive() || !r.get("method").getAsJsonPrimitive().isString()) {
            return error(respId, new RpcException(RpcException.INVALID_REQUEST, "method must be a string"));
        }
        for (String k : r.keySet()) {
            if (!k.equals("jsonrpc") && !k.equals("method") && !k.equals("params") && !k.equals("id")) {
                return error(respId, new RpcException(RpcException.INVALID_REQUEST, "unknown request member \"" + k + "\""));
            }
        }
        String method = r.get("method").getAsString();
        JsonElement params = r.get("params");
        if (params != null && !params.isJsonNull() && !params.isJsonObject()) {
            return error(respId, new RpcException(RpcException.INVALID_PARAMS, "params must be an object (by-name parameters)"));
        }
        long start = System.nanoTime();
        try {
            JsonElement result = invoke(method, params, session);
            log(session, method, start, null);
            if (notification) return null;
            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", "2.0");
            resp.add("id", respId);
            resp.add("result", result == null ? JsonNull.INSTANCE : result);
            return resp;
        } catch (RpcException e) {
            log(session, method, start, e.code() + " " + e.getMessage());
            return notification ? null : error(respId, e);
        } catch (RuntimeException e) {
            CinemaClient.LOG.error("Cinema API: {} failed", method, e);
            log(session, method, start, "internal " + e);
            return notification ? null : error(respId, internal(e));
        }
    }

    private JsonElement invoke(String method, JsonElement params, Session session) {
        if (method.equals("session.hello")) return hello(params, session);
        if (!session.authenticated) {
            throw new RpcException(RpcException.UNAUTHORIZED, "call session.hello with the token from config/everest-cinema.json first");
        }
        Handler h = methods.get(method);
        if (h == null) throw new RpcException(RpcException.METHOD_NOT_FOUND, "no method \"" + method + "\"; see session.hello for the list");
        CompletableFuture<JsonElement> f;
        try {
            f = h.call(JObj.of(params, "params"), session);
        } catch (SpecException e) {
            throw RpcException.invalidParams(e);
        }
        try {
            return f.get(MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | CompletionException e) {
            throw unwrap(e.getCause());
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new RpcException(RpcException.TIMEOUT, method + " didn't finish in 12 hours");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcException.CANCELLED, "interrupted");
        }
    }

    private JsonElement hello(JsonElement params, Session session) {
        JObj p;
        String given;
        try {
            p = JObj.of(params, "params");
            given = p.has("token") ? p.string("token") : "";
            p.finish();
        } catch (SpecException e) {
            throw RpcException.invalidParams(e);
        }
        if (!token.isEmpty()) {
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                throw new RpcException(RpcException.UNAUTHORIZED, "wrong token");
            }
        }
        session.authenticated = true;
        JsonObject o = new JsonObject();
        o.addProperty("apiVersion", CinemaClient.API_VERSION);
        o.addProperty("mod", CinemaClient.modVersion());
        o.addProperty("minecraft", "1.21.1");
        com.google.gson.JsonArray m = new com.google.gson.JsonArray();
        methods.keySet().stream().sorted().forEach(m::add);
        o.add("methods", m);
        return o;
    }

    static RpcException unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        if (t instanceof RpcException r) return r;
        if (t instanceof SpecException s) return RpcException.invalidParams(s);
        if (t instanceof java.util.concurrent.CancellationException) return new RpcException(RpcException.CANCELLED, "cancelled");
        CinemaClient.LOG.error("Cinema API: internal error", t);
        return internal(t);
    }

    private static RpcException internal(Throwable t) {
        JsonObject d = new JsonObject();
        d.addProperty("exception", t.getClass().getName());
        d.addProperty("message", String.valueOf(t.getMessage()));
        return new RpcException(RpcException.INTERNAL, "internal error: " + t, d);
    }

    private static JsonObject error(JsonElement id, RpcException e) {
        JsonObject err = new JsonObject();
        err.addProperty("code", e.code());
        err.addProperty("message", e.getMessage());
        if (e.data() != null) err.add("data", e.data());
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        return resp;
    }

    private static void log(Session s, String method, long startNanos, String error) {
        double ms = (System.nanoTime() - startNanos) / 1e6;
        CinemaClient.apiLog(String.format(java.util.Locale.ROOT, "#%d %s %.0f ms%s", s.id, method, ms, error == null ? "" : " ERROR " + error));
    }
}
