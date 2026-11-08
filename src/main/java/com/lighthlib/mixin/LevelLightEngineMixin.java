package com.lighthlib.mixin;

import com.lighthlib.api.HLightMath;
import com.lighthlib.engine.LHCEngine;
import com.lighthlib.impl.LightHLibImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends vanilla's raw brightness (used for gameplay light queries such as
 * mob spawning) with the 16-bit channel: LHCE light above the vanilla value
 * raises the effective brightness, so 16-bit-lit areas behave as lit areas.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
	@Inject(method = "getRawBrightness", at = @At("RETURN"), cancellable = true)
	private void lighthlib$boostRawBrightness(BlockPos pos, int amount,
											  CallbackInfoReturnable<Integer> cir) {
		LHCEngine engine = LightHLibImpl.engineForLightEngine((LevelLightEngine) (Object) this);
		if (engine == null) {
			return;
		}
		int h = engine.getHLight(pos);
		if (h <= 0) {
			return;
		}
		int current = cir.getReturnValueI();
		int boosted = HLightMath.vanillaNibble(h) - amount;
		if (boosted > current) {
			cir.setReturnValue(boosted);
		}
	}
}
