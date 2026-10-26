package com.example.suite;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.UUID;

/**
 * Target HUD: shows name, animated health bar and held item of the player the user is
 * looking at, or the last one they looked at / attacked within the lock window.
 */
public final class OpponentInfoHUDModule extends ClientModule {

    private static final long LOCK_DURATION_MS = 3000L;

    private static final int BOX_WIDTH = 160;
    private static final int BOX_HEIGHT = 60;
    private static final int BOX_MARGIN_RIGHT = 20;
    private static final int CORNER_RADIUS = 6;
    private static final int PADDING = 8;
    private static final int BAR_HEIGHT = 8;

    private static final int BORDER_COLOR = 0xFF2B2B3A;
    private static final int BACKGROUND_COLOR = 0xFF14141C;
    private static final int BAR_TRACK_COLOR = 0xFF2A2A33;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFB8B8C4;

    private Player lockedTarget;
    private long lastSeenMs;
    private UUID displayedFor;
    private float displayedHealth;

    public OpponentInfoHUDModule() {
        super("Target HUD", true);
    }

    @Override
    public void init() {
        AttackEntityCallback.EVENT.register(this::onAttack);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(ModuleManager.MOD_ID, "target_hud"),
                this::render
        );
    }

    @Override
    protected void onDisable() {
        lockedTarget = null;
        displayedFor = null;
    }

    private InteractionResult onAttack(Player player, Level level, InteractionHand hand,
                                       Entity entity, EntityHitResult hitResult) {
        if (isEnabled() && level.isClientSide() && player == mc.player
                && entity instanceof Player target && target != mc.player) {
            lockedTarget = target;
            lastSeenMs = System.currentTimeMillis();
        }
        return InteractionResult.PASS;
    }

    private Player resolveTarget() {
        long now = System.currentTimeMillis();

        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof Player looked
                && looked != mc.player && looked.isAlive()) {
            lockedTarget = looked;
            lastSeenMs = now;
            return looked;
        }

        if (lockedTarget != null) {
            boolean expired = now - lastSeenMs > LOCK_DURATION_MS;
            boolean gone = lockedTarget.isRemoved() || !lockedTarget.isAlive();
            if (expired || gone) {
                lockedTarget = null;
            }
        }
        return lockedTarget;
    }

    private void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!isEnabled() || mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        Player target = resolveTarget();
        if (target == null) {
            return;
        }

        float health = Math.max(0.0f, target.getHealth());
        float maxHealth = Math.max(1.0f, target.getMaxHealth());

        // Smooth the displayed health toward the real value; snap when the target changes.
        if (!target.getUUID().equals(displayedFor)) {
            displayedFor = target.getUUID();
            displayedHealth = health;
        } else {
            displayedHealth += (health - displayedHealth) * 0.12f;
        }
        float ratio = Mth.clamp(displayedHealth / maxHealth, 0.0f, 1.0f);

        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int x = screenWidth - BOX_WIDTH - BOX_MARGIN_RIGHT;
        int y = screenHeight / 2 - BOX_HEIGHT / 2;
        int x2 = x + BOX_WIDTH;
        int y2 = y + BOX_HEIGHT;

        // Border, then background.
        fillRoundedRect(graphics, x - 1, y - 1, x2 + 1, y2 + 1, CORNER_RADIUS + 1, BORDER_COLOR);
        fillRoundedRect(graphics, x, y, x2, y2, CORNER_RADIUS, BACKGROUND_COLOR);

        // Row 1: name on the left, numeric health on the right.
        int nameY = y + PADDING;
        String name = font.plainSubstrByWidth(target.getName().getString(), BOX_WIDTH - PADDING * 2 - 60);
        graphics.drawString(font, name, x + PADDING, nameY, NAME_COLOR, true);

        String hpText = String.format(Locale.ROOT, "%.1f / %.1f", health, maxHealth);
        int hpWidth = font.width(hpText);
        graphics.drawString(font, hpText, x2 - PADDING - hpWidth, nameY, SUBTEXT_COLOR, true);

        // Row 2: health bar.
        int barX = x + PADDING;
        int barY = nameY + 13;
        int barWidth = BOX_WIDTH - PADDING * 2;
        fillRoundedRect(graphics, barX, barY, barX + barWidth, barY + BAR_HEIGHT, 3, BAR_TRACK_COLOR);

        int fillWidth = Math.round(barWidth * ratio);
        if (fillWidth > 0) {
            fillRoundedRect(graphics, barX, barY, barX + fillWidth, barY + BAR_HEIGHT, 3, healthBarColor(ratio));
        }

        // Row 3: held item icon and name.
        int itemY = barY + BAR_HEIGHT + 6;
        ItemStack held = target.getMainHandItem();
        if (held.isEmpty()) {
            graphics.drawString(font, "Empty Hand", x + PADDING, itemY + 4, SUBTEXT_COLOR, true);
        } else {
            graphics.renderItem(held, x + PADDING, itemY);
            String itemName = font.plainSubstrByWidth(held.getHoverName().getString(), BOX_WIDTH - PADDING * 2 - 20);
            graphics.drawString(font, itemName, x + PADDING + 20, itemY + 4, NAME_COLOR, true);
        }
    }

    /** Green at full health, yellow at half, red near zero. Fully opaque ARGB. */
    private static int healthBarColor(float ratio) {
        int r = (int) (255.0f * Math.min(1.0f, 2.0f * (1.0f - ratio)));
        int g = (int) (255.0f * Math.min(1.0f, 2.0f * ratio));
        return 0xFF000000 | (r << 16) | (g << 8) | 0x20;
    }

    /**
     * Rounded rectangle built from one-pixel-high horizontal strips whose inset follows a
     * circle at each corner. Coordinates are [x1, x2) by [y1, y2).
     */
    private static void fillRoundedRect(GuiGraphics graphics, int x1, int y1, int x2, int y2, int radius, int color) {
        int width = x2 - x1;
        int height = y2 - y1;
        if (width <= 0 || height <= 0) {
            return;
        }

        int r = Math.min(radius, Math.min(width, height) / 2);
        if (r <= 0) {
            graphics.fill(x1, y1, x2, y2, color);
            return;
        }

        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt((double) r * r - dy * dy));
            graphics.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            graphics.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }

        graphics.fill(x1, y1 + r, x2, y2 - r, color);
    }
}
