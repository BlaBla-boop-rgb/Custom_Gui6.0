package dev.customgui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class CustomGuiMod implements ClientModInitializer {

    /** Taste zum Öffnen des Menüs. Standard: H. Umbelegbar im Einstellungs-Tab des Menüs
     *  oder unter Optionen > Steuerung > Tastenbelegung. */
    public static KeyMapping OPEN_KEY;

    @Override
    public void onInitializeClient() {
        CustomGuiConfig.load();
        HitboxRenderer.init();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("customgui", "main"));

        OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.customgui.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_KEY.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    mc.setScreen(new CustomGuiScreen());
                }
            }
        });
    }
}
