package com.lighthlib.api;

import java.util.Locale;

import com.lighthlib.engine.LHCEConfig;

/**
 * Constants and math shared by every part of LightHLib.
 *
 * <p>The 16-bit light channel runs from 0 to {@link #MAX_HLIGHT} (65535).
 * Light attenuation is linear: a value of N travels roughly N blocks through
 * air (capped by the configured max radius), so higher levels are both
 * brighter and reach further.</p>
 */
public final class HLightMath {
	/** The maximum light level of the LightHLib 16-bit channel. */
	public static final int MAX_HLIGHT = 0xFFFF;

	/** Full-bright white, used when no color component was registered. */
	public static final int WHITE_RGB = 0xFFFFFF;

	private HLightMath() {
	}

	/**
	 * Clamps an arbitrary emission value into the 16-bit channel.
	 */
	public static int clampHLight(int level) {
		return Math.max(0, Math.min(MAX_HLIGHT, level));
	}

	// --- brightness mapping -----------------------------------------------
	// The engine's proportional falloff (see HLibConstants#crossCost) cuts
	// the light field to ~37% every gradientWindow blocks, so the field is
	// (multiplicatively) steep near every source. The display mapping below
	// is logarithmic in the field value, which turns each halving of the
	// field into a fixed visible brightness step - the vanilla-style
	// "layered halo" look, at every source scale:
	//
	//   field 50 -> texel 6.3   field 500 -> 9.4   field 5000 -> 12.9
	//   field 20000 -> 13.95    field 65535 -> 15 (pure white core)
	//
	// Levels at or below 15 render dimmer than a vanilla torch through this
	// channel by design: on the 16-bit scale a value of 15 is dim. The
	// vanilla fallback (state lightLevel, clamped to 15 by HLIGHT) still
	// renders such blocks torch-bright through the vanilla channel, and the
	// render hooks take the max of both channels.

	private static final double LOG1P_RATIO = Math.log1p(MAX_HLIGHT / 15.0);

	/**
	 * True when running the "niris" build flavor, which ships patched
	 * lightmap shaders + an HDR lightmap texture and can therefore render
	 * light brighter than the vanilla 15-level maximum (bytes 241..255
	 * address the over-bright part of the extended lightmap curve).
	 */
	public static final boolean NIRIS = HLightMath.class.getResource("/niris.marker") != null;

	/**
	 * The smooth block-light byte (0..255).
	 *
	 * <ul>
	 *   <li>Field values 0..15 - a field's outermost rings - follow a power
	 *       curve ({@code byte = 240 * (h/15)^fringeCurve}, see
	 *       {@code lighthlib.json}). The vanilla-linear {@code byte = h * 16}
	 *       mapping gave every ring the same 16-byte step, so a field ended in
	 *       a hard one-block staircase at its boundary; the power curve
	 *       compresses only the outermost rings so the edge dissolves into the
	 *       ambient. At the default 1.2 the mid-fringe stays within about one
	 *       vanilla level of the linear mapping (vanilla-like falloff and
	 *       reach), while the boundary ring maps to byte ~9 - dimmer than the
	 *       old staircase step, and its color share sinks below the ambient
	 *       floor, so the visible edge still dissolves.</li>
	 *   <li>Values above 15 follow the same linear falloff logic, rendered
	 *       stronger: the main build caps at the vanilla maximum (byte 240),
	 *       while the niris build continues past it at the vanilla top-level
	 *       growth ratio into bytes 241..255, reaching ~7.4x vanilla full
	 *       brightness at 65535. The ^0.7 spread keeps small level changes
	 *       (20 -&gt; 50 -&gt; 100) clearly visible.</li>
	 * </ul>
	 */
	public static int smoothBlockByte(int hLight) {
		return smoothBlockByte(hLight, LHCEConfig.get().fringeCurve);
	}

	/**
	 * Variant with an explicit {@code fringeCurve}: render hooks pass the
	 * effective curve for the position (a per-source override, else the global
	 * config value - see {@code LHCEngine#effectiveFringeCurve}).
	 */
	public static int smoothBlockByte(int hLight, double fringeCurve) {
		if (hLight <= 0) {
			return 0;
		}
		if (hLight <= 15) {
			return (int) Math.round(240.0 * Math.pow(hLight / 15.0, fringeCurve));
		}
		if (NIRIS) {
			double t = Math.log1p(hLight / 15.0) / LOG1P_RATIO;
			int extension = (int) Math.round(15.0 * Math.pow(t, LHCEConfig.get().nirisExtensionCurve));
			return Math.min(255, 240 + extension);
		}
		return 240;
	}

	/**
	 * The curve-driven render byte: the dominating source's center byte
	 * (see {@code LHCEngine.CellCurve#centerByte}) scaled by the intensity
	 * curve at the cell's distance ratio. The source center stays exactly as
	 * bright as without a curve - the curve only reshapes the falloff between
	 * center and reach. Result clamped to 0..255 (the main build's lightmap
	 * simply saturates above 240 when sampling, same as the plain mapping).
	 */
	public static int curveBlockByte(int centerByte, MathCurveFunction curve, double distanceRatio) {
		if (centerByte <= 0) {
			return 0;
		}
		return Math.max(0, Math.min(255,
				(int) Math.round(centerByte * curve.evaluate(distanceRatio))));
	}

	/**
	 * The (fractional) lightmap texel position in [0, 15] for a 16-bit field
	 * value; saturates at 15 for anything above the vanilla range.
	 */
	public static float texelPosition(int hLight) {
		if (hLight <= 0) {
			return 0.0F;
		}
		return Math.min(15.0F, smoothBlockByte(hLight) / 16.0F);
	}

	/**
	 * The displayed brightness as a multiplier of vanilla full brightness
	 * (0..1 in the main build; 0..~7.4 in the niris build, where light above
	 * the vanilla 15-level maximum keeps growing at the vanilla ratio).
	 */
	public static float displayBrightness(int hLight) {
		int b = smoothBlockByte(hLight);
		if (b <= 0) {
			return 0.0F;
		}
		if (b <= 240) {
			return b / (960.0F - 3.0F * b);
		}
		// niris extension: continue at ~+28% per virtual level (the vanilla
		// top-level growth ratio), ~7.4x at byte 255
		return Math.min(7.4F, (float) Math.pow(1.284, (b - 240) * (8.0 / 15.0)));
	}

	/**
	 * Maps a 16-bit light level to the vanilla 0-15 nibble scale (gameplay
	 * queries such as mob spawning, and plain-nibble consumers): identical to
	 * vanilla for levels 0..15, capped at 15 above.
	 */
	public static int vanillaNibble(int hLight) {
		return Math.max(0, Math.min(15, hLight));
	}

	/**
	 * Parses a color given as {@code "#RRGGBB"} or {@code "RRGGBB"} (hex).
	 *
	 * @throws IllegalArgumentException if the string is malformed
	 */
	public static int parseHexColor(String rgbCode) {
		String s = rgbCode == null ? "" : rgbCode.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		if (s.length() != 6) {
			throw new IllegalArgumentException("Invalid RGB color code: '" + rgbCode + "', expected #RRGGBB");
		}
		try {
			return Integer.parseInt(s, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid RGB color code: '" + rgbCode + "', expected #RRGGBB", e);
		}
	}

	/**
	 * Clamps RGB components into an RGB int.
	 */
	public static int rgb(int r, int g, int b) {
		return (Math.clamp(r, 0, 255) << 16)
				| (Math.clamp(g, 0, 255) << 8)
				| Math.clamp(b, 0, 255);
	}

	/**
	 * Formats an RGB int as {@code #RRGGBB}.
	 */
	public static String formatHexColor(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}
}
