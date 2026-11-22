package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;

/**
 * The first-person hand renders in its own pass outside the entity
 * dispatcher, so no entity submit context exists there. Opening the LHCE
 * entity tint context around the hand/arm rendering (at the local player's
 * position) lets the SubmitNodeCollection hooks tint the held item and the
 * arm just like world entities.
 */
@Mixin(targets = "net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer")
public abstract class FirstPersonHandsRendererMixin {
	private static final String HAND_DESCRIPTOR =
			"renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
					+ "ILnet/minecraft/world/entity/HumanoidArm;"
					+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V";
	private static final String ARM_DESCRIPTOR =
			"renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
					+ "IFFLnet/minecraft/world/entity/HumanoidArm;"
					+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V";

	@Inject(method = {HAND_DESCRIPTOR, ARM_DESCRIPTOR}, at = @At("HEAD"))
	private void lighthlib$beginHandTint(CallbackInfo ci) {
		if (Minecraft.getInstance().player != null) {
			RenderTintContext.beginEntity(Minecraft.getInstance().player.blockPosition());
		}
	}

	@Inject(method = {HAND_DESCRIPTOR, ARM_DESCRIPTOR}, at = @At("RETURN"))
	private void lighthlib$endHandTint(CallbackInfo ci) {
		RenderTintContext.endEntity();
	}
}
