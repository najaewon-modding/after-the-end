package net.njw.aftertheend;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.njw.beyondthecity.city.CityBoundaryHandler;
import net.njw.beyondthecity.city.CityInteractionHandler;
import net.njw.beyondthecity.city.CityTeleportService;
import net.njw.beyondthecity.city.command.CityAdminCommand;
import net.njw.beyondthecity.city.generation.CityPregenerationHandler;
import net.njw.beyondthecity.city.progression.CityProgressionHandler;
import net.njw.beyondthecity.city.structure.EnderEyeHandler;
import net.njw.beyondthecity.city.structure.StructureRequirementHandler;
import net.njw.beyondthecity.network.CityNetworkHandler;
import net.njw.beyondthecity.network.CitySyncService;
import org.slf4j.Logger;

@Mod(AfterTheEnd.MODID)
public final class AfterTheEnd {
    public static final String MODID = "njw_after_the_end";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AfterTheEnd(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(CityNetworkHandler::registerPayloads);
        NeoForge.EVENT_BUS.register(CityBoundaryHandler.class);
        NeoForge.EVENT_BUS.register(CityInteractionHandler.class);
        NeoForge.EVENT_BUS.register(StructureRequirementHandler.class);
        NeoForge.EVENT_BUS.register(EnderEyeHandler.class);
        NeoForge.EVENT_BUS.register(CityPregenerationHandler.class);
        NeoForge.EVENT_BUS.register(CityAdminCommand.class);
        NeoForge.EVENT_BUS.register(CitySyncService.class);
        NeoForge.EVENT_BUS.register(CityTeleportService.class);
        NeoForge.EVENT_BUS.register(CityProgressionHandler.class);
    }
}
