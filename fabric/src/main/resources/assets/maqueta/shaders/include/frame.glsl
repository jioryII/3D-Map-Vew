#ifndef MAQUETA_FRAME_GLSL
#define MAQUETA_FRAME_GLSL

// One uniform block per rendered view (minimap or full map). Layout must match
// dev.maqueta.client.render.FrameUniforms (std140, 2 x mat4 + 15 x vec4).
layout(std140) uniform MaquetaFrame {
    mat4 ViewProj;
    mat4 InvViewProj;
    vec4 CameraEye;      // xyz eye (anchor relative), w = time in seconds
    vec4 SunDir;         // xyz towards the fixed sun, w = nightness 0..1
    vec4 SunColor;       // rgb direct light, a = saturation
    vec4 AmbientColor;   // rgb sky light, a = contrast
    vec4 EmissionColor;  // rgb block light, a = outside dim (cave layer)
    vec4 BoardColor;     // rgb table, a = table height
    vec4 BoardDeep;      // rgb table shadow, a = mode (0 full map, 1 minimap)
    vec4 Viewport;       // w, h, edge fade start, edge fade end (fraction of radius)
    vec4 Focus;          // xyz focus point, w = fog radius
    vec4 Cave;           // x cut height, y cave layer active, z veil alpha, w reveal radius
    vec4 Anchor;         // xy anchor (world x, z), z detail distance, w per-pixel shadows
    vec4 Player;         // xyz player (anchor relative), w see-through radius in pixels (0 = off)
    vec4 Atmos;          // x haze density, y cloud amount, z cloud height, w highest terrain in view
    vec4 Flags;          // x depth 0..1 (Vulkan) or -1..1 (OpenGL), y edge relief on, z table y, w dimension and cloud flag
    vec4 CutCenter;      // xy player in NDC, z distance limit, w valid clip position
};

const float TEXELS = 4.0; // texels per block edge, the maqueta's pixel grid

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * valueNoise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        a *= 0.5;
    }
    return v;
}

// 4x4 ordered dither threshold in 0..1, used for pixel-art transitions.
float bayer4(vec2 cell) {
    ivec2 p = ivec2(mod(cell, 4.0));
    int i = p.x + p.y * 4;
    const float M[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    return (M[i] + 0.5) / 16.0;
}

vec3 gradeColor(vec3 c) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, SunColor.a * 1.14); // a little extra colour for the animated look
    c = (c - 0.5) * AmbientColor.a + 0.5;
    // Soft highlight roll-off that keeps the hue: pale pink cherry leaves stay pink instead of clipping to white.
    float m = max(c.r, max(c.g, c.b));
    if (m > 0.82) c *= (0.82 + (1.0 - exp(-(m - 0.82) * 3.0)) * 0.15) / m;
    return clamp(c, 0.03, 0.97);
}

// Minimap: soft, slightly irregular dissolve towards the edge. Returns coverage 0..1.
float edgeCoverage(vec2 fragCoord, vec2 worldXZ) {
    if (BoardDeep.a < 0.5) return 1.0;
    vec2 uv = fragCoord / Viewport.xy;
    float r = length(uv - 0.5) * 2.0;
    float n = fbm(worldXZ * 0.045 + CameraEye.w * 0.02) - 0.5;
    float wobble = n * 0.10 + (valueNoise(uv * 9.0 + CameraEye.w * 0.05) - 0.5) * 0.03;
    return 1.0 - smoothstep(Viewport.z, Viewport.w, r + wobble);
}

// Aerial perspective, like looking at the Earth from a plane: almost nothing a few chunks
// away, thicker at hundreds of chunks. Exponential in the distance to the eye, so it costs one exp().
float haze(vec3 world) {
    // Air thickens with ground distance, not with altitude: looking straight down from high
    // up stays clear, the far horizon fades.
    vec3 dv = world - CameraEye.xyz;
    float d = length(dv.xz) + abs(dv.y) * 0.12;
    float k = pow(d * Atmos.x, 1.6);
    return 1.0 - exp(-k);
}

vec3 hazeColor() {
    return mix(AmbientColor.rgb * 1.08 + 0.06, BoardColor.rgb, 0.35);
}

// Soft drifting cloud cover in world space (density 0..1), pixel-snapped to 2 blocks.
float cloudDensity(vec2 worldXZ) {
    vec2 p = floor(worldXZ * 0.5) * 2.0;
    float t = CameraEye.w;
    float c = fbm(p * 0.0042 + vec2(t * 0.004, t * 0.0016)) * 0.7 + fbm(p * 0.011 - vec2(t * 0.003, -t * 0.005)) * 0.35;
    return smoothstep(0.44, 0.70, c);
}

// Shadow of the clouds on a world point (0 none .. 1 full), traced along the fixed sun.
float cloudShadow(vec3 worldAbs) {
    if (Atmos.y <= 0.001) return 0.0;
    float rise = max(SunDir.y, 0.2);
    vec2 at = worldAbs.xz + SunDir.xz * ((Atmos.z - worldAbs.y) / rise);
    return smoothstep(0.70, 0.92, cloudDensity(at)) * Atmos.y; // same coverage as the visible clouds
}

#endif
