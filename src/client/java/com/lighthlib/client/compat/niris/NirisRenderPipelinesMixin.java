package com.lighthlib.client.compat.niris;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Niris build only: retargets the LIGHTMAP render pipeline to the RGBA16F
 * format, matching the HDR lightmap texture created by
 * {@link NirisLightmapMixin}. Without this, renderpearl rejects the render
 * pass ("color attachment 0 format doesn't match pipeline format") because
 * the pipeline still declares the vanilla RGBA8 target.
 *
 * <p>The slice scopes the modification to the single
 * {@code withColorTargetState} call of the {@code pipeline/lightmap} builder
 * chain inside the {@code RenderPipelines} static initializer.</p>
 */
@Mixin(RenderPipelines.class)
public abstract class NirisRenderPipelinesMixin {
	@ModifyArg(
			method = "<clinit>",
			slice = @Slice(
					from = @At(value = "CONSTANT", args = "stringValue=pipeline/lightmap")
			),
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withColorTargetState(Lcom/mojang/renderpearl/api/pipeline/ColorTargetState;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;",
					ordinal = 0),
			remap = false)
	private static ColorTargetState lighthlib$hdrLightmapTargetState(ColorTargetState state) {
		return new ColorTargetState(state.blendFunction(), GpuFormat.RGBA16_FLOAT, state.writeMask());
	}
}
