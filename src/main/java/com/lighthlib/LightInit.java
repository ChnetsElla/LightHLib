package com.lighthlib;

import com.lighthlib.api.GlobalLightConfig;
import com.lighthlib.engine.LHCEConfig;
import com.lighthlib.impl.LightHLibImpl;

/**
 * The builder of the global config init API, obtained from
 * {@link LightHLib#init()}. Chainable setters override single config entries;
 * {@link #build()} solidifies them into the live engine configuration.
 *
 * <pre>{@code
 * GlobalLightConfig cfg = LightHLib.init()
 *         .setNodeBudgetPerTick(131072)
 *         .setFringeCurve(1.3)
 *         .setColorBlending(false)
 *         .build();
 * }</pre>
 *
 * <p><b>Merge semantics:</b> the builder starts empty - every entry not
 * explicitly chained keeps its current value ({@code config/lighthlib.json}
 * as last written by the file itself, ModMenu or a previous {@code /hl reload}
 * or init build). Only chained entries are overridden, so a mod pins exactly
 * the values it cares about without clobbering the user's other tweaks. The
 * merged result is sanitized, written back to the config file and swapped in
 * atomically - ModMenu and {@code /hl reload} see it immediately.</p>
 *
 * <p><b>Defensive clamping:</b> every numeric setter clamps to the engine's
 * valid range (the same bounds ModMenu uses), so a misconfigured value can
 * never destabilize the tick budget or the field propagation.</p>
 *
 * <p><b>Thread/contract notes:</b> call from the mod's
 * {@code onInitialize()} (or any point before world load). {@code build()}
 * may be called more than once (e.g. several mods) - later builds re-apply
 * their chained entries on top and log a warning; the first build logs the
 * init banner. The returned record is an immutable snapshot and safe to keep
 * for querying.</p>
 */
public final class LightInit {
	// explicitly chained values; null = keep the current config value
	private Integer nodeBudgetPerTick;
	private Integer falloffPerBlock;
	private Double fringeCurve;
	private Boolean enableColoredLight;
	private Boolean logStartupInfo;
	private Integer nearestSourceRadius;
	private Double ambientFloor;
	private Double tintStrength;
	private Double nirisExtensionCurve;
	private Boolean takeOverVanillaLighting;
	private Boolean enableColorBlending;

	LightInit() {
	}

	// ---- chainable setters (each clamps to the engine's valid range) ----

	/** Max propagation work-units per tick, per level (1024 .. 4194304).
	 *  Larger = faster settling, more main-thread cost. */
	public LightInit setNodeBudgetPerTick(int budget) {
		this.nodeBudgetPerTick = Math.clamp(budget, 1024, 1 << 22);
		return this;
	}

	/** 16-bit levels subtracted per block of travel through air
	 *  (1 .. 7; the wave-entry bit budget is the hard upper bound).
	 *  1 = vanilla-like falloff, a level-N source covers ~N blocks. */
	public LightInit setFalloffPerBlock(int falloff) {
		this.falloffPerBlock = Math.clamp(falloff, 1, 7);
		return this;
	}

	/** Exponent of the fringe byte mapping (1.0 .. 2.0): 1.0 is byte-for-byte
	 *  vanilla-linear (brightest reach, hard edge); higher compresses only the
	 *  outermost rings so the edge dissolves into the ambient. */
	public LightInit setFringeCurve(double curve) {
		this.fringeCurve = Math.clamp(curve, 1.0, 2.0);
		return this;
	}

	/** Master switch of the colored-light render layer. When false only the
	 *  brightness channel of HLib light renders. */
	public LightInit setColoredLight(boolean enable) {
		this.enableColoredLight = enable;
		return this;
	}

	/** Whether the engine prints its startup banner and settle statistics. */
	public LightInit setStartupLog(boolean enable) {
		this.logStartupInfo = enable;
		return this;
	}

	/** Radius (blocks, 16 .. 1024) searched for the nearest registered HLib
	 *  source when resolving a cell's color fallback. */
	public LightInit setNearestSourceRadius(int radius) {
		this.nearestSourceRadius = Math.clamp(radius, 16, 1024);
		return this;
	}

	/** The dim illumination floor the fringe merges into (0.05 .. 2.0):
	 *  tint saturation = brightness / (ambientFloor + brightness). */
	public LightInit setAmbientFloor(double floor) {
		this.ambientFloor = Math.clamp(floor, 0.05, 2.0);
		return this;
	}

	/** Global multiplier on the tint saturation (0 .. 2; 1 = designed value,
	 *  0 = brightness only, no surface tinting). */
	public LightInit setTintStrength(double strength) {
		this.tintStrength = Math.clamp(strength, 0.0, 2.0);
		return this;
	}

	/** Exponent of the niris HDR extension curve (0.3 .. 2.0, niris build
	 *  only): smaller = small level changes become visible sooner. */
	public LightInit setNirisExtensionCurve(double curve) {
		this.nirisExtensionCurve = Math.clamp(curve, 0.3, 2.0);
		return this;
	}

	/** Whether HLib takes over the rendering of vanilla light content
	 *  (niris build only; see the ModMenu toggle of the same name). */
	public LightInit setTakeOverVanillaLighting(boolean takeOver) {
		this.takeOverVanillaLighting = takeOver;
		return this;
	}

	/** Whether overlapping differently-colored HLib lights blend smoothly at
	 *  render time instead of the dominant color painting the whole cell. */
	public LightInit setColorBlending(boolean enable) {
		this.enableColorBlending = enable;
		return this;
	}

	// ---- the build: solidify, persist, swap in, remesh ----

	/**
	 * Solidifies the chained entries into the live engine configuration:
	 * merges them over the current values, sanitizes, writes
	 * {@code config/lighthlib.json}, atomically swaps the engine's config
	 * singleton and requests a client remesh - then returns the immutable
	 * {@link GlobalLightConfig} snapshot (ModMenu shows the same values).
	 */
	public GlobalLightConfig build() {
		LHCEConfig live = LHCEConfig.get();
		LHCEConfig fresh = new LHCEConfig();
		// inherit every current entry (config file / ModMenu / previous init)
		fresh.nodeBudgetPerTick = live.nodeBudgetPerTick;
		fresh.falloffPerBlock = live.falloffPerBlock;
		fresh.fringeCurve = live.fringeCurve;
		fresh.enableColoredLight = live.enableColoredLight;
		fresh.logStartupInfo = live.logStartupInfo;
		fresh.nearestSourceRadius = live.nearestSourceRadius;
		fresh.ambientFloor = live.ambientFloor;
		fresh.tintStrength = live.tintStrength;
		fresh.nirisExtensionCurve = live.nirisExtensionCurve;
		fresh.takeOverVanillaLighting = live.takeOverVanillaLighting;
		fresh.enableColorBlending = live.enableColorBlending;
		// override with the explicitly chained entries
		if (this.nodeBudgetPerTick != null) {
			fresh.nodeBudgetPerTick = this.nodeBudgetPerTick;
		}
		if (this.falloffPerBlock != null) {
			fresh.falloffPerBlock = this.falloffPerBlock;
		}
		if (this.fringeCurve != null) {
			fresh.fringeCurve = this.fringeCurve;
		}
		if (this.enableColoredLight != null) {
			fresh.enableColoredLight = this.enableColoredLight;
		}
		if (this.logStartupInfo != null) {
			fresh.logStartupInfo = this.logStartupInfo;
		}
		if (this.nearestSourceRadius != null) {
			fresh.nearestSourceRadius = this.nearestSourceRadius;
		}
		if (this.ambientFloor != null) {
			fresh.ambientFloor = this.ambientFloor;
		}
		if (this.tintStrength != null) {
			fresh.tintStrength = this.tintStrength;
		}
		if (this.nirisExtensionCurve != null) {
			fresh.nirisExtensionCurve = this.nirisExtensionCurve;
		}
		if (this.takeOverVanillaLighting != null) {
			fresh.takeOverVanillaLighting = this.takeOverVanillaLighting;
		}
		if (this.enableColorBlending != null) {
			fresh.enableColorBlending = this.enableColorBlending;
		}
		String summary = LHCEConfig.applyAndSave(fresh);
		LightHLibImpl.requestRemesh();
		GlobalLightConfig built = GlobalLightConfig.current();
		if (GlobalLightConfig.isInitialized()) {
			LightHLib.LOGGER.warn(
					"[LHLib] global light config was already initialized once; this build() re-applied: {}",
					summary);
		} else {
			LightHLib.LOGGER.info("[LHLib] global light config built: {}", summary);
		}
		GlobalLightConfig.markInitialized();
		return built;
	}
}
