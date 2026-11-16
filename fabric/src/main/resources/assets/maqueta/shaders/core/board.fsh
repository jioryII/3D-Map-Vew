#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:world.glsl>

layout(location = 0) in vec2 vNdc;
layout(location = 0) out vec4 fragColor;

// The world outside what has been explored, per dimension:
//   Overworld: open sea in the map's pixel-water style, white/grey 2D pixel clouds above it.
//   Nether:    a lava sea with drifting crust, grey 2D smoke clouds above it.
//   End:       black void, nothing else.

vec3 unproject(vec2 ndc, float depth) {
    vec4 p = InvViewProj * vec4(ndc, depth, 1.0);
    return p.xyz / p.w;
}

int dimension() {
    return int(floor(Flags.w + 0.01));
}

void main() {
    int dim = dimension();
    // Minimap: only a faint veil for contrast over snow or bright sky.
    if (BoardDeep.a > 0.5) {
        vec2 uv = gl_FragCoord.xy / Viewport.xy;
        float r = length(uv - 0.5) * 2.0;
        float a = Cave.z * (1.0 - smoothstep(Viewport.z * 0.75, Viewport.w, r));
        vec3 veil = mix(vec3(0.03, 0.04, 0.06), BoardDeep.rgb * 0.4, 0.3);
        fragColor = vec4(veil * a, a);
        return;
    }
    if (dim == 3) {
        fragColor = vec4(gradeColor(vec3(0.30, 0.29, 0.32)), 1.0); // the End: plain grey, nothing else
        return;
    }

    vec3 nearP = unproject(vNdc, 1.0);
    vec3 farP = unproject(vNdc, Flags.x > 0.5 ? 0.0 : -1.0);
    vec3 dir = normalize(farP - nearP);
    float seaY = BoardColor.a;
    float t = dir.y < -1e-4 ? (seaY - nearP.y) / dir.y : 1e7;

    // Sky behind the horizon.
    vec3 skyTop = mix(AmbientColor.rgb * 1.05, BoardDeep.rgb, 0.35);
    vec3 skyLow = hazeColor();
    if (dim == 2) {
        skyTop = vec3(0.30, 0.28, 0.28);
        skyLow = vec3(0.40, 0.37, 0.37);
    }
    vec3 sky = mix(skyLow, skyTop, smoothstep(-0.05, 0.55, dir.y));
    if (t > 1e6) {
        fragColor = vec4(gradeColor(sky), 1.0);
        return;
    }

    vec3 hit = nearP + dir * t;
    vec2 worldXZ = hit.xz + Anchor.xy;
    float shadow = 1.0 - cloudShadowAt(vec3(worldXZ.x, seaY, worldXZ.y)) * 0.28;

    // Flat colour, no texture: the outside is a calm backdrop, the maqueta is the subject.
    vec3 base;
    if (dim == 2) {
        base = vec3(0.40, 0.37, 0.37) * shadow;                // the Nether: soft warm grey
    } else {
        vec3 water = vec3(0.13, 0.33, 0.58);                   // the Overworld: plain sea colour
        base = water * (SunColor.rgb * 0.75 * shadow + AmbientColor.rgb * 0.55);
    }
    base = mix(base, sky, max(haze(hit), smoothstep(Focus.w * 2.5, Focus.w * 6.0, length(hit.xz - Focus.xz))));

    // Clouds in the game's own style (see traceClouds): white, a little see-through, flat
    // tops and block-shaded sides. Grey storm cells mixed in; smoke-grey in the Nether.
    vec3 cp;
    vec4 cl = traceClouds(nearP, dir, cp);
    if (cl.a > 0.5) {
        vec2 cellW = floor((cp.xz + Anchor.xy - cloudDrift()) / CLOUD_CELL);
        float grey = step(0.62, fbm(cellW * 0.11 + vec2(13.1, -4.7)));
        vec3 white = dim == 2 ? vec3(0.55, 0.50, 0.49) : vec3(1.0);
        vec3 stormy = dim == 2 ? vec3(0.36, 0.33, 0.33) : vec3(0.74, 0.76, 0.82);
        vec3 body = mix(white, stormy, grey * 0.8);
        float light = clamp(dot(SunColor.rgb, vec3(0.30, 0.50, 0.20)), 0.35, 1.05);
        vec3 col = body * cl.rgb * (0.30 + 0.72 * light);
        col = mix(col, hazeColor(), haze(cp) * 0.15);
        base = mix(base, col, 0.82 * Atmos.y);
    }

    // Cave layer: the room goes dark so the lit cave reads like a model under a lamp.
    if (Cave.y > 0.5) {
        float l = dot(base, vec3(0.299, 0.587, 0.114));
        float glow = 1.0 - smoothstep(Cave.w * 0.2, Cave.w * 1.2, length(hit.xz - Focus.xz));
        base = mix(vec3(l) * vec3(0.14, 0.16, 0.22), vec3(l) * vec3(0.28, 0.29, 0.34), glow);
    }

    fragColor = vec4(gradeColor(base), 1.0);
}
