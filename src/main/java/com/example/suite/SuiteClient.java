package com.example.suite;

import net.fabricmc.api.ClientModInitializer;

public final class SuiteClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModuleManager.init();
    }
}
