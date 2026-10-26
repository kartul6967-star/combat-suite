package com.example.suite;

import net.minecraft.client.Minecraft;

/**
 * Base class for every feature module in the suite.
 */
public abstract class ClientModule {

    protected final Minecraft mc = Minecraft.getInstance();

    private final String name;
    private boolean enabled;

    protected ClientModule(String name, boolean enabledByDefault) {
        this.name = name;
        this.enabled = enabledByDefault;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Called once at startup. Modules register their Fabric event hooks here. */
    public void init() {
    }

    /** Called every client tick while the module is enabled and a world is loaded. */
    public void onTick() {
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }
}
