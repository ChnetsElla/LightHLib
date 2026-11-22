package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Colored light on fluid surfaces: fluids tesselate through their own
 * renderer - neither the ModelBlockRenderer window nor the SubmitNode funnel
 * covers them. This mixin
 *
 * <ul>
 *   <li>opens the per-thread tesselation context around
 *       {@code FluidRenderer.tesselate} (so the tint below resolves the LHCE
 *       color at the fluid cell), and</li>
 *   <li>multiplies each fluid vertex's tint argument (the fluid's
 *       {@code BlockTintSource} color) by the LHCE ambient-merge tint.</li>
 * </ul>
 *
 * <p>Brightness needs no hook here: fluid light samples go through the
 * two-argument {@code LightCoordsUtil.getLightCoords} overload, which the
 * {@code LightCoordsUtilMixin} lifts.</p>
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
	private static final String TESSELATE_DESCRIPTOR =
			"tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
					+ "Lnet/minecraft/core/BlockPos;"
					+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;"
					+ "Lnet/minecraft/world/level/block/state/BlockState;"
					+ "Lnet/minecraft/world/level/material/FluidState;)V";
	private static final String VERTEX_DESCRIPTOR =
			"vertex(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFIFFI)V";

	@Inject(method = TESSELATE_DESCRIPTOR, at = @At("HEAD"))
	private void lighthlib$beginFluidTint(BlockAndTintGetter level, BlockPos pos,
										   FluidRenderer.Output output, BlockState state,
										   FluidState fluidState, CallbackInfo ci) {
		RenderTintContext.begin(level, pos);
	}

	@Inject(method = TESSELATE_DESCRIPTOR, at = @At("RETURN"))
	private void lighthlib$endFluidTint(BlockAndTintGetter level, BlockPos pos,
										 FluidRenderer.Output output, BlockState state,
										 FluidState fluidState, CallbackInfo ci) {
		RenderTintContext.end();
	}

	/** Slot 8 is the vertex's tint argument (the fluid's tint-source color):
	 *  multiply it by the LHCE color resolved at the tesselated cell. */
	@ModifyVariable(method = VERTEX_DESCRIPTOR, at = @At("HEAD"), index = 8)
	private int lighthlib$tintFluidVertex(int color) {
		return RenderTintContext.fluidTint(color);
	}
}
