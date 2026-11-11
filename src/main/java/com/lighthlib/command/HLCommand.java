package com.lighthlib.command;

import com.lighthlib.LightHLib;
import com.lighthlib.api.HLightMath;
import com.lighthlib.api.HLightRegistry;
import com.lighthlib.api.LightCurve;
import com.lighthlib.api.MathCurveFunction;
import com.lighthlib.block.HLTestBlockEntity;
import com.lighthlib.engine.LHCEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Debug / test commands of LightHLib.
 *
 * <pre>
 * /hl testblock setlightlevel [level] ["#RRGGBB"]  - sets the looked-at test block's 16-bit
 *                                                    level (default 50) and optional light color;
 *                                                    the block is found by looking at it or by
 *                                                    standing within 64 blocks of one
 * /hl Curve setLightCurveIntensity <preset>       - sets the looked-at test block's intensity
 *                                                    attenuation preset (case-insensitive)
 * /hl Curve setLightCurveColor <preset>           - sets the looked-at test block's color
 *                                                    attenuation preset
 * /hl Curve setLightCurve <preset>                - sets both intensity and color to <preset>
 *                                                    preset: VANILLA/LINEAR/SMOOTH_STEP/
 *                                                    LASER_EXPONENTIAL/EXPONENTIAL/MAGIC_EASE/
 *                                                    EASE_IN_OUT/CONSTANT/INVERSE_SQUARE/
 *                                                    GAUSSIAN/STEPPED, none/off = clear
 * /hl query                                        - shows the 16-bit light at the player
 * /hl spawncheck                                   - spawn-gate readout at the player position
 * /hl info                                         - engine statistics
 * /hl reload                                       - re-reads config/lighthlib.json at runtime
 * </pre>
 */
public final class HLCommand {
	/** Sentinel returned by {@link #resolveCurve} on an unknown name. */
	private static final MathCurveFunction CURVE_UNKNOWN = t -> -1.0;

	private HLCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
								CommandBuildContext context,
								Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("hl")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("testblock")
						.then(Commands.literal("setlightlevel")
								.executes(ctx -> setTestBlockLevel(ctx.getSource(),
										LightHLib.TEST_BLOCK_DEFAULT_LEVEL, null))
								.then(Commands.argument("level", IntegerArgumentType.integer(0, HLightMath.MAX_HLIGHT))
										.executes(ctx -> setTestBlockLevel(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "level"), null))
										.then(Commands.argument("color", StringArgumentType.string())
												.executes(ctx -> setTestBlockLevel(ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "level"),
														StringArgumentType.getString(ctx, "color")))))))
				.then(Commands.literal("query")
						.executes(ctx -> query(ctx.getSource())))
				.then(curveBranch(Commands.literal("Curve")))
				.then(curveBranch(Commands.literal("curve")))
				.then(Commands.literal("spawncheck")
						.executes(ctx -> spawnCheck(ctx.getSource())))
				.then(Commands.literal("info")
						.executes(ctx -> info(ctx.getSource())))
				.then(Commands.literal("reload")
						.executes(ctx -> reload(ctx.getSource())))
				.then(Commands.literal("version")
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() ->
									Component.literal("LightHLib " + LightHLib.VERSION
											+ " - 16-bit light channel (max "
											+ HLightMath.MAX_HLIGHT + ")"), false);
							return 1;
						})));
	}

	private static int setTestBlockLevel(CommandSourceStack source, int level, String colorHex)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level_ = player.level();
		BlockPos pos = findLookedAtTestBlock(player, level_);
		if (pos == null) {
			source.sendFailure(Component.literal(
					"No LightHLib test block found: look at one, or stand within 64 blocks of one"));
			return 0;
		}
		BlockEntity be = level_.getBlockEntity(pos);
		if (!(be instanceof HLTestBlockEntity testBlockEntity)) {
			source.sendFailure(Component.literal("The block at " + pos.toShortString()
					+ " is not a LightHLib test block"));
			return 0;
		}
		int color = testBlockEntity.getColorValue();
		if (colorHex != null) {
			try {
				color = HLightMath.parseHexColor(colorHex);
			} catch (IllegalArgumentException e) {
				source.sendFailure(Component.literal(e.getMessage()));
				return 0;
			}
		}
		boolean colorChanged = color != testBlockEntity.getColorValue();
		if (colorChanged) {
			testBlockEntity.setColorValue(color);
			LightHLib.setSourceColor(level_, pos, color);
			if (level == testBlockEntity.getLevelValue()) {
				// color-only change: collapse + re-flood so the provenance
				// colors are repainted with the new source color
				LightHLib.forceReflood(level_, pos);
			} else {
				testBlockEntity.setLevelValue(level);
			}
		} else {
			testBlockEntity.setLevelValue(level);
		}
		testBlockEntity.setChanged();
		// relight through LHCE + sync the block entity data to nearby players
		LightHLib.requestRelight(level_, pos);
		BlockState state = level_.getBlockState(pos);
		level_.sendBlockUpdated(pos, state, state, 3);
		ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(testBlockEntity);
		for (ServerPlayer nearby : level_.players()) {
			if (nearby.blockPosition().distSqr(pos) <= 64 * 64) {
				nearby.connection.send(packet);
			}
		}
		source.sendSuccess(() -> Component.literal("Test block at " + pos.toShortString()
				+ " now emits " + level + " / " + HLightMath.MAX_HLIGHT
				+ " (display " + Math.round(HLightMath.displayBrightness(level) * 100.0F) + "% brightness, "
				+ "coverage ~" + coverageBlocks(level) + " blocks, color "
				+ HLightMath.formatHexColor(testBlockEntity.getColorValue()) + ")"), true);
		return 1;
	}

	private static int coverageBlocks(int level) {
		int f = Math.max(1, com.lighthlib.engine.LHCEConfig.get().falloffPerBlock);
		// vanilla falloff logic: one level per block, so coverage = level
		return Math.max(0, level / f);
	}

	private static BlockPos findLookedAtTestBlock(ServerPlayer player, ServerLevel level) {
		// 1) the block the player looks at - generous 64-block reach so distant
		//    (but visible, since the test block renders emissively) blocks can be targeted
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		Vec3 end = eye.add(look.scale(64.0D));
		BlockHitResult hit = level.clip(new ClipContext(eye, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.BLOCK
				&& level.getBlockEntity(hit.getBlockPos()) instanceof HLTestBlockEntity) {
			return hit.getBlockPos();
		}
		// 2) nearest test block in loaded chunks within 64 blocks: iterate each
		//    chunk's block-entity map instead of probing every position
		BlockPos base = player.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		int minCx = (base.getX() - 64) >> 4;
		int maxCx = (base.getX() + 64) >> 4;
		int minCz = (base.getZ() - 64) >> 4;
		int maxCz = (base.getZ() + 64) >> 4;
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (!(chunk instanceof LevelChunk levelChunk)) {
					continue;
				}
				for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
					if (blockEntity instanceof HLTestBlockEntity
							&& blockEntity.getBlockPos().distSqr(base) < bestDist) {
						bestDist = blockEntity.getBlockPos().distSqr(base);
						best = blockEntity.getBlockPos();
					}
				}
			}
		}
		return best;
	}

	/** Builds the {@code /hl Curve} (or lowercase alias) sub-command tree. */
	private static LiteralArgumentBuilder<CommandSourceStack> curveBranch(
			LiteralArgumentBuilder<CommandSourceStack> branch) {
		return branch
				.then(Commands.literal("setLightCurveIntensity")
						.then(Commands.argument("preset", StringArgumentType.string())
								.executes(ctx -> setLightCurve(ctx.getSource(),
										StringArgumentType.getString(ctx, "preset"), true, false))))
				.then(Commands.literal("setLightCurveColor")
						.then(Commands.argument("preset", StringArgumentType.string())
								.executes(ctx -> setLightCurve(ctx.getSource(),
										StringArgumentType.getString(ctx, "preset"), false, true))))
				.then(Commands.literal("setLightCurve")
						.then(Commands.argument("preset", StringArgumentType.string())
								.executes(ctx -> setLightCurve(ctx.getSource(),
										StringArgumentType.getString(ctx, "preset"), true, true))));
	}

	/** Resolves a preset name (case-insensitive, 12 names incl. aliases).
	 *  {@code none}/{@code off} resolves to null (clear); the sentinel
	 *  {@link #CURVE_UNKNOWN} means "not a known preset". */
	private static MathCurveFunction resolveCurve(String name) {
		String key = name.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (key) {
			case "none", "off" -> null;
			case "vanilla", "linear" -> LightCurve.VANILLA;
			case "smooth_step" -> LightCurve.SMOOTH_STEP;
			case "laser_exponential", "exponential" -> LightCurve.LASER_EXPONENTIAL;
			case "magic_ease", "ease_in_out" -> LightCurve.MAGIC_EASE;
			case "constant" -> LightCurve.CONSTANT;
			case "inverse_square" -> LightCurve.INVERSE_SQUARE;
			case "gaussian" -> LightCurve.GAUSSIAN;
			case "stepped" -> LightCurve.STEPPED;
			default -> CURVE_UNKNOWN;
		};
	}

	/** Applies an intensity and/or color curve preset to the looked-at test
	 *  block's registered light source, then collapses and re-floods it. */
	private static int setLightCurve(CommandSourceStack source, String presetName,
			boolean setIntensity, boolean setColor)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		MathCurveFunction curve = resolveCurve(presetName);
		if (curve == CURVE_UNKNOWN) {
			source.sendFailure(Component.literal("Unknown curve preset '" + presetName
					+ "': use VANILLA/LINEAR/SMOOTH_STEP/LASER_EXPONENTIAL/EXPONENTIAL/"
					+ "MAGIC_EASE/EASE_IN_OUT/CONSTANT/INVERSE_SQUARE/GAUSSIAN/STEPPED, "
					+ "or none/off to clear"));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level_ = player.level();
		BlockPos pos = findLookedAtTestBlock(player, level_);
		if (pos == null) {
			source.sendFailure(Component.literal(
					"No LightHLib test block found: look at one, or stand within 64 blocks of one"));
			return 0;
		}
		// replace the curve fields outright (NOT merge: merge keeps a null
		// "fall back" field, so "none" could never clear a bound curve), but
		// keep the other fields (falloff, tint, ...) from the current set
		net.minecraft.world.level.block.Block block = level_.getBlockState(pos).getBlock();
		HLightRegistry.Overrides current = HLightRegistry.hLightOverrides(block);
		HLightRegistry.Overrides next = new HLightRegistry.Overrides(
				current != null ? current.falloff() : null,
				current != null ? current.fringeCurve() : null,
				current != null ? current.tintStrength() : null,
				current != null ? current.ambientFloor() : null,
				current != null ? current.nearestSourceRadius() : null,
				setIntensity ? curve : null,
				setColor ? curve : null);
		HLightRegistry.setOverrides(block, next);

		// the re-flood has to reach BOTH engines: the server one directly,
		// and the client one (rendering reads the client engine's field).
		// sendBlockUpdated alone does NOT reach the client engine when the
		// block state is unchanged (LevelChunk.setBlockState returns null and
		// the mixin hook is skipped), so we schedule a thread-safe reflood on
		// the client engine explicitly; its tick thread collapses and
		// re-floods with the newly bound registry curves.
		LightHLib.forceReflood(level_, pos);
		Level clientLevel = com.lighthlib.engine.LHCEngine.clientLevel();
		if (clientLevel != null) {
			com.lighthlib.engine.LHCEngine.forLevel(clientLevel).requestReflood(pos);
		}
		BlockState state = level_.getBlockState(pos);
		level_.sendBlockUpdated(pos, state, state, 3);

		String scope = setIntensity && setColor ? "intensity+color"
				: setIntensity ? "intensity" : "color";
		String presetText = curve == null ? "cleared (vanilla default)"
				: curve.toString() + " (" + presetName.trim() + ")";
		source.sendSuccess(() -> Component.literal("Test block at " + pos.toShortString()
				+ ": " + scope + " curve -> " + presetText), true);
		return 1;
	}

	/** Spawn-gate readout at the player position: the vanilla BLOCK light vs
	 *  the HLib 16-bit value and the boosted (curve-aware) nibble vanilla's
	 *  monster-spawn check would actually see. */
	private static int spawnCheck(CommandSourceStack source)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Level level = player.level();
		BlockPos pos = player.blockPosition();
		LHCEngine engine = LightHLib.LHCE(level);
		int h = engine.getHLight(pos);
		int vanillaBlock = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
		int boostedBlock = Math.max(vanillaBlock, engine.spawnNibbleAt(pos.asLong(), h));
		boolean tooBrightToSpawn = boostedBlock > 0;
		source.sendSuccess(() -> Component.literal(String.format(
				"Spawn gate at %s: vanilla BLOCK light %d/15, HLib light %d/%d, "
						+ "spawn-gate reads %d/15 -> monsters %s",
				pos.toShortString(), vanillaBlock, h, HLightMath.MAX_HLIGHT,
				boostedBlock, tooBrightToSpawn ? "CANNOT spawn (too bright)"
						: "can spawn (dark enough)")), false);
		return tooBrightToSpawn ? 1 : 0;
	}

	private static int query(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Level level = player.level();
		BlockPos pos = player.blockPosition();
		LHCEngine engine = LightHLib.LHCE(level);
		int h = engine.getHLight(pos);
		int color = engine.getColor(pos);
		int effective = engine.effectiveColor(pos);
		int vanillaBlock = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
		StringBuilder text = new StringBuilder(String.format(
				"HLight[%s] = %d / %d (color %s, effective %s, vanilla block light %d/15)",
				pos.toShortString(), h, HLightMath.MAX_HLIGHT, HLightMath.formatHexColor(color),
				HLightMath.formatHexColor(effective), vanillaBlock));
		// single-player diagnostics: the client engine is a separate instance
		// from this (server) one - comparing both pinpoints whether a color
		// issue lives in the engine data or in the render path
		Level clientLevel = LHCEngine.clientLevel();
		if (clientLevel != null) {
			LHCEngine clientEngine = LHCEngine.forLevel(clientLevel);
			text.append(String.format(" | client: h=%d, color=%s, effective=%s",
					clientEngine.getHLight(pos),
					HLightMath.formatHexColor(clientEngine.getColor(pos)),
					HLightMath.formatHexColor(clientEngine.effectiveColor(pos))));
			// curve diagnostics: what the client render hook would actually
			// sample at this position (cellCurves payload + resulting byte)
			LHCEngine.CellCurve cc = clientEngine.cellCurve(pos.asLong());
			int ch = clientEngine.getHLight(pos);
			if (cc != null) {
				text.append(String.format(
						" | client curve: intensity=%s, colorCurve=%s, centerByte=%d, distRatio=%.3f, byte=%d",
						cc.intensityCurve() != null ? cc.intensityCurve() : "none",
						cc.colorCurve() != null ? "yes" : "none",
						cc.centerByte(), cc.distanceRatio(), clientEngine.blockByteAt(pos.asLong(), ch)));
			} else {
				text.append(String.format(" | client curve: none, byte=%d (default fringe mapping)",
						clientEngine.blockByteAt(pos.asLong(), ch)));
			}
		}
		source.sendSuccess(() -> Component.literal(text.toString()), false);
		return h > 0 ? 1 : 0;
	}

	private static int reload(CommandSourceStack source) {
		// re-read config/lighthlib.json and apply sanitized values live; the
		// client remesh is requested right away so render-side mappings
		// (fringeCurve, tint strength, ambient floor) are visible immediately
		String summary;
		try {
			summary = com.lighthlib.engine.LHCEConfig.reload();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Reload failed: " + e.getMessage()));
			return 0;
		}
		com.lighthlib.impl.LightHLibImpl.requestRemesh();
		source.sendSuccess(() -> Component.literal(
				"lighthlib.json reloaded: " + summary), false);
		return 1;
	}

	private static int info(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		LHCEngine engine = LightHLib.LHCE(player.level());
		source.sendSuccess(() -> Component.literal(String.format(
				"LHCE: %d lit cells, %d sources, budget=%d/tick, falloff=%d/blk",
				engine.fieldSize(), engine.sourceCount(),
				com.lighthlib.engine.LHCEConfig.get().nodeBudgetPerTick,
				com.lighthlib.engine.LHCEConfig.get().falloffPerBlock)), false);
		return 1;
	}
}
