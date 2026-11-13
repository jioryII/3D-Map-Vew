package dev.maqueta.client.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Render pipelines for the maqueta, built on Minecraft's renderpearl API (no direct OpenGL:
 * the same pipelines run on the OpenGL and Vulkan backends). Shaders live in
 * {@code assets/maqueta/shaders} and are compiled by the game to SPIR-V.
 */
public final class MaquetaPipelines {
	public static final String FRAME_UNIFORM = "MaquetaFrame";

	public static final BindGroupLayout FRAME = BindGroupLayout.builder()
		.withUniform(FRAME_UNIFORM, UniformType.UNIFORM_BUFFER)
		.build();

	/** 20 bytes: position, albedo + code, baked light terms. Matches core MeshBuilder. */
	public static final VertexFormat TERRAIN_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.addAttribute("Light", GpuFormat.RGBA8_UNORM)
		.build();

	/** 16 bytes: position + premultiplied-ready colour for markers, trail, figures. */
	public static final VertexFormat OVERLAY_FORMAT = VertexFormat.builder(0)
		.addAttribute("Position", GpuFormat.RGB32_FLOAT)
		.addAttribute("Color", GpuFormat.RGBA8_UNORM)
		.build();

	private static final ColorTargetState OPAQUE = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
	private static final ColorTargetState PREMULTIPLIED = new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA);

	public static final RenderPipeline BOARD = RenderPipeline.builder()
		.withLocation(id("pipeline/board"))
		.withVertexShader(id("core/board"))
		.withFragmentShader(id("core/board"))
		.withBindGroupLayout(FRAME)
		.withColorTargetState(OPAQUE)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withCull(false)
		.build();

	public static final RenderPipeline TERRAIN = RenderPipeline.builder()
		.withLocation(id("pipeline/terrain"))
		.withVertexShader(id("core/terrain"))
		.withFragmentShader(id("core/terrain"))
		.withBindGroupLayout(FRAME)
		.withVertexBinding(0, TERRAIN_FORMAT)
		.withColorTargetState(OPAQUE)
		.withDepthStencilState(DepthStencilState.DEFAULT)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.build();

	/** Markers that sit on the terrain and must be hidden by it (trail, figures, rings). */
	public static final RenderPipeline OVERLAY_DEPTH = RenderPipeline.builder()
		.withLocation(id("pipeline/overlay_depth"))
		.withVertexShader(id("core/overlay"))
		.withFragmentShader(id("core/overlay"))
		.withBindGroupLayout(FRAME)
		.withVertexBinding(0, OVERLAY_FORMAT)
		.withColorTargetState(PREMULTIPLIED)
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.build();

	/** Markers that must always read (player arrow, waypoint beacons): drawn over everything. */
	public static final RenderPipeline OVERLAY_XRAY = RenderPipeline.builder()
		.withLocation(id("pipeline/overlay_xray"))
		.withVertexShader(id("core/overlay"))
		.withFragmentShader(id("core/overlay"))
		.withBindGroupLayout(FRAME)
		.withVertexBinding(0, OVERLAY_FORMAT)
		.withColorTargetState(PREMULTIPLIED)
		.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.withCull(false)
		.build();

	private MaquetaPipelines() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("maqueta", path);
	}
}
