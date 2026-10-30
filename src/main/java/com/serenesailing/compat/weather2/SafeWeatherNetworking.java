package com.serenesailing.compat.weather2;

import com.serenesailing.SereneSailing;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.WeatherNetworkingBase;
import weather2.WeatherNetworkingv2;

import java.lang.reflect.Field;

/**
 * Thread-safe deep-copy networking wrapper for Weather2.
 *
 * <p>Weather2's {@code WeatherManagerServer.syncStormUpdate} and {@code syncWindUpdate} pass
 * live mutable {@link CompoundTag} references into {@code WeatherNetworkingBase.instance()}
 * without copying. When Netty encodes {@code PacketNBTFromServer} asynchronously on Netty Server IO,
 * concurrent mutation of the CompoundTag triggers a {@link java.util.ConcurrentModificationException}.
 *
 * <p>This wrapper intercepts all packet dispatch methods on the server thread and guarantees
 * that {@code PacketNBTFromServer} always receives an isolated {@link CompoundTag#copy()}.
 */
public class SafeWeatherNetworking extends WeatherNetworkingv2 {

    public SafeWeatherNetworking() {
        super();
    }

    public static void install() {
        try {
            SafeWeatherNetworking safeWrapper = new SafeWeatherNetworking();
            Field instanceField = WeatherNetworkingBase.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, safeWrapper);
            SereneSailing.LOGGER.info("[SereneSailing] Installed SafeWeatherNetworking deep-copy wrapper for Weather2.");
        } catch (Throwable t) {
            SereneSailing.LOGGER.error("[SereneSailing] Failed to install SafeWeatherNetworking wrapper for Weather2", t);
        }
    }

    @Override
    public void clientSendToServer(CompoundTag data) {
        super.clientSendToServer(data != null ? data.copy() : new CompoundTag());
    }

    @Override
    public void serverSendToClientAll(CompoundTag data) {
        super.serverSendToClientAll(data != null ? data.copy() : new CompoundTag());
    }

    @Override
    public void serverSendToClientPlayer(CompoundTag data, Player player) {
        super.serverSendToClientPlayer(data != null ? data.copy() : new CompoundTag(), player);
    }

    @Override
    public void serverSendToClientNear(CompoundTag data, Vec3 pos, double dist, Level level) {
        super.serverSendToClientNear(data != null ? data.copy() : new CompoundTag(), pos, dist, level);
    }

    @Override
    public void serverSendToClientsInDimension(CompoundTag data, Level level) {
        super.serverSendToClientsInDimension(data != null ? data.copy() : new CompoundTag(), level);
    }
}
