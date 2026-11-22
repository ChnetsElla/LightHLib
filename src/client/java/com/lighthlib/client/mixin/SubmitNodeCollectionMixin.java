package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.lighthlib.client.render.RenderTintContext;
import net.minecraft.util.ARGB;

/**
 * THE colored-light funnel for the render-state submit pipeline.
 *
 * <p>SubmitNodeCollection is the innermost collector - every submission ends
 * up here exactly once, either directly (entity layers like the sheep's wool
 * resolve {@code collector.order(...)} first and call submitModel on the
 * returned collection, bypassing the storage wrapper) or through
 * SubmitNodeStorage, which is a pure router that forwards every argument
 * verbatim. Hooking the collection alone therefore tints everything - entity
 * models, render layers, block-model items (carried blocks) - exactly once,
 * with no double-multiply.</p>
 *
 * <p>The color arguments sit at local index 7 in both methods
 * (light, overlay, COLOR, ...).</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.SubmitNodeCollection")
public abstract class SubmitNodeCollectionMixin {
	private static final String SUBMIT_MODEL =
			"submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
					+ "Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/rendertype/RenderType;"
					+ "IIILnet/minecraft/client/renderer/texture/UvMapping;I)V";
	private static final String SUBMIT_BLOCK_MODEL =
			"submitBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;"
					+ "Lnet/minecraft/client/renderer/rendertype/RenderType;"
					+ "Ljava/util/List;[IIII)V";

	@ModifyVariable(method = SUBMIT_MODEL, at = @At("HEAD"), index = 7)
	private int lighthlib$tintModelColor(int color) {
		// ARGB.multiply(a, -1) == a, so anything outside colored light is a no-op
		return ARGB.multiply(color, RenderTintContext.entityTint());
	}

	@ModifyVariable(method = SUBMIT_BLOCK_MODEL, at = @At("HEAD"), index = 7)
	private int lighthlib$tintBlockModelColor(int color) {
		return ARGB.multiply(color, RenderTintContext.entityTint());
	}
}
