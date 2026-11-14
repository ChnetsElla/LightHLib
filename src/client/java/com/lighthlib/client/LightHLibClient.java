package com.lighthlib.client;

import com.lighthlib.client.compat.SectionRefresher;
import com.lighthlib.engine.LHCEngine;
import com.lighthlib.engine.LHCEConfig;
import com.lighthlib.impl.LightHLibImpl;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

/**
 * Client side of LightHLib:
 *
 * <ul>
 *   <li>ticks the LHCE engine of the current client level;</li>
 *   <li>feeds chunk load/unload events into the engine;</li>
 *   <li>marks sections dirty when the 16-bit field changes so meshes (and the
 *       boosted sub-texel light coordinates) are rebuilt.</li>
 * </ul>
 */
public class LightHLibClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LHCEConfig.get();
		Minecraft minecraft = Minecraft.getInstance();

		// render hooks may receive non-Level light getters (e.g. chunk meshing's
		// RenderSectionRegion); give them a way to resolve the current level
		LHCEngine.setClientLevelSupplier(() -> Minecraft.getInstance().level);

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
				LightHLibImpl.onChunkLoad(level, chunk.getPos()));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
				LightHLibImpl.onChunkUnload(level, chunk.getPos()));

		ClientTickEvents.END_LEVEL_TICK.register(level -> {
			LHCEngine engine = LightHLibImpl.engine(level);
			engine.setUpdateListener(LightHLibClient::markSectionsDirty);
			engine.tick();
		});

		// engine-side and render-side config changes (/hl reload, ModMenu) get
		// an immediate remesh instead of "press F3+A yourself"
		LightHLibImpl.remeshRequester = () -> {
			if (minecraft.isSameThread()) {
				SectionRefresher.refresh();
			} else {
				minecraft.execute(() -> SectionRefresher.refresh());
			}
		};
	}

	public static void markSectionsDirty(LongOpenHashSet sections) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		for (LongIterator it = sections.iterator(); it.hasNext(); ) {
			long packed = it.nextLong();
			level.setSectionDirtyWithNeighbors(
					SectionPos.x(packed),
					SectionPos.y(packed),
					SectionPos.z(packed));
		}
	}
}
