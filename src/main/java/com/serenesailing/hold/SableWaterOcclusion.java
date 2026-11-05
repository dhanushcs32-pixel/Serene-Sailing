package com.serenesailing.hold;

import com.serenesailing.SereneSailing;
import dev.ryanhcode.sable.render.water_occlusion.WaterOcclusionRenderer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import dev.ryanhcode.sable.util.BoundedBitVolume3i;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The ONLY class that touches Sable's {@code WaterOcclusionContainer} / {@code BoundedBitVolume3i}.
 *
 * <p>Signatures checked against Sable source at tag {@code mc1.21.1-2.0.0-neoforge} (the version this mod
 * compiles against): {@code WaterOcclusionContainer.getContainer(Level)}, {@code addRegion(BoundedBitVolume3i)},
 * {@code removeRegion(WaterOcclusionRegion)}; the volume is addressed in sub-level local (plot) coordinates.
 * Those signatures are unchanged through 2.0.6.
 *
 * <p>Sable's regions are not networked and the client container carries a "re-do all of this" TODO, so every
 * call is isolated here and failures degrade to "no Sable region": the mod's own fallback (entity mixin +
 * depth planes) keeps working without it. Each failure kind is logged once.
 */
public final class SableWaterOcclusion {

    private static boolean warnedAdd, warnedRemove;

    private SableWaterOcclusion() {
    }

    /** Registers the envelope's sealed volume as a water-occlusion region of this level, or null on failure. */
    @Nullable
    public static WaterOcclusionRegion add(Level level, HoldScanner.Envelope env) {
        if (env == null || !env.hasVolume()) {
            return null;
        }
        try {
            final WaterOcclusionContainer<?> container = WaterOcclusionContainer.getContainer(level);
            if (container == null) {
                return null; // wrapped / non-Sable level
            }
            return container.addRegion(toVolume(env));
        } catch (Throwable t) {
            if (!warnedAdd) {
                warnedAdd = true;
                SereneSailing.LOGGER.warn("[SereneSailing] Sable water occlusion region could not be registered; "
                        + "falling back to the built-in hold depth planes and entity mixin only.", t);
            }
            return null;
        }
    }

    public static void remove(Level level, @Nullable WaterOcclusionRegion region) {
        if (region == null) {
            return;
        }
        try {
            final WaterOcclusionContainer<?> container = WaterOcclusionContainer.getContainer(level);
            if (container != null) {
                container.removeRegion(region);
            }
        } catch (Throwable t) {
            if (!warnedRemove) {
                warnedRemove = true;
                SereneSailing.LOGGER.warn("[SereneSailing] Sable water occlusion region could not be removed.", t);
            }
        }
    }

    /** Sable's own answer, for diagnostics; the mod itself asks {@link HoldRegistry#isOccluded}. */
    public static boolean isOccluded(Level level, net.minecraft.world.phys.Vec3 worldPos) {
        try {
            final WaterOcclusionContainer<?> container = WaterOcclusionContainer.getContainer(level);
            return container != null && container.isOccluded(worldPos);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Client only: is Sable's translucent water-occlusion pass switched on (its sub_level_water_occlusion option)? */
    public static boolean clientRendererEnabled() {
        try {
            return WaterOcclusionRenderer.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One bit per sealed cell, tight bounds, local (plot) coordinates. */
    static BoundedBitVolume3i toVolume(HoldScanner.Envelope env) {
        final BoundedBitVolume3i volume = new BoundedBitVolume3i(
                env.volumeMinX(), env.volumeMinY(), env.volumeMinZ(),
                env.volumeMaxX(), env.volumeMaxY(), env.volumeMaxZ());
        for (int lz = env.volumeMinZ(); lz <= env.volumeMaxZ(); lz++) {
            for (int lx = env.volumeMinX(); lx <= env.volumeMaxX(); lx++) {
                final int lo = env.columnLo(lx, lz);
                final int hi = env.columnHi(lx, lz);
                for (int ly = lo; ly <= hi; ly++) {
                    volume.setOccupied(lx, ly, lz, true);
                }
            }
        }
        return volume;
    }
}

