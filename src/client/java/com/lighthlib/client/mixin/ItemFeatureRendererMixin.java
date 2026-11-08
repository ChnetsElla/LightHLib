package com.lighthlib.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.util.ARGB;

/**
 * Colored-light hook for regular item models - dropped item clusters, held
 * items (first- and third-person), item frames, armor stands' items...
 *
 * <p>Item submissions are deferred: {@code SubmitNodeCollection.submitItem}
 * stores an {@code ItemFeatureRenderer.Submit} record carrying <i>no</i> color
 * argument (per-quad colors are resolved at flush time from tint layers), so
 * the LHCE tint cannot ride the submit call the way it does for models. This
 * hook resolves the tint at flush time instead: the record's pose is
 * camera-relative, so the item's world position is the camera position
 * (stashed by the entity dispatcher hook, same frame) plus the pose
 * translation. Every quad color set by {@code prepareMainSubmit} is then
 * multiplied by that tint; GUI items keep vanilla colors.</p>
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
	@Unique
	private int lighthlib$itemTint = -1;

	@Inject(method = "prepareMainSubmit", at = @At("HEAD"))
	private void lighthlib$beginItemTint(ItemFeatureRenderer.Submit node, CallbackInfo ci) {
		this.lighthlib$itemTint = RenderTintContext.itemNodeTint(node.displayContext(), node.pose());
	}

	@ModifyArg(method = "prepareMainSubmit",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/QuadInstance;setColor(I)V"),
			index = 0)
	private int lighthlib$tintItemQuad(int color) {
		// ARGB.multiply(a, -1) == a, so quads outside colored light are a no-op
		return ARGB.multiply(color, this.lighthlib$itemTint);
	}

	@Inject(method = "prepareMainSubmit", at = @At("RETURN"))
	private void lighthlib$endItemTint(ItemFeatureRenderer.Submit node, CallbackInfo ci) {
		this.lighthlib$itemTint = -1;
	}
}
