package com.lighthlib.client.compat.niris;

import com.lighthlib.engine.LHCEConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Niris build only: replaces the 16x16 RGBA8 lightmap texture with a
 * 512x16 RGBA16F one.
 *
 * <ul>
 *   <li>{@code RGBA16_FLOAT} lets the lightmap store brightness above 1.0
 *       (values up to ~7.4x vanilla full brightness).</li>
 *   <li>The widened block axis (512 texels) gives the vanilla +8/256 texel
 *       offset room to breathe: light bytes 241..255 address texels up to
 *       263, which hold the over-bright part of the curve patched into
 *       {@code lightmap.fsh}.</li>
 *   <li>The {@code takeOverVanillaLighting} runtime switch is implemented as
 *       a second lightmap pipeline compiled with the {@code HLIB_TAKEOVER}
 *       shader define (the vanilla-exact lightmap.fsh branches on it); this
 *       redirect swaps the compiled pipeline per frame, so toggling the
 *       switch in the ModMenu screen takes effect from the next lightmap
 *       update without a shader recompile.</li>
 * </ul>
 *
 * <p>Only referenced by {@code lighthlib.niris.mixins.json}, which is shipped
 * exclusively in the niris build flavor.</p>
 */
@Mixin(Lightmap.class)
public abstract class NirisLightmapMixin {
	@Unique
	private RenderPipeline lighthlib$vanillaPipeline;
	@Unique
	private RenderPipeline lighthlib$takeoverPipeline;

	@ModifyArg(method = "<init>",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),
			index = 2)
	private GpuFormat lighthlib$hdrFormat(GpuFormat format) {
		return GpuFormat.RGBA16_FLOAT;
	}

	@ModifyArg(method = "<init>",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),
			index = 3)
	private int lighthlib$wideLightmap(int width) {
		return 512;
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void lighthlib$buildPipelines(CallbackInfo ci) {
		// The lightmap texture is RGBA16_FLOAT here (see lighthlib$hdrFormat),
		// so both pipeline variants must declare an RGBA16_FLOAT color target
		// to pass FrontendRenderPass.setPipeline's attachment-format check.
		// Same GLOBALS snippet, shaders, layouts and topology as the vanilla
		// LIGHTMAP builder sequence in RenderPipelines.<clinit>; the takeover
		// variant adds the HLIB_TAKEOVER shader define the lightmap.fsh
		// branches on.
		ColorTargetState hdrTarget = new ColorTargetState(Optional.empty(),
				GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
		this.lighthlib$vanillaPipeline = lighthlib$buildPipeline("pipeline/lightmap_hlib_vanilla", hdrTarget, false);
		this.lighthlib$takeoverPipeline = lighthlib$buildPipeline("pipeline/lightmap_takeover", hdrTarget, true);
	}

	@Unique
	private static RenderPipeline lighthlib$buildPipeline(String location, ColorTargetState colorTarget,
														   boolean takeover) {
		RenderPipeline.Builder builder = RenderPipeline.builder(
						RenderPipeline.builder().withBindGroupLayout(BindGroupLayouts.GLOBALS).buildSnippet())
				.withLocation(location)
				.withVertexShader("core/screenquad")
				.withFragmentShader("core/lightmap")
				.withBindGroupLayout(BindGroupLayouts.LIGHTMAP_INFO)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withColorTargetState(colorTarget);
		if (takeover) {
			builder = builder.withShaderDefine("HLIB_TAKEOVER");
		}
		return builder.build();
	}

	/** Picks the takeover-define lightmap pipeline when the switch is on, the
	 *  vanilla-exact one otherwise. */
	@Redirect(method = "render",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
	private CompiledRenderPipeline lighthlib$selectPipeline(RenderPipeline vanilla) {
		if (this.lighthlib$takeoverPipeline != null && this.lighthlib$vanillaPipeline != null) {
			return RenderSystem.getCompiledPipeline(LHCEConfig.get().takeOverVanillaLighting
					? this.lighthlib$takeoverPipeline
					: this.lighthlib$vanillaPipeline);
		}
		return RenderSystem.getCompiledPipeline(vanilla);
	}
}
