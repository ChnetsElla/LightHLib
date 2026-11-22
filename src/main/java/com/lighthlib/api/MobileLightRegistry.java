package com.lighthlib.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Registry of <b>mobile</b> HLib light sources: entity types and items, as
 * opposed to the block-keyed bindings of {@link HLightRegistry}.
 *
 * <p>Bindings are established once via {@code LightHLib.HLIGHT(...)} and are
 * resolved per entity, per tick, by the mobile-source sync (on each engine's
 * own thread): an entity carries its own type binding, an {@link ItemEntity}
 * carries its stack's item binding, and a living entity additionally carries
 * the strongest item binding of either hand (a held glowing item, like a
 * dynamic-lights mod but with the full 16-bit channel and color).</p>
 *
 * <p>The resolved lights are applied to the engine as transient position
 * sources that follow the entity; the provenance colors, per-source config
 * overrides and attenuation curves flow through the same machinery as block
 * sources. The sync collapses the old cell when a source moves, despawns or
 * is unloaded, so no phantom light is left behind.</p>
 */
public final class MobileLightRegistry {
	/** A resolved mobile light: the clamped 16-bit emission, the packed
	 *  0xRRGGBB color and optional per-source config overrides. */
	public record Resolved(int emission, int colorRgb, HLightRegistry.Overrides overrides) {
	}

	/** One registered binding: either an entity-instance function or an
	 *  item-stack function (never both), plus color and overrides. */
	private record Binding(ToIntFunction<Entity> entityEmission, ToIntFunction<ItemStack> itemEmission,
			int colorRgb, HLightRegistry.Overrides overrides) {
	}

	private static final Map<EntityType<?>, Binding> ENTITY_BINDINGS = new ConcurrentHashMap<>();
	private static final Map<Item, Binding> ITEM_BINDINGS = new ConcurrentHashMap<>();

	private MobileLightRegistry() {
	}

	/** True when any entity type or item carries a mobile light binding. The
	 *  per-tick sync early-outs on this (zero cost when unused). */
	public static boolean hasAnyBindings() {
		return !ENTITY_BINDINGS.isEmpty() || !ITEM_BINDINGS.isEmpty();
	}

	/** Binds a colored HLib light to every entity of {@code type}: a mobile
	 *  16-bit source that follows the entity. */
	public static void bindEntity(EntityType<?> type, ToIntFunction<Entity> emission, Integer color,
			HLightRegistry.Overrides overrides) {
		if (type == null || emission == null) {
			return;
		}
		ENTITY_BINDINGS.put(type, new Binding(emission, null,
				color != null ? color & 0xFFFFFF : HLightMath.WHITE_RGB,
				sanitize(overrides)));
	}

	/** Binds a colored HLib light to an item: dropped {@link ItemEntity}s
	 *  emit it, and a living entity holding it in either hand carries it. */
	public static void bindItem(Item item, ToIntFunction<ItemStack> emission, Integer color,
			HLightRegistry.Overrides overrides) {
		if (item == null || emission == null) {
			return;
		}
		ITEM_BINDINGS.put(item, new Binding(null, emission,
				color != null ? color & 0xFFFFFF : HLightMath.WHITE_RGB,
				sanitize(overrides)));
	}

	/** Binds a registered {@link HLight} object to every entity of the type:
	 *  the HLight-object binding style, mirroring the block-side
	 *  {@code setHLightEntity} properties chain. The HLight must define an
	 *  entity level ({@code HLightDrop.HEntityLevel}); silently ignored
	 *  otherwise. */
	public static void bindEntity(EntityType<?> type, HLight light) {
		if (type == null || light == null || light.entityFunction() == null) {
			return;
		}
		ENTITY_BINDINGS.put(type, new Binding(light::entityEmissionAt, null,
				light.hasColor() ? light.color() & 0xFFFFFF : HLightMath.WHITE_RGB,
				sanitize(light.overrides())));
	}

	/** Binds a registered {@link HLight} object to an item (dropped stacks
	 *  emit it; a living entity holding it in either hand carries it): the
	 *  HLight-object binding style, mirroring the block-side
	 *  {@code setHLightEntity} properties chain. The HLight must define an
	 *  item level ({@code HLightDrop.HItemLevel}); silently ignored
	 *  otherwise. */
	public static void bindItem(Item item, HLight light) {
		if (item == null || light == null || light.itemFunction() == null) {
			return;
		}
		ITEM_BINDINGS.put(item, new Binding(null, light::itemEmissionAt,
				light.hasColor() ? light.color() & 0xFFFFFF : HLightMath.WHITE_RGB,
				sanitize(light.overrides())));
	}

	private static HLightRegistry.Overrides sanitize(HLightRegistry.Overrides overrides) {
		return overrides != null && !overrides.isEmpty() ? overrides.sanitized() : null;
	}

	/**
	 * Resolves the effective mobile light of one entity: its own type
	 * binding, else (for item entities) the stack's item binding, else (for
	 * living entities) the strongest item binding of either hand. Returns
	 * null when nothing binds or the resolved emission is zero (the sync
	 * then treats the entity as unlit).
	 */
	public static Resolved resolve(Entity entity) {
		Binding binding = ENTITY_BINDINGS.get(entity.getType());
		if (binding != null && binding.entityEmission() != null) {
			int e = HLightMath.clampHLight(binding.entityEmission().applyAsInt(entity));
			if (e > 0) {
				return new Resolved(e, binding.colorRgb(), binding.overrides());
			}
		}
		if (entity instanceof ItemEntity itemEntity) {
			return resolveStack(itemEntity.getItem());
		}
		if (entity instanceof LivingEntity living) {
			// main hand wins over off hand on equal emission (checked first)
			Resolved hand = resolveStack(living.getMainHandItem());
			if (hand == null) {
				hand = resolveStack(living.getOffhandItem());
			}
			return hand;
		}
		return null;
	}

	private static Resolved resolveStack(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		Binding binding = ITEM_BINDINGS.get(stack.getItem());
		if (binding == null) {
			return null;
		}
		int e = HLightMath.clampHLight(binding.itemEmission().applyAsInt(stack));
		return e > 0 ? new Resolved(e, binding.colorRgb(), binding.overrides()) : null;
	}
}
