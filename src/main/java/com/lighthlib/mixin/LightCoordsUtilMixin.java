package com.lighthlib.mixin;

import com.lighthlib.api.HLightMath;
import com.lighthlib.engine.LHCEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The rendering hook of the 16-bit channel on the vanilla render path:
 * whenever the game samples packed light coordinates for a position, LHCE's
 * 16-bit field value is converted into a <b>sub-texel</b> block-light byte
 * and folded into the packed value when it exceeds the vanilla brightness.
 *
 * <p>Packed light coordinates reserve bits 0-3 as padding next to the block
 * light nibble (bits 4-7). Writing the low nibble with the fractional part
 * of the lightmap texel position makes the bilinearly-filtered lightmap
 * sample <i>between</i> texels, so brightness responds continuously to the
 * 16-bit level instead of jumping across 16 discrete steps.</p>
 */
@Mixin(LightCoordsUtil.class)
public abstract class LightCoordsUtilMixin {
	@Inject(method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;"
			+ "Lnet/minecraft/world/level/BlockAndLightGetter;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/BlockPos;)I",
			at = @At("RETURN"), cancellable = true)
	private static void lighthlib$boostLightCoords(LightCoordsUtil.BrightnessGetter getter,
												  BlockAndLightGetter level,
												  BlockState state,
												  BlockPos pos,
												  CallbackInfoReturnable<Integer> cir) {
		// Chunk meshing passes a RenderSectionRegion (not a Level); fall back to
		// the current client level so terrain meshes still pick up the boost.
		Level realLevel = level instanceof Level direct ? direct : LHCEngine.clientLevel();
		if (realLevel == null) {
			return;
		}
		LHCEngine engine = LHCEngine.forLevel(realLevel);
		int h = engine.getHLight(pos);
		if (h <= 0) {
			return;
		}
		int packed = cir.getReturnValueI();
		int vanillaByte = packed & 0xFF;
		int hByte = HLightMath.smoothBlockByte(h);
		if (hByte > vanillaByte) {
			cir.setReturnValue((packed & ~0xFF) | hByte);
		}
	}

	/**
	 * {@code LightCoordsUtil.max} re-packs the per-channel nibble maxima and so
	 * drops the sub-texel low bits. Restore the brighter of the two block
	 * bytes so fluids ({@code FluidRenderer}), block-entity brightness
	 * combining and any other consumer keep the full precision.
	 */
	@Inject(method = "max(II)I", at = @At("RETURN"), cancellable = true)
	private static void lighthlib$maxSubTexel(int a, int b, CallbackInfoReturnable<Integer> cir) {
		if (!LHCEngine.hasActiveField()) {
			// no HLib source placed anywhere: vanilla max's nibble repack is
			// exact (bits 0-3 only carry vanilla smooth-light fractions the
			// repack is *supposed* to drop); registry bindings alone (e.g. the
			// built-in test block) must NOT re-enable the restore
			return;
		}
		int result = cir.getReturnValueI();
		int winnerByte = Math.max(a & 0xFF, b & 0xFF);
		if (winnerByte > (result & 0xFF)) {
			cir.setReturnValue((result & ~0xFF) | winnerByte);
		}
	}
}
