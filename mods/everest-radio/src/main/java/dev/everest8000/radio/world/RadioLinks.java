package dev.everest8000.radio.world;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Who can be heard over the radio right now, and how badly: for each player holding a radio, every other player
 * carrying one with the signal's degradation (0 clear .. 1 worst). Immutable; RadioTracker publishes a new one a few
 * times a second for the voice thread to read.
 */
public record RadioLinks(Map<UUID, Map<UUID, Float>> bySender) {
    public static final RadioLinks NONE = new RadioLinks(Map.of());

    /** The receivers of a player holding a radio, or null if they aren't holding one. */
    @Nullable
    public Map<UUID, Float> receiversOf(UUID sender) {
        return bySender.get(sender);
    }
}
