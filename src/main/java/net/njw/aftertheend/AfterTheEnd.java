package net.njw.aftertheend;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.njw.aftertheend.city.CityBoundaryHandler;
import net.njw.aftertheend.city.CityInteractionHandler;
import net.njw.aftertheend.city.CityTeleportService;
import net.njw.aftertheend.city.altar.AltarCommand;
import net.njw.aftertheend.city.altar.AltarGenerationHandler;
import net.njw.aftertheend.city.altar.AltarRitualHandler;
import net.njw.aftertheend.city.altar.HiddenCityPreparationService;
import net.njw.aftertheend.city.command.CityAdminCommand;
import net.njw.aftertheend.city.generation.CityPregenerationHandler;
import net.njw.aftertheend.city.structure.EnderEyeHandler;
import net.njw.aftertheend.city.structure.StructureRequirementHandler;
import net.njw.aftertheend.event.ShulkerCoreDropHandler;
import net.njw.aftertheend.gametest.ModGameTests;
import net.njw.aftertheend.network.CityNetworkHandler;
import net.njw.aftertheend.network.CitySyncService;
import net.njw.aftertheend.registry.ModContent;
import org.slf4j.Logger;

@Mod(AfterTheEnd.MODID)
public final class AfterTheEnd {
    public static final String MODID = "njw_after_the_end";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AfterTheEnd(IEventBus modEventBus, ModContainer modContainer) {
        ModContent.register(modEventBus);
        ModGameTests.register(modEventBus);
        modEventBus.addListener(CityNetworkHandler::registerPayloads);
        NeoForge.EVENT_BUS.register(CityBoundaryHandler.class);
        NeoForge.EVENT_BUS.register(CityInteractionHandler.class);
        NeoForge.EVENT_BUS.register(StructureRequirementHandler.class);
        NeoForge.EVENT_BUS.register(EnderEyeHandler.class);
        NeoForge.EVENT_BUS.register(AltarGenerationHandler.class);
        NeoForge.EVENT_BUS.register(HiddenCityPreparationService.class);
        NeoForge.EVENT_BUS.register(AltarRitualHandler.class);
        NeoForge.EVENT_BUS.register(AltarCommand.class);
        NeoForge.EVENT_BUS.register(CityPregenerationHandler.class);
        NeoForge.EVENT_BUS.register(CityAdminCommand.class);
        NeoForge.EVENT_BUS.register(CitySyncService.class);
        NeoForge.EVENT_BUS.register(CityTeleportService.class);
        NeoForge.EVENT_BUS.register(ShulkerCoreDropHandler.class);
    }
}
