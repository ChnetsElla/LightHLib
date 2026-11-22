package com.lighthlib.client.compat;

import com.lighthlib.client.LightHLibClient;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

/**
 * Marks all non-empty sections of the loaded client chunks dirty after a
 * config change, so chunk meshes are rebuilt with the new lighting
 * parameters. Uses the same {@code setSectionDirtyWithNeighbors} path as
 * {@code LightHLibClient#markSectionsDirty}.
 */
public final class SectionRefresher {
	private SectionRefresher() {
	}

	public static void refresh() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		LongOpenHashSet sections = new LongOpenHashSet();
		// iterate a generous radius around the player's chunk
		int view = minecraft.options.renderDistance().get() + 1;
		int px = minecraft.player == null ? 0 : SectionPos.posToSectionCoord(minecraft.player.getX());
		int pz = minecraft.player == null ? 0 : SectionPos.posToSectionCoord(minecraft.player.getZ());
		int minY = SectionPos.blockToSectionCoord(level.getMinY());
		int maxY = SectionPos.blockToSectionCoord(level.getMinY() + level.getHeight() - 1);
		for (int cx = px - view; cx <= px + view; cx++) {
			for (int cz = pz - view; cz <= pz + view; cz++) {
				var chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				for (int y = minY; y <= maxY; y++) {
					int i = chunk.getSectionIndexFromSectionY(y);
					if (i < 0 || i >= chunk.getSections().length) {
						continue;
					}
					var section = chunk.getSections()[i];
					if (section != null && !section.hasOnlyAir()) {
						sections.add(SectionPos.asLong(cx, y, cz));
					}
				}
			}
		}
		LightHLibClient.markSectionsDirty(sections);
	}
}
