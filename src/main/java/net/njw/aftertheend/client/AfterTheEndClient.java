package net.njw.aftertheend.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.njw.aftertheend.AfterTheEnd;

@Mod(value = AfterTheEnd.MODID, dist = Dist.CLIENT)
public final class AfterTheEndClient {
    public AfterTheEndClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
