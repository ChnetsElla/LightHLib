package com.lighthlib.client.render;

import com.lighthlib.api.HLightMath;
import com.lighthlib.engine.LHCEngine;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Thread-local render context + the LHCE colored-light multiplies.
 *
 * <p>Two contexts are tracked: the block being tesselated (opened around
 * {@code ModelBlockRenderer.tesselateBlock} / Indigo's replacement of it) and
 * the entity being submitted (opened around
 * {@code EntityRenderDispatcher.submit}). Both the vanilla terrain path and
 * the Fabric Renderer API path multiply final vertex colors by the LHCE color
 * resolved at those positions; the render-state entity pipeline bakes the
 * tint into {@code SubmitNodeCollector.submitModel}'s color argument.</p>
 */
public final class RenderTintContext {
	/** [BlockAndTintGetter, BlockPos] of the block being tesselated. */
	private static final ThreadLocal<Object[]> CONTEXT = new ThreadLocal<>();
	/** Block position of the entity currently being submitted. */
	private static final ThreadLocal<BlockPos> ENTITY_POS = new ThreadLocal<>();

	private RenderTintContext() {
	}

	/** Opens the render context for the current thread (one block at a time). */
	public static void begin(BlockAndTintGetter level, BlockPos pos) {
		CONTEXT.set(new Object[]{level, pos});
	}

	/** Closes the render context for the current thread. */
	public static void end() {
		CONTEXT.remove();
	}

	/**
	 * True when the tessellated block or its light-sample cell itself emits
	 * vanilla light: the surface of a vanilla (or third-party) light source
	 * keeps its own color inside an LHCE field instead of being cross-tinted.
	 */
	private static boolean isVanillaLightSource(Level level, BlockPos pos, Direction face) {
		if (level.getBlockState(pos).getLightEmission() > 0) {
			return true;
		}
		return level.getBlockState(pos.relative(face)).getLightEmission() > 0;
	}

	/**
	 * Vanilla-path multiply: multiplies the final quad colors by the LHCE
	 * color at the context's block position; no-op when no context is open
	 * or nothing applies.
	 */
	public static void tint(QuadInstance instance, BakedQuad quad) {
		Object[] context = CONTEXT.get();
		if (context == null) {
			return;
		}
		BlockPos pos = (BlockPos) context[1];
		Level realLevel = ((BlockAndTintGetter) context[0]) instanceof Level direct ? direct : LHCEngine.clientLevel();
		if (realLevel == null) {
			return;
		}
		if (isVanillaLightSource(realLevel, pos, quad.direction())) {
			return;
		}
		LHCEngine engine = LHCEngine.forLevel(realLevel);
		int color = engine.renderColor(pos);
		if (color == HLightMath.WHITE_RGB && quad != null) {
			// opaque surfaces of low-level fields carry no light in their own
			// cell (full dampening); the face is actually lit by the adjacent
			// cell, so sample the face's direction offset once more
			color = engine.renderColor(pos.relative(quad.direction()));
		}
		if (color == HLightMath.WHITE_RGB) {
			return;
		}
		instance.multiplyColor(0xFF000000 | color);
	}

	/**
	 * The LHCE color multiply on the Fabric Renderer API (Indigo) terrain path:
	 * called at transform's RETURN, after shade/tint/AO contributions are
	 * final and the quad is about to be emitted.
	 *
	 * <p>The tint's saturation follows each vertex's (boosted) block-light byte,
	 * and a vanilla-dominance gate applies it per vertex: only vertices whose
	 * byte exceeds what vanilla block light at this position can explain are
	 * tinted, so vanilla sources (torches, glowstone) keep their own color even
	 * inside a field.</p>
	 */
	public static void tintFapiQuad(MutableQuadView quad) {
		Object[] context = CONTEXT.get();
		if (context == null) {
			return;
		}
		BlockPos pos = (BlockPos) context[1];
		Level realLevel = ((BlockAndTintGetter) context[0]) instanceof Level direct ? direct : LHCEngine.clientLevel();
		if (realLevel == null) {
			return;
		}
		if (isVanillaLightSource(realLevel, pos, quad.lightFace())) {
			return;
		}
		LHCEngine engine = LHCEngine.forLevel(realLevel);
		int color = engine.renderColor(pos);
		if (color == HLightMath.WHITE_RGB) {
			// opaque surfaces of low-level fields carry no light in their own
			// cell (full dampening); the face is actually lit by the adjacent
			// cell, so sample the quad's light face offset once more
			color = engine.renderColor(pos.relative(quad.lightFace()));
		}
		if (color == HLightMath.WHITE_RGB) {
			return;
		}
		int vanillaByte = realLevel.getBrightness(LightLayer.BLOCK, pos) << 4;
		for (int i = 0; i < 4; i++) {
			int b = quad.lightmap(i) & 0xFF;
			if (b > vanillaByte) {
				int tint = ambientMergeTint(color, b, pos, engine);
				if (tint != -1) {
					quad.color(i, ARGB.multiply(quad.color(i), tint));
				}
			}
		}
	}

	/**
	 * Ambient-merge falloff: the tint's saturation equals the LHCE light's
	 * SHARE of the total illumination - near full saturation wherever the
	 * colored light dominates (its brightness far exceeds the ambient floor),
	 * easing off only in the faint fringe where colored light and ambient are
	 * comparable, so the color dissolves into the ambient exactly where the
	 * light itself does. No early fade, no hard saturated boundary.
	 */
	private static int ambientMergeTint(int color, int lightByte, BlockPos pos, LHCEngine engine) {
		float brightness = blockBrightness(lightByte);
		if (brightness <= 0.0F) {
			return -1;
		}
		// per-source overrides of the nearest source win; null fields fall
		// back to the global LHCEConfig values
		float ambientFloor = (float) engine.effectiveAmbientFloor(pos);
		float tintStrength = (float) engine.effectiveTintStrength(pos);
		float share = brightness / (ambientFloor + brightness);
		// a per-source color curve fades the tint saturation along the field
		// (curve input: the cell's distance ratio; 1.0 = full share)
		LHCEngine.CellCurve curve = engine.cellCurve(pos.asLong());
		if (curve != null && curve.colorCurve() != null) {
			share *= (float) curve.colorCurve().evaluate(curve.distanceRatio());
		}
		share = (float) Math.min(1.0, share * tintStrength);
		int r = 255 - (int) ((255 - ARGB.red(color)) * share);
		int g = 255 - (int) ((255 - ARGB.green(color)) * share);
		int b = 255 - (int) ((255 - ARGB.blue(color)) * share);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Java replica of the (niris) lightmap block-brightness curve. */
	private static float blockBrightness(int lightByte) {
		if (lightByte <= 0) {
			return 0.0F;
		}
		if (lightByte <= 240) {
			float vanilla = lightByte / (960.0F - 3.0F * lightByte);
			return Math.max(vanilla, lightByte * 0.0019F);
		}
		return (float) Math.min(7.4, Math.pow(1.284, (lightByte - 240) * (8.0 / 15.0)));
	}

	/**
	 * The LHCE tint (with distance falloff) for a vanilla quad particle being
	 * extracted at a world position, or -1 (identity) when it is outside
	 * colored light or vanilla block light dominates there. Same gates as
	 * the entity path ({@link #tintAt}); the caller (the SingleQuadParticle
	 * mixin, which can access the particle's protected fields) multiplies the
	 * quad's rCol/gCol/bCol with it.
	 */
	public static int particleTint(double x, double y, double z) {
		return tintAt(BlockPos.containing(x, y, z));
	}

	/** Opens/closes the entity tint context around an entity submit. */
	public static void beginEntity(BlockPos pos) {
		ENTITY_POS.set(pos);
	}

	public static void endEntity() {
		ENTITY_POS.remove();
	}

	/** Camera world position of the most recent entity submit window
	 *  (render thread; refreshed per frame while any entity submits). */
	private static volatile Vec3 lastCameraPos;

	/** Called from the entity dispatcher window to record the camera position
	 *  that the (camera-relative) submit coordinates are relative to. */
	public static void updateCameraPos(Vec3 pos) {
		if (pos != null) {
			lastCameraPos = new Vec3(pos.x, pos.y, pos.z);
		}
	}

	/**
	 * The LHCE tint (with distance falloff) for the entity currently being
	 * submitted, or -1 (multiply-identity) when it is outside colored light
	 * or in a region where vanilla block light dominates. Same gates as the
	 * terrain path.
	 */
	public static int entityTint() {
		BlockPos pos = ENTITY_POS.get();
		if (pos == null) {
			return -1;
		}
		return tintAt(pos);
	}

	/**
	 * The LHCE tint for a deferred item node, resolved at flush time: the
	 * node's pose is camera-relative, so the item's world position is the
	 * stashed camera position plus the pose translation. GUI items (and
	 * anything without a world context) keep vanilla colors.
	 */
	public static int itemNodeTint(ItemDisplayContext displayContext, PoseStack.Pose pose) {
		if (displayContext == ItemDisplayContext.GUI || displayContext == ItemDisplayContext.NONE) {
			return -1;
		}
		Vec3 camera = lastCameraPos;
		if (camera == null) {
			return -1;
		}
		Matrix4f matrix = pose.pose();
		return tintAt(BlockPos.containing(
				camera.x + matrix.m30(),
				camera.y + matrix.m31(),
				camera.z + matrix.m32()));
	}

	/** Shared LHCE tint resolution at a world position; -1 (identity) when the
	 *  position is outside colored light or vanilla light dominates there. */
	private static int tintAt(BlockPos pos) {
		Level realLevel = LHCEngine.clientLevel();
		if (realLevel == null) {
			return -1;
		}
		LHCEngine engine = LHCEngine.forLevel(realLevel);
		int h = engine.getHLight(pos);
		if (h <= 0) {
			return -1;
		}
		int color = engine.renderColor(pos);
		if (color == HLightMath.WHITE_RGB) {
			return -1;
		}
		int vanillaByte = realLevel.getBrightness(LightLayer.BLOCK, pos) << 4;
		int byte01 = engine.blockByteAt(pos.asLong(), h);
		if (byte01 <= vanillaByte) {
			return -1;
		}
		return ambientMergeTint(color, byte01, pos, engine);
	}
}
