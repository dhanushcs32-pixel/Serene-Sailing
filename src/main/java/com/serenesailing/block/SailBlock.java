package com.serenesailing.block;

import com.serenesailing.registry.ModSounds;
import com.serenesailing.physics.SailAerodynamics;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SailBlock extends Block implements SimpleWaterloggedBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /**
     * Reefing state: 0 = Furled (0% area), 1 = Reefed (50% area), 2 = Full Sail (100%).
     * Effective area scales as {@code A_eff = A_base * (sail_state * 0.5)}.
     */
    public static final IntegerProperty SAIL_STATE = IntegerProperty.create("sail_state", 0, 2);
    public static final EnumProperty<SailCanvas> CANVAS = EnumProperty.create("canvas", SailCanvas.class);

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // When the sail renders on the back side of the block (0 to 4 px):
        SHAPES.put(Direction.NORTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 4.0));
        SHAPES.put(Direction.SOUTH, Block.box(0.0, 0.0, 12.0, 16.0, 16.0, 16.0));
        SHAPES.put(Direction.WEST,  Block.box(0.0, 0.0, 0.0, 4.0, 16.0, 16.0));
        SHAPES.put(Direction.EAST,  Block.box(12.0, 0.0, 0.0, 16.0, 16.0, 16.0));
    }

    public SailBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WATERLOGGED, false)
                .setValue(SAIL_STATE, 2)
                .setValue(CANVAS, SailCanvas.FULL));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return SHAPES.getOrDefault(facing, SHAPES.get(Direction.NORTH));
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
        // FACING points toward the player (away from the bow) due to
        // getHorizontalDirection().getOpposite() in getStateForPlacement().
        // The ship's forward (bow) direction is the opposite of FACING.
        Direction facing = state.getValue(FACING);
        Direction bowDir = facing.getOpposite();
        Vector3d forward = new Vector3d(bowDir.getStepX(), 0, bowDir.getStepZ());
        Vector3d normal = new Vector3d(bowDir.getClockWise().getStepX(), 0, bowDir.getClockWise().getStepZ());

        com.serenesailing.event.ServerPhysicsHandler.CachedWind cachedWind =
                com.serenesailing.event.ServerPhysicsHandler.getDimensionWind(level.dimension());
        float windAngle = cachedWind.angle();
        float windSpeed = cachedWind.speed();
        double rad = Math.toRadians(windAngle);
        // FIX (Bug 3): Use configurable MIN_WIND_SPEED instead of hardcoded 6.0 m/s floor.
        // The old hardcoded floor caused per-block force diagnostics to show thrust
        // in dead calm, inconsistent with the physics handler which uses SailingConfig.
        double minWind = com.serenesailing.config.SailingConfig.MIN_WIND_SPEED.get();
        Vector3d trueWind = new Vector3d(-Math.sin(rad), 0, Math.cos(rad)).mul(Math.max(minWind, windSpeed * 20.0));

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
        com.serenesailing.event.ServerPhysicsHandler.CachedWind cachedWind =
                com.serenesailing.event.ServerPhysicsHandler.getDimensionWind(level.dimension());
        float windSpeed = cachedWind.speed();
        float windAngle = cachedWind.angle();

        com.serenesailing.SereneSailing.LOGGER.debug("Sail LocalPos: {} -> WorldPos: {} | WindSpeed: {}, WindAngle: {}", 
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

            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (BlockPos p : sheet) {
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
            }
            int totalHeight = maxY - minY + 1;
            double bottomEdge = (maxY + 1.0) - (totalHeight * 0.5);

            for (BlockPos p : sheet) {
                BlockState s = level.getBlockState(p);
                SailCanvas canvas;
                if (next == 2) {
                    canvas = SailCanvas.FULL;
                } else if (next == 0) {
                    canvas = (p.getY() == maxY) ? SailCanvas.ROLLED : SailCanvas.EMPTY;
                } else {
                    if (p.getY() >= bottomEdge) {
                        canvas = SailCanvas.FULL;
                    } else if (p.getY() + 1.0 <= bottomEdge) {
                        canvas = SailCanvas.EMPTY;
                    } else {
                        canvas = SailCanvas.HALF;
                    }
                }
                level.setBlock(p, s.setValue(SAIL_STATE, next).setValue(CANVAS, canvas), Block.UPDATE_ALL);
            }

            if (level instanceof ServerLevel serverLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container instanceof ServerSubLevelContainer serverContainer) {
                    LevelPlot plot = serverContainer.getPlot(new ChunkPos(pos));
                    if (plot != null && plot.getSubLevel() instanceof ServerSubLevel ssl) {
                        com.serenesailing.event.ServerPhysicsHandler.invalidateSteeringCache(ssl.getUniqueId());
                    }
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
        builder.add(FACING, WATERLOGGED, SAIL_STATE, CANVAS);
    }
}