package dev.everest8000.cinema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.Action;
import dev.everest8000.cinema.state.Iris;
import dev.everest8000.cinema.state.Snapshot;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Turns shot actions into work: a Task to wait on (setup/teardown) or an immediate effect (timed events). */
public final class ActionRunner {
    private ActionRunner() {}

    /** The command an action sends, or null if it is client-side only. */
    public static String command(Action a) {
        return switch (a) {
            case Action.Command c -> c.command();
            case Action.Teleport t -> Double.isNaN(t.yaw())
                    ? String.format(Locale.ROOT, "tp @s %.5f %.5f %.5f", t.pos().x(), t.pos().y(), t.pos().z())
                    : String.format(Locale.ROOT, "tp @s %.5f %.5f %.5f %.3f %.3f", t.pos().x(), t.pos().y(), t.pos().z(), t.yaw(), t.pitch());
            case Action.Gamemode g -> "gamemode " + g.mode();
            case Action.Time t -> "time set " + t.dayTime();
            case Action.Weather w -> "weather " + w.weather();
            case Action.TickRate r -> String.format(Locale.ROOT, "tick rate %.3f", r.rate());
            case Action.Equip e -> "item replace entity @s " + Action.itemSlot(e.slot()) + " with " + e.item() + " " + e.count();
            case Action.ClearSlot c -> "item replace entity @s " + Action.itemSlot(c.slot()) + " with minecraft:air";
            default -> null;
        };
    }

    /**
     * Starts an action. With wait, returns a task that finishes when the action has taken effect (commands answered,
     * walks arrived, looks done, waits elapsed); otherwise applies it and returns null.
     */
    public static Task start(Action a, boolean wait) {
        Minecraft mc = Cinema.requireWorld();
        if (a instanceof Action.Time) Snapshot.timeChanged = true;
        if (a instanceof Action.Weather) Snapshot.weatherChanged = true;
        if (a instanceof Action.TickRate) Snapshot.tickRateChanged = true;
        String cmd = command(a);
        if (cmd != null) {
            if (a instanceof Action.Time t && t.freeze()) {
                if (!wait) {
                    Cinema.sendCommand("gamerule doDaylightCycle false");
                    Cinema.sendCommand(cmd);
                    return null;
                }
                return sequence(new CommandTask("gamerule doDaylightCycle false", 3, 200, true), new CommandTask(cmd, 3, 200, true));
            }
            if (!wait) {
                Cinema.sendCommand(cmd);
                return null;
            }
            return new CommandTask(cmd, 3, 200, true);
        }
        switch (a) {
            case Action.Wait w -> {
                return wait ? waitTask(w.seconds()) : null;
            }
            case Action.WaitLoaded l -> {
                return wait ? new LoadWaitTask(l.timeoutSec(), l.includeLods(), l.lodSettleSec()) : null;
            }
            case Action.SelectSlot s -> mc.player.getInventory().selected = s.slot();
            case Action.Walk w -> {
                CompletableFuture<JsonObject> f = Cinema.puppet.walk(w.points(), w.speed(), w.arriveRadius());
                if (wait) return awaitFuture("walk", f);
            }
            case Action.Look l -> {
                CompletableFuture<JsonObject> f = Cinema.puppet.look((float) l.yaw(), (float) l.pitch(), l.durationSec(), l.easing());
                if (wait) return awaitFuture("look", f);
            }
            case Action.LookAt l -> {
                CompletableFuture<JsonObject> f = Cinema.puppet.lookAt(l.target(), l.durationSec(), l.easing());
                if (wait) return awaitFuture("lookAt", f);
            }
            case Action.Input i -> Cinema.puppet.setInput((float) i.forward(), (float) i.strafe(), i.jump(), i.sneak(), i.sprint(), i.use());
            case Action.ClearInput c -> Cinema.puppet.clearInput();
            case Action.Jump j -> Cinema.puppet.jump();
            case Action.Swing s -> Cinema.puppet.swing(s.hand().equals("off"));
            case Action.Stop s -> Cinema.puppet.stop();
            case Action.Frozen f -> Cinema.puppet.setFrozen((float) f.fraction(), f.durationSec());
            case Action.Hud h -> applyHud(mc, h.visible(), h.chat(), h.crosshair(), h.hand());
            case Action.Shaders s -> {
                Iris.set(s.enabled());
                if (wait) return waitTask(2);
            }
            case Action.PlayerVisible v -> Cinema.playerHidden = !v.visible();
            default -> throw new IllegalStateException("unhandled action " + a.typeName());
        }
        return null;
    }

    public static void applyHud(Minecraft mc, boolean visible, boolean chat, boolean crosshair, boolean hand) {
        mc.options.hideGui = !visible;
        Cinema.chatHidden = !chat;
        Cinema.crosshairHidden = !crosshair;
        mc.gameRenderer.setRenderHand(hand);
    }

    public static Task waitTask(double seconds) {
        return new Task("wait") {
            @Override
            public boolean update(Minecraft mc) {
                if (elapsedSec() < seconds) return false;
                JsonObject r = new JsonObject();
                r.addProperty("waitedSec", seconds);
                future.complete(r);
                return true;
            }
        };
    }

    static Task awaitFuture(String name, CompletableFuture<JsonObject> f) {
        return new Task(name) {
            @Override
            public boolean update(Minecraft mc) {
                if (!f.isDone()) return false;
                try {
                    future.complete(f.join());
                } catch (RuntimeException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    future.completeExceptionally(c instanceof RpcException ? c : new RpcException(RpcException.INTERNAL, c.toString()));
                }
                return true;
            }
        };
    }

    /** Runs tasks one after another; the result is the last one's. */
    public static Task sequence(Task... steps) {
        return new Task("sequence") {
            int i;

            @Override
            public boolean update(Minecraft mc) throws Exception {
                while (i < steps.length) {
                    Task t = steps[i];
                    if (!t.future.isDone() && !t.update(mc)) return false;
                    if (t.future.isCompletedExceptionally()) {
                        try {
                            t.future.join();
                        } catch (RuntimeException e) {
                            future.completeExceptionally(e.getCause() != null ? e.getCause() : e);
                        }
                        return true;
                    }
                    i++;
                }
                JsonElement last = steps.length == 0 ? new JsonObject() : steps[steps.length - 1].future.join();
                future.complete(last);
                return true;
            }

            @Override
            public void onCancel(String reason) {
                for (Task t : steps) t.onCancel(reason);
            }
        };
    }
}
