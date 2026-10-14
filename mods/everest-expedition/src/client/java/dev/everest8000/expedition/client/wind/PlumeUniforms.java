package dev.everest8000.expedition.client.wind;

import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Every value plume.fsh reads besides its samplers, uploaded straight to the bound program. Minecraft's shader JSON
 * can't describe uniform arrays, so these bypass it; the offline preview harness uses the same class, which keeps
 * the two from drifting apart.
 */
public final class PlumeUniforms {
    public final float[] invProjView = new float[16];
    public final float[] heightsInfo = new float[4];
    public final float[] horizonInfo = new float[4];
    public final float[] params = new float[4];
    public final float[] params2 = new float[4];
    public final float[] plumeA = new float[PlumeField.MAX * 4];
    public final float[] plumeB = new float[PlumeField.MAX * 4];
    public final float[] plumeC = new float[PlumeField.MAX * 4];
    public final float[] plumeD = new float[PlumeField.MAX * 4];
    public final float[] capA = new float[4];
    public final float[] capB = new float[4];
    public int plumeCount;
    public int noiseUnit = 5;
    public final PlumeLight light = new PlumeLight();

    private final Map<String, Integer> locations = new HashMap<>();
    private int locationsFor = -1;

    public void packField(PlumeField field, double camX, double camY, double camZ) {
        plumeCount = field.pack(camX, camY, camZ, plumeA, plumeB, plumeC, plumeD, capA, capB);
    }

    public void setHorizon(HorizonMap.Result horizon) {
        horizonInfo[0] = horizon.azMin();
        horizonInfo[1] = horizon.azSpan();
        horizonInfo[2] = horizon.logMin();
        horizonInfo[3] = horizon.logSpan();
    }

    /** Uploads everything to {@code program}, which must be the one in use. */
    public void upload(int program) {
        if (program != locationsFor) {
            locations.clear();
            locationsFor = program;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = stack.mallocFloat(16);
            m.put(invProjView).flip();
            GL20.glUniformMatrix4fv(loc(program, "InvProjView"), false, m);
            vec4(program, "HeightsInfo", heightsInfo, 1, stack);
            vec4(program, "HorizonInfo", horizonInfo, 1, stack);
            vec4(program, "Params", params, 1, stack);
            vec4(program, "Params2", params2, 1, stack);
            vec4(program, "PlumeA", plumeA, PlumeField.MAX, stack);
            vec4(program, "PlumeB", plumeB, PlumeField.MAX, stack);
            vec4(program, "PlumeC", plumeC, PlumeField.MAX, stack);
            vec4(program, "PlumeD", plumeD, PlumeField.MAX, stack);
            vec4(program, "CapA", capA, 1, stack);
            vec4(program, "CapB", capB, 1, stack);
            GL20.glUniform1i(loc(program, "PlumeCount"), plumeCount);
            GL20.glUniform1i(loc(program, "Noise"), noiseUnit);
            vec3(program, "SunDir", light.sunDir);
            vec3(program, "SunColor", light.sunColor);
            vec3(program, "SkyColor", light.skyColor);
            vec3(program, "HazeColor", light.hazeColor);
        }
    }

    private int loc(int program, String name) {
        return locations.computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
    }

    private void vec4(int program, String name, float[] values, int count, MemoryStack stack) {
        int l = loc(program, name);
        if (l < 0) return;
        FloatBuffer buf = stack.mallocFloat(count * 4);
        buf.put(values, 0, count * 4).flip();
        GL20.glUniform4fv(l, buf);
    }

    private void vec3(int program, String name, float[] v) {
        int l = loc(program, name);
        if (l >= 0) GL20.glUniform3f(l, v[0], v[1], v[2]);
    }
}
