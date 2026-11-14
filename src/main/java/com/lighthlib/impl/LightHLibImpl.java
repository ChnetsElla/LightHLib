package com.lighthlib.impl;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import com.lighthlib.api.HLight;
import com.lighthlib.api.HLightBlockProperties;
import com.lighthlib.api.HLightRegistry;
import com.lighthlib.engine.LHCEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Internal wiring between the mod's entrypoints, the mixins and the engine.
 * Not part of the public API.
 */
public final class LightHLibImpl {
	private static final Map<Level, LHCEngine> ENGINES =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<LevelLightEngine, LHCEngine> BY_LIGHT_ENGINE = new ConcurrentHashMap<>();

	private LightHLibImpl() {
	}

	/** Duck-accessor implemented on {@code BlockBehaviour.Properties} via mixin. */
	public interface HLightPropertiesAccess {
		ToIntFunction<BlockState> lighthlib$getLightEmission();
	}

	public static LHCEngine engine(Level level) {
		return LHCEngine.forLevel(level);
	}

	public static LHCEngine engineForLightEngine(LevelLightEngine lightEngine) {
		return BY_LIGHT_ENGINE.get(lightEngine);
	}

	/** Called from the LevelChunk mixin on every block change. */
	public static void onBlockChanged(Level level, BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		engine(level).onBlockChanged(pos);
	}

	/** Called from the Block constructor mixin. */
	public static void onBlockConstructed(Block block, BlockBehaviour.Properties properties) {
		// a complete HLight source object bound via setHLightEntity wins over
		// every other staged component: emission, color and ID signature all
		// come from the HLight
		HLight bound = ((HLightBlockProperties) properties).lighthlib$getHLight();
		if (bound != null) {
			HLightRegistry.bindHLight(block, bound);
			// staged per-source overrides (HFalloffPerBlock / HIntensityCurve /
			// ...) apply on top of the HLight's own set as well
			HLightRegistry.Pending staged = HLightRegistry.pending();
			if (staged != null && staged.overrides() != null) {
				HLightRegistry.bindOverrides(block, staged.overrides());
			}
			HLightRegistry.clearPending();
			return;
		}
		HLightRegistry.Pending pending = HLightRegistry.pending();
		if (pending == null) {
			return;
		}
		ToIntFunction<BlockState> propsEmission =
				((HLightPropertiesAccess) properties).lighthlib$getLightEmission();

		if (pending.wrapper() != null && propsEmission == pending.wrapper()) {
			// Recommended usage: .lightLevel(LightHLib.HLIGHT(fn)) - state init has
			// already bound the emission; consume the staged color AND the
			// staged per-source overrides (falloff, curves, tint, ...).
			if (pending.color() != null) {
				HLightRegistry.bindColor(block, pending.color());
			}
			if (pending.overrides() != null) {
				HLightRegistry.bindOverrides(block, pending.overrides());
			}
			HLightRegistry.clearPending();
		} else if (pending.fn() != null) {
			// Emission staged but not installed through .lightLevel(...)
			// (pure component staging): bind the full-range emission directly,
			// plus any staged color and overrides.
			HLightRegistry.bindEmission(block, pending.fn(), pending.color());
			if (pending.overrides() != null) {
				HLightRegistry.bindOverrides(block, pending.overrides());
			}
			HLightRegistry.clearPending();
		} else if (pending.color() != null && HLightRegistry.isHLibBlock(block)) {
			// Color-only staging onto an HLib block (bound during state init).
			HLightRegistry.bindColor(block, pending.color());
			if (pending.overrides() != null) {
				HLightRegistry.bindOverrides(block, pending.overrides());
			}
			HLightRegistry.clearPending();
		} else if (pending.overrides() != null && HLightRegistry.isHLibBlock(block)) {
			// Override-only staging onto an HLib block (bound during state init).
			HLightRegistry.bindOverrides(block, pending.overrides());
			HLightRegistry.clearPending();
		}
	}

	public static void onChunkLoad(Level level, ChunkPos chunkPos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		engine(level).onChunkLoad(chunkPos);
	}

	public static void onChunkUnload(Level level, ChunkPos chunkPos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		engine(level).onChunkUnload(chunkPos);
	}

	public static void tickLevel(Level level) {
		engine(level).tick();
	}

	public static void onServerLevelLoad(MinecraftServer server, ServerLevel level) {
		BY_LIGHT_ENGINE.put(level.getLightEngine(), engine(level));
	}

	public static void onServerLevelUnload(MinecraftServer server, ServerLevel level) {
		BY_LIGHT_ENGINE.remove(level.getLightEngine());
	}

	public static void requestRelight(Level level, BlockPos pos) {
		engine(level).onBlockChanged(pos);
	}

	public static void requestRescanAll(Level level) {
		engine(level).requestRescanAll();
	}

	/** Registered by the client entrypoint: schedules a remesh of all nearby
	 *  sections on the render thread. Null on a dedicated server. */
	public static volatile Runnable remeshRequester;

	/** Requests a client remesh (engine-side + render-side config changes),
	 *  no-op on a dedicated server or before the client entrypoint ran. */
	public static void requestRemesh() {
		Runnable requester = remeshRequester;
		if (requester != null) {
			requester.run();
		}
	}
}
