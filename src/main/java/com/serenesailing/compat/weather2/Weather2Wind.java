package com.serenesailing.compat.weather2;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import weather2.util.WindReader;

/**
 * The ONLY place besides {@link Weather2Compat} that touches Weather2 classes for wind. Weather2 is an OPTIONAL
 * dependency, so everything that references {@code weather2.*} must stay behind {@link #isLoaded()}: a direct
 * {@code WindReader} call from always-loaded code throws NoClassDefFoundError on every tick when the mod is absent.
 */
public final class Weather2Wind {

    private static Boolean loaded;

    private Weather2Wind() {
    }

    /** True when Weather2 is installed (cached; false before the mod list exists, e.g. in unit tests). */
    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached == null) {
            final ModList list = ModList.get();
            if (list == null) {
                return false; // too early / headless: do not cache
            }
            cached = list.isLoaded("weather2");
            loaded = cached;
        }
        return cached;
    }

    /** Does Weather2 run a weather manager for this level? Only call when {@link #isLoaded()}. */
    public static boolean hasManager(ServerLevel level) {
        return WindReader.getWeatherManagerFor(level) != null;
    }

    /** Only call when {@link #isLoaded()}. */
    public static float windAngle(ServerLevel level) {
        return WindReader.getWindAngle(level);
    }

    /** Only call when {@link #isLoaded()}. */
    public static float windSpeed(ServerLevel level) {
        return WindReader.getWindSpeed(level);
    }
}

