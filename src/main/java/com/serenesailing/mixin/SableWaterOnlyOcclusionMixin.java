package com.serenesailing.mixin;

import com.serenesailing.hold.WaterOnlyOcclusionShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Makes Sable's water occlusion discard only WATER fragments, so glass and other translucent blocks inside a sunken
 * hold keep rendering. See {@link WaterOnlyOcclusionShader}.
 *
 * <p>Hooks every {@code GlslParser.parseExpression(String)} call in the preprocessor and rewrites only the one that
 * contains Sable's discard condition, so it does not depend on the call order. The preprocessor is byte-identical in
 * Sable 2.0.0, 2.0.3 and 2.0.6. {@code require = 0}: if a future Sable changes the snippet, glass is simply clipped
 * again as before; nothing breaks. Client only (listed under "client" in the mixin config).
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.render.water_occlusion.SableWaterOcclusionPreProcessor", remap = false)
public abstract class SableWaterOnlyOcclusionMixin {

    @ModifyArg(method = "modify",
            at = @At(value = "INVOKE", target = "Lio/github/ocelot/glslprocessor/api/GlslParser;parseExpression"),
            index = 0, require = 0, remap = false)
    private String serenesailing$waterOnly(String glsl) {
        return WaterOnlyOcclusionShader.restrictToWater(glsl);
    }
}
