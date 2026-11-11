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
	private MathCurveFunction intensityCurve;
	private MathCurveFunction colorCurve;

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

	// --- attenuation curves (first tier: presets) ---

	/**
	 * The brightness attenuation shape of this light: how fast the 16-bit
	 * field fades from the source center (curve input 0.0) to the outermost
	 * lit cell (curve input 1.0). Pick a ready-made {@link LightCurve}
	 * preset; {@code null} clears the override (engine-default linear
	 * falloff). The source center keeps its full brightness - the curve only
	 * reshapes the falloff between center and reach.
	 */
	public HLightDrop intensityCurve(MathCurveFunction curve) {
		this.intensityCurve = curve;
		return this;
	}

	/**
	 * The color fade of this light: scales the tint saturation along the
	 * field (1.0 = full saturation where the curve says, 0.0 = color already
	 * dissolved into the ambient). {@code null} clears the override.
	 */
	public HLightDrop colorCurve(MathCurveFunction curve) {
		this.colorCurve = curve;
		return this;
	}

	// --- attenuation curves (second tier: control points) ---

	/**
	 * Draws the brightness curve through control points: each
	 * {@link CurvePoint} is a (distanceRatio, intensity) pair, interpolated
	 * by a cached Catmull-Rom spline that passes exactly through every point.
	 */
	public HLightDrop customIntensityCurve(CurvePoint... points) {
		return intensityCurve(CurvePoint.spline(points));
	}

	/** Draws the color-fade curve through control points (see
	 *  {@link #customIntensityCurve(CurvePoint...)}). */
	public HLightDrop customColorCurve(CurvePoint... points) {
		return colorCurve(CurvePoint.spline(points));
	}

	// --- attenuation curves (third tier: math function injection) ---

	/**
	 * A hand-written brightness curve. The lambda is wrapped in a lookup
	 * table automatically (see {@link MathCurveFunction#cached}), so plain
	 * lambdas are safe on the render hot path; return values are clamped to
	 * 0..1, out-of-range distances clamp to the nearest sample.
	 */
	public HLightDrop mathIntensityCurve(MathCurveFunction curve) {
		return intensityCurve(curve == null ? null : MathCurveFunction.cached(curve));
	}

	/** A hand-written color-fade curve (see
	 *  {@link #mathIntensityCurve(MathCurveFunction)}). */
	public HLightDrop mathColorCurve(MathCurveFunction curve) {
		return colorCurve(curve == null ? null : MathCurveFunction.cached(curve));
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
				&& this.ambientFloor == null && this.nearestSourceRadius == null
				&& this.intensityCurve == null && this.colorCurve == null) {
			return null;
		}
		return new HLightRegistry.Overrides(this.falloff, this.fringeCurve, this.tintStrength,
				this.ambientFloor, this.nearestSourceRadius, this.intensityCurve, this.colorCurve);
	}
}
