package com.lighthlib.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Internal block -&gt; component bindings for the LightHLib 16-bit light channel.
 *
 * <p>Bindings are established in two ways:</p>
 * <ol>
 *   <li><b>State-init capture</b> - the function returned by
 *       {@code LightHLib.HLIGHT(fn)} is evaluated once per block state while
 *       the owning {@code Block} builds its state container. At that point the
 *       owning block is known, and the full-range emission is bound to it.</li>
 *   <li><b>Constructor capture</b> - a mixin on {@code Block}'s constructor
 *       consumes any components staged in the registering thread right before
 *       the block instance was created, binding emission and color.</li>
 * </ol>
 *
 * <p>This class is internal API. Mod developers should use the factories on
 * {@code com.lighthlib.LightHLib}.</p>
 */
public final class HLightRegistry {
	/**
	 * A positional emission source whose strength depends on block entity data
	 * rather than only the block state. Used internally by the test block.
	 */
	public interface DynamicEmission {
		int emission(Level level, BlockPos pos, BlockState state);
	}

	/**
	 * Per-source config overrides: the LHCE config values that make sense for a
	 * single light source ({@code falloffPerBlock}, {@code fringeCurve},
	 * {@code tintStrength}, {@code ambientFloor}, {@code nearestSourceRadius}).
	 * A null field falls back to the global {@code LHCEConfig} value.
	 */
	public record Overrides(Integer falloff, Double fringeCurve, Double tintStrength,
			Double ambientFloor, Integer nearestSourceRadius) {

		/** An empty override set: everything falls back to the global config. */
		public static final Overrides NONE = new Overrides(null, null, null, null, null);

		/** True when every field is null (nothing overrides the global config). */
		public boolean isEmpty() {
			return this.falloff == null && this.fringeCurve == null && this.tintStrength == null
					&& this.ambientFloor == null && this.nearestSourceRadius == null;
		}

		/** Merges {@code top} over {@code base}: a non-null field of {@code top}
		 *  wins, everything else comes from {@code base}. */
		public static Overrides merge(Overrides base, Overrides top) {
			if (base == null || base.isEmpty()) {
				return top;
			}
			if (top == null || top.isEmpty()) {
				return base;
			}
			return new Overrides(
					top.falloff != null ? top.falloff : base.falloff,
					top.fringeCurve != null ? top.fringeCurve : base.fringeCurve,
					top.tintStrength != null ? top.tintStrength : base.tintStrength,
					top.ambientFloor != null ? top.ambientFloor : base.ambientFloor,
					top.nearestSourceRadius != null ? top.nearestSourceRadius : base.nearestSourceRadius);
		}

		private Overrides sanitized() {
			if (isEmpty()) {
				return this;
			}
			com.lighthlib.engine.LHCEConfig cfg = com.lighthlib.engine.LHCEConfig.get();
			return new Overrides(
					this.falloff != null ? Math.clamp(this.falloff, 1, 4096) : null,
					this.fringeCurve != null ? Math.clamp(this.fringeCurve, 1.0, 2.0) : null,
					this.tintStrength != null ? Math.clamp(this.tintStrength, 0.0, 2.0) : null,
					this.ambientFloor != null ? Math.clamp(this.ambientFloor, 0.05, 2.0) : null,
					this.nearestSourceRadius != null
							? Math.clamp(this.nearestSourceRadius, 16, 1024) : null);
		}
	}

	/**
	 * A full binding for one block: static emission function, light color,
	 * optional positional override and the {@link HLight} ID signature this
	 * block's light is registered under (null for legacy bindings), plus
	 * optional per-source config {@link Overrides}.
	 */
	public record Entry(ToIntFunction<BlockState> emission, int colorRgb, DynamicEmission dynamic,
			HLight hLight, Overrides overrides) {

		/** @deprecated legacy 4-arg constructor (no overrides). */
		@Deprecated
		public Entry(ToIntFunction<BlockState> emission, int colorRgb, DynamicEmission dynamic,
				HLight hLight) {
			this(emission, colorRgb, dynamic, hLight, null);
		}
	}

	/** Components staged for the next block constructed on the current thread. */
	public record Pending(ToIntFunction<BlockState> fn, ToIntFunction<BlockState> wrapper, Integer color,
			Integer falloff, Double fringeCurve, Double tintStrength, Double ambientFloor,
			Integer nearestSourceRadius) {

		/** @deprecated legacy 3-arg constructor. */
		@Deprecated
		public Pending(ToIntFunction<BlockState> fn, ToIntFunction<BlockState> wrapper, Integer color) {
			this(fn, wrapper, color, null, null, null, null, null);
		}

		/** The staged overrides (sanitized), or null when none were staged. */
		public Overrides overrides() {
			if (this.falloff == null && this.fringeCurve == null && this.tintStrength == null
					&& this.ambientFloor == null && this.nearestSourceRadius == null) {
				return null;
			}
			return new Overrides(this.falloff, this.fringeCurve, this.tintStrength,
					this.ambientFloor, this.nearestSourceRadius).sanitized();
		}
	}

	private static final Map<Block, Entry> ENTRIES = new ConcurrentHashMap<>();
	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

	private HLightRegistry() {
	}

	public static boolean hasAnyBindings() {
		return !ENTRIES.isEmpty();
	}

	/** Fast lookup used by the light engine. */
	public static Entry entry(Block block) {
		if (ENTRIES.isEmpty()) {
			return null;
		}
		return ENTRIES.get(block);
	}

	/** Creates the wrapped function handed to vanilla {@code Properties#lightLevel}. */
	public static ToIntFunction<BlockState> wrap(ToIntFunction<BlockState> emission) {
		return state -> {
			int raw = HLightMath.clampHLight(emission.applyAsInt(state));
			// The first evaluation happens inside the Block constructor while
			// the state container is being built: bind the full-range emission.
			Block block = state.getBlock();
			Entry existing = ENTRIES.get(block);
			if (existing == null) {
				Pending pending = PENDING.get();
				Integer color = pending != null ? pending.color() : null;
				Overrides overrides = pending != null ? pending.overrides() : null;
				ENTRIES.put(block, new Entry(emission,
						color != null ? color : HLightMath.WHITE_RGB, null, null, overrides));
			} else if (existing.dynamic() == null) {
				// Re-registration (e.g. ofFullCopy): keep the strongest info we have.
				ENTRIES.put(block, new Entry(existing.emission(), existing.colorRgb(), null, existing.hLight(),
						existing.overrides()));
			}
			// Vanilla never sees more than 15 through this channel.
			return Math.min(15, raw);
		};
	}

	/** Binds a full-range emission function to a block at runtime. */
	public static void bindEmission(Block block, ToIntFunction<BlockState> emission, Integer color) {
		Entry existing = ENTRIES.get(block);
		if (existing == null) {
			ENTRIES.put(block, new Entry(emission, color != null ? color : HLightMath.WHITE_RGB, null, null, null));
		} else if (existing.dynamic() == null) {
			ENTRIES.put(block, new Entry(emission, color != null ? color : existing.colorRgb(), null,
					existing.hLight(), existing.overrides()));
		}
	}

	/** Binds a light color to a block. */
	public static void bindColor(Block block, int colorRgb) {
		Entry existing = ENTRIES.get(block);
		if (existing == null) {
			ENTRIES.put(block, new Entry(state -> 0, colorRgb & 0xFFFFFF, null, null, null));
		} else if (existing.dynamic() == null) {
			ENTRIES.put(block, new Entry(existing.emission(), colorRgb & 0xFFFFFF, null, existing.hLight(),
					existing.overrides()));
		}
	}

	/** Binds a positional (block-entity driven) emission to a block. */
	public static void bindDynamic(Block block, int colorRgb, DynamicEmission dynamic) {
		Entry existing = ENTRIES.get(block);
		ToIntFunction<BlockState> fallback = existing != null ? existing.emission() : state -> 0;
		ENTRIES.put(block, new Entry(fallback, colorRgb & 0xFFFFFF, dynamic, existing.hLight(),
				existing.overrides()));
	}

	/**
	 * Binds a complete {@link HLight} source object to a block: emission
	 * (state function or dynamic source), color when configured, and the
	 * light's ID signature. Used by the {@code setHLightEntity} properties
	 * chain and by runtime bindings.
	 */
	public static void bindHLight(Block block, HLight light) {
		Entry existing = ENTRIES.get(block);
		ToIntFunction<BlockState> fn = light.stateFunction();
		DynamicEmission dynamic = light.dynamicFunction();
		if (fn == null) {
			fn = existing != null ? existing.emission() : state -> 0;
		}
		int color = light.hasColor() ? light.color()
				: (existing != null ? existing.colorRgb() : HLightMath.WHITE_RGB);
		Overrides overrides = light.overrides() != null ? Overrides.merge(
				existing != null ? existing.overrides() : null, light.overrides())
				: (existing != null ? existing.overrides() : null);
		ENTRIES.put(block, new Entry(fn, color, dynamic, light, overrides));
	}

	/** Binds per-source config overrides to a block (runtime form of the
	 *  {@code HFalloffPerBlock}/... staged components). */
	public static void bindOverrides(Block block, Overrides overrides) {
		Entry existing = ENTRIES.get(block);
		if (existing == null) {
			ENTRIES.put(block, new Entry(state -> 0, HLightMath.WHITE_RGB, null, null,
					overrides != null && !overrides.isEmpty() ? overrides : null));
		} else {
			Overrides merged = Overrides.merge(existing.overrides(), overrides);
			ENTRIES.put(block, new Entry(existing.emission(), existing.colorRgb(), existing.dynamic(),
					existing.hLight(), merged != null && !merged.isEmpty() ? merged : null));
		}
	}

	/** The per-source config overrides of a block's light source, or null when
	 *  none were set (all values fall back to the global LHCEConfig). */
	public static Overrides hLightOverrides(Block block) {
		if (ENTRIES.isEmpty()) {
			return null;
		}
		Entry entry = ENTRIES.get(block);
		return entry == null ? null : entry.overrides();
	}

	/** The {@link HLight} ID signature a block's light is registered under, or null. */
	public static HLight hLightOf(Block block) {
		if (ENTRIES.isEmpty()) {
			return null;
		}
		Entry entry = ENTRIES.get(block);
		return entry == null ? null : entry.hLight();
	}

	/**
	 * Computes the effective 16-bit emission at a position, taking positional
	 * (dynamic) sources and the block state into account.
	 */
	public static int emissionAt(Level level, BlockPos pos, BlockState state) {
		if (ENTRIES.isEmpty()) {
			return 0;
		}
		Entry entry = ENTRIES.get(state.getBlock());
		if (entry == null) {
			return 0;
		}
		if (entry.dynamic() != null) {
			return HLightMath.clampHLight(entry.dynamic().emission(level, pos, state));
		}
		return HLightMath.clampHLight(entry.emission().applyAsInt(state));
	}

	/** The registered color for a source block (used when the engine writes a source cell). */
	public static int sourceColor(BlockState state) {
		if (ENTRIES.isEmpty()) {
			return HLightMath.WHITE_RGB;
		}
		Entry entry = ENTRIES.get(state.getBlock());
		return entry != null ? entry.colorRgb() : HLightMath.WHITE_RGB;
	}

	/** The effective falloff for a source cell: the source's per-source
	 *  override, else the global LHCEConfig value. */
	public static int effectiveFalloff(Level level, BlockPos pos, BlockState state) {
		if (!ENTRIES.isEmpty()) {
			Entry entry = ENTRIES.get(state.getBlock());
			if (entry != null && entry.overrides() != null && entry.overrides().falloff() != null) {
				return entry.overrides().falloff();
			}
		}
		return com.lighthlib.engine.LHCEConfig.get().falloffPerBlock;
	}

	public static boolean isHLibBlock(Block block) {
		return !ENTRIES.isEmpty() && ENTRIES.containsKey(block);
	}

	// --- staging ------------------------------------------------------------

	public static void stage(ToIntFunction<BlockState> fn, ToIntFunction<BlockState> wrapper, Integer color) {
		Pending current = PENDING.get();
		Integer mergedColor = color != null ? color : (current != null ? current.color() : null);
		PENDING.set(new Pending(fn, wrapper, mergedColor,
				current != null ? current.falloff() : null,
				current != null ? current.fringeCurve() : null,
				current != null ? current.tintStrength() : null,
				current != null ? current.ambientFloor() : null,
				current != null ? current.nearestSourceRadius() : null));
	}

	/** Stages a per-source config override component (merged into the pending set). */
	public static void stageOverride(String field, Number value) {
		Pending current = PENDING.get();
		Pending base = current != null ? current
				: new Pending(null, null, null, null, null, null, null, null);
		switch (field) {
			case "falloffPerBlock" -> PENDING.set(new Pending(base.fn(), base.wrapper(), base.color(),
					value.intValue(), base.fringeCurve(), base.tintStrength(), base.ambientFloor(),
					base.nearestSourceRadius()));
			case "fringeCurve" -> PENDING.set(new Pending(base.fn(), base.wrapper(), base.color(),
					base.falloff(), value.doubleValue(), base.tintStrength(), base.ambientFloor(),
					base.nearestSourceRadius()));
			case "tintStrength" -> PENDING.set(new Pending(base.fn(), base.wrapper(), base.color(),
					base.falloff(), base.fringeCurve(), value.doubleValue(), base.ambientFloor(),
					base.nearestSourceRadius()));
			case "ambientFloor" -> PENDING.set(new Pending(base.fn(), base.wrapper(), base.color(),
					base.falloff(), base.fringeCurve(), base.tintStrength(), value.doubleValue(),
					base.nearestSourceRadius()));
			case "nearestSourceRadius" -> PENDING.set(new Pending(base.fn(), base.wrapper(), base.color(),
					base.falloff(), base.fringeCurve(), base.tintStrength(), base.ambientFloor(),
					value.intValue()));
			default -> throw new IllegalArgumentException("Unknown override field: " + field);
		}
	}

	public static Pending pending() {
		return PENDING.get();
	}

	public static void clearPending() {
		PENDING.remove();
	}
}
