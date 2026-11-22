package com.lighthlib.client.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A composite row widget: a slider on the left and a separate value
 * {@code EditBox} on its right, laid out side by side (no overlap). The label
 * rides the slider's message text; the live value can be dragged (slider) or
 * typed (box), both sides stay in sync.
 *
 * <p>Clicks inside the box area go to the box (typing); clicks and drags
 * elsewhere move the slider. Typing clamps to min/max and moves the handle
 * live; incomplete input ("6.", "-") is left alone until it parses.</p>
 */
final class EditableSlider extends AbstractWidget {
	/** Width of the value EditBox placed beside the slider's right end. */
	private static final int BOX_WIDTH = 46;
	/** Horizontal gap between the slider and the EditBox. */
	private static final int BOX_GAP = 4;

	private final ButtonUtils.PlainSlider slider;
	private final EditBox box;
	private final double min;
	private final double max;
	private final java.util.function.DoubleSupplier getter;
	private final java.util.function.DoubleConsumer setter;
	private final java.util.function.DoubleUnaryOperator round;
	/** True while applying a value that came from the box: skip syncing the
	 *  box text back (the user is typing there). */
	private boolean fromBox;

	EditableSlider(Screen screen, String label, double min, double max,
			java.util.function.DoubleSupplier getter,
			java.util.function.DoubleConsumer setter,
			java.util.function.DoubleUnaryOperator round) {
		super(0, 0, 150, 20, Component.literal(label));
		this.min = min;
		this.max = max;
		this.getter = getter;
		this.setter = setter;
		this.round = round;
		this.slider = ButtonUtils.plainSlider(label, min, max, getter, v -> {
			if (this.fromBox) {
				return; // box-driven: the setter was already applied
			}
			this.setter.accept(v);
			refreshBoxText();
		});
		this.box = new EditBox(screen.getFont(), 0, 0, BOX_WIDTH, 18, Component.empty());
		this.box.setMaxLength(10);
		this.box.setBordered(true);
		refreshBoxText();
		this.box.setResponder(text -> {
			Double parsed = tryParse(text);
			if (parsed == null) {
				return; // incomplete input (e.g. "6.", "-"): wait for more typing
			}
			this.fromBox = true;
			try {
				double v = Math.clamp(parsed, this.min, this.max);
				this.setter.accept(v);
				this.slider.setExternal(v); // move the handle without re-setting
			} finally {
				this.fromBox = false;
			}
		});
		relayout();
	}

	/** Parses int/double text; null when not (yet) a complete number. */
	private static Double tryParse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Writes the current live value into the box (unless we are typing). */
	private void refreshBoxText() {
		if (this.fromBox || this.box.isFocused()) {
			return;
		}
		this.box.setValue(String.valueOf(this.round.applyAsDouble(this.getter.getAsDouble())));
	}

	// ---- layout: keep children glued to this widget's bounds ----

	@Override
	public void setX(int x) {
		super.setX(x);
		relayout();
	}

	@Override
	public void setY(int y) {
		super.setY(y);
		relayout();
	}

	@Override
	public void setWidth(int width) {
		super.setWidth(width);
		relayout();
	}

	@Override
	public void setHeight(int height) {
		super.setHeight(height);
		relayout();
	}

	private void relayout() {
		// side-by-side: the slider occupies the row minus the box and the gap,
		// so no pixel of the handle track sits under the input box
		this.slider.setX(getX());
		this.slider.setY(getY());
		this.slider.setWidth(getWidth() - BOX_WIDTH - BOX_GAP);
		this.slider.setHeight(getHeight());
		this.box.setX(getX() + getWidth() - BOX_WIDTH);
		this.box.setY(getY() + 1);
	}

	// ---- input routing: box area -> box, elsewhere -> slider ----

	private boolean inBox(double mouseX, double mouseY) {
		return this.box.isActive() && mouseX >= this.box.getX()
				&& mouseX < this.box.getX() + this.box.getWidth()
				&& mouseY >= this.box.getY() && mouseY < this.box.getY() + this.box.getHeight();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (inBox(event.x(), event.y())) {
			refreshBoxText();
			return this.box.mouseClicked(event, doubled);
		}
		this.box.setFocused(false);
		return this.slider.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.box.mouseReleased(event);
		return this.slider.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return this.slider.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.box.isFocused()) {
			if (event.keycode() == InputConstants.KEY_ESCAPE) {
				this.box.setFocused(false);
				refreshBoxText();
				return true;
			}
			return this.box.keyPressed(event);
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.box.isFocused()) {
			return this.box.charTyped(event);
		}
		return false;
	}

	@Override
	public void setFocused(boolean focused) {
		// focus follows the box only; the slider needs no keyboard focus
		this.box.setFocused(focused);
	}

	@Override
	public boolean isFocused() {
		return this.box.isFocused();
	}

	// ---- render: slider first, then the value box on top ----

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.slider.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
		this.box.extractWidgetRenderState(guiGraphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
		this.slider.updateWidgetNarration(output);
		this.box.updateWidgetNarration(output);
	}
}
