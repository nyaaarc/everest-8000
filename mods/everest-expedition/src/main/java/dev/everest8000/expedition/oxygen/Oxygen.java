package dev.everest8000.expedition.oxygen;

import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.gear.OxygenCylinderItem;
import dev.everest8000.expedition.net.OxygenSyncPayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Thin air high on the mountain. Above the oxygen line players breathe from the cylinders they carry, using more the
 * harder they work. Without oxygen their blood oxygen (SpO2) falls, slowly just over the line and within a minute in
 * the death zone. At empty they collapse, crawling and blacking out, which leaves time for a teammate to hand them a
 * cylinder; after that hypoxia hurts until they get oxygen or die. Camps refill cylinders. The vanilla air supply is
 * left alone: the client draws its own gauge from {@link OxygenSyncPayload}.
 */
public final class Oxygen {
    /** Above this you need bottled oxygen: just below Camp 3, about 7,100 m. */
    public static final double LINE_Y = 1150;
    /** The death zone, the South Col (about 7,900 m) and up. */
    public static final double DEATH_ZONE_Y = 1560;
    /** Seconds for SpO2 to fall from full to empty without oxygen, just over the line and in the death zone. */
    public static final float SPO2_EMPTY_SECONDS_AT_LINE = 210f;
    public static final float SPO2_EMPTY_SECONDS_DEATH_ZONE = 40f;
    /** Seconds from empty back to full once breathing oxygen again, or back below the line. */
    public static final float SPO2_RECOVER_SECONDS = 6f;
    /** Time spent collapsed at empty before hypoxia starts to hurt: the window for a rescue. */
    public static final int COLLAPSE_TICKS = 20 * 20;
    public static final int DAMAGE_EVERY_TICKS = 40;
    public static final float DAMAGE = 2f;
    /** Movement lost while collapsed: a crawl, slower than Slowness IV. */
    public static final double COLLAPSE_SPEED = -0.85;
    /** Cylinder points used per second standing still, walking, and sprinting or climbing steeply. */
    public static final float USE_RESTING = 0.5f;
    public static final float USE_WALKING = 1f;
    public static final float USE_STRAINING = 2f;
    /** Blocks covered in one second that count as walking, and height gained that counts as climbing steeply. */
    public static final double WALKING_BLOCKS = 0.8;
    public static final double CLIMBING_BLOCKS = 1.2;
    /** Cylinders a player can carry in their inventory; a backpack doesn't count. */
    public static final int CARRY_LIMIT = 2;
    /** Seconds for a camp to fill an empty cylinder. */
    public static final int REFILL_SECONDS = 20;
    private static final int REFILL_EVERY_TICKS = 5;
    private static final int REFILL_STEP = OxygenCylinderItem.CAPACITY * REFILL_EVERY_TICKS / (REFILL_SECONDS * 20);
    private static final int SYNC_EVERY_TICKS = 5;
    /** Camps with oxygen stocks; Camp 1 has none. */
    private static final List<Camp> CAMPS = List.of(
            new Camp("Base Camp", 16747, 62, 7309, 64),
            new Camp("Camp 2", 19350, 730, 8150, 24),
            new Camp("Camp 3", 20430, 1192, 8917, 24),
            new Camp("Camp 4", 21067, 1555, 8562, 24));

    public static final ResourceKey<DamageType> HYPOXIA = ResourceKey.create(Registries.DAMAGE_TYPE, EverestExpedition.id("hypoxia"));
    private static final ResourceLocation COLLAPSE_ID = EverestExpedition.id("hypoxia_collapse");
    private static final AttachmentType<OxygenState> STATE = AttachmentRegistry.create(EverestExpedition.id("oxygen"),
            builder -> builder.persistent(OxygenState.CODEC).initializer(() -> OxygenState.FULL));
    private static final Map<UUID, Breather> BREATHERS = new HashMap<>();
    private static final Set<UUID> HYPOXIC = new HashSet<>();

    private Oxygen() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Oxygen::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BREATHERS.remove(handler.player.getUUID()));
    }

    /** Out of oxygen and not breathing: healing is blocked (see LivingEntityHealMixin). */
    public static boolean isHypoxic(ServerPlayer player) {
        return HYPOXIC.contains(player.getUUID());
    }

    /** Seconds from full SpO2 to empty without oxygen at height y: slow near the line, fast in the death zone. */
    public static float emptySeconds(double y) {
        float t = Mth.clamp((float) ((y - LINE_Y) / (DEATH_ZONE_Y - LINE_Y)), 0f, 1f);
        return Mth.lerp(t * t * (3f - 2f * t), SPO2_EMPTY_SECONDS_AT_LINE, SPO2_EMPTY_SECONDS_DEATH_ZONE);
    }

    private static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        HYPOXIC.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive()) continue;
            Breather breather = BREATHERS.computeIfAbsent(player.getUUID(), id -> new Breather(player.position()));
            if (!player.isCreative() && !player.isSpectator() && player.level() == overworld) {
                breathe(player, breather);
            } else {
                rest(player, breather);
            }
        }
    }

    private static void breathe(ServerPlayer player, Breather breather) {
        enforceCarryLimit(player);
        Inventory inventory = player.getInventory();
        List<Integer> slots = cylinderSlots(inventory);
        breather.observe(player);
        boolean thin = player.getY() > LINE_Y;
        Camp camp = slots.isEmpty() ? null : campAt(player);
        int inUse = thin && camp == null ? emptiestWithOxygen(inventory, slots) : -1;
        boolean breathing = !thin || camp != null || inUse >= 0;
        Breathing.update(player, inUse >= 0);

        boolean refilling = false;
        if (camp != null) {
            for (int slot : slots) {
                ItemStack cylinder = inventory.getItem(slot);
                int oxygen = OxygenCylinderItem.oxygen(cylinder);
                if (oxygen >= OxygenCylinderItem.CAPACITY) continue;
                refilling = true;
                if (breather.ticks % REFILL_EVERY_TICKS == 0) OxygenCylinderItem.setOxygen(cylinder, oxygen + REFILL_STEP);
            }
        }
        if (breather.ticks % 20 == 0) {
            float use = breather.takeEffort();
            if (inUse >= 0) {
                breather.debt += use;
                int whole = (int) breather.debt;
                breather.debt -= whole;
                ItemStack cylinder = inventory.getItem(slots.get(inUse));
                OxygenCylinderItem.setOxygen(cylinder, OxygenCylinderItem.oxygen(cylinder) - whole);
            }
        }

        OxygenState state = player.getAttachedOrCreate(STATE);
        float spo2 = breathing ? Math.min(1f, state.spo2() + 1f / (SPO2_RECOVER_SECONDS * 20f))
                : Math.max(0f, state.spo2() - 1f / (emptySeconds(player.getY()) * 20f));
        int collapse = !breathing && spo2 <= 0f ? Math.min(state.collapseTicks() + 1, Integer.MAX_VALUE / 2) : 0;
        save(player, state, spo2, collapse);
        setCollapsed(player, collapse > 0);
        if (collapse > 0) HYPOXIC.add(player.getUUID());
        if (collapse > COLLAPSE_TICKS && (collapse - COLLAPSE_TICKS - 1) % DAMAGE_EVERY_TICKS == 0) {
            player.hurt(player.damageSources().source(HYPOXIA), DAMAGE);
        }

        int flags = OxygenSyncPayload.ACTIVE | (thin ? OxygenSyncPayload.THIN_AIR : 0) | (refilling ? OxygenSyncPayload.REFILLING : 0);
        int[] fills = new int[CARRY_LIMIT];
        for (int i = 0; i < fills.length; i++) fills[i] = i < slots.size() ? OxygenCylinderItem.oxygen(inventory.getItem(slots.get(i))) : -1;
        sync(player, breather, new OxygenSyncPayload(flags, Math.round(spo2 * 1000f),
                Math.min(collapse, COLLAPSE_TICKS + 1), fills, inUse < CARRY_LIMIT ? inUse : -1));
    }

    /** Creative, spectator or another dimension: no thin air, and blood oxygen comes back. */
    private static void rest(ServerPlayer player, Breather breather) {
        OxygenState state = player.getAttachedOrCreate(STATE);
        save(player, state, Math.min(1f, state.spo2() + 1f / (SPO2_RECOVER_SECONDS * 20f)), 0);
        setCollapsed(player, false);
        breather.observe(player);
        Breathing.update(player, false);
        sync(player, breather, new OxygenSyncPayload(0, 1000, 0, new int[0], -1));
    }

    private static void save(ServerPlayer player, OxygenState state, float spo2, int collapse) {
        if (state.spo2() != spo2 || state.collapseTicks() != collapse) player.setAttached(STATE, new OxygenState(spo2, collapse));
    }

    /** Collapsed: a crawl with no jumping; the client also stops sprinting (LocalPlayerSprintMixin). */
    private static void setCollapsed(ServerPlayer player, boolean collapsed) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (speed == null || jump == null) return;
        if (collapsed == speed.hasModifier(COLLAPSE_ID)) return;
        if (collapsed) {
            speed.addTransientModifier(new AttributeModifier(COLLAPSE_ID, COLLAPSE_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            jump.addTransientModifier(new AttributeModifier(COLLAPSE_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            player.setSprinting(false);
        } else {
            speed.removeModifier(COLLAPSE_ID);
            jump.removeModifier(COLLAPSE_ID);
        }
    }

    private static void sync(ServerPlayer player, Breather breather, OxygenSyncPayload payload) {
        if (payload.sameAs(breather.sent)) return;
        boolean flagsChanged = breather.sent == null || breather.sent.flags() != payload.flags();
        if (!flagsChanged && breather.ticks - breather.sentAt < SYNC_EVERY_TICKS) return;
        if (ServerPlayNetworking.canSend(player, OxygenSyncPayload.TYPE)) ServerPlayNetworking.send(player, payload);
        breather.sent = payload;
        breather.sentAt = breather.ticks;
    }

    /** Inventory slots holding cylinders, in inventory order (hotbar first). */
    private static List<Integer> cylinderSlots(Inventory inventory) {
        List<Integer> slots = new ArrayList<>(CARRY_LIMIT);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (OxygenCylinderItem.is(inventory.getItem(i))) slots.add(i);
        }
        return slots;
    }

    /** Index into {@code slots} of the emptiest cylinder that still has oxygen, so the fuller one is kept in reserve. */
    private static int emptiestWithOxygen(Inventory inventory, List<Integer> slots) {
        int best = -1;
        int bestOxygen = Integer.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            int oxygen = OxygenCylinderItem.oxygen(inventory.getItem(slots.get(i)));
            if (oxygen > 0 && oxygen < bestOxygen) {
                best = i;
                bestOxygen = oxygen;
            }
        }
        return best;
    }

    /** Over the carry limit: the emptiest extras drop at the player's feet, so taking a full one swaps out an empty. */
    private static void enforceCarryLimit(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        List<Integer> slots = cylinderSlots(inventory);
        if (slots.size() <= CARRY_LIMIT) return;
        slots.sort(Comparator.comparingInt((Integer slot) -> OxygenCylinderItem.oxygen(inventory.getItem(slot))).reversed());
        for (int slot : slots.subList(CARRY_LIMIT, slots.size())) {
            ItemEntity item = new ItemEntity(player.level(), player.getX(), player.getY() + 0.25, player.getZ(),
                    inventory.removeItemNoUpdate(slot), 0, 0.1, 0);
            item.setPickUpDelay(40);
            player.level().addFreshEntity(item);
        }
    }

    /**
     * A cylinder on the ground isn't picked up by someone already carrying the limit unless it has more oxygen than
     * their emptiest one, which then drops instead. That lets a teammate's full cylinder through without the dropped
     * empty one bouncing straight back into the inventory.
     */
    public static boolean refusesPickup(Player player, ItemStack stack) {
        if (!OxygenCylinderItem.is(stack) || player.isCreative() || player.isSpectator()) return false;
        Inventory inventory = player.getInventory();
        int carried = 0;
        int emptiest = Integer.MAX_VALUE;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack held = inventory.getItem(i);
            if (!OxygenCylinderItem.is(held)) continue;
            carried++;
            emptiest = Math.min(emptiest, OxygenCylinderItem.oxygen(held));
        }
        return carried >= CARRY_LIMIT && OxygenCylinderItem.oxygen(stack) <= emptiest;
    }

    private static Camp campAt(Entity entity) {
        for (Camp camp : CAMPS) if (camp.contains(entity)) return camp;
        return null;
    }

    /** For {@code /everest oxygen}. */
    public static String describe(ServerPlayer player) {
        OxygenState state = player.getAttachedOrCreate(STATE);
        Inventory inventory = player.getInventory();
        StringBuilder cylinders = new StringBuilder();
        for (int slot : cylinderSlots(inventory)) {
            if (!cylinders.isEmpty()) cylinders.append(", ");
            cylinders.append(OxygenCylinderItem.oxygen(inventory.getItem(slot))).append('/').append(OxygenCylinderItem.CAPACITY);
        }
        Camp camp = campAt(player);
        boolean thin = player.getY() > LINE_Y;
        List<Integer> slots = cylinderSlots(inventory);
        boolean breathing = !thin || (camp != null && !slots.isEmpty()) || emptiestWithOxygen(inventory, slots) >= 0;
        String phase = state.collapseTicks() == 0 ? (breathing ? "breathing" : "no oxygen")
                : state.collapseTicks() <= COLLAPSE_TICKS ? String.format("collapsed %.1f s", state.collapseTicks() / 20f) : "hypoxia damage";
        return String.format("SpO2 %.1f%%, %s, cylinders [%s], %s, empties in %.0f s here%s",
                state.spo2() * 100f, phase, cylinders, player.getY() > LINE_Y ? "thin air" : "below the oxygen line",
                emptySeconds(player.getY()), camp == null ? "" : ", at " + camp.name());
    }

    /** For tests: set blood oxygen directly. */
    public static void setSpo2(ServerPlayer player, float spo2) {
        player.setAttached(STATE, new OxygenState(Mth.clamp(spo2, 0f, 1f), 0));
    }

    private record Camp(String name, double x, double y, double z, double radius) {
        boolean contains(Entity entity) {
            double dx = entity.getX() - x;
            double dz = entity.getZ() - z;
            return dx * dx + dz * dz <= radius * radius && Math.abs(entity.getY() - y) <= Math.max(16, radius / 2);
        }
    }

    /** Per-player bookkeeping that doesn't need saving: effort over the last second and what the client last got. */
    private static final class Breather {
        private Vec3 last;
        private double moved;
        private double climbed;
        private int sprinting;
        private int laddering;
        private int ticks;
        private float debt;
        private OxygenSyncPayload sent;
        private int sentAt;

        Breather(Vec3 position) {
            last = position;
        }

        void observe(ServerPlayer player) {
            Vec3 now = player.position();
            double dx = now.x - last.x;
            double dy = now.y - last.y;
            double dz = now.z - last.z;
            last = now;
            ticks++;
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 10) return; // a teleport or respawn, not effort
            moved += Math.sqrt(dx * dx + dz * dz);
            climbed += dy;
            if (player.isSprinting()) sprinting++;
            if (player.onClimbable() && dy > 0) laddering++;
        }

        /** Cylinder points for the second just gone. */
        float takeEffort() {
            float use = sprinting >= 10 || laddering >= 5 || climbed >= CLIMBING_BLOCKS ? USE_STRAINING
                    : moved >= WALKING_BLOCKS ? USE_WALKING : USE_RESTING;
            moved = 0;
            climbed = 0;
            sprinting = 0;
            laddering = 0;
            return use;
        }
    }
}
