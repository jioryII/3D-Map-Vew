#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec4 Light;
layout(location = 3) in ivec2 Tile;

layout(location = 0) out vec3 vAlbedo;
layout(location = 1) out vec4 vLight;
layout(location = 2) flat out int vCode;
layout(location = 3) out vec3 vWorld;
layout(location = 4) flat out int vTile;
layout(location = 5) flat out int vMaterial;
layout(location = 6) out float vEdge;
layout(location = 7) flat out int vCap;

void main() {
    vec3 p = Position;
    int code = int(Color.a * 255.0 + 0.5);
    // Edge relief: within 8 blocks of unexplored land the terrain eases down onto the
    // table, so the border reads as a carved relief instead of cut-out walls.
    float edge = float((Tile.y >> 8) & 15) / 8.0;
    if (Flags.y > 0.5 && (code & 16) == 0) {
        float k = smoothstep(0.0, 1.0, edge);
        p.y = mix(Flags.z, p.y, k);
    }
    gl_Position = ViewProj * vec4(p, 1.0);
    vAlbedo = Color.rgb;
    vCode = code;
    vLight = Light;
    vWorld = p;
    vTile = Tile.x;
    vMaterial = Tile.y & 255;
    vCap = (Tile.y >> 12) & 1;
    vEdge = edge;
}
