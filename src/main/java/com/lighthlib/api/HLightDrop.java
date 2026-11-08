package com.lighthlib.api;

import java.util.function.ToIntFunction;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Chainable property builder ("drop") for {@link HLight} - the complete
 * definition of one light source:
 *
 * <pre>{@code
 * new HLightDrop()
 *         .HlightLevel(state -> 200)      // the 16-bit level (state-based)
 *         .HLightColor(255, 64, 0)        // the color
 *         // .HlightDynamic(...)          // OR a block-entity driven level
 * }</pre>
 */
public final class HLightDrop {
	private ToIntFunction<BlockState> level;
	private HLightRegistry.DynamicEmission dynamic;
	private int color = HLightComponent.NO_COLOR;
	private Integer falloff;
	private Double fringeCurve;
	private Double tintStrength;
	private Double ambientFloor;
	private Integer nearestSourceRadius;

	public HLightDrop() {
	}

	/**
	 * The 16-bit emission per block state (values above 65535 are clamped).
	 * Replaces any previously staged level or dynamic source.
	 */
	public HLightDrop HlightLevel(ToIntFunction<BlockState> level) {
		this.level = level;
		this.dynamic = null;
		return this;
	}

	/**
	 * A positional (block-entity driven) 16-bit emission; replaces any
	 * previously staged level function.
	 */
	public HLightDrop HlightDynamic(HLightRegistry.DynamicEmission dynamic) {
		this.dynamic = dynamic;
		this.level = null;
		return this;
	}

	/** The light color, packed {@code 0xRRGGBB}. */
	public HLightDrop HLightColor(int rgb) {
		this.color = rgb & 0xFFFFFF;
		return this;
	}

	/** The light color from components (0..255 each). */
	public HLightDrop HLightColor(int r, int g, int b) {
		return HLightColor(HLightMath.rgb(r, g, b));
	}

	/** The light color, hexadecimal form ({@code "#RRGGBB"} or {@code "RRGGBB"}). */
	public HLightDrop HLightColor(String rgbCode) {
		return HLightColor(HLightMath.parseHexColor(rgbCode));
	}

	// --- per-source config overrides (unset fields fall back to LHCEConfig) ---

	/** Per-source {@code falloffPerBlock}: 16-bit levels subtracted per block
	 *  of travel for this light's flood. */
	public HLightDrop HFalloffPerBlock(int falloff) {
		this.falloff = falloff;
		return this;
	}

	/** Per-source {@code fringeCurve}: exponent of the fringe byte mapping. */
	public HLightDrop HFringeCurve(double fringeCurve) {
		this.fringeCurve = fringeCurve;
		return this;
	}

	/** Per-source {@code tintStrength}: multiplier on this light's tint saturation. */
	public HLightDrop HTintStrength(double tintStrength) {
		this.tintStrength = tintStrength;
		return this;
	}

	/** Per-source {@code ambientFloor}: ambient floor this light's fringe merges into. */
	public HLightDrop HAmbientFloor(double ambientFloor) {
		this.ambientFloor = ambientFloor;
		return this;
	}

	/** Per-source {@code nearestSourceRadius}: source-search radius for color resolution. */
	public HLightDrop HNearestSourceRadius(int nearestSourceRadius) {
		this.nearestSourceRadius = nearestSourceRadius;
		return this;
	}

	ToIntFunction<BlockState> level() {
		return this.level;
	}

	HLightRegistry.DynamicEmission dynamic() {
		return this.dynamic;
	}

	int color() {
		return this.color;
	}

	/** The per-source config overrides of this drop (null when none set). */
	HLightRegistry.Overrides overrides() {
		if (this.falloff == null && this.fringeCurve == null && this.tintStrength == null
				&& this.ambientFloor == null && this.nearestSourceRadius == null) {
			return null;
		}
		return new HLightRegistry.Overrides(this.falloff, this.fringeCurve, this.tintStrength,
				this.ambientFloor, this.nearestSourceRadius);
	}
}
