package dev.everest8000.cinema;

import com.google.gson.JsonElement;
import dev.everest8000.cinema.rpc.RpcException;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Work that spans frames. {@link #update} runs on the render thread at the start of every frame until it returns true. */
public abstract class Task {
    public final CompletableFuture<JsonElement> future = new CompletableFuture<>();
    public final String name;
    protected final long startNanos = System.nanoTime();

    protected Task(String name) {
        this.name = name;
    }

    /** @return true once finished; complete {@link #future} before returning true. Exceptions fail the task. */
    public abstract boolean update(Minecraft mc) throws Exception;

    /** Called when the task is dropped (world closed, cancelled); release anything held. */
    public void onCancel(String reason) {}

    protected double elapsedSec() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** Polls until the supplier returns non-null; fails with TIMEOUT after timeoutSec (real time). */
    public static Task poll(String name, double timeoutSec, Supplier<JsonElement> check) {
        return new Task(name) {
            @Override
            public boolean update(Minecraft mc) {
                JsonElement r = check.get();
                if (r != null) {
                    future.complete(r);
                    return true;
                }
                if (elapsedSec() > timeoutSec) {
                    future.completeExceptionally(new RpcException(RpcException.TIMEOUT, name + " timed out after " + timeoutSec + " s"));
                    return true;
                }
                return false;
            }
        };
    }
}
