package com.lighthlib.engine;

/** Internal constants of the LHCE engine. */
public final class HLibConstants {
	/**
	 * The per-block cost of crossing into a block, mirroring the vanilla light
	 * engine's logic one-to-one: {@code max(falloff, dampening * falloff)}.
	 *
	 * <p>With the default falloff of 1 the LHCE field behaves exactly like
	 * vanilla light: every block of travel costs one level (of 65535 instead
	 * of 15), so coverage equals the source level at every scale, and the
	 * falloff ratio matches vanilla. Light above level 15 follows the same
	 * logic - just correspondingly stronger, rendered through the over-bright
	 * extension of the display mapping.</p>
	 * <p><b>Fully opaque blocks (dampening 15) never pay this cost</b>: the
	 * propagation loop hard-gates them (light cannot enter, like vanilla),
	 * because a 16-bit source would otherwise lose only 15 of e.g. 50000
	 * levels per stone block - 0.03% - and flood straight through walls, the
	 * ground surface and tinted glass. The decrease wave deliberately ignores
	 * the gate so it can reach and clear stale field data on the far side.</p>
	 */
	public static int crossCost(int level, int lightDampening) {
		int falloff = LHCEConfig.get().falloffPerBlock;
		return Math.max(falloff, lightDampening * falloff);
	}

	/** Cross cost with a per-source falloff override (see
	 *  {@link com.lighthlib.api.HLightRegistry.Overrides#falloff()}). */
	public static int crossCost(int level, int lightDampening, int falloff) {
		return Math.max(falloff, lightDampening * falloff);
	}

	private HLibConstants() {
	}
}
