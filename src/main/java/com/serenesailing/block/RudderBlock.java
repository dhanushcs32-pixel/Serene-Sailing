package com.serenesailing.block;

import com.serenesailing.entity.RudderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class RudderBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    // 2-pixel-thick centered pane shapes matching the block model
    // (blade occupies x=[7,9] at y-rotation 0; the blockstate rotates it).
    protected static final VoxelShape SHAPE_NORTH_SOUTH = Block.box(7.0, 0.0, 0.0, 9.0, 16.0, 16.0);
    protected static final VoxelShape SHAPE_EAST_WEST   = Block.box(0.0, 0.0, 7.0, 16.0, 16.0, 9.0);

    public RudderBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WATERLOGGED, false));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction direction = state.getValue(FACING);
        return (direction.getAxis() == Direction.Axis.X) ? SHAPE_EAST_WEST : SHAPE_NORTH_SOUTH;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return com.serenesailing.registry.ModBlockEntities.RUDDER.get().create(pos, state);
    }

    private InteractionResult handleSteeringClick(Level level, BlockPos pos, Player player) {
        // IMPORTANT: the angle is mutated SERVER-ONLY. If the client also
        // cycled the angle locally, client and server would both step the
        // value each click (double-stepping) and fight via sync packets -
        // the classic rudder "glitch/jitter". The client only gets
        // sidedSuccess(...) for the arm-swing animation and then receives
        // the authoritative angle through RudderBlockEntity's update packet.
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RudderBlockEntity rudder) {
                float before = rudder.getSteeringAngle();
                // Right-click: starboard (+15). Sneak + right-click: port (-15).
                // Clamped to [-30, +30] by the block entity - five discrete states.
                float step = player.isShiftKeyDown() ? -15.0f : 15.0f;
                rudder.cycleSteering(step);
                float after = rudder.getSteeringAngle();

                if (after != before) {
                    // Creak pitch rises as the blade swings from hard port to hard starboard.
                    level.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS,
                            0.7f, 0.8f + (after + 45.0f) / 90.0f * 0.4f);
                }

                player.displayClientMessage(Component.literal(
                        "\u00A7e" + Component.translatable("serenesailing.actionbar.rudder_angle",
                                describeAngle(after)).getString()),
                        true);
            }
        }
        // sidedSuccess: SUCCESS on server (consumes the click, swings arm),
        // PASS-to-success on client - never re-triggers continuously.
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Human-readable name for the discrete rudder helm states. */
    private static String describeAngle(float angle) {
        if (angle <= -37.5f) {
            return "Hard Port (-45\u00B0)";
        }
        if (angle <= -22.5f) {
            return "Port (-30\u00B0)";
        }
        if (angle <= -7.5f) {
            return "Port (-15\u00B0)";
        }
        if (angle < 7.5f) {
            return "Centered (0\u00B0)";
        }
        if (angle < 22.5f) {
            return "Starboard (+15\u00B0)";
        }
        if (angle < 37.5f) {
            return "Starboard (+30\u00B0)";
        }
        return "Hard Starboard (+45\u00B0)";
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return handleSteeringClick(level, pos, player);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        handleSteeringClick(level, pos, player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluidState = context.getLevel().getFluidState(context.getClickedPos());
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(WATERLOGGED, fluidState.getType() == Fluids.WATER);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED);
    }
}