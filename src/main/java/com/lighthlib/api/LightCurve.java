package com.lighthlib.api;

/**
 * First-tier attenuation presets: ready-made mathematical light curves.
 * Every preset is a cached {@link MathCurveFunction} (O(1) lookup table), so
 * they are safe on the render hot path.
 *
 * <pre>{@code
 * HLightDrop()
 *     .intensityCurve(LightCurve.SMOOTH_STEP)       // soft neon glow
 *     .colorCurve(LightCurve.LASER_EXPONENTIAL);    // color fades fast at edges
 * }</pre>
 *
 * <p>Aliases point at the same instance ({@code LINEAR} = {@code VANILLA},
 * {@code EXPONENTIAL} = {@code LASER_EXPONENTIAL}, {@code EASE_IN_OUT} =
 * {@code MAGIC_EASE}) so any naming preference from the design docs works.</p>
 */
public interface LightCurve extends MathCurveFunction {

	/** Literal linear falloff: brightness scales straight from center (1.0)
	 *  down to zero at the edge of coverage. NOTE: this is NOT the same as
	 *  the engine's default (no-curve) rendering - the default keeps cells at
	 *  near-full brightness across the whole covered range and only fades in
	 *  the fringe, so a LINEAR-bound field looks dramatically darker and its
	 *  color tint weaker by comparison. For the no-curve default look, bind
	 *  {@link #CONSTANT} (or simply no curve at all). */
	LightCurve VANILLA = preset("vanilla", t -> 1.0 - t);

	/** Alias of {@link #VANILLA}. */
	LightCurve LINEAR = VANILLA;

	/** S-shaped smoothstep: near-constant brightness in the core, then an
	 *  extremely soft slide to zero at the fringe - no staircase, no hard
	 *  edge. Best fit for neon signs and soft light pools. */
	LightCurve SMOOTH_STEP = preset("smooth_step", t -> {
		double s = 1.0 - t;
		return s * s * (3.0 - 2.0 * s);
	});

	/** Exponential drop: blazing center, rapidly dimming fringe. Good for
	 *  lasers and flashlights. */
	LightCurve LASER_EXPONENTIAL = preset("laser_exponential", t -> Math.exp(-4.5 * t));

	/** Alias of {@link #LASER_EXPONENTIAL}. */
	LightCurve EXPONENTIAL = LASER_EXPONENTIAL;

	/** Eased bell curve (ease-in-out sine): bright core, extremely gentle
	 *  fade at both ends. Made for magic circles and ambient runes. */
	LightCurve MAGIC_EASE = preset("magic_ease", t -> (1.0 + Math.cos(Math.PI * t)) / 2.0);

	/** Alias of {@link #MAGIC_EASE}. */
	LightCurve EASE_IN_OUT = MAGIC_EASE;

	/** No attenuation at all: full brightness from center to the very last
	 *  lit cell, then instant darkness. */
	LightCurve CONSTANT = preset("constant", t -> 1.0);

	/** Real-world physics: intensity falls with the square of the distance
	 *  (normalized so the center reads 1.0 and the reach reads ~0). */
	LightCurve INVERSE_SQUARE = preset("inverse_square", t -> {
		double d = t / Math.max(1.0E-4, 1.0 - t);
		return 1.0 / (1.0 + d * d);
	});

	/** Gaussian (normal-distribution) falloff: very bright core, bell-shaped
	 *  slide-off with a long faint tail. */
	LightCurve GAUSSIAN = preset("gaussian", t -> Math.exp(-(t * t) / 0.2025));

	/** Stepped (quantized) falloff: concentric equal-brightness rings, like
	 *  contour lines on a map. Evaluated directly (no lookup table): the
	 *  table's ~0.1% input quantization would flip cells across the ring
	 *  thresholds, and {@code ceil} is O(1) anyway. */
	LightCurve STEPPED = presetDirect("stepped", t -> Math.ceil((1.0 - t) * 4.0) / 4.0);

	/** Builds a named preset backed by a lookup table (see
	 *  {@link MathCurveFunction#cached}). */
	private static LightCurve preset(String name, MathCurveFunction fn) {
		return new Preset(name, fn);
	}

	/** Builds a named preset evaluated directly on every call (no lookup
	 *  table) - for curves whose hard thresholds would flip under the
	 *  table's ~0.1% input quantization. */
	private static LightCurve presetDirect(String name, MathCurveFunction fn) {
		return new Preset(name, fn, false);
	}

	/** A named, cached, ready-made curve. */
	final class Preset implements LightCurve {
		private final String name;
		private final MathCurveFunction fn;
		private final MathCurveFunction cached;

		Preset(String name, MathCurveFunction fn) {
			this(name, fn, true);
		}

		Preset(String name, MathCurveFunction fn, boolean useTable) {
			this.name = name;
			this.fn = fn;
			this.cached = useTable ? MathCurveFunction.cached(fn) : null;
		}

		@Override
		public double evaluate(double distanceRatio) {
			MathCurveFunction delegate = this.cached;
			return delegate != null
					? delegate.evaluate(distanceRatio)
					: Math.clamp(this.fn.evaluate(Math.clamp(distanceRatio, 0.0, 1.0)), 0.0, 1.0);
		}

		@Override
		public String toString() {
			return "LightCurve[" + this.name + "]";
		}
	}
}
