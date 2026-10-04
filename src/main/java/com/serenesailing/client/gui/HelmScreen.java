package com.serenesailing.client.gui;

import com.serenesailing.network.SetHelmAnglePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Ship's Wheel slider GUI. Replaces the old +/-15 degree click steering: the
 * player drags the wheel slider continuously between Hard Port (-45 deg) and
 * Hard Starboard (+45 deg); network dispatch is throttled to >= 1.0 deg delta
 * or on mouse release via {@link SetHelmAnglePayload}.
 */
public class HelmScreen extends Screen {

    private static final float MIN_ANGLE = -45.0f;
    private static final float MAX_ANGLE = 45.0f;

    private final BlockPos pos;
    private float angle;
    private float lastSentAngle = Float.NaN;

    public HelmScreen(BlockPos pos, float angle) {
        super(Component.literal("Ship's Wheel"));
        this.pos = pos;
        this.angle = Mth.clamp(angle, MIN_ANGLE, MAX_ANGLE);
    }

    @Override
    protected void init() {
        HelmSlider slider = new HelmSlider(this.width / 2 - 100, this.height / 2 - 25, 200, 20);
        addRenderableWidget(slider);

        addRenderableWidget(Button.builder(
                        Component.literal("\u00A7b[ Center (0\u00B0) ]"),
                        button -> slider.snapTo(0.0f))
                .bounds(this.width / 2 - 100, this.height / 2 + 5, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title,
                this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        setAngle(this.angle, true);
        super.onClose();
    }

    /** Applies a new angle and sends it to the authoritative server with throttling. */
    private void setAngle(float newAngle, boolean forceSend) {
        this.angle = Mth.clamp(newAngle, MIN_ANGLE, MAX_ANGLE);
        if (forceSend || Float.isNaN(this.lastSentAngle) || Math.abs(this.angle - this.lastSentAngle) >= 1.0f) {
            this.lastSentAngle = this.angle;
            PacketDistributor.sendToServer(new SetHelmAnglePayload(this.pos, this.angle));
        }
    }

    /** Action-bar style readout matching the old click-steering feedback. */
    private Component readout() {
        int deg = Math.round(this.angle);
        if (deg < 0) {
            return Component.literal("\u00A76Port " + (-deg) + "\u00B0");
        }
        if (deg > 0) {
            return Component.literal("\u00A76Starboard " + deg + "\u00B0");
        }
        return Component.literal("\u00A7aCentered (0\u00B0)");
    }

    private static double toSlider(float angle) {
        return (Mth.clamp(angle, MIN_ANGLE, MAX_ANGLE) - MIN_ANGLE) / (MAX_ANGLE - MIN_ANGLE);
    }

    private static float fromSlider(double sliderValue) {
        return (float) (sliderValue * (MAX_ANGLE - MIN_ANGLE) + MIN_ANGLE);
    }

    /** The wheel slider: -45 deg (left) to +45 deg (right), continuous. */
    private class HelmSlider extends AbstractSliderButton {

        HelmSlider(int x, int y, int width, int height) {
            super(x, y, width, height, readout(), toSlider(HelmScreen.this.angle));
        }

        @Override
        protected void updateMessage() {
            setMessage(readout());
        }

        @Override
        protected void applyValue() {
            setAngle(fromSlider(this.value), false);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            setAngle(fromSlider(this.value), true);
        }

        /** Quick-reset hook used by the [ Center (0 deg) ] button. */
        void snapTo(float newAngle) {
            this.value = toSlider(newAngle);
            updateMessage();
            setAngle(newAngle, true);
        }
    }
}
