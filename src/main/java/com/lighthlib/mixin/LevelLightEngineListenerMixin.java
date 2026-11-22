package com.lighthlib.mixin;

import com.lighthlib.engine.LHCEngine;
import com.lighthlib.impl.LightHLibImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Convergence point of the 26.3 monster-spawn light check:
 * {@code Monster.isDarkEnoughToSpawn} reads the raw BLOCK layer through
 * {@code getBrightness(LightLayer.BLOCK, pos)} →
 * {@code LevelLightEngine.getLayerListener(BLOCK).getLightValue(pos)} - a path
 * that bypasses {@code getRawBrightness} entirely, so the raw-brightness boost
 * mixin alone never gated spawning. Wrapping the returned BLOCK listener lifts
 * its read-outs with the HLib 16-bit channel (max of vanilla nibble and the
 * effective spawn nibble - per-source attenuation curves reshape it), exactly
 * mirroring the raw-brightness boost.
 */
@Mixin(net.minecraft.world.level.lighting.LevelLightEngine.class)
public abstract class LevelLightEngineListenerMixin {

	@Inject(method = "getLayerListener", at = @At("RETURN"), cancellable = true)
	private void lighthlib$wrapBlockListener(net.minecraft.world.level.LightLayer layer,
			CallbackInfoReturnable<LayerLightEventListener> cir) {
		if (layer != LightLayer.BLOCK
				|| !com.lighthlib.api.HLightRegistry.hasAnyBindings()) {
			return;
		}
		LayerLightEventListener vanilla = cir.getReturnValue();
		LHCEngine engine = LightHLibImpl.engineForLightEngine(
				(net.minecraft.world.level.lighting.LevelLightEngine) (Object) this);
		if (engine == null) {
			return;
		}
		cir.setReturnValue(new HLibBoostedListener(vanilla, engine));
	}

	/** Delegating BLOCK-layer listener whose reads are lifted by the HLib field. */
	private static final class HLibBoostedListener implements LayerLightEventListener {
		private final LayerLightEventListener delegate;
		private final LHCEngine engine;

		HLibBoostedListener(LayerLightEventListener delegate, LHCEngine engine) {
			this.delegate = delegate;
			this.engine = engine;
		}

		@Override
		public int getLightValue(BlockPos pos) {
			int vanilla = this.delegate.getLightValue(pos);
			int h = this.engine.getHLight(pos);
			if (h <= 0) {
				return vanilla;
			}
			// per-source attenuation curves reshape the spawn-visible nibble
			// (carried from the dominating source's wave); curve-less sources
			// keep the exact legacy vanilla-nibble mapping
			int boosted = this.engine.spawnNibbleAt(pos.asLong(), h);
			return boosted > vanilla ? boosted : vanilla;
		}

		@Override
		public void checkBlock(BlockPos pos) {
			this.delegate.checkBlock(pos);
		}

		@Override
		public boolean hasLightWork() {
			return this.delegate.hasLightWork();
		}

		@Override
		public int runLightUpdates() {
			return this.delegate.runLightUpdates();
		}

		@Override
		public void updateSectionStatus(net.minecraft.core.SectionPos pos, boolean isEmpty) {
			this.delegate.updateSectionStatus(pos, isEmpty);
		}

		@Override
		public void setLightEnabled(net.minecraft.world.level.ChunkPos chunkPos, boolean enabled) {
			this.delegate.setLightEnabled(chunkPos, enabled);
		}

		@Override
		public void propagateLightSources(net.minecraft.world.level.ChunkPos chunkPos) {
			this.delegate.propagateLightSources(chunkPos);
		}

		@Override
		public net.minecraft.world.level.chunk.DataLayer getDataLayerData(net.minecraft.core.SectionPos pos) {
			return this.delegate.getDataLayerData(pos);
		}
	}
}
