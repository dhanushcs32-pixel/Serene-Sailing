package com.serenesailing.event;

import com.serenesailing.SereneSailing;
import com.serenesailing.network.WaveStatePayload;
import com.serenesailing.physics.waves.GerstnerWaveEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the wave-state sync.
 *
 * <p>Advances the Overworld's wave state once per server tick (previously it only advanced lazily when a ship
 * physics step happened to sample a wave, so an empty server froze while clients kept smoothing) and
 * broadcasts it to the dimension's players so every client evaluates the very same ocean.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID)
public final class WaveStateSync {

    public static final int SYNC_INTERVAL_TICKS = 10;

    private WaveStateSync() {}

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        GerstnerWaveEngine.DimensionWaveState st = GerstnerWaveEngine.getDimensionState(level);
        st.update(level, now);

        if (now % SYNC_INTERVAL_TICKS != 0L || level.players().isEmpty()) {
            return;
        }
        double[] scales = GerstnerWaveEngine.currentScales();
        PacketDistributor.sendToPlayersInDimension(level, new WaveStatePayload(
                now,
                (float) st.smoothedWindSpeed, (float) st.smoothedWindDirX, (float) st.smoothedWindDirZ,
                (float) st.smoothedStormIntensity,
                (float) st.targetWindSpeed, (float) st.targetWindDirX, (float) st.targetWindDirZ,
                (float) st.targetStormIntensity,
                (float) scales[0], (float) scales[1]));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GerstnerWaveEngine.resetAll();
    }
}

