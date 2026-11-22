package com.lighthlib.mixin;

import com.lighthlib.impl.LightHLibImpl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures LightHLib components staged for the block being constructed, so
 * calls like {@code LightHLib.HlightLevel(...)} / {@code LightHLib.HLightColor(...)}
 * inside the block's {@code BlockBehaviour.Properties.of()} registration bind
 * to the right block instance.
 */
@Mixin(Block.class)
public abstract class BlockMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void lighthlib$onConstructed(BlockBehaviour.Properties properties, CallbackInfo ci) {
		LightHLibImpl.onBlockConstructed((Block) (Object) this, properties);
	}
}
