package com.lighthlib.client.compat.mixin;

import com.lighthlib.api.HLightMath;
import com.lighthlib.engine.LHCEngine;
import net.caffeinemc.mods.sodium.client.model.light.data.QuadLightData;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium (Scalium terrain renderer) compatibility.
 *
 * <p>Two things happen after the pipeline computes per-quad light:</p>
 * <ul>
 *   <li>LHCE's 16-bit field value is folded into the corner lightmap values as
 *       a sub-texel block-light byte (the over-bright extension), so brightness
 *       responds continuously to the 16-bit level.</li>
 *   <li>Per-position colored light: the corner vertex colors are multiplied by
 *       the LHCE provenance color at the quad's position - each lit area
 *       carries exactly its own source's RGB, vanilla light elsewhere is
 *       untouched, and nothing flickers or fades as the camera moves.</li>
 * </ul>
 *
 * <p>Targets Sodium by class name strings in a config with
 * {@code "required": false}: applied only when Sodium is present, silently
 * skipped otherwise.</p>
 */
@Mixin(targets = {
		"net.caffeinemc.mods.sodium.client.model.light.flat.FlatLightPipeline",
		"net.caffeinemc.mods.sodium.client.model.light.flat.FlatFluidLightPipeline",
		"net.caffeinemc.mods.sodium.client.model.light.smooth.SmoothLightPipeline"
}, remap = false)
public abstract class SodiumLightPipelineMixin {
	@Inject(method = "calculate", at = @At("TAIL"), remap = false)
	private void lighthlib$boostQuadLight(ModelQuadView quad, BlockPos pos, QuadLightData out,
										   Direction cullFace, Direction lightFace,
										   Direction shadeDirection, boolean enhanced,
										   CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return;
		}
		LHCEngine engine = LHCEngine.forLevel(minecraft.level);
		int h = engine.getHLight(pos);
		if (h <= 0) {
			return;
		}
		// 1) sub-texel brightness boost (per-source attenuation curves reshape
		//    the cell's byte; curve-less sources get the plain mapping)
		int hByte = engine.blockByteAt(pos.asLong(), h);
		int[] lm = out.lm;
		for (int i = 0; i < lm.length; i++) {
			int vanillaByte = lm[i] & 0xFF;
			if (hByte > vanillaByte) {
				lm[i] = (lm[i] & ~0xFF) | hByte;
			}
		}
		// 2) per-position vertex color multiply (100% of the source RGB,
		//    optionally blended with the runner-up contributor's color)
		if (quad instanceof MutableQuadViewImpl mutable) {
			int color = engine.renderColor(pos);
			if (color != HLightMath.WHITE_RGB) {
				int tint = 0xFF000000 | color;
				for (int i = 0; i < 4; i++) {
					mutable.setColor(i, ARGB.multiply(mutable.getColor(i), tint));
				}
			}
		}
	}
}
