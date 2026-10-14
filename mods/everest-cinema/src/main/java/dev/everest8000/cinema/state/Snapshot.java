package dev.everest8000.cinema.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.ChatLog;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.CinemaClient;
import dev.everest8000.cinema.Task;
import dev.everest8000.cinema.rpc.RpcException;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A restore point: the player (position, rotation, game mode, flight, held slot, every inventory slot), the world
 * (time of day, daylight cycle, weather, tick rate) and the client (camera, HUD, options, shaders, window size).
 * {@link #restore()} puts back whatever differs, using commands for server state.
 */
public final class Snapshot {
    public double x, y, z;
    public float yaw, pitch;
    public GameType gameMode;
    public boolean flying;
    public int selected;
    public final List<ItemStack> slots = new ArrayList<>();
    public long dayTime;
    public Boolean daylightCycle;
    public boolean raining;
    public boolean thundering;
    public float tickRate;
    public CameraType cameraType;
    public boolean hideGui;
    public int renderDistance;
    public int fov;
    public boolean bobView;
    public int guiScale;
    public boolean autoJump;
    public Boolean shaders;
    public int windowWidth, windowHeight;
    public final long takenAt = System.currentTimeMillis();

    // What this session changed, so restore only touches those.
    public static boolean timeChanged;
    public static boolean weatherChanged;
    public static boolean tickRateChanged;

    /** Takes a snapshot; the future completes after the daylight-cycle game rule has been read back. */
    public static Task take() {
        return new Task("state.snapshot") {
            Snapshot s;
            ChatLog.Collector c;

            @Override
            public boolean update(Minecraft mc) {
                if (s == null) {
                    Cinema.requireWorld();
                    s = capture(mc);
                    c = Cinema.chat.open();
                    Cinema.sendCommand("gamerule doDaylightCycle");
                    return false;
                }
                for (ChatLog.Msg m : c.messages) {
                    if ("commands.gamerule.query".equals(m.key()) && m.args().size() >= 2) s.daylightCycle = Boolean.parseBoolean(m.args().get(1));
                }
                if (s.daylightCycle == null && elapsedSec() < 3) return false;
                c.close();
                Cinema.snapshot = s;
                timeChanged = weatherChanged = tickRateChanged = false;
                future.complete(s.json());
                return true;
            }

            @Override
            public void onCancel(String reason) {
                if (c != null) c.close();
            }
        };
    }

    static Snapshot capture(Minecraft mc) {
        LocalPlayer p = mc.player;
        Snapshot s = new Snapshot();
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yaw = p.getYRot();
        s.pitch = p.getXRot();
        s.gameMode = mc.gameMode.getPlayerMode();
        s.flying = p.getAbilities().flying;
        Inventory inv = p.getInventory();
        s.selected = inv.selected;
        for (int i = 0; i < inv.items.size(); i++) s.slots.add(inv.items.get(i).copy());
        for (int i = 0; i < inv.armor.size(); i++) s.slots.add(inv.armor.get(i).copy());
        s.slots.add(inv.offhand.get(0).copy());
        s.dayTime = mc.level.getDayTime();
        s.raining = mc.level.getLevelData().isRaining();
        s.thundering = mc.level.getThunderLevel(1f) > 0.5f;
        s.tickRate = mc.level.tickRateManager().tickrate();
        s.cameraType = mc.options.getCameraType();
        s.hideGui = mc.options.hideGui;
        s.renderDistance = mc.options.renderDistance().get();
        s.fov = mc.options.fov().get();
        s.bobView = mc.options.bobView().get();
        s.guiScale = mc.options.guiScale().get();
        s.autoJump = mc.options.autoJump().get();
        s.shaders = Iris.enabled();
        boolean off = dev.everest8000.cinema.capture.RenderSize.active();
        s.windowWidth = off ? dev.everest8000.cinema.capture.RenderSize.realWidth() : mc.getWindow().getWidth();
        s.windowHeight = off ? dev.everest8000.cinema.capture.RenderSize.realHeight() : mc.getWindow().getHeight();
        return s;
    }

    /** /item replace slot for inventory index i in the order captured above (36 main, 4 armor, offhand). */
    static String slotName(int i) {
        if (i < 9) return "hotbar." + i;
        if (i < 36) return "inventory." + (i - 9);
        if (i < 40) return new String[]{"armor.feet", "armor.legs", "armor.chest", "armor.head"}[i - 36];
        return "weapon.offhand";
    }

    static List<ItemStack> current(LocalPlayer p) {
        Inventory inv = p.getInventory();
        List<ItemStack> out = new ArrayList<>();
        out.addAll(inv.items);
        out.addAll(inv.armor);
        out.add(inv.offhand.get(0));
        return out;
    }

    /** The item as a command argument, components included: minecraft:bow[minecraft:damage=3]. */
    public static String itemArg(ItemStack s, RegistryAccess ra) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        DataComponentPatch patch = s.getComponentsPatch();
        if (patch.isEmpty()) return id;
        Tag t = DataComponentPatch.CODEC.encodeStart(ra.createSerializationContext(NbtOps.INSTANCE), patch).getOrThrow();
        if (!(t instanceof CompoundTag c) || c.isEmpty()) return id;
        List<String> parts = new ArrayList<>();
        for (String key : c.getAllKeys()) {
            if (key.startsWith("!")) parts.add(key);
            else parts.add(key + "=" + c.get(key).getAsString());
        }
        return id + "[" + String.join(",", parts) + "]";
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("gamemode", gameMode.getName());
        o.addProperty("dayTime", dayTime);
        if (daylightCycle != null) o.addProperty("daylightCycle", daylightCycle);
        o.addProperty("raining", raining);
        o.addProperty("thundering", thundering);
        o.addProperty("tickRate", tickRate);
        o.addProperty("shaders", shaders);
        o.addProperty("windowWidth", windowWidth);
        o.addProperty("windowHeight", windowHeight);
        int filled = 0;
        for (ItemStack s : slots) if (!s.isEmpty()) filled++;
        o.addProperty("inventorySlotsFilled", filled);
        return o;
    }

    /** Puts everything back; the task finishes once the server has applied the game mode and position. */
    public Task restore() {
        Snapshot s = this;
        return new Task("state.restore") {
            int phase;
            long waitUntil;
            final JsonArray done = new JsonArray();
            final JsonArray warnings = new JsonArray();
            ChatLog.Collector c;

            @Override
            public boolean update(Minecraft mc) {
                Cinema.requireWorld();
                LocalPlayer p = mc.player;
                switch (phase) {
                    case 0 -> {
                        Cinema.puppet.reset();
                        Cinema.director.release();
                        Cinema.ride = false;
                        Cinema.playerHidden = false;
                        Cinema.chatHidden = false;
                        Cinema.crosshairHidden = false;
                        Cinema.tickRateCompensation = false;
                        mc.gameRenderer.setRenderHand(true);
                        c = Cinema.chat.open();
                        if (mc.gameMode.getPlayerMode() != s.gameMode) {
                            Cinema.sendCommand("gamemode " + s.gameMode.getName());
                            done.add("gamemode " + s.gameMode.getName());
                        }
                        Cinema.sendCommand(String.format(Locale.ROOT, "tp @s %.5f %.5f %.5f %.3f %.3f", s.x, s.y, s.z, s.yaw, s.pitch));
                        done.add("position and rotation");
                        restoreInventory(mc, p);
                        if (timeChanged || mc.level.getDayTime() != s.dayTime) {
                            Cinema.sendCommand("time set " + s.dayTime);
                            done.add("time " + s.dayTime);
                        }
                        if (s.daylightCycle != null) {
                            Cinema.sendCommand("gamerule doDaylightCycle " + s.daylightCycle);
                            done.add("doDaylightCycle " + s.daylightCycle);
                        } else {
                            warnings.add("doDaylightCycle was unknown at snapshot time; left as is");
                        }
                        if (weatherChanged) {
                            String w = s.thundering ? "thunder" : s.raining ? "rain" : "clear";
                            Cinema.sendCommand("weather " + w);
                            done.add("weather " + w);
                        }
                        if (tickRateChanged || Math.abs(mc.level.tickRateManager().tickrate() - s.tickRate) > 0.01f) {
                            Cinema.sendCommand(String.format(Locale.ROOT, "tick rate %.3f", s.tickRate));
                            done.add("tick rate " + s.tickRate);
                        }
                        // Client side.
                        p.getInventory().selected = s.selected;
                        mc.options.setCameraType(s.cameraType);
                        mc.options.hideGui = s.hideGui;
                        mc.options.renderDistance().set(s.renderDistance);
                        mc.options.fov().set(s.fov);
                        mc.options.bobView().set(s.bobView);
                        mc.options.guiScale().set(s.guiScale);
                        mc.options.autoJump().set(s.autoJump);
                        if (dev.everest8000.cinema.capture.RenderSize.active()) {
                            dev.everest8000.cinema.capture.RenderSize.clear(mc);
                            done.add("offscreen render size cleared");
                        }
                        mc.resizeDisplay();
                        if (s.shaders != null && !s.shaders.equals(Iris.enabled())) {
                            Iris.set(s.shaders);
                            done.add("shaders " + s.shaders);
                        }
                        if (mc.getWindow().getWidth() != s.windowWidth || mc.getWindow().getHeight() != s.windowHeight) {
                            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), s.windowWidth, s.windowHeight);
                            done.add("window " + s.windowWidth + "x" + s.windowHeight);
                        }
                        done.add("camera, HUD and client options");
                        waitUntil = System.nanoTime() + 1_500_000_000L;
                        phase = 1;
                        return false;
                    }
                    case 1 -> {
                        boolean modeOk = mc.gameMode.getPlayerMode() == s.gameMode;
                        boolean posOk = p.distanceToSqr(s.x, s.y, s.z) < 0.25;
                        if ((!modeOk || !posOk) && elapsedSec() < 15) return false;
                        if (System.nanoTime() < waitUntil) return false;
                        if (s.flying != p.getAbilities().flying && p.getAbilities().mayfly) {
                            p.getAbilities().flying = s.flying;
                            p.onUpdateAbilities();
                        }
                        if (!modeOk) warnings.add("game mode is " + mc.gameMode.getPlayerMode().getName() + ", expected " + s.gameMode.getName());
                        if (!posOk) warnings.add(String.format(Locale.ROOT, "player is at %.2f %.2f %.2f, expected %.2f %.2f %.2f",
                                p.getX(), p.getY(), p.getZ(), s.x, s.y, s.z));
                        List<ItemStack> now = current(p);
                        for (int i = 0; i < now.size() && i < s.slots.size(); i++) {
                            if (!ItemStack.matches(now.get(i), s.slots.get(i))) warnings.add("slot " + slotName(i) + " still differs");
                        }
                        for (ChatLog.Msg m : c.messages) if (m.error()) warnings.add("server: " + m.text());
                        c.close();
                        timeChanged = weatherChanged = tickRateChanged = false;
                        JsonObject r = new JsonObject();
                        r.add("restored", done);
                        r.add("warnings", warnings);
                        future.complete(r);
                        return true;
                    }
                    default -> throw new IllegalStateException();
                }
            }

            private void restoreInventory(Minecraft mc, LocalPlayer p) {
                List<ItemStack> now = current(p);
                int changed = 0;
                for (int i = 0; i < now.size() && i < s.slots.size(); i++) {
                    ItemStack want = s.slots.get(i);
                    if (ItemStack.matches(now.get(i), want)) continue;
                    String arg;
                    try {
                        arg = want.isEmpty() ? "minecraft:air" : itemArg(want, mc.level.registryAccess()) + " " + want.getCount();
                    } catch (RuntimeException e) {
                        warnings.add("couldn't serialise slot " + slotName(i) + ": " + e.getMessage());
                        CinemaClient.LOG.warn("Cinema: couldn't serialise {}", want, e);
                        continue;
                    }
                    Cinema.sendCommand("item replace entity @s " + slotName(i) + " with " + arg);
                    changed++;
                }
                if (changed > 0) done.add(changed + " inventory slot(s)");
            }

            @Override
            public void onCancel(String reason) {
                if (c != null) c.close();
            }
        };
    }
}
