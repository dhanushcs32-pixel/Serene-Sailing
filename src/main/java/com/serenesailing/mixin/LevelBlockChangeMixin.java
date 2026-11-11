package com.serenesailing.mixin;

import com.serenesailing.hold.HoldRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the hold registry when a block of a ship changes, on BOTH logical sides.
 *
 * <p>A ship whose hold has not changed is scanned only every few seconds (see {@code HoldScanSchedule}); this hook is what
 * lets it wake up the moment a plank is broken or placed, so a breach floods the hold promptly and a repaired hull dries
 * out again, without scanning every ship every second. The server also receives NeoForge's neighbour-notify event, but the
 * client has no equivalent, and this single call site covers both.
 *
 * <p>{@code Level.setBlock} only reaches {@code onBlockStateChange} when the state really changed. The handler is a map
 * lookup for levels without ships. {@code require = 0}: if the target ever changes, nothing breaks and the registry simply
 * falls back to its timers.
 */
@Mixin(Level.class)
public abstract class LevelBlockChangeMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;onBlockStateChange"),
            require = 0)
    private void serenesailing$shipBlockChanged(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                CallbackInfoReturnable<Boolean> cir) {
        HoldRegistry.markDirty((Level) (Object) this, pos);
    }
}

