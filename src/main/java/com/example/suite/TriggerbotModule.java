package com.example.suite;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Attacks automatically when the crosshair is on another player and the
 * weapon's attack cooldown has fully recharged.
 */
public final class TriggerbotModule extends ClientModule {

    public TriggerbotModule() {
        super("Triggerbot", true);
    }

    @Override
    public void onTick() {
        LocalPlayer self = mc.player;
        if (self == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (mc.screen != null) {
            return;
        }
        if (!self.isAlive() || self.isSpectator()) {
            return;
        }

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult entityHit)) {
            return;
        }

        Entity entity = entityHit.getEntity();
        if (!(entity instanceof Player target) || target == self) {
            return;
        }
        if (!target.isAlive() || target.isSpectator()) {
            return;
        }

        // The "attack cooldown progress per tick" accessor (Yarn: getAttackCooldownProgressPerTick,
        // Mojang: getCurrentItemAttackStrengthDelay) is the number of ticks a full recharge takes,
        // not the progress. It is >= 1.0 almost always and would attack every tick.
        // getAttackStrengthScale(0.5f) is the real 0..1 charge value, so that gates the swing.
        if (self.getAttackStrengthScale(0.5f) < 1.0f) {
            return;
        }

        mc.gameMode.attack(self, target);
        self.swing(InteractionHand.MAIN_HAND);
    }
}
