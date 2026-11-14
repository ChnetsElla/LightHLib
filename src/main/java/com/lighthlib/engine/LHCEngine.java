package com.lighthlib.engine;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import com.lighthlib.api.HLight;
import com.lighthlib.api.HLightMath;
import com.lighthlib.api.HLightRegistry;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * LHCE - the LightHLib Core Engine.
 *
 * <p>Client render hooks receive all kinds of light-accessor wrappers that
 * are not {@link Level} instances (chunk meshing passes
 * {@code RenderSectionRegion}, particle/model code may pass plain getters).
 * The client entrypoint therefore registers {@link #clientLevelSupplier} so
 * common-code hooks can resolve the current client level as a fallback.</p>
 *
 * <p>A parallel 16-bit block-light engine layered on top of the vanilla
 * 4-bit light engine. It maintains its own light field per {@link Level}
 * (so every dimension gets the 65535-level channel) and propagates HLib
 * light with the same proven increase/decrease flood algorithm vanilla uses,
 * scaled to 16 bits:</p>
 *
 * <ul>
 *   <li>Levels 0..65535 per block, attenuating by {@code falloffPerBlock}
 *       levels per block of travel (default 1), so higher sources are brighter
 *       and reach further.</li>
 *   <li>Propagation is budgeted per tick ({@code nodeBudgetPerTick}) so even
 *       world-spanning sources settle smoothly.</li>
 *   <li>Waves stop at unloaded chunks and resume automatically when chunks
 *       load (sources are re-flooded across borders).</li>
 *   <li>Per-source colors are carried through the field by provenance: the
 *       brightest contributor paints the cell.</li>
 * </ul>
 *
 * <p>Instances are thread-confined to the level's tick thread and obtained
 * through {@link #forLevel(Level)}.</p>
 */
public final class LHCEngine {
	/** Registered by the client entrypoint: supplies the current client level
	 *  for render hooks that receive non-Level light-accessor wrappers. */
	private static volatile Supplier<Level> clientLevelSupplier;

	/** The current client level, or null when absent / on a dedicated server. */
	@org.jetbrains.annotations.Nullable
	public static Level clientLevel() {
		Supplier<Level> supplier = clientLevelSupplier;
		return supplier == null ? null : supplier.get();
	}

	/** Called by the client entrypoint once, registers the client level supplier. */
	public static void setClientLevelSupplier(Supplier<Level> supplier) {
		clientLevelSupplier = supplier;
	}

	private static final Map<Level, LHCEngine> ENGINES =
			Collections.synchronizedMap(new WeakHashMap<>());

	/** Direction order matches vanilla's PROPAGATION_DIRECTIONS. */
	private static final Direction[] DIRECTIONS = Direction.values();

	// Queue entry packing: bits 0..2 = per-source falloff override (0 = none,
	// else 1..7), bits 3..8 = direction propagation mask (1 << ordinal), bit 9 =
	// from-emission flag, bits 10..25 = fromLevel (up to 65535), bits 26..41 =
	// the wave's source level (for the distance-ratio of attenuation curves),
	// bits 42..49 = the source's center light byte, bit 50 = has-curve flag,
	// bits 51..57 / 58..63 = interned intensity / color curve indices
	// (7+6 bits, sentinels 0x7F / 0x3F - the old 8-bit color index at shift 59
	// overran the 64-bit long and silently truncated above 31).
	private static final long FLAG_FROM_EMISSION = 1L << 9;
	private static final long FALLOFF_MASK = 0x7L;
	private static final int DIR_SHIFT = 3;
	private static final int LEVEL_SHIFT = 10;
	private static final long ALL_DIRS_MASK;
	private static final long LEVEL_MASK = ~((1L << LEVEL_SHIFT) - 1);
	private static final int RAW_SHIFT = 26;
	private static final int CENTER_SHIFT = 42;
	private static final long FLAG_HAS_CURVE = 1L << 50;
	private static final int INTENSITY_IDX_SHIFT = 51;
	private static final int COLOR_IDX_SHIFT = 58;
	/** All queue-entry bits above the fromLevel: the curve aux data a wave
	 *  carries from its source entry to every cell it writes. */
	private static final long AUX_MASK = ~((1L << RAW_SHIFT) - 1);

	/** Sections touched since the last settle: re-affirmed once at
	 *  quiescence, healing dirty marks dropped while a section was still
	 *  loading (world entry races). */
	private final LongOpenHashSet episodeSections = new LongOpenHashSet();
	/** A chunk (re)loaded since the last settle: verify all sources then. */
	private boolean settleArmed;
	/** Settle verification in progress (sources queued, awaiting drain). */
	private boolean settleVerifying;
	/** Ticks since a settle episode started (armed or verifying); forces the
	 *  episode to finish even when work never fully drains (e.g. a source that
	 *  re-queues itself every tick), so episode sections are re-affirmed. */
	private int settleTicks;
	/** Last settled field stats already logged: suppress repeat settle spam. */
	private int lastLoggedCells = -1;
	private int lastLoggedSources = -1;

	static {
		long mask = 0;
		for (Direction d : DIRECTIONS) {
			mask |= 1L << (DIR_SHIFT + d.ordinal());
		}
		ALL_DIRS_MASK = mask;
	}

	private static long increaseFromEmission(int level) {
		return ((long) level << LEVEL_SHIFT) | ALL_DIRS_MASK | FLAG_FROM_EMISSION;
	}

	private static long increaseFromEmission(int level, int falloff) {
		long e = increaseFromEmission(level);
		// zero is not a valid override (sanitized to >= 1): the packed falloff
		// field itself (bits 0..2) doubles as the presence flag
		return falloff > 0 ? (e & ~FALLOFF_MASK) | Math.min(falloff, 7) : e & ~FALLOFF_MASK;
	}

	/** Source emission entry with the full per-source curve aux block:
	 *  raw level, center byte and interned intensity/color curve indices.
	 *  {@code intensity}/{@code color} may be null (no curve override). */
	private long sourceEmissionEntry(int emission, int falloff, double fringeCurve,
			com.lighthlib.api.MathCurveFunction intensity, com.lighthlib.api.MathCurveFunction color) {
		long e = increaseFromEmission(emission, falloff);
		int centerByte = Math.max(0, Math.min(255,
				com.lighthlib.api.HLightMath.smoothBlockByte(emission, fringeCurve)));
		if (intensity == null && color == null) {
			return e;
		}
		int iIdx = intensity != null ? this.internIntensity(intensity) : 0x7F;
		int cIdx = color != null ? this.internColor(color) : 0x3F;
		return e | FLAG_HAS_CURVE
				| ((long) emission << RAW_SHIFT)
				| ((long) centerByte << CENTER_SHIFT)
				| ((long) iIdx << INTENSITY_IDX_SHIFT)
				| ((long) cIdx << COLOR_IDX_SHIFT);
	}

	private static long withFalloff(long entry, int falloff) {
		return falloff > 0
				? (entry & ~FALLOFF_MASK) | Math.min(falloff, 7)
				: entry & ~FALLOFF_MASK;
	}

	private static int entryFalloff(long entry) {
		return (int) (entry & FALLOFF_MASK);
	}

	private static boolean hasFalloff(long entry) {
		return (entry & FALLOFF_MASK) != 0;
	}

	private static long increaseSkipOne(int level, Direction skip, boolean falloffFlag, int falloff,
			long auxSource) {
		long e = ((long) level << LEVEL_SHIFT) | (ALL_DIRS_MASK & ~(1L << (DIR_SHIFT + skip.ordinal())))
				| (auxSource & AUX_MASK);
		return falloffFlag ? (e & ~FALLOFF_MASK) | Math.min(falloff, 7) : e & ~FALLOFF_MASK;
	}

	private static long increaseOnlyOne(int level, Direction only, boolean falloffFlag, int falloff,
			long auxSource) {
		long e = ((long) level << LEVEL_SHIFT) | (1L << (DIR_SHIFT + only.ordinal()))
				| (auxSource & AUX_MASK);
		return falloffFlag ? (e & ~FALLOFF_MASK) | Math.min(falloff, 7) : e & ~FALLOFF_MASK;
	}

	/** Decrease waves carry the provenance color of the light being removed in
	 *  the bits the increase-side curve aux would occupy (decrease entries use
	 *  none of them): 0 = not a removal wave (no runner-up pruning). */
	private static long decreaseAll(int fromLevel, int dyingColor) {
		return ((long) fromLevel << LEVEL_SHIFT) | ALL_DIRS_MASK
				| ((long) (dyingColor & 0xFFFFFF) << RAW_SHIFT);
	}

	private static long decreaseAll(int fromLevel) {
		return decreaseAll(fromLevel, 0);
	}

	private static long decreaseSkipOne(int fromLevel, Direction skip, int dyingColor) {
		return ((long) fromLevel << LEVEL_SHIFT) | (ALL_DIRS_MASK & ~(1L << (DIR_SHIFT + skip.ordinal())))
				| ((long) (dyingColor & 0xFFFFFF) << RAW_SHIFT);
	}

	private static long decreaseSkipOne(int fromLevel, Direction skip) {
		return decreaseSkipOne(fromLevel, skip, 0);
	}

	/** The provenance color of the light a decrease wave is removing (0 when
	 *  the wave removes nothing, e.g. the PULL_LIGHT_IN probe). */
	private static int entryDyingColor(long entry) {
		return (int) ((entry >>> RAW_SHIFT) & 0xFFFFFF);
	}

	private static int entryLevel(long entry) {
		// Mask to the 16 fromLevel bits (LEVEL_SHIFT..RAW_SHIFT-1): entries that
		// carry the curve aux block store the raw level, center byte and curve
		// indices in bits 26+ - without the mask they leak into the propagated
		// level (a 50-level source flooding at 3.2 million levels -> OOM and a
		// distance ratio that pins every curve at its center value).
		return (int) ((entry >>> LEVEL_SHIFT) & 0xFFFF);
	}

	private static boolean fromEmission(long entry) {
		return (entry & FLAG_FROM_EMISSION) != 0;
	}

	private static boolean hasCurve(long entry) {
		return (entry & FLAG_HAS_CURVE) != 0;
	}

	private static int entryRaw(long entry) {
		return (int) ((entry >>> RAW_SHIFT) & 0xFFFF);
	}

	private static int entryCenter(long entry) {
		return (int) ((entry >>> CENTER_SHIFT) & 0xFF);
	}

	private static boolean propagatesIn(long entry, Direction d) {
		return (entry & (1L << (DIR_SHIFT + d.ordinal()))) != 0;
	}

	/** Listener notified (on the level's thread) with dirty sections after a tick. */
	public interface SectionUpdateListener {
		void sectionsChanged(LongOpenHashSet sections);
	}

	private final Level level;

	/**
	 * True 16-bit field values; key is BlockPos#asLong. 0 = unlit.
	 * Synchronized because chunk mesh building runs on worker threads and
	 * samples the field while the level thread may be propagating light.
	 */
	private final Long2IntMap levels = Long2IntMaps.synchronize(new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(1024));
	/** Tracks this engine's contribution to {@link #ACTIVE_FIELD_COUNT}. */
	private volatile boolean lighthlib$fieldActive;
	/**
	 * Number of engines with a non-empty field. Read by hot render hooks
	 * ({@link #hasActiveField()}) - a plain volatile count beats iterating
	 * the engine map under its monitor on every call.
	 */
	private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_FIELD_COUNT =
			new java.util.concurrent.atomic.AtomicInteger();
	/** Provenance color per lit cell; default full white. Same threading as above. */
	private final Long2IntMap colors = Long2IntMaps.synchronize(new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(1024));
	/** Runner-up (second-brightest, differently-colored) contributor per lit
	 *  cell for render-time color blending: its level and provenance color.
	 *  Sparse - only written where the runner-up's color differs from the
	 *  primary's. Same threading as {@link #colors}. */
	private final Long2IntMap secondaryLevels =
			Long2IntMaps.synchronize(new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(256));
	private final Long2IntMap secondaryColors =
			Long2IntMaps.synchronize(new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(256));

	/**
	 * Attenuation-curve payload per lit cell, carried from the dominating
	 * source's wave: the interned intensity/color curves, the source's center
	 * light byte and the cell's normalized distance ratio. Only cells lit by
	 * a source WITH a curve override carry an entry - every other cell is a
	 * plain map miss, so curve-less sources pay nothing. Read from render
	 * hooks on worker threads (synchronized wrapper, immutable record).
	 */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectMap<CellCurve> cellCurves =
			it.unimi.dsi.fastutil.longs.Long2ObjectMaps.synchronize(
					new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(256));
	/** Interned per-source attenuation curves; queue entries reference them by index. */
	private final java.util.List<com.lighthlib.api.MathCurveFunction> intensityCurveTable =
			new java.util.concurrent.CopyOnWriteArrayList<>();
	private final java.util.List<com.lighthlib.api.MathCurveFunction> colorCurveTable =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Immutable per-cell curve payload (see {@link #cellCurves}). */
	public record CellCurve(com.lighthlib.api.MathCurveFunction intensityCurve,
			com.lighthlib.api.MathCurveFunction colorCurve, int centerByte, double distanceRatio) {
	}

	/** Interns a curve in {@link #intensityCurveTable} (small: per distinct
	 *  override object, written on the level thread only). Indexes above 126
	 *  clamp to 126 - the 7-bit entry slot holds at most 127 distinct curves
	 *  (0x7F is the "none" sentinel), far beyond any realistic setup. */
	private int internIntensity(com.lighthlib.api.MathCurveFunction curve) {
		int idx = this.intensityCurveTable.indexOf(curve);
		if (idx < 0) {
			this.intensityCurveTable.add(curve);
			idx = this.intensityCurveTable.size() - 1;
		}
		return Math.min(idx, 0x7E);
	}

	/** Interns a curve in {@link #colorCurveTable} (see {@link #internIntensity};
	 *  6-bit slot, clamp 0x3E, "none" sentinel 0x3F). */
	private int internColor(com.lighthlib.api.MathCurveFunction curve) {
		int idx = this.colorCurveTable.indexOf(curve);
		if (idx < 0) {
			this.colorCurveTable.add(curve);
			idx = this.colorCurveTable.size() - 1;
		}
		return Math.min(idx, 0x3E);
	}

	/** Per-position source color overrides (e.g. the test block's /hl command),
	 *  keyed by packed pos; -1 = none. Same threading as the level itself. */
	private final it.unimi.dsi.fastutil.longs.Long2IntMap sourceColorOverrides =
			createSourceColorOverrides();
	/** Per-position per-source config overrides (falloff is consumed during
	 *  propagation, the rest by render hooks): 0 = none (global values).
	 *  Keyed by packed pos, same threading as sourceColorOverrides. */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<HLightRegistry.Overrides> sourceOverrideCells =
			new Long2ObjectOpenHashMap<>();
	/** Bumped whenever a source color override changes; invalidates the
	 *  per-thread fallback memo of {@link #effectiveColor}. */
	private final java.util.concurrent.atomic.AtomicLong colorEpoch =
			new java.util.concurrent.atomic.AtomicLong();
	/** Per-thread single-entry memo of the last fallback resolution:
	 *  {@code [packedPos, color, epoch]}. */
	private final ThreadLocal<long[]> colorFallbackMemo =
			ThreadLocal.withInitial(() -> new long[3]);
	/** pos -> chunk index of field entries, for unload cleanup. */
	private final Long2ObjectOpenHashMap<LongOpenHashSet> fieldByChunk = new Long2ObjectOpenHashMap<>();
	/** Known HLib sources per chunk (packed positions). */
	private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesByChunk = new Long2ObjectOpenHashMap<>();
	/** Chunks scanned at least once while loaded. */
	private final LongOpenHashSet scannedChunks = new LongOpenHashSet();
	/** Chunks waiting to be scanned. */
	private final LongOpenHashSet pendingScans = new LongOpenHashSet();

	private final LongOpenHashSet nodesToCheck = new LongOpenHashSet();
	private final LongArrayFIFOQueue incPos = new LongArrayFIFOQueue();
	private final LongArrayFIFOQueue incEntry = new LongArrayFIFOQueue();
	private final LongArrayFIFOQueue decPos = new LongArrayFIFOQueue();
	private final LongArrayFIFOQueue decEntry = new LongArrayFIFOQueue();

	private LongOpenHashSet dirtySections = new LongOpenHashSet();
	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos scratchB = new BlockPos.MutableBlockPos();

	private SectionUpdateListener listener;
	private boolean rescanAll;

	private LHCEngine(Level level) {
		this.level = level;
		this.levels.defaultReturnValue(0);
		this.colors.defaultReturnValue(HLightMath.WHITE_RGB);
	}

	/** Obtains (or lazily creates) the engine instance for a level. */
	public static LHCEngine forLevel(Level level) {
		if (level == null) {
			throw new IllegalArgumentException("level must not be null");
		}
		// fast path: this is a hot lookup (per light sample during meshing)
		if (MEMO_LEVEL.get() == level) {
			LHCEngine memo = MEMO_ENGINE.get();
			if (memo != null && memo.level == level) {
				return memo;
			}
		}
		LHCEngine engine = ENGINES.get(level);
		if (engine == null) {
			synchronized (ENGINES) {
				engine = ENGINES.get(level);
				if (engine == null) {
					engine = new LHCEngine(level);
					ENGINES.put(level, engine);
				}
			}
		}
		MEMO_LEVEL.set(level);
		MEMO_ENGINE.set(engine);
		return engine;
	}

	private static final ThreadLocal<Level> MEMO_LEVEL = new ThreadLocal<>();
	private static final ThreadLocal<LHCEngine> MEMO_ENGINE = new ThreadLocal<>();

	public Level level() {
		return this.level;
	}

	public void setUpdateListener(SectionUpdateListener listener) {
		this.listener = listener;
	}

	// --- public queries ----------------------------------------------------

	/** The 16-bit light level at a position (0..65535). */
	public int getHLight(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return 0;
		}
		return this.levels.get(pos.asLong());
	}

	private static it.unimi.dsi.fastutil.longs.Long2IntMap createSourceColorOverrides() {
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap map =
				new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(32);
		map.defaultReturnValue(-1);
		return map;
	}

	/** The nearest registered HLib source within {@code maxRadius} blocks of a
	 *  position, or null. Used by the client tint layer so colored light stays
	 *  visible when the camera is outside the field but looking at it. */
	@org.jetbrains.annotations.Nullable
	public BlockPos nearestSource(BlockPos center, int maxRadius) {
		if (!HLightRegistry.hasAnyBindings()) {
			return null;
		}
		int maxChunkD = maxRadius / 16 + 1;
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		long best = Long.MAX_VALUE;
		long bestPacked = Long.MIN_VALUE;
		for (int dx = -maxChunkD; dx <= maxChunkD; dx++) {
			for (int dz = -maxChunkD; dz <= maxChunkD; dz++) {
				LongOpenHashSet set = this.sourcesByChunk.get(ChunkPos.pack(cx + dx, cz + dz));
				if (set == null) {
					continue;
				}
				for (LongIterator it = set.iterator(); it.hasNext(); ) {
					long p = it.nextLong();
					BlockPos bp = BlockPos.of(p);
					long d = (long) bp.distSqr(center);
					if (d < best && d <= (long) maxRadius * maxRadius) {
						best = d;
						bestPacked = p;
					}
				}
			}
		}
		return bestPacked == Long.MIN_VALUE ? null : BlockPos.of(bestPacked);
	}

	/**
	 * The effective per-source config {@link HLightRegistry.Overrides} for a
	 * position: the overrides of the nearest registered HLib source, or null
	 * when there is none (all values then fall back to the global LHCEConfig).
	 *
	 * <p>Render hooks consume this through the "take effective value" helpers
	 * (e.g. {@link #effectiveFringeCurve(BlockPos)}, {@link #effectiveTint}):
	 * a field absent from the source's overrides falls back to the global
	 * config value.</p>
	 */
	@org.jetbrains.annotations.Nullable
	public HLightRegistry.Overrides effectiveOverrides(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return null;
		}
		HLightRegistry.Overrides direct = this.sourceOverrideCells.get(pos.asLong());
		if (direct != null) {
			return direct;
		}
		BlockPos source = this.nearestSource(pos, LHCEConfig.get().nearestSourceRadius);
		return source == null ? null
				: HLightRegistry.hLightOverrides(this.level.getBlockState(source).getBlock());
	}

	/** The effective fringeCurve for a position (source override, else global). */
	public double effectiveFringeCurve(BlockPos pos) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Overrides o = this.sourceOverrideCells.get(pos.asLong());
			if (o != null && o.fringeCurve() != null) {
				return o.fringeCurve();
			}
		}
		return LHCEConfig.get().fringeCurve;
	}

	/** The effective tintStrength for a position (source override, else global). */
	public double effectiveTintStrength(BlockPos pos) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Overrides o = this.sourceOverrideCells.get(pos.asLong());
			if (o != null && o.tintStrength() != null) {
				return o.tintStrength();
			}
		}
		return LHCEConfig.get().tintStrength;
	}

	/** The effective ambientFloor for a position (source override, else global). */
	public double effectiveAmbientFloor(BlockPos pos) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Overrides o = this.sourceOverrideCells.get(pos.asLong());
			if (o != null && o.ambientFloor() != null) {
				return o.ambientFloor();
			}
		}
		return LHCEConfig.get().ambientFloor;
	}

	/**
	 * The attenuation-curve payload of a lit cell (carried from the dominating
	 * source's flood wave), or null when the cell is engine-default - which is
	 * the case for every cell lit by a source without curve overrides, so the
	 * fast path is a single synchronized map miss.
	 */
	@org.jetbrains.annotations.Nullable
	public CellCurve cellCurve(long packedPos) {
		return this.cellCurves.get(packedPos);
	}

	/**
	 * The render byte of a lit cell: the intensity curve applied to the
	 * dominating source's center byte at the cell's distance ratio when the
	 * source carries one, else the plain smooth-byte mapping with the
	 * effective fringeCurve (engine-default behavior, unchanged).
	 */
	public int blockByteAt(long packedPos, int hLight) {
		CellCurve curve = this.cellCurves.get(packedPos);
		if (curve != null && curve.intensityCurve() != null) {
			return HLightMath.curveBlockByte(curve.centerByte(), curve.intensityCurve(),
					curve.distanceRatio());
		}
		double fringeCurve = LHCEConfig.get().fringeCurve;
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Overrides o = this.sourceOverrideCells.get(packedPos);
			if (o != null && o.fringeCurve() != null) {
				fringeCurve = o.fringeCurve();
			}
		}
		return HLightMath.smoothBlockByte(hLight, fringeCurve);
	}

	/**
	 * The gameplay-visible nibble (0..15) of a lit cell for the monster-spawn
	 * gate: the intensity curve's byte shifted down when the source carries
	 * one, else the exact legacy vanilla-nibble mapping (unchanged).
	 */
	public int spawnNibbleAt(long packedPos, int hLight) {
		CellCurve curve = this.cellCurves.get(packedPos);
		if (curve != null && curve.intensityCurve() != null) {
			return HLightMath.curveBlockByte(curve.centerByte(), curve.intensityCurve(),
					curve.distanceRatio()) >> 4;
		}
		return HLightMath.vanillaNibble(hLight);
	}

	/** Sets a per-position source color override (packed 0xRRGGBB), used by the
	 *  provenance repaint in place of the registered block color. */
	public void setSourceColor(BlockPos pos, int rgb) {
		this.sourceColorOverrides.put(pos.asLong(), rgb & 0xFFFFFF);
		this.colorEpoch.incrementAndGet();
	}

	/** Clears the per-position source color override at a position (e.g. when
	 *  the source block there is removed), so a later source at the same
	 *  position starts from its registered color again. Also bumps the color
	 *  epoch, invalidating every effectiveColor memo. */
	public void clearSourceColor(BlockPos pos) {
		if (this.sourceColorOverrides.remove(pos.asLong()) != -1) {
			this.colorEpoch.incrementAndGet();
		}
	}

	/** The source color at a position: the per-position override, or the
	 *  registered color of the block state. */
	private int sourceColorAt(long pos, BlockState state) {
		int override = this.sourceColorOverrides.get(pos);
		return override != -1 ? override : HLightRegistry.sourceColor(state);
	}

	/** The dominant light color at a position, packed 0xRRGGBB. */
	public int getColor(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return HLightMath.WHITE_RGB;
		}
		return this.colors.get(pos.asLong());
	}

	/**
	 * The color a surface lit by HLib light at {@code pos} should carry: the
	 * provenance color painted by the flood or - when that is white - the
	 * color of the nearest HLib source (per-position override first, then
	 * the registered block color). White when the position carries no HLib
	 * light, so unlit areas and vanilla light are never affected.
	 */
	public int effectiveColor(BlockPos pos) {
		long packed = pos.asLong();
		if (this.levels.get(packed) <= 0) {
			return HLightMath.WHITE_RGB;
		}
		long[] memo = this.colorFallbackMemo.get();
		if (memo[0] == packed && memo[2] == this.colorEpoch.get()) {
			return (int) memo[1];
		}
		int color = this.resolveColor(pos, packed);
		memo[0] = packed;
		memo[1] = color;
		memo[2] = this.colorEpoch.get();
		return color;
	}

	/**
	 * The render color of a lit cell with optional color blending: the primary
	 * provenance color mixed with the runner-up contributor's color, each
	 * weighted by the square of its 16-bit level (perceptual weighting).
	 * Returns the plain primary color when blending is disabled
	 * ({@code enableColorBlending}), when no differently-colored runner-up is
	 * recorded at the cell, or when the position carries no HLib light.
	 */
	public int renderColor(BlockPos pos) {
		int primary = effectiveColor(pos);
		long packed = pos.asLong();
		if (this.levels.get(packed) <= 0) {
			return primary; // unlit: never blends
		}
		if (!LHCEConfig.get().enableColorBlending) {
			return primary;
		}
		int level2 = this.secondaryLevels.get(packed);
		if (level2 <= 0) {
			return primary;
		}
		int color2 = this.secondaryColors.get(packed);
		if (color2 == primary || color2 == 0 || color2 == HLightMath.WHITE_RGB) {
			return primary;
		}
		int level1 = this.levels.get(packed);
		if (level1 <= 0) {
			return primary;
		}
		long w1 = (long) level1 * level1;
		long w2 = (long) level2 * level2;
		long total = w1 + w2;
		int r1 = (primary >>> 16) & 0xFF, g1 = (primary >>> 8) & 0xFF, b1 = primary & 0xFF;
		int r2 = (color2 >>> 16) & 0xFF, g2 = (color2 >>> 8) & 0xFF, b2 = color2 & 0xFF;
		int r = (int) ((r1 * w1 + r2 * w2) / total);
		int g = (int) ((g1 * w1 + g2 * w2) / total);
		int b = (int) ((b1 * w1 + b2 * w2) / total);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Records the runner-up contributor of a cell for render-time color
	 *  blending: keeps the strongest seen so far (monotone - a later weaker
	 *  pass never demotes, a same-color refresh never fights itself). White
	 *  and "none" (0) carry no color and are never recorded. */
	private void putSecondary(long pos, int color, int level) {
		if (color == 0 || color == HLightMath.WHITE_RGB) {
			return;
		}
		if (level > this.secondaryLevels.get(pos)) {
			this.secondaryLevels.put(pos, level);
			this.secondaryColors.put(pos, color);
		}
	}

	/**
	 * Color resolution for a lit cell, robust against a stale provenance map:
	 * the user's explicit per-position override on the nearest source wins over
	 * a provenance color that still looks like that source's registered
	 * (default) color - i.e. one painted before the override was set.
	 */
	private int resolveColor(BlockPos pos, long packed) {
		int provenance = this.colors.get(packed);
		BlockPos source = this.nearestSource(pos, LHCEConfig.get().nearestSourceRadius);
		if (source == null) {
			return provenance;
		}
		long srcPacked = source.asLong();
		int override = this.sourceColorOverrides.get(srcPacked);
		int registry = HLightRegistry.sourceColor(this.level.getBlockState(source));
		if (override != -1 && (provenance == HLightMath.WHITE_RGB || provenance == registry)) {
			// missing or pre-override provenance: the explicit override is truth
			return override;
		}
		if (provenance != HLightMath.WHITE_RGB) {
			return provenance;
		}
		return override != -1 ? override : registry;
	}

	/** The source color of a position: the per-position override, else the
	 *  registered color of the block state there. */
	public int sourceColorOf(BlockPos pos) {
		int override = this.sourceColorOverrides.get(pos.asLong());
		if (override != -1) {
			return override;
		}
		return HLightRegistry.sourceColor(this.level.getBlockState(pos));
	}

	/** The {@link HLight} ID signature of the source lighting a position (the
	 *  nearest registered source's signature), or null when unlit or the
	 *  source carries no HLight object. */
	public HLight sourceHLight(BlockPos pos) {
		BlockPos source = this.nearestSource(pos, LHCEConfig.get().nearestSourceRadius);
		return source == null ? null
				: HLightRegistry.hLightOf(this.level.getBlockState(source).getBlock());
	}

	/** Whether any HLib light exists in this level (cheap check). */
	public boolean hasAnyHLight() {
		return HLightRegistry.hasAnyBindings() && !this.levels.isEmpty();
	}

	/** Number of lit cells (engine stat / debug). */
	public int fieldSize() {
		return this.levels.size();
	}

	/** Number of registered sources in this level (debug). */
	public int sourceCount() {
		int n = 0;
		for (LongOpenHashSet set : this.sourcesByChunk.values()) {
			n += set.size();
		}
		return n;
	}

	// --- change notification -------------------------------------------------

	/** Runs at every "block changed" of a position: clears any per-position
	 *  runtime overrides (color, per-source config) it carries, so a removed
	 *  source's state never survives into a later block at the same position.
	 *  Called from the engine's block-change paths ({@link #checkNode} source
	 *  removal, {@link #forceReflood}) before the field is recomputed. */
	private void clearRuntimeOverrides(long pos) {
		this.sourceOverrideCells.remove(pos);
		clearSourceColor(BlockPos.of(pos));
	}

	/** A block at {@code pos} changed: recompute the field around it. */
	public void onBlockChanged(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings() || !this.level.isLoaded(pos)) {
			return;
		}
		long packed = pos.asLong();
		this.nodesToCheck.add(packed);
	}

	/**
	 * Collapses the light field of the source at a position and re-scans it,
	 * so the source (with a fresh per-position color override) re-floods the
	 * area and repaints the provenance colors. Used for color-only changes
	 * where a plain re-scan would find nothing to do.
	 */
	public void forceReflood(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings() || !this.level.isLoaded(pos)) {
			return;
		}
		long packed = pos.asLong();
		int stored = this.levels.get(packed);
		if (stored > 0) {
			int dyingColor = this.colors.get(packed);
			setLevel(packed, 0);
			this.colors.remove(packed);
			enqueueDecrease(packed, decreaseAll(stored, dyingColor));
		}
		this.nodesToCheck.add(packed);
	}

	/** Cross-thread-safe variant of {@link #forceReflood}: the collapse is
	 *  scheduled and the position re-checked on the engine's own thread.
	 *  Safe to call from the server command thread against a client-level
	 *  engine (the internal queues are not synchronized, so callers on other
	 *  threads must go through this method). */
	public void requestReflood(BlockPos pos) {
		if (!HLightRegistry.hasAnyBindings() || !this.level.isLoaded(pos)) {
			return;
		}
		this.crossThreadChecks.add(pos.asLong());
	}

	/** Engine-thread consumption of {@link #requestReflood} positions. Really
	 *  concurrent: the server command thread produces, the engine's tick
	 *  thread consumes. */
	private final java.util.concurrent.ConcurrentLinkedQueue<Long> crossThreadChecks =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** A chunk (re)loaded: scan it for sources and arm the settle pass. */
	public void onChunkLoad(ChunkPos chunkPos) {
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		long packed = chunkPos.pack();
		this.pendingScans.add(packed);
		// Chunk-load storms (world entry, travel) race the flood waves: waves
		// stop at borders of chunks that were still loading, and section
		// dirty-marks can be dropped before a section exists. Rather than
		// re-checking per chunk load, arm the settle pass - at quiescence it
		// re-verifies every known source once (dark holes left by stopped
		// waves fill, correct areas prune at O(1)) and re-affirms every
		// section touched during the episode.
		this.settleArmed = true;
	}

	/** A chunk unloaded: drop its field entries so memory stays bounded. */
	public void onChunkUnload(ChunkPos chunkPos) {
		long packed = chunkPos.pack();
		this.scannedChunks.remove(packed);
		this.pendingScans.remove(packed);
		this.sourcesByChunk.remove(packed);
		removeChunkField(packed);
	}

	/** Request a full rescan of every known chunk (e.g. after a runtime binding). */
	public void requestRescanAll() {
		this.rescanAll = true;
		this.settleArmed = true;
	}

	private void removeChunkField(long chunkPacked) {
		LongOpenHashSet inChunk = this.fieldByChunk.remove(chunkPacked);
		if (inChunk == null) {
			return;
		}
		for (LongIterator it = inChunk.iterator(); it.hasNext(); ) {
			long pos = it.nextLong();
			this.levels.remove(pos);
			this.colors.remove(pos);
			this.secondaryLevels.remove(pos);
			this.secondaryColors.remove(pos);
			this.cellCurves.remove(pos);
			this.sourceOverrideCells.remove(pos);
		}
	}

	// --- tick ----------------------------------------------------------------

	/**
	 * Runs budgeted engine work. Called once per tick from the owning side
	 * (server level tick / client level tick).
	 */
	public void tick() {
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		long budget = LHCEConfig.get().nodeBudgetPerTick;

		if (this.rescanAll) {
			this.rescanAll = false;
			this.pendingScans.addAll(this.scannedChunks);
			this.scannedChunks.clear();
		}

		// 1. chunk scans
		while (!this.pendingScans.isEmpty() && budget > 0) {
			LongIterator it = this.pendingScans.iterator();
			long packed = it.nextLong();
			it.remove();
			scanChunk(packed);
			budget -= 4096;
		}

		// 2. queued block checks
		Long extPos;
		while ((extPos = this.crossThreadChecks.poll()) != null) {
			// route through forceReflood so the old field (and its stale
			// CellCurve entries) collapses first, then re-floods with the
			// freshly bound registry curves
			forceReflood(BlockPos.of(extPos));
		}
		while (!this.nodesToCheck.isEmpty() && budget > 0) {
			LongIterator it = this.nodesToCheck.iterator();
			long pos = it.nextLong();
			it.remove();
			checkNode(pos);
			budget--;
		}

		// 3. decrease waves, then 4. increase waves
		while (!this.decPos.isEmpty() && budget > 0) {
			long pos = this.decPos.dequeueLong();
			long entry = this.decEntry.dequeueLong();
			propagateDecrease(pos, entry);
			budget--;
		}
		while (!this.incPos.isEmpty() && budget > 0) {
			long pos = this.incPos.dequeueLong();
			long entry = this.incEntry.dequeueLong();
			processIncrease(pos, entry);
			budget--;
		}

		// 5. flush dirty sections to the listener (client re-mesh) - throttled:
		//    each section dirtying re-meshes itself plus its 26 neighbors, so
		//    flushing hundreds per tick during a world-entry flood stalled the
		//    render thread. Cap the per-tick flush and carry the rest over.
		if (this.listener != null && !this.dirtySections.isEmpty()) {
			final int REMESH_CAP = 48;
			if (this.dirtySections.size() <= REMESH_CAP) {
				LongOpenHashSet flush = this.dirtySections;
				this.dirtySections = new LongOpenHashSet();
				this.listener.sectionsChanged(flush);
			} else {
				LongOpenHashSet flush = new LongOpenHashSet(REMESH_CAP);
				LongOpenHashSet carry = new LongOpenHashSet(this.dirtySections.size());
				LongIterator flushIt = this.dirtySections.iterator();
				int flushed = 0;
				while (flushIt.hasNext()) {
					long packed = flushIt.nextLong();
					if (flushed < REMESH_CAP) {
						flush.add(packed);
						flushed++;
					} else {
						carry.add(packed);
					}
				}
				this.dirtySections = carry;
				this.listener.sectionsChanged(flush);
			}
		}

		// 6. settle pass: once everything drained after a chunk-load storm
		//    (world entry, travel), re-verify every known source once - waves
		//    that stopped at borders of then-unloaded chunks resume into
		//    exactly the cells that stayed dark, correct areas prune at O(1) -
		//    and when the verification has drained, re-affirm every section
		//    touched during the episode, healing dirty marks that were dropped
		//    while their section was still loading.
		boolean drained = this.pendingScans.isEmpty() && this.nodesToCheck.isEmpty()
				&& this.incPos.isEmpty() && this.decPos.isEmpty();
		if (this.settleArmed || this.settleVerifying) {
			// episode timeout: a source that re-queues work every tick (e.g. a
			// flickering dynamic source) keeps the queues permanently
			// non-empty and would starve the settle pass forever - force the
			// episode to finish after 200 ticks so sections are re-affirmed
			if (!drained && ++this.settleTicks >= 200) {
				drained = true;
			}
		} else {
			this.settleTicks = 0;
		}
		if (drained) {
			this.settleTicks = 0;
			if (this.settleVerifying) {
				this.settleVerifying = false;
				if (this.listener != null && !this.episodeSections.isEmpty()) {
					LongOpenHashSet reMark = new LongOpenHashSet(this.episodeSections);
					this.episodeSections.clear();
					this.listener.sectionsChanged(reMark);
				}
				int settledCells = fieldSize();
				int settledSources = sourceCount();
				// only log when the settled field actually changed - repeated
				// settle episodes (sources re-verified every episode) would
				// otherwise spam the log twice per tick
				if (settledCells != this.lastLoggedCells || settledSources != this.lastLoggedSources) {
					this.lastLoggedCells = settledCells;
					this.lastLoggedSources = settledSources;
					LightHLibEngineLog.LOGGER.info("[LHCE] field settled: {} lit cells, {} sources",
							settledCells, settledSources);
				}
			} else if (this.settleArmed) {
				this.settleArmed = false;
				for (LongOpenHashSet sources : this.sourcesByChunk.values()) {
					this.nodesToCheck.addAll(sources);
				}
				this.settleVerifying = true;
			}
		}
	}

	// --- core algorithm (faithful 16-bit port of the vanilla light engine) ---

	private void checkNode(long pos) {
		if (!isLoaded(pos)) {
			return;
		}
		BlockState state = this.level.getBlockState(this.scratch.set(pos));
		int emission = HLightRegistry.emissionAt(this.level, this.scratch, state);
		int stored = this.levels.get(pos);

		if (emission < stored) {
			// the light being removed carries its provenance color into the
			// decrease wave, so runner-up records of that color get pruned
			int dyingColor = this.colors.get(pos);
			setLevel(pos, 0);
			enqueueDecrease(pos, decreaseAll(stored, dyingColor));
		} else {
			// vanilla PULL_LIGHT_IN: unconditionally pull light from neighbors
			enqueueDecrease(pos, decreaseAll(0));
		}
		if (emission > 0) {
			addSource(pos);
			recordSourceOverrides(pos, state);
			// the full per-source entry carries the curve aux block
			// (raw level, center byte, interned curves): without it the wave
			// never sets FLAG_HAS_CURVE, putCellCurve never runs and every
			// curve override (command and API alike) is silently ignored
			enqueueIncrease(pos, sourceEmissionEntry(pos, state, emission));
		} else {
			removeSource(pos);
			// the block is no longer a source: drop every runtime per-position
			// override (color, per-source config) so a future source at this
			// position starts from its own registered state, not the old one's
			this.clearRuntimeOverrides(pos);
		}
		if (emission > 0 || stored > 0) {
			this.markSectionDirty(pos);
		}
	}

	/** Builds the emission queue entry of a source cell, resolving its full
	 *  per-source override set: falloff, fringeCurve (for the center byte)
	 *  and the attenuation curves (interned into the engine's curve tables). */
	private long sourceEmissionEntry(long pos, BlockState state, int emission) {
		int falloff = 0;
		double fringeCurve = LHCEConfig.get().fringeCurve;
		com.lighthlib.api.MathCurveFunction intensity = null;
		com.lighthlib.api.MathCurveFunction color = null;
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Entry entry = HLightRegistry.entry(state.getBlock());
			HLightRegistry.Overrides overrides = entry != null ? entry.overrides() : null;
			if (overrides != null) {
				if (overrides.falloff() != null) {
					falloff = overrides.falloff();
				}
				if (overrides.fringeCurve() != null) {
					fringeCurve = overrides.fringeCurve();
				}
				intensity = overrides.intensityCurve();
				color = overrides.colorCurve();
			}
		}
		return sourceEmissionEntry(emission, falloff, fringeCurve, intensity, color);
	}

	/** Rebuilds a wave's curve aux block from a surviving cell's stored
	 *  {@link CellCurve}, so relight waves re-paint the curve payload of the
	 *  cells they light instead of stripping it (the raw level is recovered
	 *  from the stored distance ratio: dr = 1 - level/raw). Returns 0 when
	 *  the survivor carries no curves. */
	private long relightAux(long survivorPos, int survivorLevel) {
		CellCurve pc = this.cellCurves.get(survivorPos);
		if (pc == null || (pc.intensityCurve() == null && pc.colorCurve() == null)) {
			return 0L;
		}
		double dr = pc.distanceRatio();
		int raw = dr < 1.0
				? Math.max(1, (int) Math.round(survivorLevel / (1.0 - dr))) : survivorLevel;
		int iIdx = pc.intensityCurve() != null ? this.internIntensity(pc.intensityCurve()) : 0xFF;
		int cIdx = pc.colorCurve() != null ? this.internColor(pc.colorCurve()) : 0xFF;
		return FLAG_HAS_CURVE
				| ((long) raw << RAW_SHIFT)
				| ((long) pc.centerByte() << CENTER_SHIFT)
				| ((long) iIdx << INTENSITY_IDX_SHIFT)
				| ((long) cIdx << COLOR_IDX_SHIFT);
	}

	/** Stores/refreshes the curve payload of a lit cell from a wave's aux data. */
	private void putCellCurve(long pos, long entry, double distanceRatio) {
		int iIdx = (int) ((entry >>> INTENSITY_IDX_SHIFT) & 0x7F);
		int cIdx = (int) ((entry >>> COLOR_IDX_SHIFT) & 0x3F);
		com.lighthlib.api.MathCurveFunction intensity = iIdx < this.intensityCurveTable.size()
				? this.intensityCurveTable.get(iIdx) : null;
		com.lighthlib.api.MathCurveFunction color = cIdx < this.colorCurveTable.size()
				? this.colorCurveTable.get(cIdx) : null;
		this.cellCurves.put(pos, new CellCurve(intensity, color, entryCenter(entry), distanceRatio));
	}

	/** Records the per-source config overrides of a source cell for the render
	 *  hooks' "take effective value" queries. */
	private void recordSourceOverrides(long pos, BlockState state) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Entry entry = HLightRegistry.entry(state.getBlock());
			HLightRegistry.Overrides overrides = entry != null ? entry.overrides() : null;
			if (overrides != null && !overrides.isEmpty()) {
				HLightRegistry.Overrides old = this.sourceOverrideCells.get(pos);
				boolean curveChanged = old == null
						? (overrides.intensityCurve() != null || overrides.colorCurve() != null)
						: (old.intensityCurve() != overrides.intensityCurve()
								|| old.colorCurve() != overrides.colorCurve());
				this.sourceOverrideCells.put(pos, overrides);
				if (curveChanged) {
					// per-cell curve payloads are only rewritten by BRIGHTER
					// waves; a curve change at the same emission leaves every
					// cell's stale CellCurve in place ("already as bright ->
					// skip"). Collapse + re-flood so the wave re-writes them;
					// safe self-call: this runs on the engine's own thread
					// (block change / rescan), so no cross-thread queue access
					forceReflood(this.scratch.set(pos));
				}
			} else {
				HLightRegistry.Overrides old = this.sourceOverrideCells.get(pos);
				this.sourceOverrideCells.remove(pos);
				if (old != null && (old.intensityCurve() != null || old.colorCurve() != null)) {
					forceReflood(this.scratch.set(pos));
				}
			}
		} else {
			this.sourceOverrideCells.remove(pos);
		}
	}

	private void processIncrease(long pos, long entry) {
		int stored = this.levels.get(pos);
		int fromLevel = entryLevel(entry);
		if (fromEmission(entry) && stored < fromLevel) {
			int newColor = sourceColorAt(pos, this.level.getBlockState(this.scratch.set(pos)));
			// color blending: the light previously dominating this cell
			// becomes the runner-up candidate when differently colored
			int oldColor = this.colors.get(pos);
			if (oldColor != newColor && oldColor != HLightMath.WHITE_RGB) {
				putSecondary(pos, oldColor, stored);
			}
			setLevel(pos, fromLevel);
			this.colors.put(pos, newColor);
			if (hasCurve(entry)) {
				// the source cell itself: distance ratio 0 (center)
				putCellCurve(pos, entry, 0.0);
			}
			stored = fromLevel;
		} else if (fromEmission(entry) && stored == fromLevel && hasCurve(entry)
				&& !this.cellCurves.containsKey(pos)) {
			// re-verify pass over an already-lit source whose curve payload was
			// stripped (a curve-less relight once passed through this cell):
			// restore it so the wave below re-paints the downstream cells
			putCellCurve(pos, entry, 0.0);
		}
		if (stored == fromLevel) {
			propagateIncrease(pos, entry, stored);
		}
	}

	private void propagateIncrease(long pos, long entry, int level) {
		int srcColor = this.colors.get(pos);
		boolean ov = hasFalloff(entry);
		int ovFalloff = entryFalloff(entry);
		for (Direction d : DIRECTIONS) {
			if (!propagatesIn(entry, d)) {
				continue;
			}
			long n = BlockPos.offset(pos, d);
			if (!isLoaded(n)) {
				continue;
			}
			int nStored = this.levels.get(n);
			if (level - 1 <= nStored) {
				// equal-level curve heal: relight waves (decrease cascades) used
				// to write cells without their source's curve payload, and this
				// skip then froze the loss forever - the cell rendered with the
				// engine-default byte mapping while its curve-bound neighbors
				// used the curve mapping (patchy over-bright blocks "covering"
				// the field). When this wave carries a curve and the cell has
				// none, repaint it - but only on an exact level match, so
				// weaker waves and other sources' ties never steal a
				// provenance they don't own.
				if (hasCurve(entry) && nStored > 0 && !this.cellCurves.containsKey(n)) {
					BlockState nState = this.level.getBlockState(this.scratch.set(n));
					int nDampening = nState.getLightDampening();
					if (nDampening < 15) {
						int cost = ov ? HLibConstants.crossCost(level, nDampening, ovFalloff)
								: HLibConstants.crossCost(level, nDampening);
						if (level - cost == nStored) {
							int raw = entryRaw(entry);
							double dr = raw > 0
									? Math.clamp(1.0 - (double) nStored / raw, 0.0, 1.0) : 1.0;
							putCellCurve(n, entry, dr);
							this.markSectionDirty(n);
						}
					}
				}
				// color blending: the losing wave may still be the runner-up
				// contributor here when its color differs from the primary's
				int pColor = this.colors.get(n);
				if (srcColor != pColor && srcColor != HLightMath.WHITE_RGB) {
					BlockState nState = this.level.getBlockState(this.scratch.set(n));
					int nDampening = nState.getLightDampening();
					if (nDampening < 15) {
						int cost = ov ? HLibConstants.crossCost(level, nDampening, ovFalloff)
								: HLibConstants.crossCost(level, nDampening);
						int newLevel = level - cost;
						if (newLevel > 0) {
							putSecondary(n, srcColor, newLevel);
						}
					}
				}
				continue;
			}
			BlockState nState = this.level.getBlockState(this.scratch.set(n));
			int nDampening = nState.getLightDampening();
			if (nDampening >= 15) {
				// Fully opaque block: light cannot enter it, exactly like the
				// vanilla engine (where 15 levels of cost extinguish any
				// vanilla source). Without this hard gate, a 16-bit source of
				// e.g. 50000 loses only 15 levels per stone block (0.03%) and
				// floods through walls, the ground surface (straight into
				// caves below) and tinted glass.
				continue;
			}
			int cost = ov ? HLibConstants.crossCost(level, nDampening, ovFalloff)
					: HLibConstants.crossCost(level, nDampening);
			int newLevel = level - cost;
			if (newLevel <= nStored) {
				continue;
			}
			// color blending: the displaced primary becomes the runner-up
			// candidate (the incoming wave takes the primary slot below)
			int oldColor = this.colors.get(n);
			if (oldColor != srcColor && oldColor != HLightMath.WHITE_RGB) {
				putSecondary(n, oldColor, nStored);
			}
			setLevel(n, newLevel);
			this.colors.put(n, srcColor);
			if (hasCurve(entry)) {
				// distance ratio of the dominating wave: consumed level over
				// the wave's source level (dampening makes occluded cells fade
				// as if they were farther out)
				int raw = entryRaw(entry);
				double dr = raw > 0 ? Math.clamp(1.0 - (double) newLevel / raw, 0.0, 1.0) : 1.0;
				putCellCurve(n, entry, dr);
			} else if (this.cellCurves.containsKey(n)) {
				// taken over by a brighter curve-less source: drop the stale
				// curve payload so this cell renders engine-default again
				this.cellCurves.remove(n);
			}
			this.markSectionDirty(n);
			if (newLevel > 1) {
				enqueueIncrease(n, increaseSkipOne(newLevel, d.getOpposite(), ov, ovFalloff, entry));
			}
		}
	}

	private void propagateDecrease(long pos, long entry) {
		int fromLevel = entryLevel(entry);
		// the provenance color of the light this wave is removing (0 = probe)
		int dyingColor = entryDyingColor(entry);
		for (Direction d : DIRECTIONS) {
			if (!propagatesIn(entry, d)) {
				continue;
			}
			long n = BlockPos.offset(pos, d);
			if (!isLoaded(n)) {
				continue;
			}
			int nStored = this.levels.get(n);
			if (nStored == 0) {
				continue;
			}
			if (nStored <= fromLevel - 1) {
				// neighbor was lit by me: remove and cascade
				BlockState nState = this.level.getBlockState(this.scratch.set(n));
				int emission = HLightRegistry.emissionAt(this.level, this.scratch, nState);
				setLevel(n, 0);
				this.colors.remove(n);
				if (emission < nStored) {
					enqueueDecrease(n, decreaseSkipOne(nStored, d.getOpposite(), dyingColor));
				}
				if (emission > 0) {
					setLevel(n, emission);
					this.colors.put(n, sourceColorAt(n, nState));
					enqueueIncrease(n, sourceEmissionEntry(n, nState, emission));
				}
				this.markSectionDirty(n);
			} else {
				// color blending: when the runner-up recorded at this surviving
				// cell belongs to the light being removed, drop it (a same-color
				// source elsewhere re-fills it at the next settle re-verify)
				if (dyingColor != 0 && this.secondaryColors.get(n) == dyingColor) {
					this.secondaryLevels.remove(n);
					this.secondaryColors.remove(n);
				}
				// neighbor is brighter than what I provided: it can relight me
				// - carrying the survivor's own curve payload, so re-lit cells
				// keep their source's attenuation curves (a curve-less relight
				// used to strip them, freezing the loss via the equal-level skip)
				enqueueIncrease(n, increaseOnlyOne(nStored, d.getOpposite(), false, 0,
						relightAux(n, nStored)));
			}
		}
	}

	private void scanChunk(long chunkPacked) {
		if (this.scannedChunks.contains(chunkPacked)) {
			return;
		}
		ChunkPos chunkPos = ChunkPos.unpack(chunkPacked);
		int cx = chunkPos.getMinBlockX();
		int cz = chunkPos.getMinBlockZ();
		if (!this.level.isLoaded(this.scratchB.set(cx + 8, this.level.getMinY(), cz + 8))) {
			return;
		}
		this.scannedChunks.add(chunkPacked);
		if (!HLightRegistry.hasAnyBindings()) {
			return;
		}
		int minY = this.level.getMinY();
		int maxY = this.level.getMaxY();
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				for (int y = minY; y < maxY; y++) {
					BlockPos pos = this.scratch.set(cx + x, y, cz + z);
					BlockState state = this.level.getBlockState(pos);
					if (HLightRegistry.entry(state.getBlock()) == null) {
						continue;
					}
					int emission = HLightRegistry.emissionAt(this.level, pos, state);
					if (emission > 0) {
						this.nodesToCheck.add(pos.asLong());
					}
				}
			}
		}
	}

	// --- storage helpers ------------------------------------------------------

	private void setLevel(long pos, int level) {
		if (level <= 0) {
			if (this.levels.remove(pos) != 0) {
				// keep colors consistent with levels: a cell whose level entry
				// is dropped must not keep a stale provenance color (nor a
				// stale blending runner-up)
				this.colors.remove(pos);
				this.secondaryLevels.remove(pos);
				this.secondaryColors.remove(pos);
				this.cellCurves.remove(pos);
				indexFieldRemove(pos);
				this.updateFieldActive();
				// the cell's effectiveColor changed: invalidate render memos
				this.colorEpoch.incrementAndGet();
			}
			return;
		}
		int old = this.levels.put(pos, level);
		if (old == 0) {
			indexFieldAdd(pos);
			this.updateFieldActive();
		} else if (old != level) {
			// brightness changed - the flood repainted this cell's provenance
			// color right around this call, so the render memo must not serve
			// the previous color (stale-memo bug seen on particles after a
			// source was broken and re-placed)
			this.colorEpoch.incrementAndGet();
		}
	}

	/** Reflects an empty/non-empty {@link #levels} transition into
	 *  {@link #ACTIVE_FIELD_COUNT} (O(1); called on the cell-count paths). */
	private void updateFieldActive() {
		boolean active = !this.levels.isEmpty();
		if (active != this.lighthlib$fieldActive) {
			this.lighthlib$fieldActive = active;
			ACTIVE_FIELD_COUNT.addAndGet(active ? 1 : -1);
		}
	}

	/**
	 * True when any engine's field has at least one lit cell. The render
	 * hooks' low-bit restore gates read this - it stays false while the mod
	 * is installed with no HLib source placed (registry bindings like the
	 * test block alone must not re-enable the restore), so vanilla worlds
	 * render byte-exact vanilla.
	 */
	public static boolean hasActiveField() {
		return ACTIVE_FIELD_COUNT.get() > 0;
	}

	/** Rough number of queued work nodes (scans + block checks + both wave
	 *  queues) - the {@code /hl spawncheck} diagnostics read this. */
	public int pendingCount() {
		return this.pendingScans.size() + this.nodesToCheck.size()
				+ this.incPos.size() + this.decPos.size();
	}

	private void indexFieldAdd(long pos) {
		long chunk = chunkKey(pos);
		LongOpenHashSet set = this.fieldByChunk.get(chunk);
		if (set == null) {
			set = new LongOpenHashSet(64);
			this.fieldByChunk.put(chunk, set);
		}
		set.add(pos);
	}

	private void indexFieldRemove(long pos) {
		long chunk = chunkKey(pos);
		LongOpenHashSet set = this.fieldByChunk.get(chunk);
		if (set != null) {
			set.remove(pos);
			if (set.isEmpty()) {
				this.fieldByChunk.remove(chunk);
			}
		}
	}

	private void addSource(long pos) {
		long chunk = chunkKey(pos);
		LongOpenHashSet set = this.sourcesByChunk.get(chunk);
		if (set == null) {
			set = new LongOpenHashSet(16);
			this.sourcesByChunk.put(chunk, set);
		}
		set.add(pos);
	}

	private void removeSource(long pos) {
		long chunk = chunkKey(pos);
		LongOpenHashSet set = this.sourcesByChunk.get(chunk);
		this.sourceOverrideCells.remove(pos);
		if (set != null) {
			set.remove(pos);
			if (set.isEmpty()) {
				this.sourcesByChunk.remove(chunk);
			}
		}
	}

	private static long chunkKey(long packedPos) {
		return ChunkPos.pack(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
	}

	private void enqueueIncrease(long pos, long entry) {
		this.incPos.enqueue(pos);
		this.incEntry.enqueue(entry);
	}

	private void enqueueDecrease(long pos, long entry) {
		this.decPos.enqueue(pos);
		this.decEntry.enqueue(entry);
	}

	private boolean isLoaded(long packedPos) {
		return this.level.isLoaded(this.scratchB.set(packedPos));
	}

	private void markSectionDirty(long packedPos) {
		long section = SectionPos.blockToSection(packedPos);
		this.dirtySections.add(section);
		this.episodeSections.add(section);
	}
}
