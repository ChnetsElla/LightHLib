package com.lighthlib.client.compat;

import com.lighthlib.engine.LHCEConfig;

/**
 * Bridge between the config screen's editable fields and {@link LHCEConfig}.
 * Kept separate so the Screen class itself stays free of engine imports.
 */
final class LHCEConfigHolder {
	private LHCEConfigHolder() {
	}

	static void loadInto(LHConfigScreen screen) {
		LHCEConfig config = LHCEConfig.get();
		screen.nodeBudgetPerTick = config.nodeBudgetPerTick;
		screen.falloffPerBlock = config.falloffPerBlock;
		screen.fringeCurve = config.fringeCurve;
		screen.enableColoredLight = config.enableColoredLight;
		screen.logStartupInfo = config.logStartupInfo;
		screen.nearestSourceRadius = config.nearestSourceRadius;
		screen.ambientFloor = config.ambientFloor;
		screen.tintStrength = config.tintStrength;
		screen.nirisExtensionCurve = config.nirisExtensionCurve;
		screen.takeOverVanillaLighting = config.takeOverVanillaLighting;
	}

	static void saveFrom(LHConfigScreen screen) {
		LHCEConfig fresh = new LHCEConfig();
		fresh.nodeBudgetPerTick = screen.nodeBudgetPerTick;
		fresh.falloffPerBlock = screen.falloffPerBlock;
		fresh.fringeCurve = screen.fringeCurve;
		fresh.enableColoredLight = screen.enableColoredLight;
		fresh.logStartupInfo = screen.logStartupInfo;
		fresh.nearestSourceRadius = screen.nearestSourceRadius;
		fresh.ambientFloor = screen.ambientFloor;
		fresh.tintStrength = screen.tintStrength;
		fresh.nirisExtensionCurve = screen.nirisExtensionCurve;
		fresh.takeOverVanillaLighting = screen.takeOverVanillaLighting;
		LHCEConfig.applyAndSave(fresh);
		SectionRefresher.refresh();
	}
}
