package com.serenesailing.block;

import com.serenesailing.entity.CapstanBlockEntity;
import com.serenesailing.event.ServerPhysicsHandler;
import com.serenesailing.registry.ModBlockEntities;
import com.serenesailing.registry.ModSounds;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Anchor winch. Right-click toggles the anchor: while anchored, the vessel's
 * planar drift and yaw rate are clamped to zero by the ship physics.
 */
public class CapstanBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** Visual + physics flag: true once the anchor is dropped. */
    public static final BooleanProperty ANCHORED = BooleanProperty.create("anchored");

    protected static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 10.0, 13.0);

    public CapstanBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ANCHORED, false));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CapstanBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type == ModBlockEntities.CAPSTAN.get()) {
            // Advances the chain winch both on the server (seabed raycast +
            // authoritative length) and on the client (animation toward the
            // last synced length).
            return (lvl, pos, st, be) -> ((CapstanBlockEntity) be).tick();
        }
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            boolean newAnchored = !state.getValue(ANCHORED);
            // Keep blockstate and block entity in lock-step so both the visual
            // model and the physics scan (either source) agree on anchor state.
            level.setBlock(pos, state.setValue(ANCHORED, newAnchored), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof CapstanBlockEntity capstanBE) {
                capstanBE.setAnchored(newAnchored);
                capstanBE.setChanged();
            }
            level.sendBlockUpdated(pos, state, state.setValue(ANCHORED, newAnchored), 3);
            if (level instanceof ServerLevel serverLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container instanceof ServerSubLevelContainer serverContainer) {
                    LevelPlot plot = serverContainer.getPlot(new ChunkPos(pos));
                    if (plot != null && plot.getSubLevel() instanceof ServerSubLevel ssl) {
                        ServerPhysicsHandler.invalidateSteeringCache(ssl.getUniqueId());
                    }
                }
            }
            level.playSound(null, pos, ModSounds.CAPSTAN_CRANK.get(), SoundSource.BLOCKS,
                    1.0F, newAnchored ? 0.8F : 1.2F);
            player.displayClientMessage(Component.literal(
                    newAnchored ? "\u00A7bAnchor: Dropped (Moored)"
                                : "\u00A7aAnchor: Weighed (Underway)"), true);
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
        builder.add(FACING, ANCHORED);
    }
}
