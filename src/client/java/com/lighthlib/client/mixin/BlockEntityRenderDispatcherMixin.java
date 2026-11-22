package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Opens/closes the entity tint context around every block-entity submission
 * (skulls, chests, signs, beds, ...): block entities render through their own
 * dispatcher, neither the ModelBlockRenderer window nor the entity dispatcher
 * covers them. With the window open, the SubmitNodeCollection funnel tints
 * everything the renderer submits for this block entity - exactly once, at
 * the BE's world position.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	private static final String SUBMIT_DESCRIPTOR =
			"submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
					+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

	@Inject(method = SUBMIT_DESCRIPTOR, at = @At("HEAD"))
	private void lighthlib$beginBlockEntityTint(BlockEntityRenderState state, PoseStack poseStack,
												SubmitNodeCollector collector, CameraRenderState cameraState,
												CallbackInfo ci) {
		RenderTintContext.beginEntity(state.blockPos);
	}

	@Inject(method = SUBMIT_DESCRIPTOR, at = @At("RETURN"))
	private void lighthlib$endBlockEntityTint(BlockEntityRenderState state, PoseStack poseStack,
											  SubmitNodeCollector collector, CameraRenderState cameraState,
											  CallbackInfo ci) {
		RenderTintContext.endEntity();
	}
}
