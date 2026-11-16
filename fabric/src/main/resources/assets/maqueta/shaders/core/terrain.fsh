#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:world.glsl>

layout(location = 0) in vec3 vAlbedo;
layout(location = 1) in vec4 vLight;   // sun, sky, contact, emission (water: shore openness)
layout(location = 2) flat in int vCode;
layout(location = 3) in vec3 vWorld;
layout(location = 4) flat in int vTile;
layout(location = 5) flat in int vMaterial;
layout(location = 6) in float vEdge;
layout(location = 7) flat in int vCap;

layout(location = 0) out vec4 fragColor;

const vec3 NORMALS[6] = vec3[](
    vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0),
    vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0)
);

// Studio fill per face orientation: tops catch the most sky.
const float FACE_SKY[6] = float[](1.0, 0.45, 0.78, 0.86, 0.80, 0.84);

const int MAT_FOLIAGE = 2;
const int MAT_LAVA = 15;
const int MAT_EMISSIVE = 16;

// Face-plane coordinates (u, v) of a world point for a given normal.
vec2 facePlane(vec3 p, int n) {
    if (n <= 1) return p.xz;
    if (n <= 3) return vec2(p.x, -p.y);
    return vec2(p.z, -p.y);
}

// Re-evaluate an interpolated value at the centre of the current texel, so baked light
// steps in the same 4x4 pixel grid as the textures instead of sliding smoothly.
vec4 snapToTexel(vec4 v, vec2 uv, vec2 uvSnap) {
    vec2 du = dFdx(uv), dv = dFdy(uv);
    float det = du.x * dv.y - du.y * dv.x;
    if (abs(det) < 1e-9) return v;
    mat2 inv = mat2(dv.y, -du.y, -dv.x, du.x) / det;
    vec4 gx = dFdx(v), gy = dFdy(v);
    vec2 d = uvSnap - uv;
    vec2 w = inv * d;
    return v + gx * w.x + gy * w.y;
}

// How much the block centred at `cell` is lifted by the cutaway (1 = gone, 0 = kept).
// The radius wobbles per block, so the opening has a built, stepped outline, not a circle.
// How strongly the cutaway removes a point (1 = gone, 0 = untouched). `cell` is the centre of
// the block it belongs to (decides above/below the player's floor, so a floor is never cut
// half-way), `p` the point itself (gives a smooth radial falloff on screen). The radius is
// stretched by slow noise, so the opening is organic rather than a perfect circle.
float cutAmount(vec3 cell, vec3 p) {
    if (cell.y < Player.y + 0.2) return 0.0;                       // the floor and below stay
    if (length(cell - CameraEye.xyz) > CutCenter.z) return 0.0;
    vec4 fc = ViewProj * vec4(p, 1.0);
    if (CutCenter.w <= 0.0 || fc.w <= 0.0) return 0.0;
    vec2 d = (CutCenter.xy - fc.xy / fc.w) * 0.5 * Viewport.xy;
    float wobble = valueNoise(p.xz * 0.21 + p.y * 0.13) - 0.5;
    float rad = Player.w * (1.0 + wobble * 0.35);
    // Wide, soft falloff: a misty clearing rather than a drawn circle.
    return 1.0 - smoothstep(rad * 0.10, rad * 1.30, length(d));
}

void main() {
    int normalId = clamp(vCode & 7, 0, 5);
    bool water = (vCode & 8) != 0;
    bool inside = (vCode & 16) != 0;
    bool built = (vCode & 32) != 0;
    bool unseen = (vCode & 64) != 0;
    bool section = (vCode & 128) != 0;
    bool caveLayer = Cave.y > 0.5;

    // Cave layer: the surface steps aside, only the sliced underground remains.
    if (caveLayer && !inside) {
        discard;
    }

    // See-through: a soft dithered window onto the player. Only what stands above the player's
    // floor and between the eye and the player fades, in an ordered dither locked to the
    // model's own 4x4 pixel grid (it never crawls while the camera moves); the floor and all
    // below it are never touched. Where a block is faded, the block under it shows its top
    // (a quiet cap) instead of a hole. On the full map the window closes as the camera pulls
    // away (Player.w is 0 there), so a far view never opens onto the underside of the world.
    bool capped = false;
    if (vCap == 1) {
        if (Player.w < 0.5 || inside || water) discard;
        vec3 above = floor(vWorld - NORMALS[normalId] * 0.01) + vec3(0.5, 1.5, 0.5);
        if (cutAmount(above, vWorld + vec3(0.0, 0.5, 0.0)) < 0.5) discard;
        capped = true;
    } else if (Player.w > 0.5 && !inside) {
        vec3 cell = floor(vWorld - NORMALS[normalId] * 0.01) + 0.5;
        float k = cutAmount(cell, vWorld);
        if (k > 0.0) {
            vec2 cutTexel = floor(facePlane(vWorld, normalId) * TEXELS);
            // Blend of ordered and random thresholds: soft and grainy, no visible pattern or ring.
            float th = mix(bayer4(cutTexel), hash12(cutTexel + floor(cell.y) * 7.3), 0.55);
            if (k * 1.15 > th) discard;
        }
    }
    vec3 n = NORMALS[normalId];
    vec3 worldAbs = vWorld + vec3(Anchor.x, 0.0, Anchor.y);

    // --- the maqueta pixel grid ---
    vec2 uv = facePlane(worldAbs - n * 0.001, normalId);
    vec2 texel = floor(uv * TEXELS);
    vec2 uvSnap = (texel + 0.5) / TEXELS;
    ivec2 local = ivec2(mod(texel, TEXELS));

    // How many screen pixels one texel covers: below ~1 the detail fades to the average.
    vec2 fw = fwidth(uv * TEXELS);
    float detail = 1.0 - smoothstep(0.55, 1.1, max(fw.x, fw.y));
    float near = 1.0 - smoothstep(Anchor.z * 0.75, Anchor.z, length(vWorld.xz - Focus.xz));

    vec4 light = detail > 0.01 ? mix(vLight, snapToTexel(vLight, uv, uvSnap), detail) : vLight;
    light.a = vLight.a; // block light stays smooth: torches glow in soft gradients, not block by block
    float sunTerm = light.r;
    float skyTerm = light.g;
    float contact = light.b;
    float emit = light.a;

    // --- material tile ---
    vec3 albedo = vAlbedo;
    if (vTile > 0 && !water) {
        ivec2 tileOrigin = ivec2(vTile % 64, vTile / 64) * 4;
        ivec2 lt = local;
        bool natural = vMaterial == 1 || (vMaterial >= 3 && vMaterial <= 9) || vMaterial >= 17;
        if (natural && normalId <= 1) {
            // Natural ground: rotate and mirror each block's tile at random so the 4x4 grid never repeats.
            float hsh = hash12(floor(uv) + vec2(17.0, 31.0));
            int rot = int(hsh * 4.0);
            for (int k = 0; k < rot; k++) lt = ivec2(3 - lt.y, lt.x);
            if (fract(hsh * 7.31) > 0.5) lt.x = 3 - lt.x;
        }
        vec3 ratio = texelFetch(TileAtlas, tileOrigin + lt, 0).rgb * 2.0;
        albedo = mix(albedo, clamp(albedo * ratio, 0.0, 1.0), detail);
    } else if (!water && detail > 0.01) {
        // Untextured faces still get a faint hand-made grain.
        float g = hash12(texel + vec2(float(normalId) * 17.0, floor(worldAbs.y))) - 0.5;
        albedo *= 1.0 + g * 0.06 * detail;
    }

    // --- sun: crisp per-texel shadow rays near the camera, baked soft light further away ---
    float ndl = dot(n, SunDir.xyz);
    if (Anchor.w > 0.5 && !inside && near > 0.01 && ndl > -0.2) {
        vec2 snapped = uvSnap;
        vec3 p = worldAbs;
        if (normalId <= 1) p.xz = snapped;
        else if (normalId <= 3) { p.x = snapped.x; p.y = -snapped.y; }
        else { p.z = snapped.x; p.y = -snapped.y; }
        float hard = sunRay(p + n * 0.02);
        sunTerm = mix(sunTerm, hard, near); // same brightness inside and outside the precise range: no visible circle
    }

    // (No cloud shadows on the explored land: the sky stays clear over the maqueta.)
    float wrap = clamp(ndl * 0.62 + 0.38, 0.0, 1.0);
    vec3 direct = SunColor.rgb * sunTerm * wrap;

    // Warm light, cool shadows (a touch of violet in the deepest ones).
    float shade = 1.0 - sunTerm * clamp(ndl * 2.0 + 0.4, 0.0, 1.0);
    vec3 ambient = AmbientColor.rgb * (0.50 + 0.50 * skyTerm) * FACE_SKY[normalId]; // open shade, never a black hole
    ambient *= mix(vec3(1.0), vec3(0.86, 0.92, 1.12), shade * 0.7);

    float ao = mix(0.68, 1.0, contact);
    vec3 lit = albedo * (direct * 0.92 + ambient * 0.98) * ao;

    // Gentle painterly banding of the light (not of the colour).
    float lum = dot(lit, vec3(0.299, 0.587, 0.114));
    float banded = (floor(lum * 9.0) + 0.5) / 9.0;
    lit *= mix(1.0, banded / max(lum, 1e-3), 0.28 * detail);

    // Block light: torches and windows take over at night; lava and lamps glow.
    float e = pow(emit, 1.35);
    if (vMaterial == MAT_LAVA || vMaterial == MAT_EMISSIVE) e = max(e, 0.85);
    lit += albedo * EmissionColor.rgb * e * 1.65;
    lit += EmissionColor.rgb * e * e * 0.18;

    if (water) {
        float t = CameraEye.w;
        vec3 view = normalize(CameraEye.xyz - vWorld);
        float fresnel = pow(1.0 - clamp(view.y, 0.0, 1.0), 3.0);
        vec3 skyRefl = mix(AmbientColor.rgb * 1.15, SunColor.rgb, 0.25);
        vec3 w = lit;
        if (normalId == 0) {
            // Pixel water: ripples as short dashes drifting with the current, rare sparkles,
            // and dithered foam where the water meets the shore.
            float row = texel.y;
            float lane = hash12(vec2(row, 3.1));
            float dash = step(0.86, fract((texel.x + floor(t * (1.5 + lane * 2.0))) / (5.0 + lane * 7.0)));
            dash *= step(0.55, lane);
            float spark = step(0.992, hash12(texel + floor(t * 2.0)));
            w += SunColor.rgb * (dash * 0.10 + spark * 0.45) * sunTerm * (1.0 - SunDir.w * 0.7) * detail;
            float open = vLight.b;
            if (detail > 0.01) open = snapToTexel(vLight, uv, uvSnap).b;
            float foam = 1.0 - smoothstep(0.15, 0.95, open);
            float wave = 0.5 + 0.5 * sin(t * 1.3 + (texel.x + texel.y) * 0.4);
            if (foam * (0.75 + 0.25 * wave) > bayer4(texel) && detail > 0.2) {
                w = mix(w, vec3(0.93, 0.97, 1.0) * (direct + ambient * 1.1), 0.85);
            }
        }
        lit = mix(w, skyRefl * 0.9, fresnel * 0.3);
    }

    if (caveLayer) {
        if (section) {
            // Solid rock at the cut: no blocks, no texture, just a deep flat shadow, so only the
            // hollows (the caves at this height) read as places.
            lit = vec3(0.035, 0.038, 0.050);
        } else if (unseen) {
            // Other caves: present but quiet, cold and dim.
            float l = dot(albedo, vec3(0.299, 0.587, 0.114));
            lit = mix(albedo, vec3(l), 0.65) * vec3(0.30, 0.33, 0.42) * ao;
        } else {
            // The space the player is in: lifted shadows, warm torches.
            lit = albedo * (vec3(0.34, 0.35, 0.42) * ao + EmissionColor.rgb * e * 1.9 + vec3(1.0, 0.78, 0.5) * e * 0.6);
        }
        float reveal = 1.0 - smoothstep(Cave.w * 0.8, Cave.w, length(vWorld.xz - Focus.xz));
        lit *= mix(0.45, 1.0, reveal);
    }

    if (built && !caveLayer) {
        lit *= 1.0 + 0.05 * (1.0 - abs(ndl));
    }

    vec3 color = gradeColor(lit);
    // A lifted block's floor: the block's own top, just a little quieter (it is under cover).
    if (capped) color *= 0.82;
    // The border of the explored world eases into the table's paper tone, in pixel steps.
    if (!inside && Flags.y > 0.5 && vEdge < 1.0) {
        float paper = (1.0 - smoothstep(0.0, 1.0, vEdge)) * 0.55;
        float l = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(color, mix(vec3(l), BoardColor.rgb, 0.65), paper);
    }
    color = mix(color, hazeColor(), haze(vWorld) * 0.6);

    float coverage = edgeCoverage(gl_FragCoord.xy, vWorld.xz);
    if (coverage <= 0.002) {
        discard;
    }
    fragColor = vec4(color * coverage, coverage);
}
