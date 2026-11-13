#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec3 vAlbedo;
layout(location = 1) in vec4 vLight;   // sun, sky, contact, emission
layout(location = 2) flat in int vCode;
layout(location = 3) in vec3 vWorld;

layout(location = 0) out vec4 fragColor;

const vec3 NORMALS[6] = vec3[](
    vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0),
    vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0)
);

// Studio-like fill per face orientation: tops catch the most sky.
const float FACE_SKY[6] = float[](1.0, 0.45, 0.78, 0.86, 0.80, 0.84);

void main() {
    int normalId = vCode & 7;
    bool water = (vCode & 8) != 0;
    bool inside = (vCode & 16) != 0;
    bool built = (vCode & 32) != 0;
    bool caveLayer = Cave.y > 0.5;

    // Cave layer: only the space the player is in is shown; the surface steps aside.
    if (caveLayer && !inside) {
        discard;
    }

    // Whatever covers the focus point (a canopy, a roof) turns see-through with a soft
    // screen-door pattern, so the player is never hidden.
    float coverR = length(vWorld.xz - Focus.xz);
    if (!inside && vWorld.y > Focus.y + 2.5 && coverR < 7.0) {
        float fade = smoothstep(7.0, 3.0, coverR);
        ivec2 p = ivec2(gl_FragCoord.xy) & 3;
        float threshold = float((p.x * 4 + p.y * 2 + (p.x ^ p.y)) & 15) / 16.0 + 0.03;
        if (fade * 0.85 > threshold) {
            discard;
        }
    }

    vec3 n = NORMALS[clamp(normalId, 0, 5)];
    vec3 albedo = vAlbedo;
    float sunTerm = vLight.r;
    float skyTerm = vLight.g;
    float contact = vLight.b;
    float emit = vLight.a;

    // Wrapped lambert keeps the model readable on faces turned away from the sun.
    float ndl = dot(n, SunDir.xyz);
    float wrap = clamp(ndl * 0.62 + 0.38, 0.0, 1.0);
    vec3 direct = SunColor.rgb * sunTerm * wrap;

    // Cool shadows, warm lights (design 3.1).
    float shadow = 1.0 - sunTerm * clamp(ndl * 2.0 + 0.4, 0.0, 1.0);
    vec3 ambient = AmbientColor.rgb * (0.30 + 0.70 * skyTerm) * FACE_SKY[clamp(normalId, 0, 5)];
    ambient *= mix(vec3(1.0), vec3(0.90, 0.96, 1.10), shadow * 0.6);

    vec3 lit = albedo * (direct * 0.92 + ambient * 0.82) * mix(0.55, 1.0, contact);

    // Block light: torches and windows take over at night.
    float e = pow(emit, 1.35);
    lit += albedo * EmissionColor.rgb * e * 1.65;
    lit += EmissionColor.rgb * e * e * 0.18;

    if (water) {
        float t = CameraEye.w;
        float waves = sin(dot(vWorld.xz, vec2(0.62, 0.41)) + t * 1.4) * sin(dot(vWorld.xz, vec2(-0.37, 0.71)) - t * 1.1);
        float glint = smoothstep(0.55, 0.95, waves) * 0.22 + fbm(vWorld.xz * 0.15 + t * 0.12) * 0.06;
        vec3 view = normalize(CameraEye.xyz - vWorld);
        float fresnel = pow(1.0 - clamp(view.y, 0.0, 1.0), 3.0);
        vec3 skyRefl = mix(AmbientColor.rgb * 1.15, SunColor.rgb, 0.25);
        lit = mix(lit, skyRefl * 0.9, fresnel * 0.35) + SunColor.rgb * glint * sunTerm * (1.0 - SunDir.w * 0.7);
    }

    if (caveLayer) {
        if (inside) {
            // Interior of the cave: lifted shadows so it reads, torches stay warm.
            lit = albedo * (vec3(0.30, 0.32, 0.40) * mix(0.5, 1.0, contact) + EmissionColor.rgb * e * 1.9 + vec3(1.0, 0.78, 0.5) * e * 0.6);
            float reveal = 1.0 - smoothstep(Cave.w * 0.8, Cave.w, length(vWorld.xz - Focus.xz));
            lit *= mix(0.35, 1.0, reveal);
        } else {
            // Outside the cave: darken and desaturate so the cave pops.
            float l = dot(lit, vec3(0.299, 0.587, 0.114));
            lit = mix(lit, vec3(l) * vec3(0.55, 0.6, 0.75), 0.55) * (1.0 - EmissionColor.a);
        }
    }

    if (built && !caveLayer) {
        // A whisper of rim light so constructions read against the terrain.
        lit *= 1.0 + 0.06 * (1.0 - abs(ndl));
    }

    vec3 color = gradeColor(lit);

    // Far terrain melts into the board.
    float fog = distanceFog(vWorld);
    color = mix(color, BoardColor.rgb, fog);

    float coverage = edgeCoverage(gl_FragCoord.xy, vWorld.xz);
    if (coverage <= 0.002) {
        discard;
    }
    fragColor = vec4(color * coverage, coverage);
}
