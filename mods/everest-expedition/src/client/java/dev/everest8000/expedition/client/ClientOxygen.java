package dev.everest8000.expedition.client;

import dev.everest8000.expedition.gear.OxygenCylinderItem;
import dev.everest8000.expedition.net.OxygenSyncPayload;
import dev.everest8000.expedition.oxygen.Oxygen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * This player's breathing as the server last reported it, eased tick by tick so the gauge and the vignette move
 * smoothly between updates. Also plays the refill hiss.
 */
public final class ClientOxygen {
    /** The gauge fades in or out over this many ticks. */
    private static final float FADE_TICKS = 10f;
    private static final int HISS_EVERY_TICKS = 45;

    private static OxygenSyncPayload state;
    private static float spo2 = 1f;
    private static float spo2Before = 1f;
    private static float collapse;
    private static float collapseBefore;
    private static float alpha;
    private static float alphaBefore;
    private static final float[] fills = {-1f, -1f};
    private static final float[] fillsBefore = {-1f, -1f};
    private static int hissIn;
    private static boolean wasRefilling;

    private ClientOxygen() {}

    static void receive(OxygenSyncPayload payload) {
        state = payload;
    }

    static void reset() {
        state = null;
        spo2 = spo2Before = 1f;
        collapse = collapseBefore = 0f;
        alpha = alphaBefore = 0f;
        wasRefilling = false;
    }

    static void tick(Minecraft mc) {
        spo2Before = spo2;
        collapseBefore = collapse;
        alphaBefore = alpha;
        System.arraycopy(fills, 0, fillsBefore, 0, fills.length);
        LocalPlayer player = mc.player;
        boolean active = state != null && player != null && state.has(OxygenSyncPayload.ACTIVE);
        float targetSpo2 = state == null ? 1f : state.spo2() / 1000f;
        spo2 += (targetSpo2 - spo2) * 0.3f;
        float targetCollapse = state == null ? 0f : Math.min(1f, state.collapse() / (float) Oxygen.COLLAPSE_TICKS);
        // Blacking out comes on fast; a rescue clears it a little slower, like coming round.
        collapse += (targetCollapse - collapse) * (targetCollapse > collapse ? 0.25f : 0.08f);
        for (int i = 0; i < fills.length; i++) {
            int points = state != null && i < state.cylinders().length ? state.cylinders()[i] : -1;
            float target = points < 0 ? -1f : points / (float) OxygenCylinderItem.CAPACITY;
            fills[i] = target < 0f || fills[i] < 0f ? target : fills[i] + (target - fills[i]) * 0.3f;
            if (fillsBefore[i] < 0f) fillsBefore[i] = fills[i];
        }
        boolean show = active && (state.has(OxygenSyncPayload.THIN_AIR) || state.has(OxygenSyncPayload.REFILLING) || targetSpo2 < 0.999f);
        alpha = Mth.clamp(alpha + (show ? 1f : -1f) / FADE_TICKS, 0f, 1f);

        boolean refilling = active && state.has(OxygenSyncPayload.REFILLING);
        if (refilling && (!wasRefilling || --hissIn <= 0)) {
            // A soft hiss of gas going into the bottle.
            play(mc, player, SoundEvents.FIRE_EXTINGUISH, 0.12f, 1.6f + player.getRandom().nextFloat() * 0.2f);
            hissIn = HISS_EVERY_TICKS;
        } else if (!refilling && wasRefilling && active) {
            // The valve closing once they're full.
            play(mc, player, SoundEvents.LEVER_CLICK, 0.25f, 1.7f);
        }
        wasRefilling = refilling;
    }

    private static void play(Minecraft mc, LocalPlayer player, SoundEvent sound, float volume, float pitch) {
        if (mc.level != null) mc.level.playLocalSound(player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, volume, pitch, false);
    }

    /** Collapsed at empty: no sprinting (LocalPlayerSprintMixin). */
    public static boolean collapsed() {
        return state != null && state.collapse() > 0;
    }

    public static float spo2() {
        return spo2;
    }

    public static float spo2(float partialTick) {
        return Mth.lerp(partialTick, spo2Before, spo2);
    }

    /** How far through the collapse, 0..1. */
    public static float collapse() {
        return collapse;
    }

    public static float collapse(float partialTick) {
        return Mth.lerp(partialTick, collapseBefore, collapse);
    }

    /** Hypoxia damage has started. */
    public static boolean suffocating() {
        return state != null && state.collapse() > Oxygen.COLLAPSE_TICKS;
    }

    public static float gaugeAlpha() {
        return alpha;
    }

    static float gaugeAlpha(float partialTick) {
        return Mth.lerp(partialTick, alphaBefore, alpha);
    }

    /** Cylinder i's oxygen, 0..1, or -1 for an empty slot. */
    static float fill(int i, float partialTick) {
        if (fills[i] < 0f) return -1f;
        return Mth.lerp(partialTick, fillsBefore[i], fills[i]);
    }

    static int inUse() {
        return state == null ? -1 : state.inUse();
    }

    static boolean refilling() {
        return state != null && state.has(OxygenSyncPayload.REFILLING);
    }
}
