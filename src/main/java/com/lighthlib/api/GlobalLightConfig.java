package com.lighthlib.api;

import com.lighthlib.engine.LHCEConfig;

/**
 * The immutable snapshot of LightHLib's global engine configuration, produced
 * by {@link com.lighthlib.LightInit#build()}.
 *
 * <p>Being a record, the snapshot is frozen the moment it is built: async
 * engine threads (flood waves, render hooks) can read it without locks, and
 * nobody can mutate the effective engine values through it. The live, mutable
 * configuration remains {@link LHCEConfig} (config file + ModMenu +
 * {@code /hl reload}); this type is the read-only view handed back to the
 * initializing mod.</p>
 *
 * <p>Each field mirrors one ModMenu / {@code config/lighthlib.json} entry - see
 * {@code LightHLib-API.md} for the full key-by-key semantics.</p>
 */
public record GlobalLightConfig(
		int nodeBudgetPerTick,
		int falloffPerBlock,
		double fringeCurve,
		boolean enableColoredLight,
		boolean logStartupInfo,
		int nearestSourceRadius,
		double ambientFloor,
		double tintStrength,
		double nirisExtensionCurve,
		boolean takeOverVanillaLighting,
		boolean enableColorBlending) {

	/** Whether a {@code LightHLib.init()...build()} ever completed in this
	 *  process (first build logs the init banner, later builds log a warning
	 *  and re-apply). */
	private static volatile boolean initialized;

	/** True once any global config build completed. */
	public static boolean isInitialized() {
		return initialized;
	}

	/** Marks the config as initialized (called by the builder). */
	public static void markInitialized() {
		initialized = true;
	}

	/** A snapshot of the current live configuration (config file / ModMenu /
	 *  init-build state), for querying at any time without keeping the record
	 *  returned by {@code build()}. */
	public static GlobalLightConfig current() {
		LHCEConfig c = LHCEConfig.get();
		return new GlobalLightConfig(
				c.nodeBudgetPerTick,
				c.falloffPerBlock,
				c.fringeCurve,
				c.enableColoredLight,
				c.logStartupInfo,
				c.nearestSourceRadius,
				c.ambientFloor,
				c.tintStrength,
				c.nirisExtensionCurve,
				c.takeOverVanillaLighting,
				c.enableColorBlending);
	}

	@Override
	public String toString() {
		return "GlobalLightConfig[nodeBudget=" + nodeBudgetPerTick
				+ ", falloff=" + falloffPerBlock
				+ ", fringeCurve=" + fringeCurve
				+ ", coloredLight=" + enableColoredLight
				+ ", colorBlending=" + enableColorBlending
				+ ", tintStrength=" + tintStrength
				+ ", ambientFloor=" + ambientFloor
				+ ", nearestSourceRadius=" + nearestSourceRadius
				+ ", nirisExtensionCurve=" + nirisExtensionCurve
				+ ", takeOverVanillaLighting=" + takeOverVanillaLighting
				+ ", startupLog=" + logStartupInfo + "]";
	}
}
