package com.lighthlib.block;

import com.lighthlib.LightHLib;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The LightHLib test block.
 *
 * <p>Its 16-bit light level is stored per-position in a block entity and can
 * be changed at runtime with {@code /hl testblock setlightlevel <level>}
 * (default {@value LightHLib#TEST_BLOCK_DEFAULT_LEVEL}). Breaking it or mining
 * it does not require a tool.</p>
 */
public class HLTestBlock extends Block implements EntityBlock {
	public static final int DEFAULT_LEVEL = LightHLib.TEST_BLOCK_DEFAULT_LEVEL;

	public HLTestBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new HLTestBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		// no server-side ticking required
		return null;
	}
}
