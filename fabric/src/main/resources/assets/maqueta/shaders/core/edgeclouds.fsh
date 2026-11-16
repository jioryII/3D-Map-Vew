#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:world.glsl>

layout(location = 0) in vec2 vNdc;
layout(location = 0) out vec4 fragColor;

// Cloud banks along the border of what has been explored: puffy, volumetric, and dressed
// by the biome underneath: white over plains and forests, dark and lit by lightning over
// mountains and oceans, snowing over frozen lands, a sandstorm over deserts. The biome mix
// comes from a bilinear per-chunk mask, so transitions are soft by construction.

vec3 unproject(vec2 ndc, float depth) {
    vec4 p = InvViewProj * vec4(ndc, depth, 1.0);
    return p.xyz / p.w;
}

// Where the bank lives: a band centred just outside the explored edge.
// The mask is blurred on the CPU (5x5 binomial over chunks), so one bilinear tap gives a
// wide, soft explored field with round corners.
vec4 wideMask(vec2 w) {
    return edgeMask(w);
}

float bandOf(float m) {
    // Only where explored and unexplored meet: zero over open sea (m = 0) and inland (m = 1).
    return smoothstep(0.0, 0.10, m) * (1.0 - smoothstep(0.40, 0.75, m));
}

float band(vec2 worldXZ) {
    return bandOf(wideMask(worldXZ).r);
}

float puff(vec2 p, float t) {
    return valueNoise(p * 0.03 + vec2(t * 0.02, t * 0.008)) * 0.6 + valueNoise(p * 0.08 - vec2(t * 0.015, -t * 0.02)) * 0.4;
}
// Tallest explored ground around a border point: the bank rises above it, so cliffs and
// mountains at the edge of the explored world never poke out of the fog.
float localTop(vec2 w) {
    float h = max(max(heightAt(w + vec2(18.0, 0.0)), heightAt(w - vec2(18.0, 0.0))),
                  max(heightAt(w + vec2(0.0, 18.0)), heightAt(w - vec2(0.0, 18.0))));
    h = max(h, heightAt(w));
    return max(BoardColor.a + 42.0, h + 16.0);
}

void main() {
    int dim = int(floor(Flags.w + 0.01));
    if (fract(Flags.w) < 0.25 || Cave.y > 0.5) discard;   // cloud banks off
    vec3 nearP = unproject(vNdc, 1.0);
    vec3 farP = unproject(vNdc, Flags.x > 0.5 ? 0.0 : -1.0);
    vec3 dir = normalize(farP - nearP);
    float y0 = BoardColor.a - 3.0, y1 = max(BoardColor.a + 42.0, Atmos.w + 18.0);
    if (abs(dir.y) < 1e-4) discard;
    float tA = (y1 - nearP.y) / dir.y, tB = (y0 - nearP.y) / dir.y;
    float t0 = max(min(tA, tB), 0.0), t1 = max(tA, tB);
    if (t1 <= t0) discard;

    // Cheap rejection: most pixels never cross the border band. Eight taps, so grazing
    // views (long rays) cannot slip between them and leave the edge naked.
    float any = 0.0;
    for (int k = 0; k <= 7; k++) any += band((nearP + dir * mix(t0, t1, float(k) / 7.0)).xz + Anchor.xy);
    if (any < 0.02) discard;

    const int STEPS = 16;
    float dt = (t1 - t0) / float(STEPS);
    float t = t0 + dt * bayer4(gl_FragCoord.xy);
    float time = CameraEye.w;
    vec4 acc = vec4(0.0);
    float hitT = -1.0;
    float snowW = 0.0, dustW = 0.0;
    float bankTop = -1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = nearP + dir * t;
        vec2 w = p.xz + Anchor.xy;
        vec4 m = wideMask(w);
        float b = bandOf(m.r);
        if (b > 0.02) {
            if (bankTop < 0.0) bankTop = localTop(w);   // once per pixel: the bank height near this border
            float top = bankTop;
            float h = (p.y - y0) / (top - y0);
            float n = puff(w, time);
            float sand = m.a / max(m.r, 0.05);
            float storm = m.g / max(m.r, 0.05);
            float snow = m.b / max(m.r, 0.05);
            float crown = mix(0.62 + 0.38 * n * n, 0.40 + 0.25 * n, dim == 1 ? clamp(sand, 0.0, 1.0) : 0.0); // uneven tops; sandstorms stay lower
            float dens = smoothstep(crown, crown - 0.30, h) * smoothstep(-0.05, 0.06, h) * b;
            dens *= smoothstep(0.20, 0.50, n + b * 0.55);
            if (dens > 0.01) {
                float ws = clamp(storm, 0.0, 1.0), wn = clamp(snow, 0.0, 1.0), wd = clamp(sand, 0.0, 1.0);
                vec3 col;
                if (dim == 2) {
                    // Nether: red banks; soul sand valleys bluish, basalt deltas grey, warped forests teal-green.
                    col = vec3(0.70, 0.22, 0.16);
                    col = mix(col, vec3(0.36, 0.58, 0.74), ws);
                    col = mix(col, vec3(0.46, 0.45, 0.47), wn);
                    col = mix(col, vec3(0.16, 0.58, 0.52), wd);
                    ws = 0.0; wn = 0.0; wd = 0.0;   // no lightning, snow or sand down here
                } else if (dim == 3) {
                    col = vec3(0.52, 0.34, 0.72);  // the End: a purple veil, nothing else
                    ws = 0.0; wn = 0.0; wd = 0.0;
                } else {
                    col = vec3(1.0);
                    col = mix(col, vec3(0.40, 0.44, 0.53), ws * 0.85);
                    col = mix(col, vec3(0.90, 0.94, 1.0), wn);
                    col = mix(col, vec3(0.86, 0.66, 0.42), wd);
                }
                float shade = mix(0.62, 1.05, clamp(h / max(crown, 0.05), 0.0, 1.0)); // lit crowns, cooler bellies
                vec3 lit = col * (SunColor.rgb * 0.65 * shade + AmbientColor.rgb * 0.60);
                // Lightning inside storm banks.
                if (ws > 0.25) {
                    float cellId = hash12(floor(w / 24.0) + floor(time * 5.0));
                    float flash = step(0.992, cellId) * ws;
                    lit += vec3(0.85, 0.9, 1.0) * flash * 1.6;
                }
                float a = clamp(dens * dt * 0.16, 0.0, 1.0); // soft fog bank rather than solid plates
                acc.rgb += (1.0 - acc.a) * lit * a;
                snowW += (1.0 - acc.a) * a * wn;
                dustW += (1.0 - acc.a) * a * wd;
                acc.a += (1.0 - acc.a) * a;
                if (hitT < 0.0 && acc.a > 0.3) hitT = t;
                if (acc.a > 0.96) break;
            }
        }
        t += dt;
    }
    if (acc.a < 0.01) discard;
    vec3 hp = nearP + dir * (hitT < 0.0 ? t0 : hitT);
    vec3 col = gradeColor(acc.rgb / acc.a);

    // Snowflakes and sand grains: a few dots stuck to the cloud itself in world space (a 3D
    // grid of half-block cells around the visible surface), drifting with time, so they move
    // with the bank and never slide over the screen.
    vec3 wp = hp + vec3(Anchor.x, 0.0, Anchor.y);
    if (snowW > 0.05) {
        vec3 g = floor(vec3(wp.x, wp.y + time * 1.6, wp.z) * 1.5);
        float flake = step(0.965, hash12(g.xz + g.y * 17.17));
        col = mix(col, vec3(1.0), flake * clamp(snowW / acc.a, 0.0, 1.0) * 0.85);
    }
    if (dustW > 0.05) {
        vec3 g = floor(vec3(wp.x + time * 3.0, wp.y, wp.z) * 1.5);
        float grain = step(0.96, hash12(g.xz + g.y * 23.71 + 37.0));
        col = mix(col, col * vec3(0.70, 0.56, 0.40), grain * clamp(dustW / acc.a, 0.0, 1.0));
    }

    col = mix(col, hazeColor(), haze(hp) * 0.5);
    vec4 clip = ViewProj * vec4(hp, 1.0);
    float ndcZ = clip.z / clip.w;
    gl_FragDepth = Flags.x > 0.5 ? ndcZ : ndcZ * 0.5 + 0.5;
    fragColor = vec4(col * acc.a, acc.a);
}
