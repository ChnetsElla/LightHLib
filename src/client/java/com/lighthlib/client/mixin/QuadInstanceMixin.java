package com.lighthlib.client.mixin;

import com.lighthlib.engine.LHCEngine;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.util.LightCoordsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Terrain meshes and block-entity models pass every quad through
 * {@code QuadInstance.getLightCoordsWithEmission}, which repacks the light
 * coordinates via {@link LightCoordsUtil#lightCoordsWithEmission}. That repack
 * re-derives the block nibble ({@code packed >> 4 & 15}) and discards the low
 * four bits of the packed value - exactly where LightHLib stores its
 * sub-texel / over-bright extension - so without this hook the 16-bit
 * brightness would collapse back to vanilla 16-step light on terrain (while
 * particles, which don't use this path, render correctly).
 *
 * <p>This redirect re-applies the original low byte after the vanilla repack,
 * keeping the vanilla emission max (e.g. emissive materials stay full-bright)
 * but restoring LightHLib's sub-texel position whenever it exceeds it. Gated
 * on {@link LHCEngine#hasActiveField()}: with no HLib source placed anywhere
 * the vanilla repack is exact, so vanilla worlds render byte-exact vanilla
 * (registry bindings like the test block alone must not re-enable this).</p>
 */
@Mixin(QuadInstance.class)
public abstract class QuadInstanceMixin {
	@Redirect(method = "getLightCoordsWithEmission",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/util/LightCoordsUtil;lightCoordsWithEmission(II)I"))
	private static int lighthlib$restoreSubTexelByte(int packed, int emission) {
		int result = LightCoordsUtil.lightCoordsWithEmission(packed, emission);
		if (!LHCEngine.hasActiveField()) {
			// no HLib source placed: the repack's nibble re-derivation is exact
			// and the low bits only carry vanilla smooth-light data the repack
			// is supposed to drop - keep the byte-exact vanilla result
			return result;
		}
		int vanillaByte = result & 0xFF;
		int subTexelByte = packed & 0xFF;
		if (subTexelByte > vanillaByte) {
			result = (result & ~0xFF) | subTexelByte;
		}
		return result;
	}
}
