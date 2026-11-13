#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec2 vNdc;
layout(location = 0) out vec4 fragColor;

vec3 unproject(vec2 ndc, float depth) {
    vec4 p = InvViewProj * vec4(ndc, depth, 1.0);
    return p.xyz / p.w;
}

void main() {
    // Minimap: only a faint veil for contrast over snow or bright sky.
    if (BoardDeep.a > 0.5) {
        vec2 uv = gl_FragCoord.xy / Viewport.xy;
        float r = length(uv - 0.5) * 2.0;
        float a = Cave.z * (1.0 - smoothstep(Viewport.z * 0.75, Viewport.w, r));
        vec3 veil = mix(vec3(0.03, 0.04, 0.06), BoardDeep.rgb * 0.4, 0.3);
        fragColor = vec4(veil * a, a);
        return;
    }

    // Full map: a neutral board under drifting low clouds (unexplored land).
    vec3 nearP = unproject(vNdc, 1.0);
    vec3 farP = unproject(vNdc, 0.0);
    vec3 dir = normalize(farP - nearP);
    float boardY = BoardColor.a;
    float t = dir.y < -1e-4 ? (boardY - nearP.y) / dir.y : 1e7;
    vec3 hit = nearP + dir * t;

    float vignette = smoothstep(0.35, 1.45, length(vNdc * vec2(0.85, 1.0)));
    vec3 base = mix(BoardColor.rgb, BoardDeep.rgb, vignette * 0.85);

    if (t < 1e6) {
        vec2 p = hit.xz;
        float dist = length(p - Focus.xz);
        // Chunk grid, fading with distance and with zoom.
        vec2 g = abs(fract(p / 16.0 - 0.5) - 0.5) * 16.0;
        float lineW = max(0.06, length(CameraEye.xyz - hit) * 0.0012);
        float grid = 1.0 - smoothstep(0.0, lineW, min(g.x, g.y));
        float gridFade = 1.0 - smoothstep(Focus.w * 0.25, Focus.w * 0.9, dist);
        base = mix(base, base * 0.86, grid * gridFade * 0.55);

        // Clouds: two fbm layers drifting in different directions.
        float time = CameraEye.w;
        float c1 = fbm(p * 0.0045 + vec2(time * 0.006, time * 0.002));
        float c2 = fbm(p * 0.011 - vec2(time * 0.004, -time * 0.007));
        float cloud = smoothstep(0.42, 0.78, c1 * 0.65 + c2 * 0.45);
        vec3 cloudCol = mix(BoardColor.rgb * 1.12 + 0.05, vec3(0.92, 0.94, 0.97), 0.35 * (1.0 - SunDir.w));
        cloudCol = mix(cloudCol, BoardColor.rgb * 1.2, SunDir.w * 0.6);
        base = mix(base, cloudCol, cloud * 0.55 * (1.0 - vignette * 0.5));

        // Soft cloud shadow on the board.
        float sh = fbm((p + SunDir.xz * 20.0) * 0.0045 + vec2(time * 0.006, time * 0.002));
        base *= 1.0 - smoothstep(0.45, 0.8, sh) * 0.08;
    }

    // Cave layer: the outside goes dark so the lit cave reads like a model in a dark room.
    if (Cave.y > 0.5) {
        float l = dot(base, vec3(0.299, 0.587, 0.114));
        float glow = 1.0 - smoothstep(Cave.w * 0.2, Cave.w * 1.2, length(hit.xz - Focus.xz));
        base = mix(vec3(l) * vec3(0.16, 0.18, 0.24), vec3(l) * vec3(0.30, 0.31, 0.36), glow);
    }

    fragColor = vec4(gradeColor(base), 1.0);
}
