#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec2 vNdc;

// Full-screen triangle without a vertex buffer.
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec2 ndc = uv * 2.0 - 1.0;
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    gl_Position = vec4(ndc, 0.0, 1.0);
#else
    gl_Position = vec4(ndc, -1.0, 1.0);
#endif
    vNdc = ndc;
}
