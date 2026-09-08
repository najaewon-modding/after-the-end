from pathlib import Path

service_path = Path("src/main/java/net/njw/aftertheend/city/basecamp/BasecampPlacementService.java")
text = service_path.read_text()
start = text.index("    private static ScoredCandidate findGuaranteedFallback(")
end = text.index("\n    private static int interpolate(", start)
replacement = '''    private static ScoredCandidate findGuaranteedFallback(
            ServerLevel level,
            SearchBounds bounds,
            TemplateSize size,
            List<PlacedFootprint> reserved
    ) {
        ScoredCandidate best = null;
        CandidatePoint firstClear = null;
        for (int gx = 0; gx < FALLBACK_GRID_AXIS; gx++) {
            int centerX = interpolate(bounds.minCenterX(), bounds.maxCenterX(), gx, FALLBACK_GRID_AXIS);
            for (int gz = 0; gz < FALLBACK_GRID_AXIS; gz++) {
                int centerZ = interpolate(bounds.minCenterZ(), bounds.maxCenterZ(), gz, FALLBACK_GRID_AXIS);
                if (!hasStructuralClearance(centerX, centerZ, size, reserved, 0)) continue;
                if (firstClear == null) firstClear = new CandidatePoint(centerX, centerZ);
                TerrainAssessment terrain = assessTerrain(level, centerX, centerZ, size, true);
                if (terrain == null) continue;
                ScoredCandidate scored = scoreCandidate(bounds, centerX, centerZ, terrain, reserved);
                if (best == null || scored.score() < best.score()) best = scored;
            }
        }

        if (best == null) {
            CandidatePoint emergency = firstClear != null
                    ? firstClear
                    : findEmergencyNonOverlappingSlot(bounds, size, reserved);
            TerrainAssessment terrain = emergencyTerrainAssessment(level, emergency.x(), emergency.z(), size);
            best = scoreCandidate(bounds, emergency.x(), emergency.z(), terrain, reserved);
            AfterTheEnd.LOGGER.warn(
                    "Basecamp terrain sampling failed; using mandatory emergency placement at ({}, {}), size={}x{}",
                    emergency.x(), emergency.z(), size.width(), size.width()
            );
            return best;
        }

        TerrainAssessment full = assessTerrain(level, best.centerX(), best.centerZ(), size, false);
        if (full != null) best = scoreCandidate(bounds, best.centerX(), best.centerZ(), full, reserved);
        AfterTheEnd.LOGGER.warn(
                "Basecamp used guaranteed fallback at ({}, {}), size={}x{}, score={}",
                best.centerX(), best.centerZ(), size.width(), size.width(), String.format("%.2f", best.score())
        );
        return best;
    }

    private static CandidatePoint findEmergencyNonOverlappingSlot(
            SearchBounds bounds,
            TemplateSize size,
            List<PlacedFootprint> reserved
    ) {
        int stride = Math.max(1, size.width() + 1);
        for (int x = bounds.minCenterX(); x <= bounds.maxCenterX(); x += stride) {
            for (int z = bounds.minCenterZ(); z <= bounds.maxCenterZ(); z += stride) {
                if (hasStructuralClearance(x, z, size, reserved, 0)) return new CandidatePoint(x, z);
            }
        }
        if (hasStructuralClearance(bounds.maxCenterX(), bounds.maxCenterZ(), size, reserved, 0)) {
            return new CandidatePoint(bounds.maxCenterX(), bounds.maxCenterZ());
        }

        int centerX = (bounds.minCenterX() + bounds.maxCenterX()) / 2;
        int centerZ = (bounds.minCenterZ() + bounds.maxCenterZ()) / 2;
        AfterTheEnd.LOGGER.error(
                "Basecamp region is too constrained for a non-overlapping emergency slot; using city-center fallback at ({}, {})",
                centerX, centerZ
        );
        return new CandidatePoint(centerX, centerZ);
    }

    private static TerrainAssessment emergencyTerrainAssessment(
            ServerLevel level,
            int centerX,
            int centerZ,
            TemplateSize size
    ) {
        level.getChunk(centerX >> 4, centerZ >> 4);
        TerrainAssessment terrain = assessTerrain(level, centerX, centerZ, size, true);
        if (terrain != null) return terrain;

        SurfaceSample center = findSurfaceSample(level, centerX, centerZ);
        int targetSurfaceY;
        boolean fluid;
        if (center != null) {
            targetSurfaceY = center.surfaceY();
            fluid = center.fluid();
        } else {
            targetSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);
            if (targetSurfaceY <= level.getMinY()) targetSurfaceY = level.getSeaLevel();
            targetSurfaceY = Math.max(level.getMinY() + 1, Math.min(level.getMaxY(), targetSurfaceY));
            fluid = false;
        }
        return new TerrainAssessment(
                targetSurfaceY,
                1_000_000.0,
                0.0,
                0.0,
                0.0,
                0.0,
                fluid ? 1.0 : 0.0
        );
    }
'''
service_path.write_text(text[:start] + replacement + text[end:])

command_path = Path("src/main/java/net/njw/aftertheend/city/basecamp/BasecampCommand.java")
command_path.write_text('''package net.njw.aftertheend.city.basecamp;\n\nimport net.minecraft.commands.CommandSourceStack;\nimport net.minecraft.commands.Commands;\nimport net.minecraft.network.chat.Component;\nimport net.neoforged.bus.api.SubscribeEvent;\nimport net.neoforged.neoforge.event.RegisterCommandsEvent;\nimport net.njw.aftertheend.city.City;\nimport net.njw.aftertheend.city.CityManager;\n\npublic final class BasecampCommand {\n    private BasecampCommand() { }\n\n    @SubscribeEvent\n    public static void onRegisterCommands(RegisterCommandsEvent event) {\n        event.getDispatcher().register(\n                Commands.literal("basecamp")\n                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))\n                        .then(Commands.literal("locate").executes(context -> locateNearest(context.getSource())))\n        );\n    }\n\n    private static int locateNearest(CommandSourceStack source) {\n        double sourceX = source.getPosition().x();\n        double sourceZ = source.getPosition().z();\n        BasecampPlacement nearest = null;\n        double nearestDistanceSquared = Double.POSITIVE_INFINITY;\n\n        for (City city : CityManager.getCities(source.getServer())) {\n            for (BasecampPlacement placement : BasecampManager.getPlacements(source.getServer(), city.id())) {\n                int half = placement.large() ? 13 : 5;\n                double centerX = placement.blockX() + half;\n                double centerZ = placement.blockZ() + half;\n                double dx = centerX - sourceX;\n                double dz = centerZ - sourceZ;\n                double distanceSquared = dx * dx + dz * dz;\n                if (distanceSquared < nearestDistanceSquared) {\n                    nearestDistanceSquared = distanceSquared;\n                    nearest = placement;\n                }\n            }\n        }\n\n        if (nearest == null) {\n            source.sendFailure(Component.literal("No Basecamp has been generated yet."));\n            return 0;\n        }\n\n        int half = nearest.large() ? 13 : 5;\n        int x = nearest.blockX() + half;\n        int y = nearest.y() + 2;\n        int z = nearest.blockZ() + half;\n        long distance = Math.round(Math.sqrt(nearestDistanceSquared));\n        source.sendSuccess(\n                () -> Component.literal("Nearest Basecamp: [" + x + ", " + y + ", " + z + "] (" + distance + " blocks away)"),\n                false\n        );\n        return 1;\n    }\n}\n''')

mod_path = Path("src/main/java/net/njw/aftertheend/AfterTheEnd.java")
mod = mod_path.read_text()
if "BasecampCommand" not in mod:
    mod = mod.replace(
        "import net.njw.aftertheend.city.basecamp.BasecampGenerationHandler;",
        "import net.njw.aftertheend.city.basecamp.BasecampCommand;\nimport net.njw.aftertheend.city.basecamp.BasecampGenerationHandler;"
    )
    mod = mod.replace(
        "NeoForge.EVENT_BUS.register(BasecampGenerationHandler.class);",
        "NeoForge.EVENT_BUS.register(BasecampGenerationHandler.class);\n        NeoForge.EVENT_BUS.register(BasecampCommand.class);"
    )
mod_path.write_text(mod)
