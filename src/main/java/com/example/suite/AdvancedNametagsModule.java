package com.example.suite;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Replaces vanilla player nametags with "Name [HP] distance", scaled with distance.
 *
 * In 1.21.9+ the player renderer no longer exposes one stable method to override, so this
 * module hides the vanilla tag (EntityRendererMixin) and draws its own tag as a screen
 * overlay. Each frame, the world pass records the camera position and view matrix; the HUD
 * pass projects each player's head position to the screen with them.
 */
public final class AdvancedNametagsModule extends ClientModule {

    private static final float HEAD_OFFSET = 0.35f;
    private static final int BACKGROUND_COLOR = 0x99000000;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int DISTANCE_COLOR = 0xFFAAAAAA;

    // Camera state captured during the world pass.
    private final Matrix4f viewMatrix = new Matrix4f();
    private Vec3 cameraPos = Vec3.ZERO;
    private float capturedPartialTick = 0.0f;
    private boolean captured = false;

    private final Vector3f scratch = new Vector3f();

    public AdvancedNametagsModule() {
        super("Advanced Nametags", true);
    }

    @Override
    public void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::capture);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(ModuleManager.MOD_ID, "advanced_nametags"),
                this::renderOverlay
        );
    }

    /** Used by EntityRendererMixin: true when the vanilla tag for this player should be hidden. */
    public boolean shouldHideVanilla(Player player) {
        return isEnabled() && mc.player != null && player != mc.player;
    }

    private void capture(WorldRenderContext context) {
        viewMatrix.set(context.matrices().last().pose());
        Vec3 pos = context.worldState().cameraRenderState.pos;
        cameraPos = new Vec3(pos.x, pos.y, pos.z);
        capturedPartialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        captured = true;
    }

    private void renderOverlay(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!isEnabled() || !captured || mc.level == null || mc.player == null || mc.options.hideGui) {
            return;
        }

        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        float tanHalfFov = (float) Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5);

        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }

            // Head position, interpolated for this frame, relative to the camera.
            double x = Mth.lerp(capturedPartialTick, player.xo, player.getX());
            double y = Mth.lerp(capturedPartialTick, player.yo, player.getY()) + player.getBbHeight() + HEAD_OFFSET;
            double z = Mth.lerp(capturedPartialTick, player.zo, player.getZ());
            scratch.set((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z));

            // World (camera-relative) -> view space. The camera looks down -Z.
            viewMatrix.transformPosition(scratch);
            float depth = -scratch.z;
            if (depth < 0.1f) {
                continue;
            }

            // View space -> normalized device coordinates -> GUI pixels.
            float ndcX = scratch.x / (depth * tanHalfFov * aspect);
            float ndcY = scratch.y / (depth * tanHalfFov);
            if (ndcX < -1.3f || ndcX > 1.3f || ndcY < -1.3f || ndcY > 1.3f) {
                continue;
            }
            float screenX = (ndcX * 0.5f + 0.5f) * screenWidth;
            float screenY = (0.5f - ndcY * 0.5f) * screenHeight;

            drawTag(graphics, font, player, screenX, screenY);
        }
    }

    private void drawTag(GuiGraphics graphics, Font font, Player player, float screenX, float screenY) {
        float distance = mc.player.distanceTo(player);

        float health = player.getHealth();
        float absorption = player.getAbsorptionAmount();
        float maxHealth = Math.max(1.0f, player.getMaxHealth());
        float ratio = Mth.clamp(health / maxHealth, 0.0f, 1.0f);

        // Green at full health, yellow at half, red near zero.
        int r = (int) (255.0f * Math.min(1.0f, 2.0f * (1.0f - ratio)));
        int g = (int) (255.0f * Math.min(1.0f, 2.0f * ratio));
        int healthColor = 0xFF000000 | (r << 16) | (g << 8);

        String name = player.getName().getString();
        String healthText = absorption > 0.0f
                ? String.format(Locale.ROOT, "[%.1f+%.1f HP]", health, absorption)
                : String.format(Locale.ROOT, "[%.1f HP]", health);
        String distanceText = String.format(Locale.ROOT, "%.1fm", distance);

        int gap = font.width("  ");
        int nameWidth = font.width(name);
        int healthWidth = font.width(healthText);
        int distanceWidth = font.width(distanceText);
        int totalWidth = nameWidth + gap + healthWidth + gap + distanceWidth;

        // Screen-space tags keep a constant pixel size, so grow them with distance for far targets.
        float scale = Mth.clamp(0.85f + distance / 45.0f, 0.85f, 2.0f);

        int left = -totalWidth / 2;
        int top = -(font.lineHeight + 3);

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(screenX, screenY);
        pose.scale(scale, scale);

        graphics.fill(left - 3, top - 2, left + totalWidth + 3, top + font.lineHeight + 1, BACKGROUND_COLOR);

        int cursor = left;
        graphics.drawString(font, name, cursor, top, NAME_COLOR, true);
        cursor += nameWidth + gap;
        graphics.drawString(font, healthText, cursor, top, healthColor, true);
        cursor += healthWidth + gap;
        graphics.drawString(font, distanceText, cursor, top, DISTANCE_COLOR, true);

        pose.popMatrix();
    }
}
