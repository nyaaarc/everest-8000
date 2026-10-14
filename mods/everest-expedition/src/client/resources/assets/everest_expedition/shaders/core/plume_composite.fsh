#version 150

// Lays the reduced-resolution plume (premultiplied colour) over the frame, with a small tent blur that hides the
// ray marcher's dither. Seen from under the cloud deck, the plume is above the clouds: wherever the frame behind it
// is cloud (grey or white rather than blue sky) it is hidden in proportion, whichever mod drew those clouds.

uniform sampler2D PlumeSampler;
uniform sampler2D BackgroundSampler;
uniform mat4 InvProjView;
uniform vec2 Deck;              // cloud base relative to the camera, 1 when the camera is under it

in vec2 ndc;
out vec4 fragColor;

void main() {
    vec2 uv = ndc * 0.5 + 0.5;
    vec2 texel = 1.0 / vec2(textureSize(PlumeSampler, 0));
    vec4 sum = texture(PlumeSampler, uv) * 4.0;
    sum += texture(PlumeSampler, uv + vec2(texel.x, 0.0)) * 2.0;
    sum += texture(PlumeSampler, uv - vec2(texel.x, 0.0)) * 2.0;
    sum += texture(PlumeSampler, uv + vec2(0.0, texel.y)) * 2.0;
    sum += texture(PlumeSampler, uv - vec2(0.0, texel.y)) * 2.0;
    sum += texture(PlumeSampler, uv + texel);
    sum += texture(PlumeSampler, uv - texel);
    sum += texture(PlumeSampler, uv + vec2(texel.x, -texel.y));
    sum += texture(PlumeSampler, uv + vec2(-texel.x, texel.y));
    vec4 plume = sum / 16.0;
    if (Deck.y > 0.5 && plume.a > 0.0) {
        vec4 far = InvProjView * vec4(ndc, 1.0, 1.0);
        vec3 dir = normalize(far.xyz / far.w);
        if (dir.y > 0.0) {
            vec3 bg = texture(BackgroundSampler, uv).rgb;
            float hi = max(bg.r, max(bg.g, bg.b));
            float lo = min(bg.r, min(bg.g, bg.b));
            float saturation = (hi - lo) / max(hi, 1.0e-3);
            float cloud = 1.0 - smoothstep(0.07, 0.24, saturation);
            plume *= 1.0 - 0.92 * cloud;
        }
    }
    fragColor = plume;
}
