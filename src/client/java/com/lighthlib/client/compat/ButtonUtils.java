package com.lighthlib.client.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Helpers to build the vanilla widgets used by {@link LHConfigScreen}
 * (Button via its builder, boolean toggles via CycleButton.onOffBuilder,
 * numeric fields via anonymous AbstractSliderButton subclasses).
 */
final class ButtonUtils {
	private ButtonUtils() {
	}

	static Button button(Component label, Button.OnPress onPress, int x, int y, int width) {
		return Button.builder(label, onPress).bounds(x, y, width, 20).build();
	}

	static CycleButton<Boolean> toggle(String label, boolean initial, java.util.function.Consumer<Boolean> setter) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(initial)
				.create(Component.literal(label), (btn, value) -> setter.accept(value));
		button.setWidth(150);
		return button;
	}

	/** Double slider with fixed min/max; the getter/setter keep the live value.
	 *  The label is baked into the widget's message ("label: value"). */
	static AbstractSliderButton slider(String label, double min, double max, double initial,
			java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter,
			java.util.function.DoubleUnaryOperator formatter) {
		return new AbstractSliderButton(0, 0, 150, 20, Component.empty(), (initial - min) / (max - min)) {
			// bake the "label: value" message right after construction so it is
			// visible on the first extracted frame
			{
				updateMessage();
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal(label + ": " + formatter.applyAsDouble(getter.getAsDouble())));
			}

			@Override
			protected void applyValue() {
				setter.accept(min + this.value * (max - min));
			}
		};
	}

	static AbstractWidget width(AbstractWidget widget, int width) {
		widget.setWidth(width);
		return widget;
	}
}
