package com.lighthlib.mixin;

import com.lighthlib.api.HLight;
import com.lighthlib.api.HLightBlockProperties;
import com.lighthlib.impl.LightHLibImpl;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.ToIntFunction;

/**
 * Exposes the private {@code lightEmission} function of
 * {@code BlockBehaviour.Properties} to the component-capture logic, and adds
 * the {@code setHLightEntity} chain method for binding a complete
 * {@link HLight} source object (see {@link HLightBlockProperties}).
 */
@Mixin(BlockBehaviour.Properties.class)
public abstract class BlockBehaviourPropertiesMixin
		implements LightHLibImpl.HLightPropertiesAccess, HLightBlockProperties {
	@Shadow
	private ToIntFunction<BlockState> lightEmission;

	@Unique
	private HLight lighthlib$hLight;

	@Override
	public ToIntFunction<BlockState> lighthlib$getLightEmission() {
		return this.lightEmission;
	}

	@Override
	public BlockBehaviour.Properties setHLightEntity(HLight light) {
		this.lighthlib$hLight = light;
		if (light != null) {
			// automatic vanilla fallback: vanilla's lightLevel sees the
			// clamped 0-15 part of the 16-bit channel
			((BlockBehaviour.Properties) (Object) this).lightLevel(light::stateEmission);
		}
		return (BlockBehaviour.Properties) (Object) this;
	}

	@Override
	public HLight lighthlib$getHLight() {
		return this.lighthlib$hLight;
	}
}
