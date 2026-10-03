package com.serenesailing.block;

import com.serenesailing.entity.TaffrailLogBlockEntity;
import com.serenesailing.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Stern taffrail log: a rotor sensor streamed astern through the water.
 * Reports ship speed in knots (m/s) via right-click and emits a redstone
 * comparator signal proportional to 0-15 knots. Speed telemetry is pushed
 * from the ship physics handler; mounted ashore it reads zero.
 */
public class TaffrailLogBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** Slim horizontal rotor housing streaming aft. */
    protected static final VoxelShape SHAPE = Block.box(0.0, 6.0, 6.0, 16.0, 10.0, 10.0);

    public TaffrailLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TaffrailLogBlockEntity log
                ? log.getRedstoneSignal() : 0;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TaffrailLogBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.TAFFRAIL_LOG.get()) {
            return (lvl, pos, st, be) -> ((TaffrailLogBlockEntity) be).tick();
        }
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            TaffrailLogBlockEntity log = null;
            if (level.getBlockEntity(pos) instanceof TaffrailLogBlockEntity be) {
                log = be;
            } else if (level instanceof ServerLevel serverLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container instanceof ServerSubLevelContainer serverContainer) {
                    LevelPlot plot = serverContainer.getPlot(new ChunkPos(pos));
                    if (plot != null) {
                        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                            if (holder.getChunk() != null && holder.getChunk().getBlockEntity(pos) instanceof TaffrailLogBlockEntity be) {
                                log = be;
                                break;
                            }
                        }
                    }
                }
            }
            if (log != null) {
                player.displayClientMessage(Component.literal(log.getActionBarDisplay()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
