package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;

/**
 * Opens/closes the entity tint context around each entity submission: the
 * submit coordinates are camera-relative, so the world position is the
 * camera position plus them. The SubmitNodeCollection tint hook uses the
 * context to tint everything submitted for that entity.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.EntityRenderDispatcher")
public abstract class EntityRenderDispatcherMixin {
	private static final String SUBMIT_DESCRIPTOR =
			"submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
					+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
					+ "DDDLcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;)V";

	@Inject(method = SUBMIT_DESCRIPTOR, at = @At("HEAD"))
	private void lighthlib$beginEntityTint(EntityRenderState state, CameraRenderState cameraState,
											double x, double y, double z, PoseStack poseStack,
											SubmitNodeCollector collector, CallbackInfo ci) {
		if (cameraState.pos != null) {
			RenderTintContext.updateCameraPos(cameraState.pos);
			RenderTintContext.beginEntity(BlockPos.containing(
					cameraState.pos.x + x, cameraState.pos.y + y, cameraState.pos.z + z));
		}
	}

	@Inject(method = SUBMIT_DESCRIPTOR, at = @At("RETURN"))
	private void lighthlib$endEntityTint(EntityRenderState state, CameraRenderState cameraState,
										   double x, double y, double z, PoseStack poseStack,
										   SubmitNodeCollector collector, CallbackInfo ci) {
		RenderTintContext.endEntity();
	}
}
