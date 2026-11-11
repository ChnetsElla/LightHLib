package com.lighthlib.api;

import java.util.function.ToIntFunction;

/**
 * An immutable LightHLib block-light component.
 *
 * <p>Components are created through the static factories on
 * {@code com.lighthlib.LightHLib}:</p>
 *
 * <ul>
 *   <li>{@code LightHLib.HlightLevel(state -> 50000)} - the 16-bit emission component</li>
 *   <li>{@code LightHLib.HLightColor(255, 64, 0)} / {@code LightHLib.HLightColor("#FF4000")} - the color component</li>
 * </ul>
 *
 * <p>When created inside a block's {@code BlockBehaviour.Properties.of()} chain
 * (i.e. before the {@code Block} instance is constructed), components are
 * automatically bound to that block. See the javadoc of {@code LightHLib} for
 * end-to-end usage examples.</p>
 *
 * @param emission the 16-bit emission function, or {@code null} for a pure color component
 * @param colorRgb the packed {@code 0xRRGGBB} light color, or {@code -1} if not set
 */
public record HLightComponent(ToIntFunction<net.minecraft.world.level.block.state.BlockState> emission, int colorRgb) {

	public static final int NO_COLOR = -1;

	public HLightComponent {
		if (colorRgb != NO_COLOR) {
			colorRgb &= 0xFFFFFF;
		}
	}

	public boolean hasEmission() {
		return emission != null;
	}

	public boolean hasColor() {
		return colorRgb != NO_COLOR;
	}
}
