package dev.everest8000.cinema;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Game (system) messages as they arrive, so a command's feedback can be returned to the caller. Commands carry no
 * request id, so a collector simply takes every message that arrives while it is open. Main thread only.
 */
public final class ChatLog {
    private static final TextColor RED = TextColor.fromLegacyFormat(ChatFormatting.RED);

    public record Msg(long nanos, String text, String key, List<String> args, boolean error) {
        public JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("text", text);
            if (key != null) {
                o.addProperty("translationKey", key);
                JsonArray a = new JsonArray();
                args.forEach(a::add);
                o.add("args", a);
            }
            o.addProperty("error", error);
            return o;
        }
    }

    public final class Collector {
        public final List<Msg> messages = new ArrayList<>();
        public long lastNanos = System.nanoTime();

        public void close() {
            collectors.remove(this);
        }

        public boolean hasError() {
            return messages.stream().anyMatch(Msg::error);
        }

        public JsonArray json() {
            JsonArray a = new JsonArray();
            messages.forEach(m -> a.add(m.json()));
            return a;
        }
    }

    private final List<Collector> collectors = new ArrayList<>();

    public Collector open() {
        Collector c = new Collector();
        collectors.add(c);
        return c;
    }

    public void onMessage(Component c) {
        Msg m = parse(c);
        for (Collector col : collectors) {
            col.messages.add(m);
            col.lastNanos = m.nanos;
        }
    }

    static Msg parse(Component c) {
        boolean error = isRed(c);
        TranslatableContents tc = findTranslatable(c);
        List<String> args = new ArrayList<>();
        if (tc != null) {
            for (Object a : tc.getArgs()) args.add(a instanceof Component comp ? comp.getString() : String.valueOf(a));
        }
        return new Msg(System.nanoTime(), c.getString(), tc == null ? null : tc.getKey(), args, error);
    }

    private static boolean isRed(Component c) {
        if (RED.equals(c.getStyle().getColor())) return true;
        for (Component s : c.getSiblings()) if (RED.equals(s.getStyle().getColor())) return true;
        return false;
    }

    private static TranslatableContents findTranslatable(Component c) {
        if (c.getContents() instanceof TranslatableContents tc) return tc;
        for (Component s : c.getSiblings()) {
            TranslatableContents t = findTranslatable(s);
            if (t != null) return t;
        }
        return null;
    }
}
