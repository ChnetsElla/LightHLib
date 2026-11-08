package com.lighthlib.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import com.lighthlib.LightHLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A named LightHLib light source object - the <b>ID signature</b> of a 16-bit
 * light. An HLight bundles everything that defines one light source:
 *
 * <ul>
 *   <li>a {@link ResourceKey} identity (the signature other systems can match
 *       against), created via {@code ResourceKey.create(HLight.REGISTRY_KEY, id)};</li>
 *   <li>the 16-bit emission - either a state function ({@link HLightDrop#HlightLevel})
 *       or a positional block-entity driven source ({@link HLightDrop#HlightDynamic});</li>
 *   <li>an optional light color.</li>
 * </ul>
 *
 * <pre>{@code
 * HLight mylight = new HLight(ResourceKey.create(HLight.REGISTRY_KEY,
 *                 Identifier.fromNamespaceAndPath("mymod", "mylight")),
 *         new HLightDrop()
 *                 .HlightLevel(state -> 200)
 *                 .HLightColor(255, 64, 0));
 *
 * // in the block's Properties chain:
 * BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
 * props = ((HLightBlockProperties) props).setHLightEntity(mylight);
 * new MyBlock(props);
 * }</pre>
 *
 * <p>HLight objects can also be registered directly - a pure, complete light
 * source definition independent of any block:</p>
 *
 * <pre>{@code
 * HLight.register(mylight);     // later: HLight.get(key)
 * }</pre>
 */
public final class HLight {
	/** The key space every {@link HLight} is registered in. */
	public static final ResourceKey<? extends Registry<HLight>> REGISTRY_KEY =
			ResourceKey.createRegistryKey(LightHLib.id("hlight"));

	private static final Map<ResourceKey<HLight>, HLight> REGISTERED = new ConcurrentHashMap<>();

	private final ResourceKey<HLight> key;
	private final ToIntFunction<BlockState> emission;
	private final HLightRegistry.DynamicEmission dynamic;
	private final int colorRgb;
	private final HLightRegistry.Overrides overrides;

	/**
	 * @param key  the ID signature of this light
	 * @param drop the configured builder (level or dynamic required)
	 */
	public HLight(ResourceKey<HLight> key, HLightDrop drop) {
		this.key = Objects.requireNonNull(key, "key");
		Objects.requireNonNull(drop, "drop");
		this.emission = drop.level();
		this.dynamic = drop.dynamic();
		this.colorRgb = drop.color();
		this.overrides = drop.overrides();
		if (this.emission == null && this.dynamic == null) {
			throw new IllegalArgumentException("HLight " + key.identifier()
					+ " needs a level: call HLightDrop.HlightLevel(...) or HLightDrop.HlightDynamic(...)");
		}
	}

	/** Registers (or replaces) a light source object under its key. */
	public static HLight register(HLight light) {
		Objects.requireNonNull(light, "light");
		REGISTERED.put(light.key(), light);
		return light;
	}

	/** The registered light source for a key, or null when unregistered. */
	public static HLight get(ResourceKey<HLight> key) {
		return REGISTERED.get(key);
	}

	/** The ID signature of this light. */
	public ResourceKey<HLight> key() {
		return this.key;
	}

	/** The light color, packed {@code 0xRRGGBB}, or -1 when unset. */
	public int color() {
		return this.colorRgb;
	}

	/** Whether a color was configured for this light. */
	public boolean hasColor() {
		return this.colorRgb != HLightComponent.NO_COLOR;
	}

	/** The per-source config overrides of this light (null when none set: every
	 *  value falls back to the global LHCEConfig). */
	public HLightRegistry.Overrides overrides() {
		return this.overrides;
	}

	/** The full 16-bit emission at a position (0 when nothing applies). */
	public int emissionAt(Level level, BlockPos pos, BlockState state) {
		if (this.dynamic != null) {
			return HLightMath.clampHLight(this.dynamic.emission(level, pos, state));
		}
		return this.emission == null ? 0 : HLightMath.clampHLight(this.emission.applyAsInt(state));
	}

	/** The vanilla-compatible 0..15 emission of a state; used for the
	 *  automatic {@code Properties#lightLevel} fallback of
	 *  {@code setHLightEntity}. */
	public int stateEmission(BlockState state) {
		return this.emission == null ? 0
				: Math.min(15, HLightMath.clampHLight(this.emission.applyAsInt(state)));
	}

	// package-private accessors for the registry binding
	ToIntFunction<BlockState> stateFunction() {
		return this.emission;
	}

	HLightRegistry.DynamicEmission dynamicFunction() {
		return this.dynamic;
	}

	@Override
	public String toString() {
		return "HLight[" + this.key.identifier() + "]";
	}
}
