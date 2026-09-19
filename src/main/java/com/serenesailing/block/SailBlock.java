package com.serenesailing.block;

import com.serenesailing.registry.ModSounds;
import com.serenesailing.physics.SailAerodynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import weather2.util.WindReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SailBlock extends Block implements SimpleWaterloggedBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /**
     * Reefing state: 0 = Furled (0% area), 1 = Reefed (50% area), 2 = Full Sail (100%).
     * Effective area scales as {@code A_eff = A_base * (sail_state * 0.5)}.
     */
    public static final IntegerProperty SAIL_STATE = IntegerProperty.create("sail_state", 0, 2);

    protected static final VoxelShape SHAPE_NORTH_SOUTH = Block.box(7.0, 0.0, 0.0, 9.0, 16.0, 16.0);
    protected static final VoxelShape SHAPE_EAST_WEST   = Block.box(0.0, 0.0, 7.0, 16.0, 16.0, 9.0);

    public SailBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WATERLOGGED, false)
                .setValue(SAIL_STATE, 2));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction direction = state.getValue(FACING);
        return (direction.getAxis() == Direction.Axis.X) ? SHAPE_EAST_WEST : SHAPE_NORTH_SOUTH;
    }

    /**
     * Calculates live propulsion force using Weather2 apparent wind.
     *
     * <p>WARNING: {@code pos} is interpreted as a WORLD position here. Inside an
     * assembled Sable sub-level, block positions are sub-level-local plot
     * coordinates - passing them straight to Weather2 samples the wrong sky cell
     * (the coordinate-collapse bug). Callers holding local positions must
     * transform them through the sub-level pose first and use
     * {@link #getPropulsionForce(Level, BlockPos, BlockState, Vector3d, Vec3)}.
     */
    public Vector3d getPropulsionForce(Level level, BlockPos pos, BlockState state, Vector3d contraptionVelocity) {
        return getPropulsionForce(level, pos, state, contraptionVelocity, Vec3.atCenterOf(pos));
    }

    /**
     * As above, but samples Weather2 at an explicit WORLD position
     * ({@code worldSamplePos}) while using {@code pos} only for block-state data.
     */
    public Vector3d getPropulsionForce(Level level, BlockPos pos, BlockState state, Vector3d contraptionVelocity, Vec3 worldSamplePos) {
        Direction facing = state.getValue(FACING);
        Vector3d forward = new Vector3d(facing.getStepX(), 0, facing.getStepZ());
        Vector3d normal = new Vector3d(facing.getClockWise().getStepX(), 0, facing.getClockWise().getStepZ());

        // Resolve a level Weather2 actually tracks (plot accessors / unregistered
        // dimensions read 0.0 wind forever otherwise).
        Level queryLevel = com.serenesailing.event.ServerPhysicsHandler.resolveWeatherQueryLevel(level);
        if (queryLevel == null) {
            return new Vector3d(0, 0, 0);
        }
        float windAngle = WindReader.getWindAngle(queryLevel, worldSamplePos);
        float windSpeed = WindReader.getWindSpeed(queryLevel, BlockPos.containing(worldSamplePos));
        double rad = Math.toRadians(windAngle);
        Vector3d trueWind = new Vector3d(-Math.sin(rad), 0, Math.cos(rad)).mul(Math.max(6.0, windSpeed * 20.0));

        return SailAerodynamics.calculateSailForce(trueWind, contraptionVelocity, forward, normal, 1.0);
    }

    public interface SubLevel {
        BlockPos localToWorld(BlockPos localPos);
    }

    /**
     * Diagnostic Test 3: Wind Vector Coordinate Transform Probe
     */
    public static void logWindTransformProbe(Level level, SubLevel subLevel, BlockPlaceContext ctx) {
        BlockPos worldPos = subLevel.localToWorld(ctx.getClickedPos());
        float windSpeed = WindReader.getWindSpeed(level, worldPos);
        float windAngle = WindReader.getWindAngle(level, Vec3.atCenterOf(worldPos));

        com.serenesailing.SereneSailing.LOGGER.info("Sail LocalPos: {} -> WorldPos: {} | WindSpeed: {}, WindAngle: {}", 
            ctx.getClickedPos(), worldPos, windSpeed, windAngle);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            int current = state.getValue(SAIL_STATE);
            // Right-click furls down: Full (2) -> Reefed (1) -> Furled (0) -> Full (2).
            // Sneak + right-click trims back up in reverse.
            int next = player.isShiftKeyDown()
                    ? (current + 1) % 3
                    : (current + 2) % 3;

            // Connected-component flood-fill: stacked/adjacent sails of the
            // same facing form one rig - trim/furl the whole sheet at once.
            List<BlockPos> sheet = findConnectedSailSheet(level, pos, state, MAX_SHEET_SIZE);

            for (BlockPos p : sheet) {
                BlockState s = level.getBlockState(p);
                if (s.getValue(SAIL_STATE) != next) {
                    level.setBlock(p, s.setValue(SAIL_STATE, next), Block.UPDATE_ALL);
                }
            }

            if (next != current) {
                // Canvas rustle once per sheet, pitch drops as canvas is let out.
                float pitch = 1.2f - (next / 2.0f) * 0.4f;
                level.playSound(null, pos, ModSounds.SAIL_RUSTLE.get(),
                        SoundSource.BLOCKS, 1.0F, pitch);
            }

            String label = describeSailState(next);
            player.displayClientMessage(Component.literal("\u00A7e" + Component.translatable(
                    "serenesailing.actionbar.sail_state", label).getString()
                    + String.format(" \u00A77(%d Canvas Blocks)", sheet.size())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Safety cap for the connected-sail-sheet flood-fill. */
    private static final int MAX_SHEET_SIZE = 64;

    /**
     * Breadth-first search over all face-adjacent {@link SailBlock} blocks that
     * share the clicked sail's {@code FACING} - i.e. one contiguous canvas
     * sheet on the same rigging plane.
     */
    private static List<BlockPos> findConnectedSailSheet(Level level, BlockPos origin,
                                                         BlockState originState, int cap) {
        Direction facing = originState.getValue(FACING);
        List<BlockPos> sheet = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty() && sheet.size() < cap) {
            BlockPos current = queue.poll();
            sheet.add(current);
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (visited.contains(neighbor)) {
                    continue;
                }
                visited.add(neighbor);
                BlockState neighborState = level.getBlockState(neighbor);
                if (neighborState.getBlock() instanceof SailBlock
                        && neighborState.getValue(FACING) == facing) {
                    queue.add(neighbor);
                }
            }
        }
        return sheet;
    }

    /** Human-readable label for a reefing state. */
    private static String describeSailState(int state) {
        return switch (state) {
            case 0 -> "Furled (0%)";
            case 1 -> "Reefed (50%)";
            default -> "Full Sail (100%)";
        };
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
        builder.add(FACING, WATERLOGGED, SAIL_STATE);
    }
}