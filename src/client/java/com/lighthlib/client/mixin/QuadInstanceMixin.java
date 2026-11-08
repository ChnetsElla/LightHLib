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
 * but restoring LightHLib's sub-texel position whenever it exceeds it.</p>
 */
@Mixin(QuadInstance.class)
public abstract class QuadInstanceMixin {
	@Redirect(method = "getLightCoordsWithEmission",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/util/LightCoordsUtil;lightCoordsWithEmission(II)I"))
	private static int lighthlib$restoreSubTexelByte(int packed, int emission) {
		if (!LHCEngine.hasActiveField()) {
			// 26.3 vanilla also uses bits 0-7 as a smooth block-light byte
			// (smoothPack); re-ORing them past the emission repack would
			// brighten vanilla emissive quads in worlds with no HLib source
			// placed (registry bindings alone must NOT enable this)
			return LightCoordsUtil.lightCoordsWithEmission(packed, emission);
		}
		int result = LightCoordsUtil.lightCoordsWithEmission(packed, emission);
		int vanillaByte = result & 0xFF;
		int subTexelByte = packed & 0xFF;
		if (subTexelByte > vanillaByte) {
			result = (result & ~0xFF) | subTexelByte;
		}
		return result;
	}
}
