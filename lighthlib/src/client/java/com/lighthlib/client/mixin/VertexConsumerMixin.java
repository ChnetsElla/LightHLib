package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * The final colored-light multiply on the verified vertex write: every block
 * quad - whatever prepared its colors upstream (flat light, ambient
 * occlusion, biome tint) - reaches vertex data through putBlockBakedQuad,
 * so the LHCE color multiply is applied here, once, at the last possible
 * point before the corner colors are read out.
 */
@Mixin(VertexConsumer.class)
public interface VertexConsumerMixin {
	@Inject(method = "putBlockBakedQuad(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;"
			+ "Lcom/mojang/blaze3d/vertex/QuadInstance;)V",
			at = @At("HEAD"))
	private void lighthlib$tintBlockQuad(float x, float y, float z, BakedQuad quad,
										 QuadInstance instance, CallbackInfo ci) {
		RenderTintContext.tint(instance, quad);
	}
}
