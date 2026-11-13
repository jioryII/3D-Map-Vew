#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vWorld;

void main() {
    gl_Position = ViewProj * vec4(Position, 1.0);
    vColor = Color;
    vWorld = Position;
}
