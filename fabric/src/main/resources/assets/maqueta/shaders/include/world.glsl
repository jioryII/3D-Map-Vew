#ifndef MAQUETA_WORLD_GLSL
#define MAQUETA_WORLD_GLSL

#include <maqueta:frame.glsl>

uniform sampler2D TileAtlas;
uniform sampler2D HeightMap;
uniform sampler2D EdgeMask;

// Smooth per-chunk field (bilinear): r = explored, g = storm, b = snow, a = sand.
vec4 edgeMask(vec2 worldXZ) {
    // Fixed window of 8192 blocks centred on the anchor; outside it nothing is explored.
    vec2 uv = (worldXZ - Anchor.xy + vec2(4096.0)) / 8192.0;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return vec4(0.0);
    return texture(EdgeMask, uv);
}

const int HEIGHT_SIZE = 2048;
const float NO_HEIGHT = -9000.0;

// Surface height of an absolute world column (wrapping heightmap).
float heightAt(vec2 worldXZ) {
    ivec2 c = ivec2(floor(worldXZ));
    ivec2 t = c & ivec2(HEIGHT_SIZE - 1);
    return texelFetch(HeightMap, t, 0).r;
}

// Hard sun visibility from an absolute world point: 1 lit, 0 in shadow. Marches the
// heightmap towards the fixed sun in half-block steps (pixel-precise maqueta shadows).
float sunRay(vec3 p) {
    vec2 dirXZ = normalize(SunDir.xz);
    float rise = SunDir.y / max(length(SunDir.xz), 1e-3);
    // Adaptive steps (fine near the surface, longer further out) and an early exit as soon as
    // the ray is above the highest terrain in view: most lit pixels finish in a few steps.
    float maxY = Atmos.w + 1.0;
    float t = 0.5;
    for (int i = 0; i < 64; i++) {
        float rayY = p.y + rise * t;
        if (rayY > maxY) break;
        if (heightAt(p.xz + dirXZ * t) > rayY) return 0.0;
        t += t < 6.0 ? 0.5 : (t < 20.0 ? 0.75 : 1.25);
    }
    return 1.0;
}

// How much explored terrain surrounds a table point (contact shadow for the base).
float tableOcclusion(vec2 xz, float tableY) {
    float occ = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec2 d = vec2(cos(a), sin(a));
        float h1 = heightAt(xz + d * 1.5);
        float h2 = heightAt(xz + d * 4.0);
        occ += (h1 > tableY ? 0.7 : 0.0) + (h2 > tableY ? 0.3 : 0.0);
    }
    return clamp(occ / 8.0, 0.0, 1.0);
}


// ---- Minecraft-style clouds over the unexplored outside --------------------------------
// The game's own look: a flat layer of 12x12-block cells, 4 blocks thick, drifting slowly;
// white tops, sides shaded like blocks. Never over explored land. Traced with a short grid
// walk through the thin slab: a handful of steps per pixel, no ray marching.

const float CLOUD_CELL = 12.0;
const float CLOUD_THICK = 6.0;

vec2 cloudDrift() {
    return vec2(CameraEye.w * 0.9, 0.0);                      // the game's clouds drift along x
}

// Is the cloud cell `cell` (grid coordinates, drift removed) a cloud?
bool cloudCell(vec2 cell) {
    vec2 w = (cell + 0.5) * CLOUD_CELL;
    if (edgeMask(w + cloudDrift()).r > 0.02) return false;    // clear sky over the maqueta
    float c = fbm(w * 0.0030) * 0.70 + fbm(w * 0.0110 + vec2(5.2, 1.3)) * 0.40;
    return c > (Flags.w >= 1.99 ? 0.70 : 0.60);           // only a few clouds in the Nether
}

// Trace the cloud slab. Returns rgb = face shade (top 1.0, x sides 0.9, z sides 0.8), a = hit.
// `hitP` receives the hit point (anchor-relative).
vec4 traceClouds(vec3 nearP, vec3 dir, out vec3 hitP) {
    hitP = vec3(0.0);
    float y0 = Atmos.z, y1 = Atmos.z + CLOUD_THICK;
    if (Atmos.y <= 0.001 || nearP.y <= y1 || dir.y > -1e-4) return vec4(0.0);
    float tTop = (y1 - nearP.y) / dir.y, tBot = (y0 - nearP.y) / dir.y;
    vec2 g = (nearP.xz + dir.xz * tTop + Anchor.xy - cloudDrift()) / CLOUD_CELL;
    vec2 cell = floor(g);
    if (cloudCell(cell)) {
        hitP = nearP + dir * tTop;
        return vec4(1.0, 1.0, 1.0, 1.0);
    }
    vec2 d = dir.xz / CLOUD_CELL;
    vec2 stepS = vec2(d.x >= 0.0 ? 1.0 : -1.0, d.y >= 0.0 ? 1.0 : -1.0);
    vec2 inv = vec2(abs(d.x) > 1e-7 ? 1.0 / d.x : 1e9, abs(d.y) > 1e-7 ? 1.0 / d.y : 1e9);
    vec2 tMax = tTop + (cell + max(stepS, 0.0) - g) * inv;
    vec2 tDelta = abs(inv);
    for (int i = 0; i < 12; i++) {
        float t;
        float shade;
        if (tMax.x < tMax.y) {
            t = tMax.x; tMax.x += tDelta.x; cell.x += stepS.x; shade = 0.90;
        } else {
            t = tMax.y; tMax.y += tDelta.y; cell.y += stepS.y; shade = 0.80;
        }
        if (t > tBot) break;
        if (cloudCell(cell)) {
            hitP = nearP + dir * t;
            return vec4(vec3(shade), 1.0);
        }
    }
    return vec4(0.0);
}

// Shadow of the clouds on a point of the outside, cast along the fixed sun.
float cloudShadowAt(vec3 worldAbs) {
    if (Atmos.y <= 0.001) return 0.0;
    float rise = max(SunDir.y, 0.2);
    vec2 at = worldAbs.xz + SunDir.xz * ((Atmos.z - worldAbs.y) / rise);
    return cloudCell(floor((at - cloudDrift()) / CLOUD_CELL)) ? Atmos.y : 0.0;
}

#endif
