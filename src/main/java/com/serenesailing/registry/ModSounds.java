package com.serenesailing.registry;

import com.serenesailing.SereneSailing;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Custom nautical sound events. The actual audio samples are mapped in
 * {@code assets/serenesailing/sounds.json}.
 */
public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, SereneSailing.MOD_ID);

    /** Canvas adjustment / reefing rustle. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SAIL_RUSTLE = register("sail_rustle");
    /** Ambient hull stress creak under high yaw torque / wave roll. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHIP_CREAK = register("ship_creak");
    /** Wind billow while the sails generate near-maximum lift. */
    public static final DeferredHolder<SoundEvent, SoundEvent> WIND_BILLOW = register("wind_billow");
    /** Ratcheting click when the capstan anchor is toggled. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CAPSTAN_CRANK = register("capstan_crank");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(SereneSailing.MOD_ID, name)));
    }
}
