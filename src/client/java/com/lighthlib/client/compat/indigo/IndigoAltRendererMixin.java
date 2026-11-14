package com.lighthlib.client.compat.indigo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.lighthlib.client.render.RenderTintContext;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Colored-light support for Indigo, Fabric Renderer API's default terrain
 * path: fabric-renderer-api-v1 redirects SectionCompiler's vanilla
 * tesselateBlock call to {@code AltModelBlockRendererImpl}, so the LHCE color
 * multiply hooks Indigo's per-quad pipeline instead.
 *
 * <p>{@code tesselateBlock} opens the per-thread render context (level + block
 * position); {@code transform} - which runs shadeQuad (light/AO), tintQuad
 * (biome tint) and returns whether the quad is emitted - applies the color
 * multiply at RETURN, after all other color contributions are final.</p>
 */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
public abstract class IndigoAltRendererMixin {
	@Inject(method = "tesselateBlock", at = @At("HEAD"), remap = false)
	private void lighthlib$beginTint(QuadEmitter emitter, float x, float y, float z,
									  BlockAndTintGetter level, BlockPos pos, BlockState state,
									  BlockStateModel model, long seed, CallbackInfo ci) {
		RenderTintContext.begin(level, pos);
	}

	@Inject(method = "tesselateBlock", at = @At("RETURN"), remap = false)
	private void lighthlib$endTint(QuadEmitter emitter, float x, float y, float z,
									BlockAndTintGetter level, BlockPos pos, BlockState state,
									BlockStateModel model, long seed, CallbackInfo ci) {
		RenderTintContext.end();
	}

	@Inject(method = "transform", at = @At("RETURN"), remap = false)
	private void lighthlib$tintQuad(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			RenderTintContext.tintFapiQuad(quad);
		}
	}
}
