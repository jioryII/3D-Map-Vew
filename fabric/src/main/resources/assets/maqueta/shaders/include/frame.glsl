#ifndef MAQUETA_FRAME_GLSL
#define MAQUETA_FRAME_GLSL

// One uniform block per rendered view (minimap or full map). Layout must match
// dev.maqueta.client.render.FrameUniforms (std140, 2 x mat4 + 10 x vec4).
layout(std140) uniform MaquetaFrame {
    mat4 ViewProj;
    mat4 InvViewProj;
    vec4 CameraEye;      // xyz eye (anchor relative), w = time in seconds
    vec4 SunDir;         // xyz towards the fixed sun, w = nightness 0..1
    vec4 SunColor;       // rgb direct light, a = saturation
    vec4 AmbientColor;   // rgb sky light, a = contrast
    vec4 EmissionColor;  // rgb block light, a = outside dim (cave layer)
    vec4 BoardColor;     // rgb board, a = board height
    vec4 BoardDeep;      // rgb board shadow, a = mode (0 full map, 1 minimap)
    vec4 Viewport;       // w, h, edge fade start, edge fade end (fraction of radius)
    vec4 Focus;          // xyz focus point, w = fog radius
    vec4 Cave;           // x cut height, y cave layer active, z veil alpha, w reveal radius
};

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

vec3 gradeColor(vec3 c) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, SunColor.a);
    c = (c - 0.5) * AmbientColor.a + 0.5;
    return clamp(c, 0.035, 0.97);
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

// Full map: far terrain melts into the board.
float distanceFog(vec3 world) {
    if (BoardDeep.a > 0.5) return 0.0;
    float d = length(world.xz - Focus.xz);
    return smoothstep(Focus.w * 0.75, Focus.w * 1.25, d);
}

#endif
