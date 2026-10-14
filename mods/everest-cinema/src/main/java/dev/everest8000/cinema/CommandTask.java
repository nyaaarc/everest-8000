package dev.everest8000.cinema;

import com.google.gson.JsonObject;
import dev.everest8000.cinema.rpc.RpcException;
import net.minecraft.client.Minecraft;

/**
 * Sends one command and collects the game messages that follow until none arrive for quietMs (or timeoutSec passes).
 * With failOnError, a red (error) reply fails the task with COMMAND_FAILED.
 */
public final class CommandTask extends Task {
    private final String command;
    private final double timeoutSec;
    private final long quietNanos;
    private final boolean failOnError;
    private ChatLog.Collector collector;

    public CommandTask(String command, double timeoutSec, int quietMs, boolean failOnError) {
        super("command " + command);
        this.command = command.startsWith("/") ? command.substring(1) : command;
        this.timeoutSec = timeoutSec;
        this.quietNanos = quietMs * 1_000_000L;
        this.failOnError = failOnError;
    }

    @Override
    public boolean update(Minecraft mc) {
        if (collector == null) {
            Cinema.requireWorld();
            collector = Cinema.chat.open();
            Cinema.sendCommand(command);
            return false;
        }
        long now = System.nanoTime();
        boolean quiet = !collector.messages.isEmpty() && now - collector.lastNanos > quietNanos;
        if (!quiet && elapsedSec() < timeoutSec) return false;
        collector.close();
        JsonObject r = new JsonObject();
        r.addProperty("command", command);
        r.add("feedback", collector.json());
        r.addProperty("ok", !collector.hasError());
        if (failOnError && collector.hasError()) {
            future.completeExceptionally(new RpcException(RpcException.COMMAND_FAILED,
                    "\"" + command + "\" failed: " + collector.messages.get(0).text(), r));
        } else {
            future.complete(r);
        }
        return true;
    }

    @Override
    public void onCancel(String reason) {
        if (collector != null) collector.close();
    }
}
