package com.lighthlib.block;

import com.lighthlib.LightHLib;
import com.lighthlib.api.HLightMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Block entity of the LightHLib test block: stores the per-position 16-bit
 * light level and light color. The LHCE dynamic emission source reads the
 * level through {@link #levelAt(Level, BlockPos)}; the color feeds the
 * per-position source color override of the engine, so
 * {@code /hl testblock setlightlevel <level> #RRGGBB} recolors the glow.
 *
 * <p>Note: the stored light level is named {@code hlLevel} on purpose - a
 * field called {@code level} would shadow {@link BlockEntity#level}.</p>
 */
public class HLTestBlockEntity extends BlockEntity {
	public static final String TAG_LEVEL = "hl_level";
	public static final String TAG_COLOR = "hl_color";
	/** The default warm color of the test block (matches the registered one). */
	public static final int DEFAULT_COLOR = 0xFFD664;

	/** The stored 16-bit emission level of this test block. */
	private int hlLevel = HLTestBlock.DEFAULT_LEVEL;
	/** The stored light color of this test block, packed 0xRRGGBB. */
	private int hlColor = DEFAULT_COLOR;

	public HLTestBlockEntity(BlockPos pos, BlockState state) {
		super(LightHLib.HL_TEST_BLOCK_BE, pos, state);
	}

	/** The effective level of the test block at a position (0 if no BE). */
	public static int levelAt(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof HLTestBlockEntity be) {
			return be.getLevelValue();
		}
		return 0;
	}

	public int getLevelValue() {
		return this.hlLevel;
	}

	public void setLevelValue(int level) {
		this.hlLevel = Math.clamp(level, 0, HLightMath.MAX_HLIGHT);
	}

	public int getColorValue() {
		return this.hlColor;
	}

	public void setColorValue(int color) {
		this.hlColor = color & 0xFFFFFF;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt(TAG_LEVEL, this.hlLevel);
		output.putInt(TAG_COLOR, this.hlColor);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		int oldLevel = this.hlLevel;
		int oldColor = this.hlColor;
		this.hlLevel = Math.clamp(input.getIntOr(TAG_LEVEL, HLTestBlock.DEFAULT_LEVEL), 0, HLightMath.MAX_HLIGHT);
		this.hlColor = input.getIntOr(TAG_COLOR, DEFAULT_COLOR) & 0xFFFFFF;
		if (this.level != null && (this.hlLevel != oldLevel || this.hlColor != oldColor)) {
			// Data-only updates (e.g. the /hl command's sync packet) do not
			// produce a block-change event, so the LHCE engine of the
			// receiving side must be poked manually when the data changed.
			LightHLib.setSourceColor(this.level, this.worldPosition, this.hlColor);
			if (this.hlColor != oldColor && this.hlLevel == oldLevel) {
				// color-only change: collapse + re-flood repaints the glow
				LightHLib.forceReflood(this.level, this.worldPosition);
			} else {
				LightHLib.requestRelight(this.level, this.worldPosition);
			}
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
		CompoundTag tag = super.getUpdateTag(lookup);
		tag.putInt(TAG_LEVEL, this.hlLevel);
		tag.putInt(TAG_COLOR, this.hlColor);
		return tag;
	}
}
