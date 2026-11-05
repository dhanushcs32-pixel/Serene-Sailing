package com.serenesailing.client;

import com.serenesailing.SereneSailing;
import com.serenesailing.hold.HoldRegistry;
import com.serenesailing.hold.SableWaterOcclusion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * {@code /serenesailing holddebug}: stand where the water feels wrong and run it. Prints what the hold registry,
 * Sable's occlusion container and the player's own fluid state say, so "invisible water that still slows me down"
 * can be pinned on one layer instead of guessed at.
 */
@EventBusSubscriber(modid = SereneSailing.MOD_ID, value = Dist.CLIENT)
public final class HoldDebugCommand {

    private HoldDebugCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("serenesailing")
                .then(Commands.literal("holddebug").executes(ctx -> run(ctx.getSource()))));
    }

    private static int run(CommandSourceStack source) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || Minecraft.getInstance().level == null) {
            return 0;
        }
        final var level = Minecraft.getInstance().level;
        final Vec3 feet = player.position();
        final Vec3 eye = player.getEyePosition();
        say(source, "--- Serene Sailing hold debug ---");
        say(source, String.format("feet=(%.2f, %.2f, %.2f) eye.y=%.3f", feet.x, feet.y, feet.z, eye.y));
        for (final String line : HoldRegistry.describe(level, feet)) {
            say(source, line);
        }
        say(source, "mod registry:  feet=" + HoldRegistry.isOccluded(level, feet)
                + " eye=" + HoldRegistry.isOccluded(level, eye)
                + " entityDry=" + HoldRegistry.isEntityInDryHold(level, feet, player.getBoundingBox().getCenter()));
        say(source, "Sable occlusion: feet=" + SableWaterOcclusion.isOccluded(level, feet)
                + " eye=" + SableWaterOcclusion.isOccluded(level, eye)
                + " rendererEnabled=" + SableWaterOcclusion.clientRendererEnabled());
        say(source, "player fluid state: isInWater=" + player.isInWater()
                + " isUnderWater=" + player.isUnderWater()
                + " isInFluidType=" + player.isInFluidType()
                + " waterHeight=" + String.format("%.3f", player.getFluidTypeHeight(NeoForgeMod.WATER_TYPE.value()))
                + " swimming=" + player.isSwimming());
        return 1;
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}

