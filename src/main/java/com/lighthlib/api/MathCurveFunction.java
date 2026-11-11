package com.lighthlib.api;

/**
 * Third-tier attenuation-curve injection: a developer-supplied function that
 * maps the normalized distance from a light source to the light's remaining
 * intensity.
 *
 * <ul>
 *   <li>{@code distanceRatio}: 0.0 at the source center, 1.0 at the light's
 *       outermost reach (the ratio of the 16-bit level consumed by travel -
 *       including dampening through blocks, so an occluded cell fades as if
 *       it were farther away).</li>
 *   <li>return value: the remaining intensity, clamped to 0.0..1.0 (1.0 =
 *       as bright as the source center).</li>
 * </ul>
 *
 * <p><b>Never hand a plain {@code Function<Double, Double>} to the APIs:</b>
 * curves are evaluated per lit vertex on the render hot path, so every curve
 * entering the engine should be an O(1) table lookup. Wrap hand-written
 * lambdas with {@link #cached(MathCurveFunction)} (1024-entry lookup table,
 * built once); the built-in {@link LightCurve} presets are already cached.
 * Raw lambdas passed to the drop builders are wrapped automatically.</p>
 *
 * <pre>{@code
 * HLightDrop()
 *     .mathIntensityCurve(distance -> Math.sin(distance * Math.PI)); // wave
 * }</pre>
 */
@FunctionalInterface
public interface MathCurveFunction {
	/** The table resolution of {@link #cached} wrappers (1 &lt;&lt; 10). */
	int LUT_SIZE = 1024;

	/**
	 * @param distanceRatio 0.0 (source center) .. 1.0 (outermost lit cell)
	 * @return remaining intensity, 0.0 .. 1.0
	 */
	double evaluate(double distanceRatio);

	/**
	 * Wraps any hand-written curve in a 1024-entry lookup table so render
	 * hooks evaluate it in O(1) (the table is built once, eagerly, on the
	 * calling thread; values are pre-clamped to 0..1 and out-of-range
	 * distances clamp to the nearest sample).
	 */
	static MathCurveFunction cached(MathCurveFunction fn) {
		return new CachedCurve(fn);
	}

	/** Pre-computed lookup-table wrapper around an arbitrary curve. */
	final class CachedCurve implements MathCurveFunction {
		private final float[] table = new float[LUT_SIZE];

		CachedCurve(MathCurveFunction fn) {
			for (int i = 0; i < LUT_SIZE; i++) {
				double t = (double) i / (LUT_SIZE - 1);
				this.table[i] = (float) Math.clamp(fn.evaluate(t), 0.0, 1.0);
			}
		}

		@Override
		public double evaluate(double distanceRatio) {
			double t = Math.clamp(distanceRatio, 0.0, 1.0);
			return this.table[(int) (t * (LUT_SIZE - 1))];
		}
	}
}
