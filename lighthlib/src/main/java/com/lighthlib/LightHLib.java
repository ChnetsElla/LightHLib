package com.lighthlib;

import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.lighthlib.api.HLight;
import com.lighthlib.api.HLightBlockProperties;
import com.lighthlib.api.HLightComponent;
import com.lighthlib.api.HLightDrop;
import com.lighthlib.api.HLightMath;
import com.lighthlib.api.HLightRegistry;
import com.lighthlib.block.HLTestBlock;
import com.lighthlib.block.HLTestBlockEntity;
import com.lighthlib.command.HLCommand;
import com.lighthlib.engine.LHCEConfig;
import com.lighthlib.engine.LHCEngine;
import com.lighthlib.impl.LightHLibImpl;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <h1>LightHLib - the 16-bit light channel library</h1>
 *
 * <p>LightHLib breaks the vanilla 15-level block-light ceiling and raises the
 * block-light channel of every dimension to <b>65535 levels</b>. It ships:</p>
 *
 * <ul>
 *   <li>the <b>LHCE</b> (LightHLib Core Engine): a parallel 16-bit light field
 *       computed with the same flood-fill algorithm the vanilla engine uses,
 *       budgeted per tick and dimension-agnostic;</li>
 *   <li>a block-light component API for mod developers (below);</li>
 *   <li>a brightness overdrive + colored-light approximation layer on the
 *       client lightmap;</li>
 *   <li>debug commands ({@code /hl ...}) and a test block.</li>
 * </ul>
 *
 * <h2>Usage for mod developers</h2>
 *
 * <p>Register the 16-bit emission component inside the block's
 * {@code BlockBehaviour.Properties.of()} chain. The function returned by
 * {@link #HLIGHT} / {@link #HlightLevel} is a drop-in argument for vanilla's
 * {@code Properties#lightLevel(ToIntFunction)}: vanilla keeps working with a
 * safe 0-15 fallback, while LHCE propagates the full 0-65535 channel.</p>
 *
 * <pre>{@code
 * // A blinding source: full brightness, huge coverage
 * public static final Block SUN_BLOCK = registerBlock("sun_block",
 *     () -> new Block(
 *         BlockBehaviour.Properties.of()
 *             .setId(BLOCK_ID)
 *             .strength(0.3F)
 *             .lightLevel(LightHLib.HLIGHT(state -> 50000))));
 *
 * // Same block, written with the HlightLevel component form
 * BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
 *     .setId(BLOCK_ID)
 *     .strength(0.3F);
 * LightHLib.HlightLevel(state -> 50000);   // stage the level component
 * LightHLib.HLightColor(255, 64, 0);        // stage the color component
 * new Block(props);                          // components bind to this block
 * }</pre>
 *
 * <p>Color variants: {@code LightHLib.HLightColor(int R, int G, int B)} and
 * {@code LightHLib.HLightColor(String rgbCode)} where {@code rgbCode} is
 * {@code "#RRGGBB"}. Stage the color right after the level component, before
 * the block instance is constructed. Blocks of other mods or vanilla blocks
 * can get a channel at runtime via {@link #HLIGHT(Block, ToIntFunction)}.</p>
 *
 * <h2>The HLight source object (ID signature)</h2>
 *
 * <p>A complete light source - 16-bit emission, color and a
 * {@link HLight#REGISTRY_KEY} ID signature - can be bundled into one
 * {@link HLight} object built with the {@link HLightDrop} chain:</p>
 *
 * <pre>{@code
 * HLight mylight = new HLight(
 *         ResourceKey.create(HLight.REGISTRY_KEY, myIdentifier("mylight")),
 *         new HLightDrop()
 *                 .HlightLevel(state -> 200)
 *                 .HLightColor(255, 64, 0));
 *
 * BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
 * props = ((HLightBlockProperties) props).setHLightEntity(mylight);
 * new MyBlock(props);   // emission + color + ID signature bound, and the
 *                       // vanilla 0-15 lightLevel fallback wired automatically
 * }</pre>
 *
 * <p>{@code setHLightEntity} is woven into {@code BlockBehaviour.Properties}
 * at runtime, so the duck-typed interface cast above is the portable form; no
 * other LightHLib chain call is needed for a block bound this way. HLight
 * objects can also be registered standalone with {@link HLight#register}
 * (pure light source definitions, lookup via {@link HLight#get}).</p>
 *
 * <p>Runtime queries:</p>
 * <ul>
 *   <li>{@link #getHLight(Level, BlockPos)} - the 16-bit level at a position</li>
 *   <li>{@link #getHLightColor(Level, BlockPos)} - the dominant 0xRRGGBB color</li>
 *   <li>{@link #LHCE(Level)} - the engine instance of a level</li>
 * </ul>
 */
public class LightHLib implements ModInitializer {
	public static final String MOD_ID = "lighthlib";

	/** The mod version, read from fabric.mod.json (expanded from gradle.properties). */
	public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("fabric-bata-v1.0");

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// ---------------------------------------------------------------------
	// Component API
	// ---------------------------------------------------------------------

	/**
	 * The main interface of the 16-bit light channel.
	 *
	 * <p>Returns a function suitable for vanilla's
	 * {@code BlockBehaviour.Properties#lightLevel(ToIntFunction)}:</p>
	 *
	 * <pre>{@code .lightLevel(LightHLib.HLIGHT(state -> 50000))}</pre>
	 *
	 * <p>The returned function feeds vanilla a clamped 0-15 fallback while
	 * automatically registering the full 0-65535 emission with LHCE for the
	 * owning block.</p>
	 *
	 * @param lightEmission a function computing the 16-bit emission of a state
	 *                      (values above 65535 are clamped)
	 */
	public static ToIntFunction<BlockState> HLIGHT(ToIntFunction<BlockState> lightEmission) {
		Objects.requireNonNull(lightEmission, "lightEmission");
		ToIntFunction<BlockState> wrapper = HLightRegistry.wrap(lightEmission);
		HLightRegistry.stage(lightEmission, wrapper, null);
		return wrapper;
	}

	/**
	 * The level component added into a block's
	 * {@code BlockBehaviour.Properties.of()} chain. Alias of {@link #HLIGHT}:
	 * both stage the full 16-bit emission; the returned function is meant for
	 * {@code Properties#lightLevel(...)}, or the component may be staged and
	 * bound implicitly when the block is constructed right afterwards.
	 *
	 * <pre>{@code
	 * LightHLib.HlightLevel(state -> 50000);
	 * LightHLib.HLightColor("#FF4000");
	 * new Block(BlockBehaviour.Properties.of().setId(id).strength(0.3F));
	 * }</pre>
	 *
	 * @param lightEmission a function computing the 16-bit emission of a state
	 */
	public static ToIntFunction<BlockState> HlightLevel(ToIntFunction<BlockState> lightEmission) {
		return HLIGHT(lightEmission);
	}

	/**
	 * The color component: sets the tint of the light emitted by the block.
	 *
	 * <p>Stage it right before the block's constructor (after the level
	 * component), or apply it to an existing block directly.</p>
	 *
	 * @param r red (0..255)
	 * @param g green (0..255)
	 * @param b blue (0..255)
	 */
	public static HLightComponent HLightColor(int r, int g, int b) {
		int rgb = HLightMath.rgb(r, g, b);
		HLightRegistry.stage(null, null, rgb);
		return new HLightComponent(null, rgb);
	}

	/**
	 * The color component, hexadecimal form. Example:
	 * {@code lightHlib.HLightColor("#FF4000")}.
	 *
	 * @param rgbCode a {@code "#RRGGBB"} or {@code "RRGGBB"} hex string
	 */
	public static HLightComponent HLightColor(String rgbCode) {
		int rgb = HLightMath.parseHexColor(rgbCode);
		HLightRegistry.stage(null, null, rgb);
		return new HLightComponent(null, rgb);
	}

	/** Binds a 16-bit emission channel to an arbitrary (vanilla or modded) block at runtime. */
	public static void HLIGHT(Block block, ToIntFunction<BlockState> lightEmission) {
		HLightRegistry.bindEmission(block, lightEmission, null);
		requestRescanAll();
	}

	/** Binds a 16-bit emission channel plus color to an arbitrary block at runtime. */
	public static void HLIGHT(Block block, ToIntFunction<BlockState> lightEmission, int r, int g, int b) {
		HLightRegistry.bindEmission(block, lightEmission, HLightMath.rgb(r, g, b));
		requestRescanAll();
	}

	/** Binds a light color to an arbitrary block at runtime. */
	public static void HLightColor(Block block, int r, int g, int b) {
		HLightRegistry.bindColor(block, HLightMath.rgb(r, g, b));
	}

	/** Binds a light color to an arbitrary block at runtime, hexadecimal form. */
	public static void HLightColor(Block block, String rgbCode) {
		HLightRegistry.bindColor(block, HLightMath.parseHexColor(rgbCode));
	}

	// --- per-source config override components ------------------------------

	/**
	 * Per-source {@code falloffPerBlock} override, staged the same way as the
	 * color component: call it inside the block's
	 * {@code BlockBehaviour.Properties.of()} registration, before the block
	 * constructor. Unset values fall back to the global {@code LHCEConfig}.
	 *
	 * <pre>{@code
	 * BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(id);
	 * LightHLib.HlightLevel(state -> 50000);
	 * LightHLib.HFalloffPerBlock(3);      // this source attenuates 3 levels/block
	 * LightHLib.HTintStrength(0.6);       // and tints at 60% strength
	 * new Block(props);
	 * }</pre>
	 */
	public static void HFalloffPerBlock(int falloff) {
		HLightRegistry.stageOverride("falloffPerBlock", falloff);
	}

	/** Per-source {@code fringeCurve} override (see {@link #HFalloffPerBlock}). */
	public static void HFringeCurve(double fringeCurve) {
		HLightRegistry.stageOverride("fringeCurve", fringeCurve);
	}

	/** Per-source {@code tintStrength} override (see {@link #HFalloffPerBlock}). */
	public static void HTintStrength(double tintStrength) {
		HLightRegistry.stageOverride("tintStrength", tintStrength);
	}

	/** Per-source {@code ambientFloor} override (see {@link #HFalloffPerBlock}). */
	public static void HAmbientFloor(double ambientFloor) {
		HLightRegistry.stageOverride("ambientFloor", ambientFloor);
	}

	/** Per-source {@code nearestSourceRadius} override (see {@link #HFalloffPerBlock}). */
	public static void HNearestSourceRadius(int nearestSourceRadius) {
		HLightRegistry.stageOverride("nearestSourceRadius", nearestSourceRadius);
	}

	/** Binds per-source config overrides to an arbitrary block at runtime. */
	public static void HLightOverrides(Block block, HLightRegistry.Overrides overrides) {
		HLightRegistry.bindOverrides(block, overrides);
	}

	// ---------------------------------------------------------------------
	// Runtime queries
	// ---------------------------------------------------------------------

	/** The 16-bit light level (0..65535) at a position, or 0 if unlit. */
	public static int getHLight(Level level, net.minecraft.core.BlockPos pos) {
		return LHCE(level).getHLight(pos);
	}

	/** The dominant light color at a position, packed 0xRRGGBB. */
	public static int getHLightColor(Level level, net.minecraft.core.BlockPos pos) {
		return LHCE(level).getColor(pos);
	}

	/** The LHCE engine instance of a level (never null; lazily created). */
	public static LHCEngine LHCE(Level level) {
		return LightHLibImpl.engine(level);
	}

	/** Asks the engine to recompute the field around a position (e.g. after changing BE data). */
	public static void requestRelight(Level level, net.minecraft.core.BlockPos pos) {
		LightHLibImpl.requestRelight(level, pos);
	}

	/**
	 * Sets a runtime per-position source color override (packed 0xRRGGBB): the
	 * source at {@code pos} is repainted with this color instead of its
	 * registered one, on the engine of {@code level}. Typically called from a
	 * light-source block entity when its color data changes, then followed by
	 * {@link #requestRelight(Level, net.minecraft.core.BlockPos)} (or
	 * {@link #forceReflood} for color-only changes).
	 */
	public static void setSourceColor(Level level, net.minecraft.core.BlockPos pos, int rgb) {
		LHCE(level).setSourceColor(pos, rgb);
	}

	/**
	 * Clears the runtime per-position source color override at a position.
	 * Called automatically when the block at {@code pos} stops being a light
	 * source (broken/replaced), so a new source at the same position starts
	 * from its registered color; call it yourself when you discard the block
	 * entity state that owns the override.
	 */
	public static void clearSourceColor(Level level, net.minecraft.core.BlockPos pos) {
		LHCE(level).clearSourceColor(pos);
	}

	/**
	 * Collapses the light field of the source at a position and re-floods it
	 * with the (possibly updated) per-position color override - the repaint
	 * path for color-only changes where a plain relight would find nothing to
	 * do. Equivalent to {@code LHCE(level).forceReflood(pos)}.
	 */
	public static void forceReflood(Level level, net.minecraft.core.BlockPos pos) {
		LHCE(level).forceReflood(pos);
	}

	/**
	 * Sets whether HLib takes over the rendering of vanilla light content
	 * (niris build only). {@code false} (default) keeps the entire vanilla
	 * byte range (0..240) rendering exactly vanilla - stepped 16-level
	 * curve, warm block-light tint, 1.0 output clamp - so installing the
	 * mod with no HLib source placed changes nothing vanilla. {@code true}
	 * applies HLib's enhanced lightmap to vanilla content too: continuous
	 * curve, lifted fringe, neutral-white tint and HDR output headroom
	 * (brighter, whiter torch light). Persists to
	 * {@code config/lighthlib.json} and takes effect from the next rendered
	 * frame. On the main (Sodium/Iris-compatible) build the flag is stored
	 * but has no rendering effect.
	 *
	 * @return the new value
	 */
	public static boolean HLibAccessToOriginalContent(boolean takeOver) {
		LHCEConfig fresh = new LHCEConfig();
		LHCEConfig live = LHCEConfig.get();
		fresh.nodeBudgetPerTick = live.nodeBudgetPerTick;
		fresh.falloffPerBlock = live.falloffPerBlock;
		fresh.fringeCurve = live.fringeCurve;
		fresh.nearestSourceRadius = live.nearestSourceRadius;
		fresh.ambientFloor = live.ambientFloor;
		fresh.tintStrength = live.tintStrength;
		fresh.nirisExtensionCurve = live.nirisExtensionCurve;
		fresh.enableColoredLight = live.enableColoredLight;
		fresh.logStartupInfo = live.logStartupInfo;
		fresh.takeOverVanillaLighting = takeOver;
		LHCEConfig.applyAndSave(fresh);
		return takeOver;
	}

	/**
	 * Whether HLib currently takes over the rendering of vanilla light
	 * content (niris build only; see
	 * {@link #HLibAccessToOriginalContent(boolean)}). Mirrors the
	 * {@code takeOverVanillaLighting} entry of {@code config/lighthlib.json}
	 * and the ModMenu toggle.
	 */
	public static boolean HLibAccessToOriginalContent() {
		return LHCEConfig.get().takeOverVanillaLighting;
	}

	/** Asks the engine to rescan every known chunk of this level (e.g. after runtime bindings). */
	public static void requestRescanAll(Level level) {
		LightHLibImpl.requestRescanAll(level);
	}

	private static void requestRescanAll() {
		// engines are per-level; rescan request applies once a level ticks
		RESCAN_ALL_REQUESTED = true;
	}

	/** Set when a runtime binding wants all levels rescanned. */
	static volatile boolean RESCAN_ALL_REQUESTED;

	// ---------------------------------------------------------------------
	// Registrations
	// ---------------------------------------------------------------------

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static final ResourceKey<Block> HL_TEST_BLOCK_KEY =
			ResourceKey.create(Registries.BLOCK, id("hl_test_block"));
	public static final ResourceKey<Item> HL_TEST_BLOCK_ITEM_KEY =
			ResourceKey.create(Registries.ITEM, id("hl_test_block"));
	public static final ResourceKey<BlockEntityType<?>> HL_TEST_BLOCK_BE_KEY =
			ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, id("hl_test_block"));

	/** Default light level of the test block (see {@code /hl testblock setlightlevel}). */
	public static final int TEST_BLOCK_DEFAULT_LEVEL = 50;

	/** The ID signature of the test block's built-in light source. */
	public static final ResourceKey<HLight> HL_TEST_LIGHT_KEY =
			ResourceKey.create(HLight.REGISTRY_KEY, id("test_light"));

	/** The test block's complete light source object: a block-entity driven
	 *  16-bit level with a warm color, registered under its ID signature. */
	public static final HLight HL_TEST_LIGHT = HLight.register(new HLight(HL_TEST_LIGHT_KEY,
			new HLightDrop()
					.HlightDynamic((level, pos, state) -> HLTestBlockEntity.levelAt(level, pos))
					.HLightColor(HLTestBlockEntity.DEFAULT_COLOR)));

	// NOTE: the test block deliberately has NO vanilla lightLevel fallback.
	// Its entire visible light comes from the LHCE 16-bit channel (the dynamic
	// emission source bound below reads the block entity), so every
	// /hl testblock setlightlevel change is directly visible in the world -
	// including going completely dark at 0. Vanilla's state-based lightLevel
	// cannot read block entity data, so any fallback there would be a constant.
	public static final HLTestBlock HL_TEST_BLOCK = Registry.register(
			BuiltInRegistries.BLOCK, HL_TEST_BLOCK_KEY,
			new HLTestBlock(BlockBehaviour.Properties.of()
					.setId(HL_TEST_BLOCK_KEY)
					.mapColor(net.minecraft.world.item.DyeColor.YELLOW)
					.sound(SoundType.AMETHYST)
					.strength(1.2F, 3.0F)
					.emissiveRendering(state -> true)));

	public static final BlockEntityType<HLTestBlockEntity> HL_TEST_BLOCK_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, HL_TEST_BLOCK_BE_KEY,
			new BlockEntityType<>(HLTestBlockEntity::new, Set.of(HL_TEST_BLOCK)));

	public static final BlockItem HL_TEST_BLOCK_ITEM = Registry.register(
			BuiltInRegistries.ITEM, HL_TEST_BLOCK_ITEM_KEY,
			new BlockItem(HL_TEST_BLOCK, new Item.Properties()
					.setId(HL_TEST_BLOCK_ITEM_KEY)
					.useBlockDescriptionPrefix()));

	@Override
	public void onInitialize() {
		LHCEConfig.get();

		// The test block's emission is positional (block-entity driven): its
		// complete light source - dynamic level, warm color and ID signature -
		// is the registered HL_TEST_LIGHT object.
		HLightRegistry.bindHLight(HL_TEST_BLOCK, HL_TEST_LIGHT);

		// Server lifecycle wiring
		ServerLevelEvents.LOAD.register(LightHLibImpl::onServerLevelLoad);
		ServerLevelEvents.UNLOAD.register(LightHLibImpl::onServerLevelUnload);
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (RESCAN_ALL_REQUESTED) {
				RESCAN_ALL_REQUESTED = false;
				LightHLibImpl.requestRescanAll(level);
			}
			LightHLibImpl.tickLevel(level);
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) ->
				LightHLibImpl.onChunkLoad(level, chunk.getPos()));
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
				LightHLibImpl.onChunkUnload(level, chunk.getPos()));

		// Commands
		CommandRegistrationCallback.EVENT.register(HLCommand::register);

		// Creative tab
		CreativeModeTabEvents.modifyOutputEvent(
						ResourceKey.create(Registries.CREATIVE_MODE_TAB,
								Identifier.fromNamespaceAndPath("minecraft", "functional_blocks")))
				.register(output -> output.accept(HL_TEST_BLOCK_ITEM));

		if (LHCEConfig.get().logStartupInfo) {
			LOGGER.info("LightHLib initialized: 16-bit light channel active (max {})", HLightMath.MAX_HLIGHT);
			LOGGER.info("LHCE: falloff={} level/block, budget={} work/tick, colored={}",
					LHCEConfig.get().falloffPerBlock,
					LHCEConfig.get().nodeBudgetPerTick,
					LHCEConfig.get().enableColoredLight);
		}
	}
}
