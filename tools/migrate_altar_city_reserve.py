from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def move(old: str, new: str) -> None:
    src = ROOT / old
    dst = ROOT / new
    if not src.exists():
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        raise RuntimeError(f"destination already exists: {dst}")
    src.rename(dst)


# Rename Java package/classes.
move("src/main/java/net/njw/aftertheend/city/basecamp", "src/main/java/net/njw/aftertheend/city/altar")
altar_java = ROOT / "src/main/java/net/njw/aftertheend/city/altar"
if altar_java.exists():
    for path in sorted(altar_java.glob("*Basecamp*.java")):
        path.rename(path.with_name(path.name.replace("Basecamp", "Altar")))

# Rename data/function/structure resources.
move("src/main/resources/data/njw_after_the_end/function/basecamp", "src/main/resources/data/njw_after_the_end/function/altar")
move("src/main/resources/data/njw_after_the_end/structure/basecamp", "src/main/resources/data/njw_after_the_end/structure/altar")
altar_structures = ROOT / "src/main/resources/data/njw_after_the_end/structure/altar"
if altar_structures.exists():
    for path in sorted(altar_structures.glob("basecamp*")):
        path.rename(path.with_name(path.name.replace("basecamp", "altar")))

# Rename structure generator tools.
move("tools/generate_basecamp_structure.py", "tools/generate_altar_structure.py")
move("tools/generate_small_basecamp_structure.py", "tools/generate_small_altar_structure.py")

# Rename Basecamp terminology in all project text files.  The migration script and
# its workflow are excluded because they necessarily contain the legacy search term.
text_suffixes = {".java", ".md", ".mcfunction", ".py", ".json", ".toml", ".gradle", ".properties", ".yml", ".yaml"}
excluded = {
    ROOT / "tools/migrate_altar_city_reserve.py",
    ROOT / ".github/workflows/altar-city-reserve-migration.yml",
}
for path in ROOT.rglob("*"):
    if not path.is_file() or path in excluded or ".git" in path.parts or path.suffix.lower() not in text_suffixes:
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    replaced = text.replace("BASECAMP", "ALTAR").replace("Basecamp", "Altar").replace("basecamp", "altar")
    if replaced != text:
        path.write_text(replaced, encoding="utf-8")

# -----------------------------------------------------------------------------
# City reserve model
# -----------------------------------------------------------------------------
city_saved_data = ROOT / "src/main/java/net/njw/aftertheend/city/CitySavedData.java"
text = city_saved_data.read_text(encoding="utf-8")
text = text.replace(
    "        maxCityCount = Math.max(Math.max(1, maxCityCount), cities.size());",
    "        maxCityCount = Math.max(Math.max(1, maxCityCount), accessibleCityIds.size());",
)
needle = """    public Collection<City> getAccessibleCities() {
        List<City> result = new ArrayList<>(accessibleCityIds.size());
        for (UUID cityId : accessibleCityIds) { City city = cities.get(cityId); if (city != null) result.add(city); }
        return List.copyOf(result);
    }
"""
replacement = needle + """
    public Collection<City> getLockedCities() {
        List<City> result = new ArrayList<>();
        for (City city : cities.values()) if (!accessibleCityIds.contains(city.id())) result.add(city);
        return List.copyOf(result);
    }
    public int getLockedCityCount() { return cities.size() - accessibleCityIds.size(); }
    public City getNextLockedCity() {
        for (City city : cities.values()) if (!accessibleCityIds.contains(city.id())) return city;
        return null;
    }
"""
if needle not in text:
    raise RuntimeError("CitySavedData accessible city block not found")
text = text.replace(needle, replacement)
text = text.replace(
    '        if (maxCityCount < cities.size()) throw new IllegalArgumentException("Maximum city count cannot be lower than current city count.");',
    '        if (maxCityCount < accessibleCityIds.size()) throw new IllegalArgumentException("Maximum city count cannot be lower than current unlocked city count.");',
)
city_saved_data.write_text(text, encoding="utf-8")

city_manager = ROOT / "src/main/java/net/njw/aftertheend/city/CityManager.java"
text = city_manager.read_text(encoding="utf-8")
needle = """    public static Collection<City> getCities(MinecraftServer server) { return getSavedData(server).getCities(); }
    public static Collection<City> getAccessibleCities(MinecraftServer server) { return getSavedData(server).getAccessibleCities(); }
"""
replacement = needle + """    public static Collection<City> getLockedCities(MinecraftServer server) { return getSavedData(server).getLockedCities(); }
    public static int getLockedCityCount(MinecraftServer server) { return getSavedData(server).getLockedCityCount(); }
    public static City getNextLockedCity(MinecraftServer server) { return getSavedData(server).getNextLockedCity(); }
"""
if needle not in text:
    raise RuntimeError("CityManager city collection block not found")
city_manager.write_text(text.replace(needle, replacement), encoding="utf-8")

# Replace lifecycle service with explicit 3-city locked reserve semantics.
lifecycle = ROOT / "src/main/java/net/njw/aftertheend/city/CityLifecycleService.java"
lifecycle.write_text('''package net.njw.aftertheend.city;\n\nimport java.util.UUID;\nimport net.minecraft.server.MinecraftServer;\nimport net.minecraft.server.level.ServerPlayer;\nimport net.njw.aftertheend.AfterTheEnd;\nimport net.njw.aftertheend.city.altar.AltarManager;\nimport net.njw.aftertheend.city.altar.AltarPlacementService;\nimport net.njw.aftertheend.city.generation.CityPregenerationHandler;\nimport net.njw.aftertheend.city.placement.CityPlacementService;\nimport net.njw.aftertheend.network.CitySyncService;\n\npublic final class CityLifecycleService {\n    public static final int LOCKED_CITY_RESERVE_COUNT = 3;\n\n    private CityLifecycleService() {\n    }\n\n    public static City createAccessibleCity(MinecraftServer server) {\n        ensureCanAddUnlockedCity(server);\n        UUID cityId = newCityId(server);\n        City city = CityPlacementService.placeAccessibleCity(server, cityId, cityId.toString());\n        generateAltarsOrRollback(server, city);\n        finishCityStateChange(server);\n        return city;\n    }\n\n    public static City createLockedCity(MinecraftServer server) {\n        if (CityManager.getLockedCityCount(server) >= LOCKED_CITY_RESERVE_COUNT) {\n            throw new IllegalStateException(\"Locked city reserve is already full: \" + LOCKED_CITY_RESERVE_COUNT + \"/\" + LOCKED_CITY_RESERVE_COUNT);\n        }\n        City city = createLockedCityInternal(server);\n        finishCityStateChange(server);\n        return city;\n    }\n\n    public static int ensureLockedCityReserve(MinecraftServer server) {\n        int created = 0;\n        while (CityManager.getLockedCityCount(server) < LOCKED_CITY_RESERVE_COUNT) {\n            int slot = CityManager.getLockedCityCount(server) + 1;\n            AfterTheEnd.LOGGER.info(\"Preparing locked city reserve [{}/{}].\", slot, LOCKED_CITY_RESERVE_COUNT);\n            City city = createLockedCityInternal(server);\n            created++;\n            AfterTheEnd.LOGGER.info(\"Prepared locked city reserve [{}/{}]: {}\", slot, LOCKED_CITY_RESERVE_COUNT, city.id());\n        }\n        if (created > 0) finishCityStateChange(server);\n        return created;\n    }\n\n    public static City unlockCity(MinecraftServer server, UUID cityId) {\n        City city = requireCity(server, cityId);\n        AltarPlacementService.ensureGenerated(server, city);\n        if (CityManager.isCityAccessible(server, cityId)) return city;\n        ensureCanAddUnlockedCity(server);\n\n        // Prepare the replacement first.  The reserve is briefly four cities, then\n        // returns to exactly three when the requested city becomes accessible.\n        createLockedCityInternal(server);\n        CityManager.unlockCity(server, cityId);\n        finishCityStateChange(server);\n        return city;\n    }\n\n    public static void deleteCity(MinecraftServer server, UUID cityId) {\n        if (CityRegistry.STARTING_CITY_ID.equals(cityId)) throw new IllegalArgumentException(\"Starting city cannot be deleted.\");\n        City city = requireCity(server, cityId);\n        boolean wasLocked = !CityManager.isCityAccessible(server, cityId);\n        for (ServerPlayer player : server.getPlayerList().getPlayers()) {\n            if (city.contains(player.level().dimension(), player.getBlockX(), player.getBlockZ())) {\n                throw new IllegalStateException(\"Cannot delete city while player \" + player.getName().getString() + \" is inside it.\");\n            }\n        }\n        CityPregenerationHandler.removeCity(cityId);\n        AltarManager.removeCity(server, cityId);\n        CityManager.removeCity(server, cityId);\n        if (wasLocked) {\n            while (CityManager.getLockedCityCount(server) < LOCKED_CITY_RESERVE_COUNT) createLockedCityInternal(server);\n        }\n        finishCityStateChange(server);\n    }\n\n    public static void setMaxCityCount(MinecraftServer server, int maxCityCount) {\n        CityManager.setMaxCityCount(server, maxCityCount);\n        CitySyncService.syncToAll(server);\n    }\n\n    private static City createLockedCityInternal(MinecraftServer server) {\n        UUID cityId = newCityId(server);\n        City city = CityPlacementService.placeLockedCity(server, cityId, cityId.toString());\n        generateAltarsOrRollback(server, city);\n        return city;\n    }\n\n    private static void generateAltarsOrRollback(MinecraftServer server, City city) {\n        try {\n            AltarPlacementService.ensureGenerated(server, city);\n        } catch (RuntimeException exception) {\n            AltarManager.removeCity(server, city.id());\n            CityManager.removeCity(server, city.id());\n            PlayerPositionTracker.invalidateAllCityCaches();\n            throw exception;\n        }\n    }\n\n    private static UUID newCityId(MinecraftServer server) {\n        UUID cityId;\n        do {\n            cityId = UUID.randomUUID();\n        } while (CityManager.getCity(server, cityId) != null);\n        return cityId;\n    }\n\n    private static void ensureCanAddUnlockedCity(MinecraftServer server) {\n        int current = CityManager.getAccessibleCities(server).size();\n        int maximum = CityManager.getMaxCityCount(server);\n        if (current >= maximum) throw new IllegalStateException(\"Maximum unlocked city count reached: \" + current + \"/\" + maximum);\n    }\n\n    private static City requireCity(MinecraftServer server, UUID cityId) {\n        City city = CityManager.getCity(server, cityId);\n        if (city == null) throw new IllegalArgumentException(\"Unknown city: \" + cityId);\n        return city;\n    }\n\n    private static void finishCityStateChange(MinecraftServer server) {\n        PlayerPositionTracker.invalidateAllCityCaches();\n        CitySyncService.syncToAll(server);\n    }\n}\n''', encoding="utf-8")

# Server startup initializes the three locked cities before checking missing altars.
generation_handler = ROOT / "src/main/java/net/njw/aftertheend/city/altar/AltarGenerationHandler.java"
generation_handler.write_text('''package net.njw.aftertheend.city.altar;\n\nimport net.minecraft.server.MinecraftServer;\nimport net.neoforged.bus.api.SubscribeEvent;\nimport net.neoforged.neoforge.event.server.ServerStartedEvent;\nimport net.njw.aftertheend.AfterTheEnd;\nimport net.njw.aftertheend.city.City;\nimport net.njw.aftertheend.city.CityLifecycleService;\nimport net.njw.aftertheend.city.CityManager;\n\npublic final class AltarGenerationHandler {\n    private AltarGenerationHandler() { }\n\n    @SubscribeEvent\n    public static void onServerStarted(ServerStartedEvent event) {\n        MinecraftServer server = event.getServer();\n        int createdCities = CityLifecycleService.ensureLockedCityReserve(server);\n        int generatedAltars = 0;\n        for (City city : CityManager.getCities(server)) {\n            if (AltarPlacementService.ensureGeneratedIfMissing(server, city)) generatedAltars++;\n        }\n        if (createdCities > 0) {\n            AfterTheEnd.LOGGER.info(\"Prepared {} locked city/cities during server startup.\", createdCities);\n        }\n        if (generatedAltars > 0) {\n            AfterTheEnd.LOGGER.info(\"Generated missing Altars for {} city/cities during server startup.\", generatedAltars);\n        }\n    }\n}\n''', encoding="utf-8")

# Only accessible cities and the next locked city are synchronized to the C-key UI.
sync_service = ROOT / "src/main/java/net/njw/aftertheend/network/CitySyncService.java"
text = sync_service.read_text(encoding="utf-8")
needle = """        for (\n                City city :\n                CityManager.getCities(\n                        server\n                )\n        ) {\n            boolean unlocked =\n                    CityManager.isCityAccessible(\n                            server,\n                            city.id()\n                    );\n\n            cities.add(\n                    CitySyncPayload.CityData.fromCity(\n                            city,\n                            unlocked\n                    )\n            );\n        }\n"""
replacement = """        City nextLockedCity = CityManager.getNextLockedCity(server);\n\n        for (\n                City city :\n                CityManager.getCities(\n                        server\n                )\n        ) {\n            boolean unlocked =\n                    CityManager.isCityAccessible(\n                            server,\n                            city.id()\n                    );\n\n            if (!unlocked && (nextLockedCity == null || !city.id().equals(nextLockedCity.id()))) {\n                continue;\n            }\n\n            cities.add(\n                    CitySyncPayload.CityData.fromCity(\n                            city,\n                            unlocked\n                    )\n            );\n        }\n"""
if needle not in text:
    raise RuntimeError("CitySyncService snapshot loop not found")
sync_service.write_text(text.replace(needle, replacement), encoding="utf-8")

# Admin status now distinguishes unlocked-cap semantics from the hidden reserve.
admin = ROOT / "src/main/java/net/njw/aftertheend/city/command/CityAdminCommand.java"
text = admin.read_text(encoding="utf-8")
old = '        source.sendSuccess(() -> Component.literal("Cities: " + cities.size() + "/" + CityManager.getMaxCityCount(server)), false);'
new = '        int unlockedCount = CityManager.getAccessibleCities(server).size();\n        int lockedCount = CityManager.getLockedCityCount(server);\n        source.sendSuccess(() -> Component.literal("Cities: " + cities.size() + " (unlocked " + unlockedCount + "/" + CityManager.getMaxCityCount(server) + ", locked reserve " + lockedCount + "/" + CityLifecycleService.LOCKED_CITY_RESERVE_COUNT + ")"), false);'
if old not in text:
    raise RuntimeError("CityAdminCommand list header not found")
text = text.replace(old, new)
text = text.replace('"Maximum city count: " + maximum', '"Maximum unlocked city count: " + maximum')
text = text.replace('"Maximum city count set to " + count', '"Maximum unlocked city count set to " + count')
admin.write_text(text, encoding="utf-8")

# README terminology plus the city-reserve behavior should reflect the implemented model.
readme = ROOT / "README.md"
text = readme.read_text(encoding="utf-8")
text = text.replace(
    "해금된 도시는 전용 GUI에서 확인할 수 있습니다.",
    "해금된 도시는 전용 GUI에서 확인할 수 있으며, 해금 대기 중인 도시는 다음 대상 하나만 표시됩니다.",
)
anchor = "### 도시 목록 GUI\n"
reserve_section = """### 해금 대기 도시\n\n월드가 처음 준비될 때 해금되지 않은 도시 3개를 미리 생성하고 각 도시의 Altar까지 배치합니다. 도시 하나가 해금되면 새로운 해금 대기 도시 하나를 즉시 생성하여 항상 3개의 reserve city를 유지합니다. 실제 도시 데이터에는 세 도시가 모두 존재하지만, 도시 목록 GUI에는 그중 가장 먼저 생성된 다음 해금 대상 하나만 표시됩니다.\n\n"""
if reserve_section not in text and anchor in text:
    text = text.replace(anchor, reserve_section + anchor)
readme.write_text(text, encoding="utf-8")

print("altar rename and locked-city reserve migration applied")
