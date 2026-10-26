package com.example.suite.mixin;

import com.example.suite.ModuleManager;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases the ESP's native and GPU buffers when the game renderer closes. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "close", at = @At("RETURN"))
    private void combatsuite$closeEsp(CallbackInfo ci) {
        ModuleManager.PLAYER_ESP.close();
    }
}
