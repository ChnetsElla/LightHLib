package com.lighthlib.client.mixin;

import com.lighthlib.api.HLightMath;
import com.lighthlib.engine.LHCEngine;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities (mobs, players, dropped items, item frames, paintings, minecarts,
 * boats, first-person held items...) sample their block light through
 * {@code EntityRenderer.getPackedLightCoords}, which reads the raw vanilla
 * 4-bit {@code Level.getBrightness(LightLayer.BLOCK, pos)} - bypassing the
 * LightHLib channel entirely.
 *
 * <p>This hook boosts the returned packed coordinates with LHCE's 16-bit field
 * value at the entity's light probe position (the same sub-texel byte the
 * terrain path uses), so entities render with the full HLib brightness -
 * including the over-bright extension in the niris build.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I",
			at = @At("RETURN"), cancellable = true)
	private void lighthlib$boostEntityLight(Entity entity, float partialTick,
											CallbackInfoReturnable<Integer> cir) {
		Level level = entity.level();
		LHCEngine engine = LHCEngine.forLevel(level);
		BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTick));
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
}
