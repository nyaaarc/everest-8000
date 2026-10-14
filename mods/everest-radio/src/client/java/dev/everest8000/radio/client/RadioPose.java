package dev.everest8000.radio.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.net.RadioKeyedPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Raising the radio to a talker's face, like a walkie-talkie: how far up each player's radio is (0 down .. 1 at the
 * mouth) and the pose that amount blends in. The server says who is talking ({@link RadioKeyedPayload}); the radio
 * comes up in about 175 ms and goes down in 300 ms, eased, timed in client ticks so frame-stepped captures repeat
 * exactly. Third person, the radio arm swings up to follow the head (which stays free) and the radio moves from the
 * hand to the corner of the mouth; first person, the radio comes up toward the bottom middle of the view.
 * <p>
 * Public hook for other client mods, such as the cinema's actor clones; it takes client-side entity ids and no
 * Minecraft types, so it can be called through reflection without depending on this mod:
 * <ul>
 * <li>{@link #setKeyed(int, boolean)} raises or lowers that entity's radio with the same easing as a transmission;
 * <li>{@link #setAmount(int, float)} pins the amount (0..1) directly, NaN lets go;
 * <li>{@link #amount(int, float)} is the current amount.
 * </ul>
 * Both last until changed or until the entity unloads. The arm is the one holding a radio (main hand first), or the
 * main arm if neither hand has one.
 */
public final class RadioPose {
    private static final float RAISE_TICKS = 3.5f; // 175 ms up
    private static final float LOWER_TICKS = 6f; // 300 ms down
    /** A talker the server hasn't confirmed for this long (ticks) is dropped, in case a "stop" never arrives. */
    private static final int SERVER_TIMEOUT_TICKS = 3 * RadioKeyedPayload.REFRESH_TICKS;

    // Third person, matching art/radio_pose_preview.py. Angles in degrees; positions in model pixels in the head's
    // frame: "out" from the middle toward the radio's side, then model y and z (negative is up and forward).
    /** The head pitch the radio follows, clamped so the arm never folds into the body or reaches over the head. */
    static float pitchMin = -40, pitchMax = 55;
    /** Where the radio sits when fully up: by the corner of the mouth, the grille at mouth height. */
    static float mouthOut = 5.5f, mouthY = 1, mouthZ = -8;
    /** How it is turned there: about its upright axis (negative shows its face to the front), leaned, rolled. */
    static float mouthTurn = -35, mouthLean = -10, mouthRoll = 0;
    /** Where the hand ends: below and outside the radio, gripping its lower half. The arm is aimed at it. */
    static float gripOut = 7, gripY = 0.5f, gripZ = -8.5f;

    // First person, in the hand's frame after vanilla's own placement: x in toward the middle, y up, z toward you.
    // Tuned in game: the radio comes in to the bottom middle, nearer, turned and tilted to your mouth, and stays well
    // below the crosshair looking level, down or up.
    static float fpIn = 0.40f, fpUp = -0.04f, fpBack = 0.26f;
    static float fpTurn = 45, fpLean = -15, fpRoll = 10;

    private static final Int2ObjectMap<State> STATES = new Int2ObjectOpenHashMap<>();
    private static int ticks;

    private RadioPose() {
    }

    private static final class State {
        boolean server;
        int heard;
        boolean hook;
        float pinned = Float.NaN;
        float progress;
        float previous;

        boolean up() {
            return hook || server && ticks - heard <= SERVER_TIMEOUT_TICKS;
        }
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RadioKeyedPayload.TYPE, (payload, context) -> {
            State s = payload.active() ? STATES.computeIfAbsent(payload.entityId(), id -> new State()) : STATES.get(payload.entityId());
            if (s != null) {
                s.server = payload.active();
                s.heard = ticks;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        // Out of sight or gone: forget it (the server tells us again if they come back into view while talking).
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> STATES.remove(entity.getId()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> STATES.clear());
    }

    /** Raises (or lowers) the radio of the client-side entity {@code entityId}, eased, until called again. */
    public static void setKeyed(int entityId, boolean keyed) {
        State s = keyed ? STATES.computeIfAbsent(entityId, id -> new State()) : STATES.get(entityId);
        if (s != null) {
            s.hook = keyed;
        }
    }

    /** Pins how far up the radio of {@code entityId} is (0..1), skipping the easing; NaN lets go of it. */
    public static void setAmount(int entityId, float amount) {
        State s = Float.isNaN(amount) ? STATES.get(entityId) : STATES.computeIfAbsent(entityId, id -> new State());
        if (s != null) {
            s.pinned = Float.isNaN(amount) ? Float.NaN : Mth.clamp(amount, 0, 1);
        }
    }

    /** How far up the radio of {@code entityId} is this frame: 0 down .. 1 at the mouth. */
    public static float amount(int entityId, float partialTick) {
        State s = STATES.get(entityId);
        if (s == null) {
            return 0;
        }
        if (!Float.isNaN(s.pinned)) {
            return s.pinned;
        }
        float p = Mth.lerp(Mth.clamp(partialTick, 0, 1), s.previous, s.progress);
        return p * p * (3 - 2 * p); // ease in and out
    }

    private static void tick() {
        ticks++;
        if (STATES.isEmpty()) {
            return;
        }
        STATES.values().removeIf(s -> {
            s.previous = s.progress;
            s.progress = s.up() ? Math.min(1, s.progress + 1 / RAISE_TICKS) : Math.max(0, s.progress - 1 / LOWER_TICKS);
            return s.progress == 0 && s.previous == 0 && !s.up() && Float.isNaN(s.pinned);
        });
    }

    /** The arm that comes up: the one holding a radio (main hand first), else the main arm. */
    public static HumanoidArm arm(LivingEntity entity) {
        if (!EverestRadio.isRadio(entity.getMainHandItem()) && EverestRadio.isRadio(entity.getOffhandItem())) {
            return entity.getMainArm().getOpposite();
        }
        return entity.getMainArm();
    }

    /**
     * Third person, after vanilla's setupAnim: blend the radio arm {@code k} of the way to the walkie-talkie pose. The
     * arm points from the shoulder at the grip point, which turns with the head, so the hand stays at the radio
     * wherever the player looks.
     */
    public static void poseArm(ModelPart arm, ModelPart head, boolean right, float k) {
        float side = right ? 1 : -1;
        Vector3f toGrip = new Vector3f(-side * gripOut, gripY, gripZ)
                .rotateX(clampedPitch(head)).rotateY(head.yRot).rotateZ(head.zRot)
                .add(head.x, head.y, head.z).sub(arm.x, arm.y, arm.z).normalize();
        // The arm hangs along +y; xRot then yRot (zRot 0) turn it to point along toGrip.
        float xRot = -(float) Math.acos(Mth.clamp(toGrip.y, -1, 1));
        float yRot = (float) Mth.atan2(-toGrip.x, -toGrip.z);
        arm.xRot = Mth.lerp(k, arm.xRot, xRot);
        arm.yRot = Mth.lerp(k, arm.yRot, yRot);
        arm.zRot = Mth.lerp(k, arm.zRot, 0);
    }

    private static float clampedPitch(ModelPart head) {
        return Mth.clamp(head.xRot, pitchMin * Mth.DEG_TO_RAD, pitchMax * Mth.DEG_TO_RAD);
    }

    /**
     * Third person, in place of vanilla's hand transform for the radio: from the hand ({@code k} = 0) to the corner of
     * the mouth ({@code k} = 1), moving and turning smoothly in between. Applied to {@code pose}, which is at the
     * model's origin; the item is then drawn with its usual third-person display transform.
     */
    public static void placeRadio(PoseStack pose, HumanoidModel<?> model, HumanoidArm arm, float k) {
        boolean left = arm == HumanoidArm.LEFT;
        PoseStack hand = new PoseStack();
        model.translateToHand(arm, hand);
        hand.mulPose(Axis.XP.rotationDegrees(-90));
        hand.mulPose(Axis.YP.rotationDegrees(180));
        hand.translate((left ? -1 : 1) / 16f, 0.125f, -0.625f);

        PoseStack mouth = new PoseStack();
        ModelPart head = model.head;
        float headPitch = head.xRot;
        head.xRot = clampedPitch(head);
        head.translateAndRotate(mouth);
        head.xRot = headPitch;
        float side = left ? -1 : 1;
        mouth.translate(-side * mouthOut / 16, mouthY / 16, mouthZ / 16);
        mouth.mulPose(Axis.ZP.rotationDegrees(180));
        mouth.mulPose(Axis.YP.rotationDegrees(side * mouthTurn));
        mouth.mulPose(Axis.XP.rotationDegrees(mouthLean));
        mouth.mulPose(Axis.ZP.rotationDegrees(side * mouthRoll));

        Matrix4f from = hand.last().pose(), to = mouth.last().pose();
        Vector3f at = from.getTranslation(new Vector3f()).lerp(to.getTranslation(new Vector3f()), k);
        Quaternionf turn = from.getNormalizedRotation(new Quaternionf()).slerp(to.getNormalizedRotation(new Quaternionf()), k);
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(turn);
    }

    /** First person, just before the radio is drawn: bring it {@code k} of the way up toward your mouth. */
    public static void raiseFirstPerson(PoseStack pose, boolean right, float k) {
        float side = right ? 1 : -1;
        pose.translate(-side * fpIn * k, fpUp * k, fpBack * k);
        pose.mulPose(Axis.YP.rotationDegrees(side * fpTurn * k));
        pose.mulPose(Axis.XP.rotationDegrees(fpLean * k));
        pose.mulPose(Axis.ZP.rotationDegrees(side * fpRoll * k));
    }
}
