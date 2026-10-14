package dev.everest8000.cinema.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.Task;
import dev.everest8000.cinema.actor.Actor;
import dev.everest8000.cinema.actor.Actors;
import dev.everest8000.cinema.actor.McBlockQuery;
import dev.everest8000.cinema.lod.DhProbe;
import dev.everest8000.cinema.lod.FrameProbe;
import dev.everest8000.cinema.lod.LodGate;
import dev.everest8000.cinema.nav.PathFinder;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.rpc.RpcServer.Handler;
import dev.everest8000.cinema.spec.ActorAction;
import dev.everest8000.cinema.spec.ActorSpec;
import dev.everest8000.cinema.spec.JObj;
import dev.everest8000.cinema.spec.LodGateSpec;
import dev.everest8000.cinema.spec.Pose;
import dev.everest8000.cinema.spec.SpecException;
import dev.everest8000.cinema.spec.Vec3d;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** actor.*, nav.path, world.awaitLodReady and world.lodStatus. */
final class DirectorApi {
    private DirectorApi() {}

    static void register(Map<String, Handler> m) {
        m.put("actor.spawn", (p, s) -> {
            ActorSpec spec = ActorSpec.parse(p.obj("actor"));
            boolean start = p.bool("start", true);
            p.finish();
            return Cinema.onMain(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                return Actors.spawn(spec, start);
            });
        });
        m.put("actor.despawn", (p, s) -> {
            String id = p.string("id", ActorSpec.ID, "an actor id");
            p.finish();
            return Cinema.onMain(() -> {
                Cinema.requireIdle();
                Actors.despawn(id);
                JsonObject r = new JsonObject();
                r.addProperty("despawned", id);
                return r;
            });
        });
        m.put("actor.clear", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                Cinema.requireIdle();
                Actors.clear();
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                return r;
            });
        });
        m.put("actor.list", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                JsonObject r = new JsonObject();
                r.add("actors", Actors.list());
                return r;
            });
        });
        m.put("actor.get", (p, s) -> {
            String id = p.string("id", ActorSpec.ID, "an actor id");
            p.finish();
            return Cinema.onMain(() -> Actors.get(id).json());
        });
        m.put("actor.run", (p, s) -> {
            // Replaces an actor's timeline (taking control of the player as actor "player" on demand) and starts it now.
            String id = p.string("id", ActorSpec.ID, "an actor id");
            boolean isPlayer = ActorSpec.PLAYER.equals(id);
            if (!p.has("timeline")) throw new SpecException("params.timeline", "required (an array of cues)");
            List<ActorSpec.Cue> timeline = ActorSpec.parseTimeline(p, "timeline", isPlayer);
            boolean wait = p.bool("wait", false);
            double timeout = p.num("timeoutSec", 1, 3600, 600);
            p.finish();
            JsonObject playerRaw = new JsonObject();
            playerRaw.addProperty("id", id);
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                Actor a = Actors.find(id);
                if (a == null) {
                    if (!isPlayer) throw new RpcException(RpcException.NOT_FOUND, "no actor \"" + id + "\" (actor.spawn it first)");
                    Actors.spawn(ActorSpec.parse(JObj.of(playerRaw, "player")), false);
                    a = Actors.get(id);
                }
                a.start(timeline);
                Actor started = a;
                if (!wait) return CompletableFuture.completedFuture(started.json());
                return Cinema.addTask(new Task("actor.run " + id) {
                    @Override
                    public boolean update(Minecraft mc) {
                        if (Actors.find(id) != started) {
                            future.completeExceptionally(new RpcException(RpcException.CANCELLED, "the actor was despawned"));
                            return true;
                        }
                        if (started.timelineDone()) {
                            future.complete(started.json());
                            return true;
                        }
                        if (elapsedSec() > timeout) {
                            future.completeExceptionally(new RpcException(RpcException.TIMEOUT, "the timeline didn't finish in " + timeout + " s",
                                    started.json()));
                            return true;
                        }
                        return false;
                    }
                });
            });
        });
        m.put("actor.do", (p, s) -> {
            String id = p.string("id", ActorSpec.ID, "an actor id");
            ActorAction a = ActorAction.parse(p.obj("action"));
            boolean wait = p.bool("wait", true);
            double timeout = p.num("timeoutSec", 1, 3600, 300);
            p.finish();
            if (ActorSpec.PLAYER.equals(id) && (a instanceof ActorAction.Equip || a instanceof ActorAction.Hood || a instanceof ActorAction.Mask)) {
                throw new SpecException("params.action", "\"" + a.typeName() + "\" is for actors; give the player items with commands");
            }
            return Cinema.onMainAsync(() -> {
                Cinema.requireWorld();
                Cinema.requireIdle();
                Actor actor = Actors.find(id);
                if (actor == null) {
                    if (!ActorSpec.PLAYER.equals(id)) throw new RpcException(RpcException.NOT_FOUND, "no actor \"" + id + "\"");
                    JsonObject raw = new JsonObject();
                    raw.addProperty("id", id);
                    Actors.spawn(ActorSpec.parse(JObj.of(raw, "player")), true);
                    actor = Actors.get(id);
                }
                Actor target = actor;
                int before = target.cueCount();
                target.act(a);
                if (!wait) return CompletableFuture.completedFuture(target.json());
                return Cinema.addTask(new Task("actor.do " + id) {
                    @Override
                    public boolean update(Minecraft mc) {
                        String st = target.cueState(before);
                        if (st.equals("done")) {
                            future.complete(target.json());
                            return true;
                        }
                        if (st.equals("failed")) {
                            future.completeExceptionally(new RpcException(a instanceof ActorAction.WalkTo ? RpcException.STUCK : RpcException.INTERNAL,
                                    a.typeName() + " failed: " + target.cueError(before), target.json()));
                            return true;
                        }
                        if (elapsedSec() > timeout) {
                            future.completeExceptionally(new RpcException(RpcException.TIMEOUT, a.typeName() + " didn't finish in " + timeout + " s",
                                    target.json()));
                            return true;
                        }
                        return false;
                    }
                });
            });
        });
        m.put("nav.path", (p, s) -> {
            Vec3d from = p.vec3("from"), to = p.vec3("to");
            int maxNodes = p.integer("maxNodes", 100, 1000000, 60000);
            int maxDrop = p.integer("maxDrop", 0, 16, 3);
            boolean simplify = p.bool("simplify", true);
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Cinema.requireWorld();
                PathFinder pf = new PathFinder(new McBlockQuery(mc.level), new PathFinder.Options(maxNodes, maxDrop, 0.8, 0.6, 0.6, 0.6));
                long t0 = System.nanoTime();
                PathFinder.Result r = pf.find(from, to);
                List<Vec3d> pts = simplify ? PathFinder.simplify(r.points()) : r.points();
                JsonObject o = new JsonObject();
                o.addProperty("status", r.status().name().toLowerCase(java.util.Locale.ROOT));
                o.addProperty("expanded", r.expanded());
                o.addProperty("cost", Math.round(r.cost() * 100) / 100.0);
                o.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
                JsonArray a = new JsonArray();
                for (Vec3d v : pts) a.add(Api.vec(v.x(), v.y(), v.z()));
                o.add("points", a);
                return o;
            });
        });
        m.put("world.awaitLodReady", (p, s) -> {
            List<Vec3d> path = p.has("path") ? p.vec3List("path", 1, 1000) : List.of();
            JsonObject gate = p.raw().deepCopy();
            gate.remove("path");
            gate.remove("ride");
            gate.remove("fov");
            gate.remove("stillsBase");
            boolean ride = p.bool("ride", true);
            double fov = p.num("fov", 1, 170, 70);
            java.nio.file.Path stillsBase = null;
            if (p.has("stillsBase")) {
                stillsBase = java.nio.file.Path.of(p.string("stillsBase", java.util.regex.Pattern.compile(".{1,400}"), "a path prefix"));
                if (!stillsBase.isAbsolute()) throw new SpecException("params.stillsBase", "must be an absolute path prefix");
            }
            for (String k : gate.keySet()) p.allow(k);
            p.finish();
            LodGateSpec spec = LodGateSpec.parse(JObj.of(gate, "params"));
            java.nio.file.Path stills = stillsBase;
            return Cinema.onMainAsync(() -> {
                Minecraft mc = Cinema.requireWorld();
                Cinema.requireIdle();
                List<Pose> poses = new ArrayList<>();
                for (int i = 0; i < path.size(); i++) {
                    Vec3d a = path.get(i), b = path.get(Math.min(i + 1, path.size() - 1));
                    double yaw = i + 1 < path.size() ? a.yawTo(b) : (i > 0 ? path.get(i - 1).yawTo(a) : 0);
                    poses.add(new Pose(a, yaw, 10, 0, fov));
                }
                Runnable atStart = null;
                List<Pose> warm = poses;
                if (!poses.isEmpty()) {
                    Pose first = poses.get(0);
                    warm = poses.subList(1, poses.size());
                    atStart = () -> Cinema.director.play(new dev.everest8000.cinema.spec.Move.Static(first.pos(), first.yaw(), first.pitch(),
                            null, 0, 0, first.fov(), Double.NaN, null), Double.POSITIVE_INFINITY);
                    atStart.run();
                }
                boolean doRide = ride && mc.player.isSpectator() && !poses.isEmpty();
                boolean wasRiding = Cinema.ride;
                if (doRide) Cinema.ride = true;
                LodGate g = new LodGate(spec, new ArrayList<>(warm), atStart, doRide, stills);
                return Cinema.addTask(g).whenComplete((r, e) -> Cinema.ride = wasRiding || doRide).thenApply(r -> {
                    if (spec.failOnTimeout() && "timeout".equals(r.getAsJsonObject().get("status").getAsString())) {
                        throw new RpcException(RpcException.TIMEOUT, "Distant Horizons wasn't ready after " + spec.timeoutSec() + " s", r);
                    }
                    return r;
                });
            });
        });
        m.put("world.particles", (p, s) -> {
            // Live particles by class (to find what is drawing), and optionally a new block list / a clear.
            java.util.List<String> block = p.has("block") ? p.stringList("block", 0, 64) : null;
            boolean clear = p.bool("clear", false);
            p.finish();
            return Cinema.onMain(() -> {
                Minecraft mc = Cinema.requireWorld();
                if (block != null) Cinema.particleBlock = block.toArray(new String[0]);
                if (clear) ((dev.everest8000.cinema.mixin.ParticleEngineAccess) mc.particleEngine).cinema$clearParticles();
                java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                int total = 0;
                for (var q : ((dev.everest8000.cinema.mixin.ParticleEngineAccess) mc.particleEngine).cinema$particles().values()) {
                    for (var part : q) {
                        counts.merge(part.getClass().getName(), 1, Integer::sum);
                        total++;
                    }
                }
                JsonObject r = new JsonObject();
                r.addProperty("total", total);
                JsonObject byClass = new JsonObject();
                counts.forEach(byClass::addProperty);
                r.add("byClass", byClass);
                JsonArray bl = new JsonArray();
                for (String b : Cinema.particleBlock) bl.add(b);
                r.add("block", bl);
                r.addProperty("blockedSoFar", Cinema.particlesBlocked);
                return r;
            });
        });
        m.put("world.lodStatus", (p, s) -> {
            p.finish();
            return Cinema.onMain(() -> {
                JsonObject r = DhProbe.status().json();
                r.add("f3", DhProbe.f3Lines());
                double fd = FrameProbe.lastDiff();
                r.addProperty("frameDiff", Double.isNaN(fd) ? -1 : fd);
                r.addProperty("serverPositionCorrections", Cinema.serverPositions);
                return r;
            });
        });
    }
}
