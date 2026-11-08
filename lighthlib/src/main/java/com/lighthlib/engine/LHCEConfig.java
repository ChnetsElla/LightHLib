package com.lighthlib.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.lighthlib.api.HLightMath;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Runtime configuration for LightHLib / LHCE, stored at
 * {@code config/lighthlib.json}.
 *
 * <p>All fields are public and mutable; the file is only read once at startup
 * and written back when it does not exist yet.</p>
 */
public final class LHCEConfig {
	/** Max propagation work-units per tick, per level. Larger = faster light
	 *  settling, more main-thread cost. */
	public int nodeBudgetPerTick = 1048576;

	/** 16-bit levels subtracted per block of travel through air - the exact
	 *  vanilla falloff logic scaled to 65535 levels: with the default of 1 a
	 *  source covers `level` blocks (level 50 -> 50 blocks, level 65535 can
	 *  span the loaded world, settled gradually by the tick budget). Raise
	 *  this to trade coverage for performance (e.g. 16 gives vanilla-like
	 *  15-block ranges). */
	public int falloffPerBlock = 1;

	/** Exponent of the fringe byte mapping (see HLightMath#smoothBlockByte):
	 *  1.0 is byte-for-byte vanilla-linear ({@code byte = level * 16}) -
	 *  brightest reach, but the field ends in a hard one-block staircase;
	 *  higher values compress only the outermost rings so the edge dissolves
	 *  into the ambient, at the cost of a slightly faster visible falloff.
	 *  Default 1.2: vanilla-like falloff (within ~one level through the
	 *  fringe) with a softened edge. Clamped to [1.0, 2.0]. */
	public double fringeCurve = 1.2;

	/** Whether LHCE's colored-light layer is active. When on, surfaces lit by
	 *  colored HLib light have their vertex colors multiplied by the source's
	 *  RGB (100% of the configured color, per position, without affecting
	 *  vanilla light elsewhere). */
	public boolean enableColoredLight = true;

	/** Print a startup banner with engine statistics. */
	public boolean logStartupInfo = true;

	/** Radius (blocks) searched for the nearest registered HLib source when
	 *  resolving a cell's color and the flood's provenance map has no entry
	 *  (white / stale). Larger = more robust color at field edges, more
	 *  lookup cost. Clamped to [16, 1024]. */
	public int nearestSourceRadius = 128;

	/** The dim illumination floor the fringe merges into (night ambient):
	 *  the tint's saturation is {@code brightness / (ambientFloor +
	 *  brightness)}, so higher values make colored light dominate its color
	 *  over the ambient further out (more saturated, wider), lower values
	 *  fade the color earlier. Clamped to [0.05, 2.0]. */
	public double ambientFloor = 0.20;

	/** Global multiplier on the tint saturation (1.0 = designed value).
	 *  0 disables surface tinting of colored light while keeping brightness;
	 *  > 1 pushes colors toward full source saturation. Clamped to
	 *  [0.0, 2.0]. */
	public double tintStrength = 1.0;

	/** Exponent of the niris HDR extension curve (bytes 241..255):
	 *  {@code extension = 15 * t^curve}. Lower = small level changes become
	 *  visible sooner (reaches full 7.4x earlier); higher = the
	 *  over-bright range stretches out. Only used by the niris build.
	 *  Clamped to [0.3, 2.0]. */
	public double nirisExtensionCurve = 0.7;

	/** Whether HLib takes over the rendering of vanilla light content
	 *  (niris build only). When false (default), lightmap bytes 0..240 -
	 *  the entire vanilla range - render exactly vanilla (stepped 16-level
	 *  curve, warm BlockLightTint, 1.0 output clamp); installing the mod
	 *  with no HLib source placed changes nothing vanilla. When true, HLib's
	 *  enhanced lightmap applies to vanilla content too: continuous curve,
	 *  lifted fringe, neutral-white tint and HDR output headroom (brighter,
	 *  whiter torch light). Toggleable at runtime via the ModMenu screen,
	 *  {@code /hl reload} or {@code LightHLib.HLibAccessToOriginalContent}. */
	public boolean takeOverVanillaLighting = false;

	private static volatile LHCEConfig instance;

	public static LHCEConfig get() {
		LHCEConfig local = instance;
		if (local == null) {
			synchronized (LHCEConfig.class) {
				local = instance;
				if (local == null) {
					local = load();
					instance = local;
				}
			}
		}
		return instance;
	}

	private static LHCEConfig load() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path path = FabricLoader.getInstance().getConfigDir().resolve("lighthlib.json");
		LHCEConfig config = new LHCEConfig();
		if (Files.exists(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				LHCEConfig read = gson.fromJson(json, LHCEConfig.class);
				if (read != null) {
					config = read;
				}
			} catch (Exception e) {
				LightHLibEngineLog.LOGGER.warn("Could not read lighthlib.json, using defaults", e);
			}
		} else {
			try {
				Files.createDirectories(path.getParent());
				Files.writeString(path, gson.toJson(config), StandardCharsets.UTF_8);
			} catch (IOException e) {
				LightHLibEngineLog.LOGGER.warn("Could not write default lighthlib.json", e);
			}
		}
		config.sanitize();
		return config;
	}

	/** Applies {@code fresh} as the live configuration: sanitizes it, writes it
	 *  back to {@code config/lighthlib.json} and replaces the singleton.
	 *  Returns a short summary of the new values. Used by the ModMenu config
	 *  screen. */
	public static String applyAndSave(LHCEConfig fresh) {
		fresh.sanitize();
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path path = FabricLoader.getInstance().getConfigDir().resolve("lighthlib.json");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, gson.toJson(fresh), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LightHLibEngineLog.LOGGER.warn("Could not write lighthlib.json", e);
		}
		instance = fresh;
		return "nodeBudget=" + fresh.nodeBudgetPerTick + ", falloff=" + fresh.falloffPerBlock
				+ ", fringeCurve=" + fresh.fringeCurve + ", nearestSourceRadius=" + fresh.nearestSourceRadius
				+ ", ambientFloor=" + fresh.ambientFloor + ", tintStrength=" + fresh.tintStrength
				+ ", nirisExtensionCurve=" + fresh.nirisExtensionCurve
				+ ", enableColoredLight=" + fresh.enableColoredLight
				+ ", takeOverVanillaLighting=" + fresh.takeOverVanillaLighting;
	}

	private void sanitize() {
		this.nodeBudgetPerTick = Math.clamp(this.nodeBudgetPerTick, 1024, 1 << 22);
		this.falloffPerBlock = Math.clamp(this.falloffPerBlock, 1, 4096);
		this.fringeCurve = Math.clamp(this.fringeCurve, 1.0, 2.0);
		this.nearestSourceRadius = Math.clamp(this.nearestSourceRadius, 16, 1024);
		this.ambientFloor = Math.clamp(this.ambientFloor, 0.05, 2.0);
		this.tintStrength = Math.clamp(this.tintStrength, 0.0, 2.0);
		this.nirisExtensionCurve = Math.clamp(this.nirisExtensionCurve, 0.3, 2.0);
	}

	/** Reloads {@code config/lighthlib.json} at runtime, applying sanitized
	 *  values to the live instance. Returns a short summary of the new values
	 *  for feedback. Used by {@code /hl reload}. */
	public static String reload() {
		LHCEConfig fresh = load();
		instance = fresh;
		return "nodeBudget=" + fresh.nodeBudgetPerTick + ", falloff=" + fresh.falloffPerBlock
				+ ", fringeCurve=" + fresh.fringeCurve + ", nearestSourceRadius=" + fresh.nearestSourceRadius
				+ ", ambientFloor=" + fresh.ambientFloor + ", tintStrength=" + fresh.tintStrength
				+ ", nirisExtensionCurve=" + fresh.nirisExtensionCurve
				+ ", enableColoredLight=" + fresh.enableColoredLight
				+ ", takeOverVanillaLighting=" + fresh.takeOverVanillaLighting;
	}
}
