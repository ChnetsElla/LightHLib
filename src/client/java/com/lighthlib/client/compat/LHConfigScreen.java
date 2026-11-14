package com.lighthlib.client.compat;

import java.util.function.Consumer;

import com.lighthlib.api.HLightMath;
import com.lighthlib.client.LightHLibClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Vanilla-style configuration screen for LightHLib (opened from ModMenu).
 *
 * <p>No scrolling list: the 11 config widgets are laid out directly on the
 * screen in two columns (5 + 6), with the label baked into each widget's
 * message text, plus "保存并关闭" / "取消" buttons in the footer. Direct
 * widget placement keeps MC 26.3's extract-render pipeline (positioning and
 * hit-testing) working without a custom list container.</p>
 */
public final class LHConfigScreen extends Screen {
	private final Screen parent;

	// live values being edited (copied from the current config on open)
	int nodeBudgetPerTick;
	int falloffPerBlock;
	double fringeCurve;
	boolean enableColoredLight;
	boolean logStartupInfo;
	int nearestSourceRadius;
	double ambientFloor;
	double tintStrength;
	double nirisExtensionCurve;
	boolean takeOverVanillaLighting;
	boolean enableColorBlending;

	public LHConfigScreen(Screen parent) {
		super(Component.literal("LightHLib 配置"));
		this.parent = parent;
		LHCEConfigHolder.loadInto(this);
	}

	@Override
	protected void init() {
		int colW = 200;
		int gap = 10;
		int leftX = (this.width - colW * 2 - gap) / 2;
		int rightX = leftX + colW + gap;
		int topY = 50;
		int rowH = 26;

		// left column (5 rows)
		addRenderableWidget(col(leftX, topY, "节点预算/刻", sliderInt("节点预算/刻", 1024, 1 << 22,
				() -> nodeBudgetPerTick, v -> nodeBudgetPerTick = v)));
		addRenderableWidget(col(leftX, topY + rowH, "每方块衰减", sliderInt("每方块衰减", 1, 7,
				() -> falloffPerBlock, v -> falloffPerBlock = v)));
		addRenderableWidget(col(leftX, topY + rowH * 2, "边缘曲线", sliderDouble("边缘曲线", 1.0, 2.0,
				() -> fringeCurve, v -> fringeCurve = v)));
		addRenderableWidget(col(leftX, topY + rowH * 3, "光源搜索半径", sliderInt("光源搜索半径", 16, 1024,
				() -> nearestSourceRadius, v -> nearestSourceRadius = v)));
		addRenderableWidget(col(leftX, topY + rowH * 4, "染色强度", sliderDouble("染色强度", 0.0, 2.0,
				() -> tintStrength, v -> tintStrength = v)));

		// right column (6 rows): the two color switches lead at the top
		addRenderableWidget(col(rightX, topY, "彩色光照", toggle("彩色光照", enableColoredLight,
				v -> enableColoredLight = v)));
		addRenderableWidget(col(rightX, topY + rowH, "重叠混色", toggle("重叠混色",
				enableColorBlending, v -> enableColorBlending = v)));
		addRenderableWidget(col(rightX, topY + rowH * 2, "启动日志", toggle("启动日志", logStartupInfo,
				v -> logStartupInfo = v)));
		addRenderableWidget(col(rightX, topY + rowH * 3, "环境光下限", sliderDouble("环境光下限", 0.05, 2.0,
				() -> ambientFloor, v -> ambientFloor = v)));
		addRenderableWidget(col(rightX, topY + rowH * 4, "niris 扩展曲线", sliderDouble("niris 扩展曲线", 0.3, 2.0,
				() -> nirisExtensionCurve, v -> nirisExtensionCurve = v)));
		addRenderableWidget(col(rightX, topY + rowH * 5,
				HLightMath.NIRIS ? "接管原版光照" : "接管原版光照(仅niris)",
				toggle(HLightMath.NIRIS ? "接管原版光照" : "接管原版光照(仅niris)",
						takeOverVanillaLighting, v -> takeOverVanillaLighting = v)));
		// footer
		addRenderableWidget(Button.builder(Component.literal("保存并关闭"), b -> saveAndClose())
				.bounds(leftX, this.height - 30, colW, 20).build());
		addRenderableWidget(Button.builder(Component.literal("取消"),
				b -> this.minecraft.gui.setScreen(this.parent))
				.bounds(rightX, this.height - 30, colW, 20).build());
	}

	private AbstractWidget col(int x, int y, String label, AbstractWidget widget) {
		widget.setX(x);
		widget.setY(y);
		widget.setWidth(200);
		widget.setHeight(20);
		return widget;
	}

	private AbstractWidget toggle(String label, boolean initial, Consumer<Boolean> setter) {
		return CycleButton.onOffBuilder(initial)
				.create(Component.literal(label), (btn, value) -> setter.accept(value));
	}

	private AbstractWidget sliderInt(String label, int min, int max,
			java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
		return new EditableSlider(this, label, min, max,
				() -> (double) getter.getAsInt(),
				v -> setter.accept((int) Math.round(v)),
				v -> (double) (int) Math.round(v));
	}

	private AbstractWidget sliderDouble(String label, double min, double max,
			java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter) {
		return new EditableSlider(this, label, min, max, getter, setter,
				v -> Double.parseDouble(String.format("%.3f", v)));
	}

	private void saveAndClose() {
		LHCEConfigHolder.saveFrom(this);
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
