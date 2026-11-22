package com.lighthlib.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu entrypoint. This class (and only this class) references ModMenu;
 * without ModMenu installed the entrypoint simply never loads.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return (ConfigScreenFactory<LHConfigScreen>) LHConfigScreen::new;
	}
}
