package dev.everest8000.cinema.lod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Distant Horizons' own loading progress, read from its internals by reflection (its public API has no progress signal;
 * the names below were read from the 3.3.3 jar, not guessed). Three layers, all typed in {@link Status}:
 * <ul>
 * <li>work in flight: the task executors behind its F3 lines (world gen/import, render load, file handler, update
 * propagator, LOD builder, networking), the LOD retrieval queue (generation, or requests to the server in
 * multiplayer), the full-data network request queues, and the render-thread task queue (GPU uploads);</li>
 * <li>the render quadtree: enabled sections still building, uploading or dirty; sections drawn coarser than the detail
 * level the quadtree wants at their distance; positions with no data yet (missing) or queued for retrieval; sections
 * queued to reload;</li>
 * <li>DH's F3 lines, verbatim, for the record.</li>
 * </ul>
 * Anything not found is reported in {@code error}, never fatal.
 */
public final class DhProbe {
    private static final String P = "com.seibel.distanthorizons.";
    private static final String[] EXECUTORS = {"getWorldGenExecutor", "getRenderLoadingExecutor", "getFileHandlerExecutor",
            "getUpdatePropagatorExecutor", "getChunkToLodBuilderExecutor", "getNetworkCompressionExecutor"};

    /**
     * One reading. {@link #activity()} is the work in flight (zero when DH is idle); missing and coarse sections are
     * what is still to come, and stay above zero where there is no data to get (past the edge of a pre-generated map).
     */
    public record Status(boolean available, String error,
                         int execQueued, int execRunning, long execCompleted, JsonObject executors,
                         int retrievalWaiting, int retrievalInProgress, long retrievalRemainingChunks,
                         int networkQueues, int networkWaiting, int networkInProgress,
                         int renderTasksQueued,
                         boolean quadtree, int sectionsEnabled, int sectionsBuilding, int sectionsCoarse,
                         int missingPositions, int queuedPositions, int sectionsToReload,
                         int missingNear, int queuedNear, long networkFinished, long retrievalFinished,
                         int loadQueued, int loadRunning) {
        /**
         * Work in flight that a take has to wait for: DH's task pools, render-thread uploads, sections building or queued
         * to reload, and the server sync queue (finite: the LODs that changed since the last visit). The remote
         * generation/retrieval queue is not in it: DH keeps requesting LODs out to its whole render radius, past the edge
         * of the map too, so that queue never empties; what matters of it is counted in {@link #outstanding}.
         */
        public int activity() {
            return activity(true);
        }

        /**
         * withSync false leaves out the server sync queue: on arriving somewhere DH re-checks every LOD it has there with
         * the server (thousands of requests, ~8 a second); on a pre-generated map they rarely change what is drawn.
         */
        public int activity(boolean withSync) {
            if (withSync) {
                return execQueued + execRunning + networkWaiting + networkInProgress + renderTasksQueued + sectionsBuilding
                        + sectionsToReload;
            }
            // Without the sync: only the pools that turn data into what is drawn (render load, LOD builder, world gen,
            // update propagator); the file handler and network compression are busy storing the sync's answers.
            return loadQueued + loadRunning + renderTasksQueued + sectionsBuilding + sectionsToReload;
        }

        /** Still to come near the camera: sections drawn coarser than wanted, positions within nearRadius without data. */
        public int outstanding() {
            return sectionsCoarse + missingNear + queuedNear;
        }

        public JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("available", available);
            if (error != null) o.addProperty("error", error);
            o.addProperty("activity", activity());
            o.addProperty("outstanding", outstanding());
            JsonObject ex = new JsonObject();
            ex.addProperty("queued", execQueued);
            ex.addProperty("running", execRunning);
            ex.addProperty("completed", execCompleted);
            ex.addProperty("loadQueued", loadQueued);
            ex.addProperty("loadRunning", loadRunning);
            if (executors != null) ex.add("byExecutor", executors);
            o.add("executors", ex);
            JsonObject r = new JsonObject();
            r.addProperty("waiting", retrievalWaiting);
            r.addProperty("inProgress", retrievalInProgress);
            r.addProperty("estimatedRemainingChunks", retrievalRemainingChunks);
            o.add("retrieval", r);
            JsonObject n = new JsonObject();
            n.addProperty("queues", networkQueues);
            n.addProperty("waiting", networkWaiting);
            n.addProperty("inProgress", networkInProgress);
            o.add("network", n);
            o.addProperty("renderTasksQueued", renderTasksQueued);
            JsonObject q = new JsonObject();
            q.addProperty("available", quadtree);
            q.addProperty("sectionsEnabled", sectionsEnabled);
            q.addProperty("sectionsBuilding", sectionsBuilding);
            q.addProperty("sectionsCoarse", sectionsCoarse);
            q.addProperty("missingPositions", missingPositions);
            q.addProperty("queuedPositions", queuedPositions);
            q.addProperty("sectionsToReload", sectionsToReload);
            q.addProperty("nearRadius", nearRadius);
            q.addProperty("missingNear", missingNear);
            q.addProperty("queuedNear", queuedNear);
            o.add("quadtree", q);
            n.addProperty("finished", networkFinished);
            r.addProperty("finished", retrievalFinished);
            return o;
        }
    }

    /** Older callers: pending work and a completion counter. */
    public record Snapshot(boolean available, int pending, long completed, JsonObject detail, String error) {}

    private DhProbe() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    public static Snapshot snapshot() {
        return snapshot(false);
    }

    public static Snapshot snapshot(boolean withF3) {
        Status s = status();
        JsonObject d = s.json();
        if (withF3) d.add("f3", f3Lines());
        return new Snapshot(s.available(), s.activity(), s.execCompleted(), d, s.error());
    }

    public static Status status() {
        if (!installed()) {
            return new Status(false, "Distant Horizons isn't installed", 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0);
        }
        List<String> errors = new ArrayList<>();
        int eq = 0, er = 0, lq = 0, lr = 0;
        long ec = 0;
        JsonObject exJson = new JsonObject();
        boolean execOk = false;
        try {
            Class<?> util = Class.forName(P + "core.util.threading.ThreadPoolUtil");
            for (String getter : EXECUTORS) {
                try {
                    Object e = util.getMethod(getter).invoke(null);
                    if (e == null) continue;
                    int q = (int) e.getClass().getMethod("getQueueSize").invoke(e);
                    int r = (int) e.getClass().getMethod("getRunningTaskCount").invoke(e);
                    int c = (int) e.getClass().getMethod("getCompletedTaskCount").invoke(e);
                    JsonObject j = new JsonObject();
                    j.addProperty("queued", q);
                    j.addProperty("running", r);
                    j.addProperty("completed", c);
                    exJson.add(getter.substring(3, getter.length() - "Executor".length()), j);
                    eq += q;
                    er += r;
                    ec += c;
                    if (!getter.equals("getFileHandlerExecutor") && !getter.equals("getNetworkCompressionExecutor")) {
                        lq += q;
                        lr += r;
                    }
                    execOk = true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    errors.add(getter + ": " + e);
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            errors.add("executors: " + e);
        }

        int rw = 0, rp = 0, nq = 0, nw = 0, np = 0, rt = 0;
        long rc = 0, nf = 0, rf = 0;
        boolean qt = false;
        int en = 0, bld = 0, coarse = 0, miss = 0, queued = 0, reload = 0, missNear = 0, queuedNear = 0;
        try {
            Class<?> shared = Class.forName(P + "core.api.internal.SharedApi");
            Object world = shared.getMethod("getAbstractDhWorld").invoke(null);
            if (world != null) {
                Class<?> clientLevelType = Class.forName(P + "core.level.DhClientLevel");
                Class<?> netQueueType = Class.forName(P + "core.multiplayer.client.AbstractFullDataNetworkRequestQueue");
                Iterable<?> levels = (Iterable<?>) world.getClass().getMethod("getAllLoadedLevels").invoke(world);
                for (Object level : levels) {
                    // Full-data network request queues (multiplayer), wherever they hang off the level.
                    List<Object> found = new ArrayList<>();
                    collect(level, netQueueType, found, new IdentityHashMap<>(), 0);
                    for (Object q : found) {
                        nw += (int) netQueueType.getMethod("getWaitingTaskCount").invoke(q);
                        np += (int) netQueueType.getMethod("getInProgressTaskCount").invoke(q);
                        nf += ((java.util.concurrent.atomic.AtomicInteger) field(q, "finishedRequests")).get();
                        nq++;
                    }
                    if (!clientLevelType.isInstance(level)) continue;
                    // LOD retrieval queue (world gen, or the server in multiplayer).
                    try {
                        Object module = field(level, "lodRequestModule");
                        Object state = module == null ? null : ((AtomicReference<?>) field(module, "lodRequestStateRef")).get();
                        Object rq = state == null ? null : field(state, "retrievalQueue");
                        if (rq != null) {
                            Class<?> rqType = Class.forName(P + "core.generation.queues.IFullDataSourceRetrievalQueue");
                            rw += (int) rqType.getMethod("getWaitingTaskCount").invoke(rq);
                            rp += (int) rqType.getMethod("getInProgressTaskCount").invoke(rq);
                            rc += (long) rqType.getMethod("getRetrievalEstimatedRemainingChunkCount").invoke(rq);
                            if (netQueueType.isInstance(rq)) {
                                rf += ((java.util.concurrent.atomic.AtomicInteger) field(rq, "finishedRequests")).get();
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        errors.add("retrieval: " + e);
                    }
                    // The render quadtree.
                    try {
                        Object clientside = field(level, "clientside");
                        Object renderState = clientside == null ? null : ((AtomicReference<?>) field(clientside, "ClientRenderStateRef")).get();
                        Object tree = renderState == null ? null : field(renderState, "quadtree");
                        if (tree != null) {
                            int[] t = quadtree(tree);
                            qt = true;
                            en += t[0];
                            bld += t[1];
                            coarse += t[2];
                            miss += t[3];
                            queued += t[4];
                            reload += t[5];
                            missNear += t[6];
                            queuedNear += t[7];
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        errors.add("quadtree: " + e);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            errors.add("levels: " + e);
        }
        try {
            Class<?> h = Class.forName(P + "core.render.RenderThreadTaskHandler");
            Field f = h.getDeclaredField("RENDER_THREAD_RUNNABLE_QUEUE");
            f.setAccessible(true);
            rt = ((Collection<?>) f.get(null)).size();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            errors.add("render tasks: " + e);
        }
        return new Status(execOk, errors.isEmpty() ? null : String.join("; ", errors), eq, er, ec, exJson, rw, rp, rc, nq, nw, np,
                rt, qt, en, bld, coarse, miss, queued, reload, missNear, queuedNear, nf, rf, lq, lr);
    }

    /** How far from the camera missing or queued positions count as "near" (they block a take). */
    public static volatile double nearRadius = 6000;

    /**
     * {enabled, building/uploading/dirty, coarser than wanted, missing positions, queued positions, to reload,
     * missing within nearRadius, queued within nearRadius}.
     */
    private static int[] quadtree(Object tree) throws ReflectiveOperationException {
        Class<?> treeType = tree.getClass();
        Object missSet = field(tree, "missingGenerationPosSet"), queuedSet = field(tree, "queuedGenerationPosSet");
        int miss = sizeOf(missSet);
        int queued = sizeOf(queuedSet);
        int reload = sizeOf(field(tree, "sectionsToReload"));
        ArrayList<Object> sections = new ArrayList<>();
        treeType.getMethod("populateListWithEnabledRenderSections", ArrayList.class).invoke(tree, sections);
        Object center = treeType.getMethod("getCenterBlockPos").invoke(tree);
        Class<?> secPos = Class.forName(P + "core.pos.DhSectionPos");
        int missNear = near(missSet, center, secPos), queuedNear = near(queuedSet, center, secPos);
        Class<?> posType = Class.forName(P + "core.pos.blockPos.DhBlockPos2D");
        Method expected = treeType.getMethod("calcExpectedDetailLevel", posType, long.class);
        Method detailOf = Class.forName(P + "core.pos.DhSectionPos").getMethod("getDetailLevel", long.class);
        // As LodQuadTree.recursivelyUpdateRenderSectionNode: wanted = min(expected, minRootRenderDetailLevel) + 6
        // (section detail levels start at data detail level + 6).
        byte minRoot = (byte) field(tree, "minRootRenderDetailLevel");
        int building = 0, coarse = 0;
        Method canRender = null, uploading = null;
        Field dirty = null, posField = null, futureRef = null;
        for (Object s : sections) {
            if (canRender == null) {
                Class<?> st = s.getClass();
                canRender = st.getMethod("canRender");
                uploading = st.getMethod("gpuUploadInProgress");
                dirty = st.getField("renderDataDirty");
                posField = st.getField("pos");
                futureRef = st.getDeclaredField("getAndBuildRenderDataFutureRef");
                futureRef.setAccessible(true);
            }
            boolean busy = !(boolean) canRender.invoke(s) || (boolean) uploading.invoke(s) || dirty.getBoolean(s);
            if (!busy) {
                Object f = ((AtomicReference<?>) futureRef.get(s)).get();
                busy = f instanceof CompletableFuture<?> cf && !cf.isDone();
            }
            if (busy) building++;
            long pos = posField.getLong(s);
            if (center != null) {
                int want = Math.min((byte) expected.invoke(tree, center, pos), minRoot) + 6;
                byte have = (byte) detailOf.invoke(null, pos);
                if (have > want) coarse++; // a higher detail-level number is coarser
            }
        }
        return new int[]{sections.size(), building, coarse, miss, queued, reload, missNear, queuedNear};
    }

    /** How many section positions in the set have their centre within nearRadius blocks of the tree centre. */
    private static int near(Object set, Object center, Class<?> secPos) throws ReflectiveOperationException {
        if (!(set instanceof Collection<?> c) || center == null || c.isEmpty()) return 0;
        Method cx = secPos.getMethod("getCenterBlockPosX", long.class), cz = secPos.getMethod("getCenterBlockPosZ", long.class);
        int x0 = center.getClass().getField("x").getInt(center), z0 = center.getClass().getField("z").getInt(center);
        double r2 = nearRadius * nearRadius;
        int n = 0;
        Object[] items;
        synchronized (c) {
            items = c.toArray();
        }
        for (Object o : items) {
            if (!(o instanceof Long pos)) continue;
            double dx = (int) cx.invoke(null, pos) - x0, dz = (int) cz.invoke(null, pos) - z0;
            if (dx * dx + dz * dz <= r2) n++;
        }
        return n;
    }

    private static int sizeOf(Object o) {
        return o instanceof Collection<?> c ? c.size() : 0;
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    private static void collect(Object o, Class<?> type, List<Object> out, Map<Object, Boolean> seen, int depth) {
        if (o == null || depth > 2 || seen.put(o, true) != null) return;
        if (type.isInstance(o)) {
            out.add(o);
            return;
        }
        if (!o.getClass().getName().startsWith("com.seibel.distanthorizons")) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    collect(f.get(o), type, out, seen, depth + 1);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Inaccessible field: skip.
                }
            }
        }
    }

    /** Distant Horizons' own F3 lines (colour codes removed), for the record. */
    public static JsonArray f3Lines() {
        JsonArray a = new JsonArray();
        try {
            Class<?> f3 = Class.forName(P + "core.logging.f3.F3Screen");
            Method m = f3.getMethod("addStringToDisplay", List.class);
            List<String> lines = new ArrayList<>();
            m.invoke(null, lines);
            for (String s : lines) {
                String t = s.replaceAll("§.", "").trim();
                if (!t.isEmpty()) a.add(t);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            a.add("unavailable: " + e);
        }
        return a;
    }
}
