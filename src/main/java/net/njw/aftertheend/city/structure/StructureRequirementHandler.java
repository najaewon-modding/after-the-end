package net.njw.aftertheend.city.structure;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CitySavedData;

public final class StructureRequirementHandler {

    private StructureRequirementHandler() {
    }

    @SubscribeEvent
    public static void onServerStarted(
            ServerStartedEvent event
    ) {
        MinecraftServer server =
                event.getServer();

        CitySavedData savedData =
                server.getDataStorage()
                        .computeIfAbsent(
                                CitySavedData.TYPE
                        );

        if (savedData
                .areStructureRequirementsInitialized()) {
            return;
        }

        ServerLevel overworld =
                server.getLevel(Level.OVERWORLD);

        ServerLevel nether =
                server.getLevel(Level.NETHER);

        if (overworld == null || nether == null) {
            return;
        }

        boolean strongholdExists =
                StructureRequirementService
                        .hasStronghold(overworld);

        boolean fortressExists =
                StructureRequirementService
                        .hasFortress(nether);

        AfterTheEnd.LOGGER.info(
                "Starting stronghold exists: {}",
                strongholdExists
        );

        AfterTheEnd.LOGGER.info(
                "Starting fortress exists: {}",
                fortressExists
        );

        /*
         * 다음 단계에서:
         *
         * if (!strongholdExists)
         *     forcePlaceStronghold(...)
         *
         * if (!fortressExists)
         *     forcePlaceFortress(...)
         */

        if (strongholdExists && fortressExists) {
            savedData
                    .markStructureRequirementsInitialized();
        }
    }
}