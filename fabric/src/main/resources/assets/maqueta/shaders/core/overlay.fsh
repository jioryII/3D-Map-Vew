#version 330
#extension GL_ARB_separate_shader_objects : require

#include <maqueta:frame.glsl>

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec3 vWorld;

layout(location = 0) out vec4 fragColor;

void main() {
    float coverage = edgeCoverage(gl_FragCoord.xy, vWorld.xz);
    float a = vColor.a * coverage;
    if (a <= 0.003) {
        discard;
    }
    // Premultiplied output: the map texture is composited with premultiplied alpha.
    fragColor = vec4(vColor.rgb * a, a);
}
