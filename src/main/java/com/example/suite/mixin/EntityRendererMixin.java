package com.example.suite.mixin;

import com.example.suite.ModuleManager;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the vanilla nametag of other players while Advanced Nametags is on.
 * require = 0 so a signature change degrades to "vanilla tag still shows" instead of a crash.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true, require = 0)
    private void combatsuite$hideVanillaTag(Entity entity, double distanceToCameraSq,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player player && ModuleManager.NAMETAGS.shouldHideVanilla(player)) {
            cir.setReturnValue(false);
        }
    }
}
