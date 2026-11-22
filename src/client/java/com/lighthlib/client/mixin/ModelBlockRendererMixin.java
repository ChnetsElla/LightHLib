package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Opens/closes the per-thread tesselation context around vanilla
 * ModelBlockRenderer.tesselateBlock, so the VertexConsumer tint hook can
 * resolve the LHCE color at the block being meshed. (On Fabric Renderer API
 * environments this path is redirected away - the Indigo hook covers it
 * instead; this mixin serves as the vanilla-environment fallback.)
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
	private static final String TESSELATE_DESCRIPTOR =
			"tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;"
					+ "FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;"
					+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
					+ "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V";

	@Inject(method = TESSELATE_DESCRIPTOR, at = @At("HEAD"))
	private void lighthlib$beginTintContext(BlockQuadOutput output, float x, float y, float z,
											BlockAndTintGetter level, BlockPos pos, BlockState state,
											BlockStateModel model, long seed, CallbackInfo ci) {
		RenderTintContext.begin(level, pos);
	}

	@Inject(method = TESSELATE_DESCRIPTOR, at = @At("RETURN"))
	private void lighthlib$endTintContext(BlockQuadOutput output, float x, float y, float z,
										   BlockAndTintGetter level, BlockPos pos, BlockState state,
										   BlockStateModel model, long seed, CallbackInfo ci) {
		RenderTintContext.end();
	}
}
