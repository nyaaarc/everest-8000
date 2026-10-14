#version 150

// The summit plume: snow and cloud torn off Everest, Lhotse and Nuptse by the jet stream, plus the cloud cap that
// sits on the upper mountain during a storm. Ray-marched at reduced resolution into an offscreen target after the
// world is drawn, then laid over the frame. Positions are relative to the camera.
// The envelope numbers in plumeEnv mirror PlumeField.box(); change them together.

uniform sampler2D SceneDepth;   // the world's depth buffer: nearby blocks and entities
uniform sampler2D Heights;      // baked terrain surface (R32F), so the cloud never sits inside rock
uniform sampler2D Horizon;      // highest terrain angle per azimuth (rows) and distance (columns), R32F
uniform sampler3D Noise;        // 64^3 tileable noise with mipmaps: r body, g billows, b detail, a warp

uniform mat4 InvProjView;       // clip space -> camera-relative world
uniform vec4 HeightsInfo;       // grid corner x, z (camera-relative), cell size, camera y
uniform vec4 HorizonInfo;       // azimuth start, azimuth span (radians), log of nearest and span of distances

uniform vec4 PlumeA[3];         // origin xyz, length
uniform vec4 PlumeB[3];         // wind direction x, z, strength, size
uniform vec4 PlumeC[3];         // advection, storm, meander phase, seed
uniform vec4 PlumeD[3];         // box: half width, bottom, top, upwind start
uniform int PlumeCount;
uniform vec4 CapA;              // centre xyz, radius
uniform vec4 CapB;              // base y, top y, strength, advection

uniform vec3 SunDir;
uniform vec3 SunColor;
uniform vec3 SkyColor;
uniform vec3 HazeColor;
uniform vec4 Params;            // steps, time (s), overall fade, density scale
uniform vec4 Params2;           // radians per pixel of this target, near fade distance, unused, unused

in vec2 ndc;
out vec4 fragColor;

// The view ray, for the density function: fibres seen end-on would fan out from the vanishing point.
vec3 viewDir;

const float EXTINCTION = 0.022;
const float FAR = 1.0e8;
const float STRETCH = 5.0; // keep PlumeField's texture period in step

float terrainY(vec2 xz) {
    vec2 g = (xz - HeightsInfo.xy) / HeightsInfo.z - 0.5;
    ivec2 i = ivec2(floor(g));
    ivec2 size = textureSize(Heights, 0);
    if (i.x < 0 || i.y < 0 || i.x >= size.x - 1 || i.y >= size.y - 1) return -1.0e4;
    vec2 f = g - vec2(i);
    float a = texelFetch(Heights, i, 0).r;
    float b = texelFetch(Heights, i + ivec2(1, 0), 0).r;
    float c = texelFetch(Heights, i + ivec2(0, 1), 0).r;
    float d = texelFetch(Heights, i + ivec2(1, 1), 0).r;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) - HeightsInfo.w;
}

// Noise filtered to the pixel's footprint: unfiltered detail kilometres away turns into glitter.
// texelBlocks is how many blocks one texel spans at this lookup's scale.
vec4 noise(vec3 coord, float texelBlocks, float footprint) {
    float lod = log2(max(footprint / texelBlocks, 1.0));
    return textureLod(Noise, coord, lod);
}

// Envelope of plume i at p (no noise): the smooth shape the noise is carved from. q gets the plume-frame
// coordinates (downwind, across from the meandering centre line, above the sinking centre line).
float plumeEnv(int i, vec3 p, out vec3 q) {
    vec4 A = PlumeA[i];
    vec4 B = PlumeB[i];
    vec4 C = PlumeC[i];
    vec3 d = p - A.xyz;
    float s = dot(d.xz, B.xy);
    float c = dot(d.xz, vec2(-B.y, B.x));
    float L = A.w, k = B.w, storm = C.y;
    float u = clamp(s / L, 0.0, 1.0);
    // Lee lift: the banner rises a little above the summit as it streams off; a storm drags it down the face.
    float lift = 14.0 * k + (0.05 - 0.07 * storm) * max(s, 0.0) * (1.0 - 0.5 * u);
    float sink = 60.0 * k * storm * (1.0 - exp(-max(s, 0.0) / (220.0 * k)));
    float hc = lift - sink;
    float cc = k * 30.0 * sin(s / (380.0 * k) + C.z) * smoothstep(0.0, 500.0 * k, s);
    float grow = pow(u, 0.8);
    float wc = k * (26.0 + 50.0 * storm) + (0.11 + 0.08 * storm) * L * grow;
    float dh = d.y - hc;
    float hv = dh > 0.0 ? k * (20.0 + 40.0 * storm) + 0.06 * L * grow
                        : k * (45.0 + 110.0 * storm) + 0.10 * L * grow;
    float dc = c - cc;
    float e = exp(-1.2 * dc * dc / (wc * wc) - 1.4 * dh * dh / (hv * hv));
    float root = smoothstep(-55.0 * k, 20.0 * k, s);
    float tail = 1.0 - smoothstep(0.45 * L, L, s);
    // Densest where it tears off the summit.
    float feed = 1.0 + 0.9 * exp(-max(s, 0.0) / (160.0 * k));
    q = vec3(s, dc, dh);
    return min(e * root * tail * (1.0 - 0.45 * u) * feed, 1.0) * B.z;
}

float capEnv(vec3 p) {
    if (CapB.z <= 0.0) return 0.0;
    float r = length(p.xz - CapA.xz);
    float radial = 1.0 - smoothstep(0.45 * CapA.w, CapA.w, r);
    float vertical = smoothstep(CapB.x - 30.0, CapB.x + 180.0, p.y) * (1.0 - smoothstep(CapB.y - 220.0, CapB.y + 80.0, p.y));
    // Never a solid block: the noise always shows through, so the cap reads as billows from below too.
    return radial * vertical * CapB.z * 0.72;
}

// Where the envelope is strong the noise only adds texture; where it is weak only the noise's peaks survive, which
// frays the edges into wisps. Soft on both sides so thin parts fade rather than break into specks.
float carve(float n, float env) {
    float edge = 1.0 - 0.85 * env;
    return smoothstep(edge - 0.08, edge + 0.5, n) * smoothstep(0.0, 0.2, env);
}

float density(vec3 p, float footprint) {
    float total = 0.0;
    for (int i = 0; i < 3; i++) {
        if (i >= PlumeCount) break;
        vec3 q;
        float env = plumeEnv(i, p, q);
        if (env < 0.004) continue;
        vec4 C = PlumeC[i];
        float F = 150.0 * PlumeB[i].w;
        float along = q.x - C.x;
        // Body: stretched along the wind and carried downwind with it.
        vec3 nc = vec3(along / (F * STRETCH), q.y / F, q.z / (F * 0.55)) + C.w;
        nc.z += Params.y * 0.004;
        float warp = noise(nc * 0.0625, F * 0.55 / 4.0, footprint).a - 0.5;
        nc += vec3(0.0, warp * 0.9, warp * 0.6);
        vec4 n = noise(nc * 0.25, F * 0.55 / 16.0, footprint);
        // Streaks: fine across the wind, very long along it.
        vec3 sc = vec3(along / (F * 10.0), q.y / (F * 0.3), q.z / (F * 0.25)) + C.w * 1.7 + vec3(0.0, warp * 0.5, 0.0);
        float streak = noise(sc * 0.25, F * 0.25 / 16.0, footprint).b;
        // Pulses: the summit sheds cloud in bursts that ride downwind.
        float pulse = noise(vec3(along / (F * 2.5), C.w * 3.0, 0.37) * 0.25, F * 2.5 / 16.0, footprint).r;
        // Summing channels flattens the noise; stretch it back to full contrast.
        float fibrous = clamp((n.r * 0.45 + n.g * 0.25 + streak * 0.30 - 0.5) * 2.1 + 0.5, 0.0, 1.0);
        // Near the summit the cloud billows (rounder, less stretched); the fibres take over toward the tail.
        float rootness = 1.0 - smoothstep(0.12 * PlumeA[i].w, 0.32 * PlumeA[i].w, q.x);
        // Looking along the wind the fibres would converge into a starburst: there the billows take over too.
        float endOn = smoothstep(0.55, 0.9, abs(dot(viewDir, vec3(PlumeB[i].x, 0.0, PlumeB[i].y))));
        float body = fibrous;
        if (rootness > 0.0 || endOn > 0.0) {
            vec3 bc = vec3(along / (F * 1.25), q.y / (F * 0.75), q.z / (F * 0.6)) + C.w * 0.5 + vec3(0.0, warp, warp * 0.5);
            vec4 nb = noise(bc * 0.25, F * 0.6 / 16.0, footprint);
            float billow = clamp((nb.g * 0.65 + nb.r * 0.35 - 0.5) * 2.3 + 0.5, 0.0, 1.0);
            body = mix(fibrous, billow, max(rootness * 0.75, endOn));
        }
        // Bursts ride downwind; the root itself is fed steadily, so it never comes loose from the summit.
        float d = carve(body, env * mix(1.0, mix(0.55, 1.3, pulse), 1.0 - 0.7 * rootness));
        float detail = noise(nc * 0.75 + vec3(0.37, 0.11, 0.73), F * 0.55 / 48.0, footprint).b;
        d = clamp(d - (1.0 - d) * (detail - 0.4) * 0.6 * (1.0 - 0.8 * endOn), 0.0, 1.0);
        total += d * (0.6 + 0.6 * env) * (1.0 + 0.25 * rootness);
    }
    float cap = capEnv(p);
    if (cap > 0.004) {
        vec2 w = PlumeB[0].xy;
        vec2 rel = p.xz - CapA.xz;
        vec3 nc = vec3((dot(rel, w) - CapB.w) / 520.0, dot(rel, vec2(-w.y, w.x)) / 300.0, (p.y - CapA.y) / 240.0);
        nc.z += Params.y * 0.006;
        float warp = noise(nc * 0.0625 + 0.5, 240.0 / 4.0, footprint).a - 0.5;
        nc += warp * 0.7;
        vec4 n = noise(nc * 0.25, 240.0 / 16.0, footprint);
        // Billows with firmer edges than the plume's streaks.
        float body = clamp((n.g * 0.6 + n.r * 0.4 - 0.5) * 2.2 + 0.5, 0.0, 1.0);
        float edge = 1.0 - 0.9 * cap;
        float d = smoothstep(edge - 0.04, edge + 0.22, body) * smoothstep(0.0, 0.15, cap);
        float detail = noise(nc + 0.21, 240.0 / 64.0, footprint).b;
        d = clamp(d - (1.0 - d) * (detail - 0.4) * 0.5, 0.0, 1.0);
        total += d * (0.4 + 0.6 * cap);
    }
    if (total <= 0.0) return 0.0;
    float ground = terrainY(p.xz);
    return total * smoothstep(ground - 2.0, ground + 14.0, p.y);
}

// The envelopes only: cheap, for finding empty space and for light reaching a point.
float shapeAt(vec3 p) {
    float s = 0.0;
    vec3 q;
    for (int i = 0; i < 3; i++) {
        if (i >= PlumeCount) break;
        s += plumeEnv(i, p, q);
    }
    return s + capEnv(p);
}

bool slab(float o, float d, float lo, float hi, inout float t0, inout float t1) {
    if (abs(d) < 1.0e-6) return o >= lo && o <= hi;
    float a = (lo - o) / d, b = (hi - o) / d;
    t0 = max(t0, min(a, b));
    t1 = min(t1, max(a, b));
    return t0 <= t1;
}

void plumeInterval(int i, vec3 dir, inout float lo, inout float hi) {
    vec4 A = PlumeA[i];
    vec4 B = PlumeB[i];
    vec4 D = PlumeD[i];
    vec2 w = B.xy, n = vec2(-w.y, w.x);
    // The camera is at the origin; in the plume frame it sits at -A.
    float os = dot(-A.xz, w), oc = dot(-A.xz, n), oh = -A.y;
    float ds = dot(dir.xz, w), dc = dot(dir.xz, n), dh = dir.y;
    float t0 = 0.0, t1 = FAR;
    if (slab(os, ds, D.w, A.w, t0, t1) && slab(oc, dc, -D.x, D.x, t0, t1) && slab(oh, dh, D.y, D.z, t0, t1)) {
        lo = min(lo, t0);
        hi = max(hi, t1);
    }
}

void capInterval(vec3 dir, inout float lo, inout float hi) {
    if (CapB.z <= 0.0) return;
    float t0 = 0.0, t1 = FAR;
    if (slab(0.0, dir.x, CapA.x - CapA.w, CapA.x + CapA.w, t0, t1)
            && slab(0.0, dir.z, CapA.z - CapA.w, CapA.z + CapA.w, t0, t1)
            && slab(0.0, dir.y, CapB.x - 40.0, CapB.y + 100.0, t0, t1)) {
        lo = min(lo, t0);
        hi = max(hi, t1);
    }
}

float sceneDistance() {
    ivec2 size = textureSize(SceneDepth, 0);
    vec2 uv = ndc * 0.5 + 0.5;
    ivec2 c = ivec2(uv * vec2(size));
    // The nearest of the full-resolution depths under this pixel, so the cloud never bleeds over an edge.
    float depth = 1.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++)
            depth = min(depth, texelFetch(SceneDepth, clamp(c + ivec2(x, y), ivec2(0), size - 1), 0).r);
    if (depth >= 1.0) return FAR;
    vec4 p = InvProjView * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    return length(p.xyz / p.w);
}

// The highest terrain angle in this ray's azimuth closer than horizontal distance h, interpolated between the
// table's azimuth columns and distance steps so a ridge comes out as a smooth line, not a staircase of cells.
// azRow is the ray's fractional row in the table, or negative when the table doesn't cover its azimuth.
float horizonAngle(float azRow, float h) {
    if (azRow < 0.0) return -2.0;
    ivec2 size = textureSize(Horizon, 0);
    float fd = clamp((log(max(h, 1.0)) - HorizonInfo.z) / HorizonInfo.w, 0.0, 1.0) * float(size.x - 1);
    int d0 = int(floor(fd));
    int d1 = min(d0 + 1, size.x - 1);
    int a0 = int(floor(azRow));
    int a1 = a0 + 1;
    // A full circle wraps around; a slice is clamped at its edges.
    bool full = HorizonInfo.y > 6.28;
    a0 = full ? (a0 + size.y) % size.y : clamp(a0, 0, size.y - 1);
    a1 = full ? (a1 + size.y) % size.y : clamp(a1, 0, size.y - 1);
    float wd = fd - float(d0), wa = fract(azRow);
    float h0 = mix(texelFetch(Horizon, ivec2(d0, a0), 0).r, texelFetch(Horizon, ivec2(d1, a0), 0).r, wd);
    float h1 = mix(texelFetch(Horizon, ivec2(d0, a1), 0).r, texelFetch(Horizon, ivec2(d1, a1), 0).r, wd);
    return mix(h0, h1, wa);
}

float phaseHG(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.566 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

void main() {
    vec4 far = InvProjView * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w);
    viewDir = dir;

    float lo = FAR, hi = 0.0;
    for (int i = 0; i < 3; i++) {
        if (i >= PlumeCount) break;
        plumeInterval(i, dir, lo, hi);
    }
    capInterval(dir, lo, hi);
    hi = min(hi, sceneDistance());
    // This ray's place in the horizon table; terrain hides a sample when the sample sits below the terrain's angle.
    float horiz = length(dir.xz);
    float elev = atan(dir.y, horiz);
    float azU = mod(atan(dir.z, dir.x) - HorizonInfo.x, 6.2831853) / HorizonInfo.y;
    float azRow = azU >= 0.0 && azU < 1.0 ? azU * float(textureSize(Horizon, 0).y) - 0.5 : -1.0;
    // The edge is feathered over about a pixel and a half, so it reads as the cloud passing behind the ridge.
    float feather = Params2.x * 1.5;
    if (hi <= lo) {
        fragColor = vec4(0.0);
        return;
    }

    int steps = int(Params.x);
    float dt = (hi - lo) / float(steps);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float t = lo + dt * jitter;

    float cosSun = dot(dir, SunDir);
    // Forward scattering gives the bright rim when looking toward the sun; the rest is a soft isotropic glow.
    float phase = 0.6 + 5.0 * phaseHG(cosSun, 0.6) + 1.0 * phaseHG(cosSun, -0.25);
    float k = EXTINCTION * Params.w;
    vec3 light = vec3(0.0);
    float transmit = 1.0;
    for (int n = 0; n < 256; n++) {
        if (t >= hi || transmit < 0.01) break;
        vec3 p = dir * t;
        float shape = shapeAt(p);
        if (shape < 0.004) {
            // Empty part of the box: stride over it.
            t += dt * 2.0;
            continue;
        }
        float visible = smoothstep(-feather, feather, elev - horizonAngle(azRow, t * horiz));
        // The table only rises with distance: once a ridge hides the ray, everything beyond is hidden too.
        if (visible <= 0.0) break;
        float footprint = t * Params2.x;
        // Up close the cloud thins out: the snow particles carry the near field.
        float dens = density(p, footprint) * Params.w * smoothstep(Params2.y * 0.3, Params2.y, t) * visible;
        if (dens > 0.002) {
            float toSun = shapeAt(p + SunDir * 50.0) * 70.0 + shapeAt(p + SunDir * 170.0) * 150.0;
            // Clouds are brighter inside than single scattering allows; a second, weaker extinction stands in
            // for the light that bounces around before it leaves.
            float sun = max(exp(-toSun * k * 0.45), 0.7 * exp(-toSun * k * 0.12));
            float powder = 1.0 - exp(-dens * 5.0);
            // Light from the sky above, dimmed under thick cloud: storm bases are grey.
            float above = shapeAt(p + vec3(0.0, 110.0, 0.0)) * 170.0;
            float sky = 0.45 + 0.55 * exp(-above * k * 0.35);
            // Deep inside the storm cap little light gets down from its top: its base is a grey ceiling.
            float capShare = capEnv(p) / max(shape, 1.0e-3);
            float capDepth = clamp((CapB.y - p.y) / max(CapB.y - CapB.x, 1.0), 0.0, 1.0);
            float shade = 1.0 - 0.65 * capShare * capDepth;
            vec3 radiance = SunColor * sun * phase * mix(0.75, 1.0, powder) * shade + SkyColor * sky * (0.6 + 0.4 * shade);
            float a = exp(-dens * k * dt);
            light += transmit * radiance * (1.0 - a);
            transmit *= a;
        }
        t += dt;
    }

    float alpha = (1.0 - transmit) * Params.z;
    if (alpha <= 0.002) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 color = light / max(1.0 - transmit, 1.0e-4);
    // A little air between the eye and a cloud kilometres away.
    float haze = 1.0 - exp(-0.5 * (lo + hi) / 60000.0);
    color = mix(color, HazeColor, haze);
    color = pow(max(color, vec3(0.0)), vec3(1.0 / 2.2));
    fragColor = vec4(color * alpha, alpha);
}
