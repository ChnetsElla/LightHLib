package com.lighthlib.mixin;

import com.lighthlib.impl.LightHLibImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Funnels every block change (server writes, client updates) into LHCE so the
 * 16-bit light field is recomputed around the changed position.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	@Inject(method = "setBlockState", at = @At("TAIL"))
	private void lighthlib$onSetBlockState(BlockPos pos, BlockState state, int flags,
											CallbackInfoReturnable<BlockState> cir) {
		if (cir.getReturnValue() == null) {
			return;
		}
		LightHLibImpl.onBlockChanged(this.level, pos);
	}
}
