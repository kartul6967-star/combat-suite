package com.example.suite;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns all module instances, their toggle keybinds and the per-tick dispatch.
 */
public final class ModuleManager {

    public static final String MOD_ID = "combatsuite";

    public static final TriggerbotModule TRIGGERBOT = new TriggerbotModule();
    public static final PlayerESPModule PLAYER_ESP = new PlayerESPModule();
    public static final AdvancedNametagsModule NAMETAGS = new AdvancedNametagsModule();
    public static final OpponentInfoHUDModule TARGET_HUD = new OpponentInfoHUDModule();

    private static final Map<ClientModule, KeyMapping> KEYBINDS = new LinkedHashMap<>();

    private static KeyMapping.Category category;
    private static boolean initialized = false;

    private ModuleManager() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "modules"));

        bind(TRIGGERBOT, "key.combatsuite.triggerbot", InputConstants.KEY_G);
        bind(PLAYER_ESP, "key.combatsuite.player_esp", InputConstants.KEY_H);
        bind(NAMETAGS, "key.combatsuite.nametags", InputConstants.KEY_J);
        bind(TARGET_HUD, "key.combatsuite.target_hud", InputConstants.KEY_K);

        for (ClientModule module : KEYBINDS.keySet()) {
            module.init();
        }

        ClientTickEvents.END_CLIENT_TICK.register(ModuleManager::tick);
    }

    private static void bind(ClientModule module, String translationKey, int defaultKey) {
        KeyMapping mapping = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, category));
        KEYBINDS.put(module, mapping);
    }

    private static void tick(Minecraft client) {
        for (Map.Entry<ClientModule, KeyMapping> entry : KEYBINDS.entrySet()) {
            ClientModule module = entry.getKey();
            KeyMapping mapping = entry.getValue();
            while (mapping.consumeClick()) {
                module.toggle();
                if (client.player != null) {
                    String state = module.isEnabled() ? "§aON" : "§cOFF";
                    client.player.displayClientMessage(
                            Component.literal("§7" + module.getName() + ": " + state), true);
                }
            }
        }

        if (client.player == null || client.level == null) {
            return;
        }

        for (ClientModule module : KEYBINDS.keySet()) {
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }
}
