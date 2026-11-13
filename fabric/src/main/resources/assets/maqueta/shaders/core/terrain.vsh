#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec4 Light;

layout(location = 0) out vec3 vAlbedo;
layout(location = 1) out vec4 vLight;
layout(location = 2) flat out int vCode;
layout(location = 3) out vec3 vWorld;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
    vAlbedo = Color.rgb;
    vCode = int(Color.a * 255.0 + 0.5);
    vLight = Light;
    vWorld = Position;
}
