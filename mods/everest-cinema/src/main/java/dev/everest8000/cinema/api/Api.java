package dev.everest8000.cinema.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.ActionRunner;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.CommandTask;
import dev.everest8000.cinema.LoadWaitTask;
import dev.everest8000.cinema.Task;
import dev.everest8000.cinema.camera.Director;
import dev.everest8000.cinema.camera.McTargets;
import dev.everest8000.cinema.camera.MovePlayer;
import dev.everest8000.cinema.capture.Recorder;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.rpc.RpcServer.Handler;
import dev.everest8000.cinema.shot.ShotRunner;
import dev.everest8000.cinema.spec.Action;
import dev.everest8000.cinema.spec.Easing;
import dev.everest8000.cinema.spec.JObj;
import dev.everest8000.cinema.spec.Move;
import dev.everest8000.cinema.spec.Pose;
import dev.everest8000.cinema.spec.PreviewSettings;
import dev.everest8000.cinema.spec.RenderSettings;
import dev.everest8000.cinema.spec.ShotSpec;
import dev.everest8000.cinema.spec.SpecException;
import dev.everest8000.cinema.spec.Target;
import dev.everest8000.cinema.spec.Vec3d;
import dev.everest8000.cinema.state.Iris;
import dev.everest8000.cinema.state.Snapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Every JSON-RPC method. Parameters are parsed strictly on the connection thread; work runs on the render thread. */
public final class Api {
    private static final Pattern ABS_PATH = Pattern.compile("^([A-Za-z]:[\\\\/]|/|\\\\\\\\).+");

    private Api() {}

    public static Map<String, Handler> methods() {
        Map<String, Handler> m = new LinkedHashMap<>();

        // ---- state
        m.put("state.get", (p, s) -> {
            p.finish();
            return Cinema.onMain(Api::state);
        });
        m.put("state.snapshot", (p, s) -> {
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                return Cinema.addTask(Snapshot.take());
            });
        });
        m.put("state.restore", (p, s) -> {
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                if (Cinema.snapshot == null) throw new RpcException(RpcException.NO_SNAPSHOT, "no snapshot; call state.snapshot first");
                return Cinema.addTask(Cinema.snapshot.restore());
            });
        });
        m.put("game.quit", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                // Reply first, then stop on a later frame.
                mc.tell(() -> mc.tell(mc::stop));
                JsonObject r = new JsonObject();
                r.addProperty("quitting", true);
                return r;
            });
        });
        m.put("screen.get", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                JsonObject r = new JsonObject();
                r.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getName());
                r.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
                r.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
                r.addProperty("guiScale", mc.getWindow().getGuiScale());
                JsonArray widgets = new JsonArray();
                if (mc.screen != null) {
                    for (var child : mc.screen.children()) {
                        JsonObject w = new JsonObject();
                        w.addProperty("class", child.getClass().getName());
                        if (child instanceof net.minecraft.client.gui.components.AbstractWidget aw) {
                            w.addProperty("message", aw.getMessage().getString());
                            w.addProperty("x", aw.getX());
                            w.addProperty("y", aw.getY());
                            w.addProperty("width", aw.getWidth());
                            w.addProperty("height", aw.getHeight());
                            w.addProperty("active", aw.active);
                        }
                        widgets.add(w);
                    }
                }
                r.add("widgets", widgets);
                return r;
            });
        });
        m.put("screen.click", (p, s) -> {
            double x = p.num("x", -10000, 10000), y = p.num("y", -10000, 10000);
            int button = p.integer("button", 0, 2, 0);
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen == null) throw new RpcException(RpcException.NOT_FOUND, "no screen is open");
                boolean down = mc.screen.mouseClicked(x, y, button);
                boolean up = mc.screen.mouseReleased(x, y, button);
                JsonObject r = new JsonObject();
                r.addProperty("handled", down || up);
                r.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getName());
                return r;
            });
        });
        m.put("screen.close", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                JsonObject r = new JsonObject();
                r.addProperty("closed", mc.screen == null ? null : mc.screen.getClass().getName());
                if (mc.screen != null) mc.setScreen(null);
                return r;
            });
        });

        // ---- camera
        m.put("camera.setPose", (p, s) -> {
            Pose pose = pose(p);
            p.finish();
            return main(() -> {
                Cinema.director.play(new Move.Static(pose.pos(), pose.yaw(), pose.pitch(), null, 0, pose.roll(), pose.fov(), Double.NaN, null),
                        Double.POSITIVE_INFINITY);
                return poseJson(pose);
            });
        });
        m.put("camera.getPose", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                Cinema.requireWorld();
                JsonObject r = new JsonObject();
                r.addProperty("mode", Cinema.director.mode().name().toLowerCase(Locale.ROOT));
                Pose pose = Cinema.director.lastPose();
                if (Cinema.director.mode() == Director.Mode.CINEMA && pose != null && pose.pos() != null) {
                    r.add("pose", poseJson(pose));
                } else {
                    var c = Minecraft.getInstance().gameRenderer.getMainCamera();
                    var pos = c.getPosition();
                    r.add("pose", poseJson(new Pose(new Vec3d(pos.x, pos.y, pos.z), c.getYRot(), c.getXRot(), 0,
                            Minecraft.getInstance().options.fov().get())));
                }
                return r;
            });
        });
        m.put("camera.lookAt", (p, s) -> {
            Target t = Target.parse(p.obj("target"));
            double fov = p.num("fov", 1, 170, Move.DEFAULT_FOV);
            double smooth = p.num("lookSmoothSec", 0, 10, 0);
            p.finish();
            return main(() -> {
                Vec3d pos = Cinema.director.position();
                Cinema.director.play(new Move.Static(pos, Double.NaN, Double.NaN, t, smooth, 0, fov, Double.NaN, null), Double.POSITIVE_INFINITY);
                return status();
            });
        });
        m.put("camera.play", (p, s) -> {
            Move move = Move.parse(p.obj("move"));
            boolean wait = p.bool("wait", false);
            p.finish();
            return play(move, wait);
        });
        m.put("camera.orbit", (p, s) -> {
            JsonObject raw = p.raw().deepCopy();
            raw.addProperty("type", "orbit");
            boolean wait = p.bool("wait", false);
            raw.remove("wait");
            Move move = Move.parse(JObj.of(raw, "params"));
            if (Double.isNaN(move.durationSec())) throw new SpecException("params.durationSec", "required");
            return play(move, wait);
        });
        m.put("camera.dolly", (p, s) -> sugar(p, "dolly", true));
        m.put("camera.truck", (p, s) -> sugar(p, "truck", true));
        m.put("camera.follow", (p, s) -> sugar(p, "follow", false));
        m.put("camera.firstPerson", (p, s) -> sugar(p, "firstPerson", false));
        m.put("camera.release", (p, s) -> {
            p.finish();
            return main(() -> {
                Cinema.director.release();
                Cinema.ride = false;
                return status();
            });
        });

        // ---- paths
        m.put("path.play", (p, s) -> {
            JsonObject raw = p.raw().deepCopy();
            raw.addProperty("type", "keyframes");
            boolean wait = p.bool("wait", false);
            raw.remove("wait");
            Move move = Move.parse(JObj.of(raw, "params"));
            return play(move, wait);
        });
        m.put("path.stop", (p, s) -> {
            p.finish();
            return main(() -> {
                Cinema.director.stop();
                return status();
            });
        });
        m.put("path.status", (p, s) -> {
            p.finish();
            return Cinema.onMain(Api::pathStatus);
        });

        // ---- player
        m.put("player.get", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> player(Cinema.requireWorld()));
        });
        m.put("player.moveTo", (p, s) -> {
            Vec3d pos = p.vec3("pos");
            String speed = p.choiceOr("speed", "walk", Action.SPEEDS);
            double radius = p.num("arriveRadius", 0.1, 16, 0.6);
            boolean wait = p.bool("wait", true);
            p.finish();
            return action(new Action.Walk(List.of(pos), speed, radius), wait);
        });
        m.put("player.walkPath", (p, s) -> {
            List<Vec3d> pts = p.vec3List("points", 1, 10000);
            String speed = p.choiceOr("speed", "walk", Action.SPEEDS);
            double radius = p.num("arriveRadius", 0.1, 16, 0.6);
            boolean wait = p.bool("wait", true);
            p.finish();
            return action(new Action.Walk(List.copyOf(pts), speed, radius), wait);
        });
        m.put("player.look", (p, s) -> {
            double yaw = p.num("yaw", -36000, 36000), pitch = p.num("pitch", -90, 90);
            double d = p.num("durationSec", 0, 600, 0);
            Easing e = Easing.read(p, "easing", Easing.SINE_IN_OUT);
            boolean wait = p.bool("wait", true);
            p.finish();
            return action(new Action.Look(yaw, pitch, d, e), wait);
        });
        m.put("player.lookAt", (p, s) -> {
            Target t = Target.parse(p.obj("target"));
            double d = p.num("durationSec", 0, 600, 0);
            Easing e = Easing.read(p, "easing", Easing.SINE_IN_OUT);
            boolean wait = p.bool("wait", true);
            p.finish();
            return action(new Action.LookAt(t, d, e), wait);
        });
        m.put("player.setInput", (p, s) -> {
            Action a = new Action.Input(p.num("forward", -1, 1, 0), p.num("strafe", -1, 1, 0), p.bool("jump", false),
                    p.bool("sneak", false), p.bool("sprint", false), p.bool("use", false));
            p.finish();
            return action(a, false);
        });
        m.put("player.clearInput", (p, s) -> simple(p, new Action.ClearInput()));
        m.put("player.stop", (p, s) -> simple(p, new Action.Stop()));
        m.put("player.jump", (p, s) -> simple(p, new Action.Jump()));
        m.put("player.swing", (p, s) -> {
            Action a = new Action.Swing(p.choiceOr("hand", "main", "main", "off"));
            p.finish();
            return action(a, false);
        });
        m.put("player.equip", (p, s) -> {
            Action a = new Action.Equip(p.choice("slot", Action.SLOTS), p.string("item", Action.ITEM_ID, "a namespaced item id"),
                    p.integer("count", 1, 99, 1));
            p.finish();
            return action(a, true);
        });
        m.put("player.clearSlot", (p, s) -> {
            Action a = new Action.ClearSlot(p.choice("slot", Action.SLOTS));
            p.finish();
            return action(a, true);
        });
        m.put("player.selectSlot", (p, s) -> {
            Action a = new Action.SelectSlot(p.integer("slot", 0, 8));
            p.finish();
            return action(a, false);
        });
        m.put("player.teleport", (p, s) -> {
            Vec3d pos = p.vec3("pos");
            boolean y = p.has("yaw"), pi = p.has("pitch");
            if (y != pi) throw new SpecException(p.at(y ? "pitch" : "yaw"), "yaw and pitch must be given together");
            Action a = new Action.Teleport(pos, y ? p.num("yaw", -36000, 36000) : Double.NaN, pi ? p.num("pitch", -90, 90) : Double.NaN);
            p.finish();
            return action(a, true);
        });
        m.put("player.setGamemode", (p, s) -> {
            Action a = new Action.Gamemode(p.choice("mode", Action.GAMEMODES));
            p.finish();
            return action(a, true);
        });
        m.put("player.setVisible", (p, s) -> {
            boolean v = p.bool("visible");
            p.finish();
            return main(() -> {
                Cinema.playerHidden = !v;
                return status();
            });
        });
        m.put("player.ride", (p, s) -> {
            boolean on = p.bool("enabled");
            p.finish();
            return main(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (on && !mc.player.isSpectator()) {
                    throw new RpcException(RpcException.UNSUPPORTED, "riding along with the camera needs spectator mode (player.setGamemode)");
                }
                Cinema.ride = on;
                if (on) Cinema.playerHidden = true;
                return status();
            });
        });
        m.put("player.setFrozen", (p, s) -> {
            Action a = new Action.Frozen(p.num("fraction", 0, 1), p.num("durationSec", 0, 600, 0));
            p.finish();
            return action(a, false);
        });
        m.put("player.perspective", (p, s) -> {
            String mode = p.choice("mode", "first", "back", "front");
            p.finish();
            return main(() -> {
                Cinema.director.release();
                Minecraft.getInstance().options.setCameraType(switch (mode) {
                    case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
                    case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
                    default -> net.minecraft.client.CameraType.FIRST_PERSON;
                });
                return status();
            });
        });

        // ---- world
        m.put("world.command", (p, s) -> {
            String cmd = p.string("command");
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (cmd.isBlank() || cmd.indexOf('\n') >= 0) throw new SpecException("params.command", "must be one non-empty line");
            double timeout = p.num("timeoutSec", 0.1, 60, 2);
            int quiet = p.integer("quietMs", 0, 10000, 250);
            boolean fail = p.bool("failOnError", false);
            p.finish();
            String c = cmd;
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                return Cinema.addTask(new CommandTask(c, timeout, quiet, fail));
            });
        });
        m.put("world.time", (p, s) -> {
            boolean set = p.has("dayTime");
            long day = set ? p.longValue("dayTime", 0, 24000) : -1;
            Boolean freeze = p.optBool("freeze");
            p.finish();
            return Cinema.onMainAsync(() -> {
                Minecraft mc = Cinema.requireWorld();
                Cinema.requireIdle();
                if (!set && freeze == null) {
                    JsonObject r = new JsonObject();
                    r.addProperty("dayTime", mc.level.getDayTime());
                    r.addProperty("timeOfDay", mc.level.getDayTime() % 24000);
                    r.addProperty("gameTime", mc.level.getGameTime());
                    return CompletableFuture.completedFuture(r);
                }
                Snapshot.timeChanged = true;
                Task a = set ? new CommandTask("time set " + day, 3, 200, true) : null;
                Task b = freeze != null ? new CommandTask("gamerule doDaylightCycle " + !freeze, 3, 200, true) : null;
                return Cinema.addTask(a != null && b != null ? ActionRunner.sequence(b, a) : a != null ? a : b);
            });
        });
        m.put("world.weather", (p, s) -> {
            Action a = new Action.Weather(p.choice("weather", "clear", "rain", "thunder"));
            p.finish();
            return action(a, true);
        });
        m.put("world.tickRate", (p, s) -> {
            Action a = new Action.TickRate(p.num("rate", 1, 10000));
            p.finish();
            return action(a, true);
        });
        m.put("world.waitLoaded", (p, s) -> {
            double timeout = p.num("timeoutSec", 1, 3600, 60);
            boolean lods = p.bool("includeLods", true);
            double settle = p.num("lodSettleSec", 0, 600, 4);
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                return Cinema.addTask(new LoadWaitTask(timeout, lods, settle));
            });
        });
        m.put("world.findEntities", (p, s) -> {
            String type = p.has("entityType") ? p.string("entityType", Target.ID, "a namespaced id like minecraft:cow") : null;
            double max = p.num("maxDistance", 1, 1024, 64);
            int limit = p.integer("limit", 1, 1000, 20);
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Cinema.requireWorld();
                JsonArray a = new JsonArray();
                mc.level.entitiesForRendering().forEach(e -> {
                    if (e == mc.player || e.distanceTo(mc.player) > max) return;
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                    if (type != null && !type.equals(id)) return;
                    JsonObject o = new JsonObject();
                    o.addProperty("uuid", e.getUUID().toString());
                    o.addProperty("type", id);
                    o.add("pos", vec(e.getX(), e.getY(), e.getZ()));
                    o.addProperty("distance", Math.round(e.distanceTo(mc.player) * 10) / 10.0);
                    if (e.hasCustomName()) o.addProperty("name", e.getCustomName().getString());
                    a.add(o);
                });
                JsonArray sorted = new JsonArray();
                a.asList().stream().sorted((x, y) -> Double.compare(x.getAsJsonObject().get("distance").getAsDouble(),
                        y.getAsJsonObject().get("distance").getAsDouble())).limit(limit).forEach(sorted::add);
                JsonObject r = new JsonObject();
                r.add("entities", sorted);
                return r;
            });
        });

        // ---- actions (the same steps shots use in setup/teardown)
        m.put("actions.run", (p, s) -> {
            List<Action> actions = new java.util.ArrayList<>();
            for (JObj a : p.objList("actions", 1, 10000)) actions.add(Action.parse(a));
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                JsonArray results = new JsonArray();
                java.util.ArrayDeque<Action> queue = new java.util.ArrayDeque<>(actions);
                return Cinema.addTask(new Task("actions.run") {
                    Task current;
                    int index;

                    @Override
                    public boolean update(Minecraft mc) throws Exception {
                        while (true) {
                            if (current == null) {
                                Action next = queue.poll();
                                if (next == null) {
                                    JsonObject r = new JsonObject();
                                    r.add("results", results);
                                    future.complete(r);
                                    return true;
                                }
                                current = ActionRunner.start(next, true);
                                if (current == null) {
                                    results.add(status());
                                    index++;
                                    continue;
                                }
                            }
                            if (!current.future.isDone() && !current.update(mc)) return false;
                            try {
                                results.add(current.future.join());
                            } catch (RuntimeException e) {
                                Throwable c = e.getCause() != null ? e.getCause() : e;
                                JsonObject d = new JsonObject();
                                d.addProperty("index", index);
                                d.add("results", results);
                                RpcException re = c instanceof RpcException r ? r : new RpcException(RpcException.INTERNAL, c.toString());
                                future.completeExceptionally(new RpcException(re.code(), "actions[" + index + "]: " + re.getMessage(), d));
                                return true;
                            }
                            current = null;
                            index++;
                        }
                    }

                    @Override
                    public void onCancel(String reason) {
                        if (current != null) current.onCancel(reason);
                    }
                });
            });
        });

        // ---- display
        m.put("hud.set", (p, s) -> {
            Action.Hud h = new Action.Hud(p.bool("visible"), p.bool("chat", false), p.bool("crosshair", true), p.bool("hand", true));
            p.finish();
            return action(h, false);
        });
        m.put("shaders.set", (p, s) -> {
            boolean on = p.bool("enabled");
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireIdle();
                if (!Iris.present()) throw new RpcException(RpcException.UNSUPPORTED, "Iris isn't installed");
                if (!Boolean.valueOf(on).equals(Iris.enabled())) Iris.set(on);
                return Cinema.addTask(Task.poll("shaders.set", 30, () -> {
                    JsonObject r = new JsonObject();
                    r.addProperty("enabled", Iris.enabled());
                    r.addProperty("inUse", Iris.inUse());
                    return r;
                }));
            });
        });
        m.put("display.set", (p, s) -> {
            // width/height are optional, but only together.
            boolean size = p.has("width") || p.has("height");
            int width = p.has("width") ? p.integer("width", 16, 7680) : 0;
            int height = p.has("height") ? p.integer("height", 16, 4320) : 0;
            if (size && (width == 0 || height == 0)) throw new SpecException("params", "give width and height together");
            Integer rd = p.has("renderDistance") ? p.integer("renderDistance", 2, 64) : null;
            Integer fov = p.has("fov") ? p.integer("fov", 30, 110) : null;
            Boolean bob = p.optBool("bobView");
            Integer gui = p.has("guiScale") ? p.integer("guiScale", 0, 8) : null;
            p.finish();
            return Cinema.onMainAsync(() -> {
                Minecraft mc = Minecraft.getInstance();
                Cinema.requireIdle();
                if (rd != null) mc.options.renderDistance().set(rd);
                if (fov != null) mc.options.fov().set(fov);
                if (bob != null) mc.options.bobView().set(bob);
                if (gui != null) {
                    mc.options.guiScale().set(gui);
                    mc.resizeDisplay();
                }
                if (size) {
                    dev.everest8000.cinema.capture.RenderSize.clear(mc); // a window size replaces any offscreen size
                    if (mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), width, height);
                }
                long start = System.nanoTime();
                return Cinema.addTask(Task.poll("display.set", 10, () -> {
                    boolean ok = !size || (mc.getWindow().getWidth() == width && mc.getWindow().getHeight() == height);
                    if (!ok && System.nanoTime() - start < 5_000_000_000L) return null;
                    JsonObject r = new JsonObject();
                    r.addProperty("width", mc.getWindow().getWidth());
                    r.addProperty("height", mc.getWindow().getHeight());
                    r.addProperty("sizeMatches", ok);
                    r.addProperty("renderDistance", mc.options.renderDistance().get());
                    r.addProperty("fov", mc.options.fov().get());
                    r.addProperty("bobView", mc.options.bobView().get());
                    r.addProperty("guiScale", mc.options.guiScale().get());
                    return r;
                }));
            });
        });

        // ---- capture
        m.put("capture.frame", (p, s) -> {
            Path path = absPath(p, "path", ".png");
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Recorder.StillGrab g = new Recorder.StillGrab(path);
                Cinema.requestStill(g);
                return g.done.thenApply(x -> (JsonElement) x);
            });
        });
        m.put("capture.start", (p, s) -> {
            Path path = absPath(p, "path", null);
            RenderSettings rs = RenderSettings.parse(p.optObj("render"));
            p.finish();
            return main(() -> {
                Recorder.Plan plan = new Recorder.Plan(path, rs, rs.fps(), Integer.MAX_VALUE, Map.of(), List.of(), null,
                        path.getFileName().toString(), Cinema.config.ffmpeg());
                Cinema.tickRateCompensation = rs.serverTickSync();
                Cinema.recorder.start(plan);
                JsonObject r = new JsonObject();
                r.addProperty("path", path.toString());
                r.addProperty("width", Cinema.recorder.width());
                r.addProperty("height", Cinema.recorder.height());
                r.addProperty("fps", rs.fps());
                return r;
            });
        });
        m.put("capture.stop", (p, s) -> {
            p.finish();
            return Cinema.onMainAsync(() -> {
                if (Cinema.render != null) throw RpcException.busy("capture.render");
                Cinema.tickRateCompensation = false;
                return Cinema.recorder.stop().thenApply(x -> (JsonElement) x);
            });
        });
        m.put("capture.status", (p, s) -> {
            p.finish();
            return Cinema.onMain(Api::captureStatus);
        });
        m.put("capture.render", (p, s) -> {
            ShotSpec shot = ShotSpec.parse(p.obj("shot"));
            Path out = absPath(p, "outDir", null);
            RenderSettings rs = RenderSettings.parse(p.optObj("render"));
            PreviewSettings preview = PreviewSettings.parse(p.optObj("preview"));
            p.finish();
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                ShotRunner r = new ShotRunner(shot, rs, preview, out);
                Cinema.render = r;
                return Cinema.addTask(r);
            });
        });
        m.put("capture.cancel", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                ShotRunner r = Cinema.render;
                JsonObject o = new JsonObject();
                o.addProperty("cancelled", r != null);
                if (r != null) {
                    r.future.completeExceptionally(new RpcException(RpcException.CANCELLED, "capture.cancel"));
                    r.onCancel("capture.cancel");
                    Cinema.tasks().remove(r);
                }
                return o;
            });
        });
        DirectorApi.register(m);
        return m;
    }

    // ---- helpers

    private static CompletableFuture<JsonElement> main(java.util.function.Supplier<JsonElement> work) {
        return Cinema.onMain(() -> {
            Cinema.requireWorld();
            Cinema.requireIdle();
            return work.get();
        });
    }

    private static CompletableFuture<JsonElement> simple(JObj p, Action a) {
        p.finish();
        return action(a, false);
    }

    private static CompletableFuture<JsonElement> action(Action a, boolean wait) {
        return Cinema.onMainAsync(() -> {
            Cinema.requireWorld();
            Cinema.requireIdle();
            Task t = ActionRunner.start(a, wait);
            if (t == null) return CompletableFuture.completedFuture(status());
            return Cinema.addTask(t);
        });
    }

    private static CompletableFuture<JsonElement> sugar(JObj p, String type, boolean needsDuration) {
        JsonObject raw = p.raw().deepCopy();
        raw.addProperty("type", type);
        boolean wait = p.bool("wait", false);
        raw.remove("wait");
        Move move = Move.parse(JObj.of(raw, "params"));
        if (needsDuration && Double.isNaN(move.durationSec())) throw new SpecException("params.durationSec", "required");
        return play(move, wait);
    }

    private static CompletableFuture<JsonElement> play(Move move, boolean wait) {
        return Cinema.onMainAsync(() -> {
            Cinema.requireWorld();
            Cinema.requireIdle();
            for (Target t : MovePlayer.targets(move)) {
                if (!(t instanceof Target.Point)) {
                    try {
                        McTargets.INSTANCE.entity(t);
                    } catch (RuntimeException e) {
                        throw new RpcException(RpcException.NOT_FOUND, e.getMessage());
                    }
                }
            }
            Cinema.director.play(move, Double.POSITIVE_INFINITY);
            if (!wait || Double.isNaN(move.durationSec())) return CompletableFuture.completedFuture(pathStatus());
            MovePlayer started = Cinema.director.current();
            return Cinema.addTask(new Task("camera move") {
                @Override
                public boolean update(Minecraft mc) {
                    if (Cinema.director.current() != started) {
                        future.completeExceptionally(new RpcException(RpcException.CANCELLED, "the move was replaced or stopped"));
                        return true;
                    }
                    if (!Cinema.director.finished()) return false;
                    future.complete(pathStatus());
                    return true;
                }
            });
        });
    }

    private static Pose pose(JObj p) {
        Vec3d pos = p.vec3("pos");
        return new Pose(pos, p.num("yaw", -36000, 36000), p.num("pitch", -90, 90), p.num("roll", -180, 180, 0),
                p.num("fov", 1, 170, Move.DEFAULT_FOV));
    }

    private static Path absPath(JObj p, String key, String ext) {
        String s = p.string(key);
        if (!ABS_PATH.matcher(s).matches()) throw new SpecException(p.at(key), "must be an absolute path");
        if (ext != null && !s.toLowerCase(Locale.ROOT).endsWith(ext)) throw new SpecException(p.at(key), "must end in " + ext);
        return Path.of(s);
    }

    static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(Math.round(x * 1000) / 1000.0);
        a.add(Math.round(y * 1000) / 1000.0);
        a.add(Math.round(z * 1000) / 1000.0);
        return a;
    }

    static JsonObject poseJson(Pose p) {
        JsonObject o = new JsonObject();
        if (p.pos() != null) o.add("pos", vec(p.pos().x(), p.pos().y(), p.pos().z()));
        o.addProperty("yaw", Math.round(p.yaw() * 1000) / 1000.0);
        o.addProperty("pitch", Math.round(p.pitch() * 1000) / 1000.0);
        o.addProperty("roll", Math.round(p.roll() * 1000) / 1000.0);
        if (!Double.isNaN(p.fov())) o.addProperty("fov", Math.round(p.fov() * 100) / 100.0);
        return o;
    }

    static JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    static JsonObject pathStatus() {
        JsonObject o = new JsonObject();
        MovePlayer mp = Cinema.director.current();
        o.addProperty("mode", Cinema.director.mode().name().toLowerCase(Locale.ROOT));
        o.addProperty("playing", mp != null && !Cinema.director.finished());
        if (mp != null) {
            o.addProperty("type", mp.move().typeName());
            o.addProperty("t", Math.round(Cinema.director.time() * 1000) / 1000.0);
            if (!Double.isInfinite(mp.duration())) {
                o.addProperty("durationSec", mp.duration());
                o.addProperty("progress", Math.min(1, Cinema.director.time() / mp.duration()));
            }
        }
        if (Cinema.director.error() != null) o.addProperty("error", Cinema.director.error());
        return o;
    }

    static JsonObject captureStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("active", Cinema.recorder.active());
        o.addProperty("frame", Cinema.recorder.frame());
        if (Cinema.recorder.plan() != null && Cinema.recorder.plan().video() != null) o.addProperty("path", Cinema.recorder.plan().video().toString());
        JsonElement r = ShotRunner.describe();
        if (r != null) o.add("render", r);
        return o;
    }

    static JsonObject player(Minecraft mc) {
        LocalPlayer pl = mc.player;
        JsonObject o = new JsonObject();
        o.add("pos", vec(pl.getX(), pl.getY(), pl.getZ()));
        o.addProperty("yaw", pl.getYRot());
        o.addProperty("pitch", pl.getXRot());
        o.addProperty("gamemode", mc.gameMode.getPlayerMode().getName());
        o.addProperty("health", pl.getHealth());
        o.addProperty("onGround", pl.onGround());
        o.addProperty("frozen", pl.getPercentFrozen());
        o.addProperty("visible", !Cinema.playerHidden);
        o.addProperty("ride", Cinema.ride);
        o.add("puppet", Cinema.puppet.status());
        Inventory inv = pl.getInventory();
        o.addProperty("selectedSlot", inv.selected);
        JsonObject slots = new JsonObject();
        for (int i = 0; i < 36; i++) addSlot(slots, i < 9 ? "hotbar." + i : "inventory." + (i - 9), inv.items.get(i));
        String[] armor = {"feet", "legs", "chest", "head"};
        for (int i = 0; i < 4; i++) addSlot(slots, armor[i], inv.armor.get(i));
        addSlot(slots, "offhand", inv.offhand.get(0));
        o.add("inventory", slots);
        return o;
    }

    private static void addSlot(JsonObject slots, String name, ItemStack s) {
        if (s.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        if (s.isDamageableItem()) o.addProperty("damage", s.getDamageValue());
        slots.add(name, o);
    }

    static JsonObject state() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        boolean inWorld = mc.level != null && mc.player != null && mc.getConnection() != null;
        o.addProperty("inWorld", inWorld);
        o.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
        o.addProperty("fps", Math.round(Cinema.realFps() * 10) / 10.0);
        o.addProperty("framesRendered", Cinema.framesRendered());
        JsonObject win = new JsonObject();
        win.addProperty("width", mc.getWindow().getWidth());
        win.addProperty("height", mc.getWindow().getHeight());
        win.addProperty("offscreen", dev.everest8000.cinema.capture.RenderSize.active());
        if (dev.everest8000.cinema.capture.RenderSize.active()) {
            win.addProperty("screenWidth", dev.everest8000.cinema.capture.RenderSize.realWidth());
            win.addProperty("screenHeight", dev.everest8000.cinema.capture.RenderSize.realHeight());
        }
        o.add("window", win);
        o.addProperty("hudVisible", !mc.options.hideGui);
        o.addProperty("shaders", Iris.enabled());
        o.addProperty("shadersInUse", Iris.inUse());
        o.add("path", pathStatus());
        o.add("capture", captureStatus());
        o.addProperty("snapshot", Cinema.snapshot != null);
        JsonObject clock = new JsonObject();
        clock.addProperty("installed", dev.everest8000.cinema.capture.FrameClock.installed());
        clock.addProperty("virtual", dev.everest8000.cinema.capture.FrameClock.isVirtual());
        clock.addProperty("nanos", net.minecraft.Util.getNanos());
        o.add("clock", clock);
        if (inWorld) {
            o.add("player", player(mc));
            Pose cp = Cinema.director.lastPose();
            if (cp != null && cp.pos() != null) o.add("camera", poseJson(cp));
            JsonObject chunks = new JsonObject();
            chunks.addProperty("renderedAllSections", LoadWaitTask.sectionsDone(mc));
            chunks.addProperty("loaded", LoadWaitTask.loadedChunks(mc));
            chunks.addProperty("renderDistance", mc.options.renderDistance().get());
            o.add("chunks", chunks);
            JsonObject lod = new JsonObject();
            lod.addProperty("installed", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons"));
            lod.addProperty("note", "Distant Horizons exposes no load progress; warm-ups wait lodSettleSec instead");
            o.add("lod", lod);
            JsonObject world = new JsonObject();
            world.addProperty("dayTime", mc.level.getDayTime());
            world.addProperty("timeOfDay", mc.level.getDayTime() % 24000);
            world.addProperty("raining", mc.level.getLevelData().isRaining());
            world.addProperty("rainLevel", mc.level.getRainLevel(1f));
            world.addProperty("tickRate", mc.level.tickRateManager().tickrate());
            o.add("world", world);
        }
        return o;
    }
}
