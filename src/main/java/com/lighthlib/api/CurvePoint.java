package com.lighthlib.api;

import java.util.Arrays;

/**
 * Second-tier curve control: one user-drawn point of a custom attenuation
 * curve - a (distanceRatio, intensity) pair. The drop builders
 * ({@code customIntensityCurve} / {@code customColorCurve}) turn a point list
 * into a cached Catmull-Rom spline automatically, so developers "draw" the
 * curve instead of writing formulas.
 *
 * <pre>{@code
 * HLightDrop()
 *     .customIntensityCurve(
 *         new CurvePoint(0.0f, 1.0f),   // full brightness at the source
 *         new CurvePoint(0.6f, 0.8f),   // still 80% at 60% of the reach
 *         new CurvePoint(1.0f, 0.0f));  // dead at the outermost cell
 * }</pre>
 */
public record CurvePoint(float distance, float intensity) {

	public CurvePoint {
		if (distance < 0.0F || distance > 1.0F) {
			throw new IllegalArgumentException("CurvePoint distance must be 0..1, got " + distance);
		}
		if (intensity < 0.0F || intensity > 1.0F) {
			throw new IllegalArgumentException("CurvePoint intensity must be 0..1, got " + intensity);
		}
	}

	/**
	 * Builds a cached Catmull-Rom spline through the given points (sorted by
	 * distance; the first/last points act as the spline ends - endpoints are
	 * duplicated so the curve passes exactly through every given point).
	 * Out-of-range distances clamp to the nearest end.
	 */
	public static MathCurveFunction spline(CurvePoint... points) {
		if (points == null || points.length < 2) {
			throw new IllegalArgumentException("A custom curve needs at least 2 points");
		}
		CurvePoint[] sorted = points.clone();
		Arrays.sort(sorted, (a, b) -> Float.compare(a.distance, b.distance));
		float[] x = new float[sorted.length];
		float[] y = new float[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			if (i > 0 && sorted[i].distance() <= sorted[i - 1].distance()) {
				throw new IllegalArgumentException("CurvePoint distances must be strictly increasing");
			}
			x[i] = sorted[i].distance();
			y[i] = sorted[i].intensity();
		}
		return MathCurveFunction.cached(t -> evaluateSpline(x, y, Math.clamp(t, 0.0, 1.0)));
	}

	/** Catmull-Rom evaluation: find the segment then blend P0..P3. */
	private static double evaluateSpline(float[] x, float[] y, double t) {
		int n = x.length;
		if (t <= x[0]) {
			return y[0];
		}
		if (t >= x[n - 1]) {
			return y[n - 1];
		}
		int i = 0;
		while (i < n - 2 && t > x[i + 1]) {
			i++;
		}
		float x0 = x[i];
		float x1 = x[i + 1];
		float p0 = y[Math.max(0, i - 1)];
		float p1 = y[i];
		float p2 = y[i + 1];
		float p3 = y[Math.min(n - 1, i + 2)];
		double u = (t - x0) / (x1 - x0);
		double u2 = u * u;
		double u3 = u2 * u;
		// uniform Catmull-Rom basis
		return 0.5 * ((2.0 * p1)
				+ (-p0 + p2) * u
				+ (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2
				+ (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
	}
}
