package com.lighthlib.api;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Duck-typed access to the {@code setHLightEntity} chain method that
 * LightHLib adds to {@code BlockBehaviour.Properties} at runtime.
 *
 * <p>{@code setHLightEntity} is woven into {@code BlockBehaviour.Properties}
 * by a mixin, so it does not exist on the vanilla type at compile time. The
 * standard pattern for calling mixin-added methods is this interface cast -
 * it compiles against vanilla types and resolves at runtime where the mixin
 * applies:</p>
 *
 * <pre>{@code
 * BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
 * props = ((HLightBlockProperties) props).setHLightEntity(mylight);  // keeps chaining
 * new MyBlock(props);
 * }</pre>
 */
public interface HLightBlockProperties {
	/**
	 * Binds a complete {@link HLight} source object to the block being built
	 * with these properties, replacing all other LightHLib chain calls:
	 * emission, color and the light's ID signature all come from the HLight.
	 * The vanilla 0-15 {@code lightLevel} fallback is wired automatically.
	 *
	 * @return the same properties instance, for further chaining
	 */
	BlockBehaviour.Properties setHLightEntity(HLight light);

	/** Internal: the HLight stashed on these properties, or null. */
	HLight lighthlib$getHLight();
}
