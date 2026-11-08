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

	// Queue entry packing: bit 0 = from-emission flag, bit 1 = has-falloff-override
	// flag, bits 3..8 = direction propagation mask (1 << ordinal), bits 10.. =
	// fromLevel (up to 65535).
	private static final long FLAG_FROM_EMISSION = 1L;
	private static final long FLAG_HAS_FALLOFF = 2L;
	private static final int DIR_SHIFT = 3;
	private static final int LEVEL_SHIFT = 10;
	private static final long ALL_DIRS_MASK;
	private static final long LEVEL_MASK = ~((1L << LEVEL_SHIFT) - 1);

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
		// zero is not a valid override (sanitized to >= 1): bit 1 doubles as
		// the presence flag
		return falloff > 0 ? (e & ~FLAG_HAS_FALLOFF) | FLAG_HAS_FALLOFF | (falloff & 0x3) : e;
	}

	private static long withFalloff(long entry, int falloff) {
		return falloff > 0
				? (entry & ~FLAG_HAS_FALLOFF) | FLAG_HAS_FALLOFF | (falloff & 0x3)
				: entry & ~FLAG_HAS_FALLOFF & ~0x3;
	}

	private static int entryFalloff(long entry) {
		return (entry & FLAG_HAS_FALLOFF) != 0 ? (int) (entry & 0x3) : 0;
	}

	private static boolean hasFalloff(long entry) {
		return (entry & FLAG_HAS_FALLOFF) != 0;
	}

	private static long increaseSkipOne(int level, Direction skip, boolean falloffFlag, int falloff) {
		long e = ((long) level << LEVEL_SHIFT) | (ALL_DIRS_MASK & ~(1L << (DIR_SHIFT + skip.ordinal())));
		return falloffFlag ? e | FLAG_HAS_FALLOFF | (falloff & 0x3) : e;
	}

	private static long increaseOnlyOne(int level, Direction only, boolean falloffFlag, int falloff) {
		long e = ((long) level << LEVEL_SHIFT) | (1L << (DIR_SHIFT + only.ordinal()));
		return falloffFlag ? e | FLAG_HAS_FALLOFF | (falloff & 0x3) : e;
	}

	private static long decreaseAll(int fromLevel) {
		return ((long) fromLevel << LEVEL_SHIFT) | ALL_DIRS_MASK;
	}

	private static long decreaseSkipOne(int fromLevel, Direction skip) {
		return ((long) fromLevel << LEVEL_SHIFT) | (ALL_DIRS_MASK & ~(1L << (DIR_SHIFT + skip.ordinal())));
	}

	private static int entryLevel(long entry) {
		return (int) (entry >>> LEVEL_SHIFT);
	}

	private static boolean fromEmission(long entry) {
		return (entry & FLAG_FROM_EMISSION) != 0;
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

	private final LongOpenHashSet dirtySections = new LongOpenHashSet();
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

	/**
	 * Whether ANY engine's field is non-empty across all loaded levels -
	 * true only while an actual HLib source is placed somewhere. Unlike
	 * {@link HLightRegistry#hasAnyBindings()} (true as soon as any block is
	 * bound, e.g. the built-in test block at mod init), this is the correct
	 * "is anyone using HLib light right now" gate for global render hooks
	 * (sub-texel restore in QuadInstanceMixin / LightCoordsUtil.max): an
	 * empty world of levels renders byte-identically to vanilla.
	 */
	public static boolean hasActiveField() {
		if (!HLightRegistry.hasAnyBindings()) {
			return false;
		}
		return ACTIVE_FIELD_COUNT.get() > 0;
	}

	/** Number of lit cells (engine stat / debug). */
	public int fieldSize() {
		return this.levels.size();
	}

	/** Number of nodes still queued for processing (scans + block checks +
	 *  decrease/increase waves) - non-zero right after a source change means
	 *  the per-tick budget is still draining the field, not a leak. */
	public int pendingCount() {
		return this.pendingScans.size() + this.nodesToCheck.size()
				+ this.decPos.size() + this.incPos.size();
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
			setLevel(packed, 0);
			this.colors.remove(packed);
			enqueueDecrease(packed, decreaseAll(stored));
		}
		this.nodesToCheck.add(packed);
	}

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
			this.sourceOverrideCells.remove(pos);
		}
		this.updateFieldActive();
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

		// 5. flush dirty sections to the listener (client re-mesh)
		if (this.listener != null && !this.dirtySections.isEmpty()) {
			LongOpenHashSet flush = new LongOpenHashSet(this.dirtySections);
			this.dirtySections.clear();
			this.listener.sectionsChanged(flush);
		}

		// 6. settle pass: once everything drained after a chunk-load storm
		//    (world entry, travel), re-verify every known source once - waves
		//    that stopped at borders of then-unloaded chunks resume into
		//    exactly the cells that stayed dark, correct areas prune at O(1) -
		//    and when the verification has drained, re-affirm every section
		//    touched during the episode, healing dirty marks that were dropped
		//    while their section was still loading.
		if (this.pendingScans.isEmpty() && this.nodesToCheck.isEmpty()
				&& this.incPos.isEmpty() && this.decPos.isEmpty()) {
			if (this.settleVerifying) {
				finishSettle();
			} else if (this.settleArmed) {
				armSettleVerification();
			}
		} else if (this.settleArmed || this.settleVerifying) {
			// timeout fallback: a source that re-queues work every tick (e.g. a
			// flickering dynamic source) keeps the queues permanently
			// non-empty, which would starve the settle pass forever - force
			// the episode's section re-affirmation after SETTLE_TIMEOUT_TICKS
			this.settleTicks++;
			if (this.settleTicks >= SETTLE_TIMEOUT_TICKS) {
				boolean rearm = this.settleArmed;
				finishSettle();
				if (rearm) {
					// re-arm for the next episode after the forced one
					armSettleVerification();
				}
			}
		}
	}

	/** Ticks after which a settle episode is force-finished even when work
	 *  never fully drains. */
	private static final int SETTLE_TIMEOUT_TICKS = 200;

	private void finishSettle() {
		this.settleVerifying = false;
		this.settleArmed = false;
		this.settleTicks = 0;
		if (this.listener != null && !this.episodeSections.isEmpty()) {
			LongOpenHashSet reMark = new LongOpenHashSet(this.episodeSections);
			this.episodeSections.clear();
			this.listener.sectionsChanged(reMark);
		}
		LightHLibEngineLog.LOGGER.info("[LHCE] field settled: {} lit cells, {} sources",
				fieldSize(), sourceCount());
	}

	private void armSettleVerification() {
		this.settleArmed = false;
		this.settleTicks = 0;
		for (LongOpenHashSet sources : this.sourcesByChunk.values()) {
			this.nodesToCheck.addAll(sources);
		}
		this.settleVerifying = true;
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
			setLevel(pos, 0);
			enqueueDecrease(pos, decreaseAll(stored));
		} else {
			// vanilla PULL_LIGHT_IN: unconditionally pull light from neighbors
			enqueueDecrease(pos, decreaseAll(0));
		}
		if (emission > 0) {
			addSource(pos);
			recordSourceOverrides(pos, state);
			enqueueIncrease(pos, increaseFromEmission(emission, sourceFalloff(pos, state)));
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

	/** The per-source falloff override of a source cell, or 0 when global. */
	private int sourceFalloff(long pos, BlockState state) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Entry entry = HLightRegistry.entry(state.getBlock());
			if (entry != null && entry.overrides() != null && entry.overrides().falloff() != null) {
				return entry.overrides().falloff();
			}
		}
		return 0;
	}

	/** Records the per-source config overrides of a source cell for the render
	 *  hooks' "take effective value" queries. */
	private void recordSourceOverrides(long pos, BlockState state) {
		if (HLightRegistry.hasAnyBindings()) {
			HLightRegistry.Entry entry = HLightRegistry.entry(state.getBlock());
			HLightRegistry.Overrides overrides = entry != null ? entry.overrides() : null;
			if (overrides != null && !overrides.isEmpty()) {
				this.sourceOverrideCells.put(pos, overrides);
			} else {
				this.sourceOverrideCells.remove(pos);
			}
		} else {
			this.sourceOverrideCells.remove(pos);
		}
	}

	private void processIncrease(long pos, long entry) {
		int stored = this.levels.get(pos);
		int fromLevel = entryLevel(entry);
		if (fromEmission(entry) && stored < fromLevel) {
			setLevel(pos, fromLevel);
			this.colors.put(pos, sourceColorAt(pos, this.level.getBlockState(this.scratch.set(pos))));
			stored = fromLevel;
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
				continue; // neighbor already at least as bright
			}
			BlockState nState = this.level.getBlockState(this.scratch.set(n));
			int cost = ov ? HLibConstants.crossCost(level, nState.getLightDampening(), ovFalloff)
					: HLibConstants.crossCost(level, nState.getLightDampening());
			int newLevel = level - cost;
			if (newLevel <= nStored) {
				continue;
			}
			setLevel(n, newLevel);
			this.colors.put(n, srcColor);
			this.markSectionDirty(n);
			if (newLevel > 1) {
				enqueueIncrease(n, increaseSkipOne(newLevel, d.getOpposite(), ov, ovFalloff));
			}
		}
	}

	private void propagateDecrease(long pos, long entry) {
		int fromLevel = entryLevel(entry);
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
					enqueueDecrease(n, decreaseSkipOne(nStored, d.getOpposite()));
				}
				if (emission > 0) {
					setLevel(n, emission);
					this.colors.put(n, sourceColorAt(n, nState));
					enqueueIncrease(n, increaseFromEmission(emission, sourceFalloff(n, nState)));
				}
				this.markSectionDirty(n);
			} else {
				// neighbor is brighter than what I provided: it can relight me
				enqueueIncrease(n, increaseOnlyOne(nStored, d.getOpposite(), false, 0));
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
				// is dropped must not keep a stale provenance color
				this.colors.remove(pos);
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
