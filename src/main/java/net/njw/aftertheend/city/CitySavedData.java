package net.njw.aftertheend.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CitySavedData extends SavedData {
    public static final int DEFAULT_MAX_CITY_COUNT = 5;
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<SafePosition> SAFE_POSITION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(SafePosition::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(SafePosition::x), Codec.DOUBLE.fieldOf("y").forGetter(SafePosition::y), Codec.DOUBLE.fieldOf("z").forGetter(SafePosition::z),
            Codec.FLOAT.fieldOf("yRot").forGetter(SafePosition::yRot), Codec.FLOAT.fieldOf("xRot").forGetter(SafePosition::xRot)
    ).apply(instance, SafePosition::new));
    private static final Codec<Map<String, SafePosition>> PLAYER_POSITIONS_CODEC = Codec.unboundedMap(Codec.STRING, SAFE_POSITION_CODEC);
    private static final Codec<Set<UUID>> PENDING_RETURNS_CODEC = Codec.STRING.listOf().xmap(list -> {
        Set<UUID> result = new LinkedHashSet<>();
        for (String value : list) result.add(UUID.fromString(value));
        return result;
    }, set -> set.stream().map(UUID::toString).sorted().toList());
    private static final Codec<Map<UUID, City>> CITIES_CODEC = City.CODEC.listOf().xmap(list -> {
        Map<UUID, City> result = new LinkedHashMap<>();
        for (City city : list) {
            City previous = result.putIfAbsent(city.id(), city);
            if (previous != null) throw new IllegalArgumentException("Duplicate city id: " + city.id());
        }
        return result;
    }, map -> new ArrayList<>(map.values()));
    private static final Codec<Set<UUID>> ACCESSIBLE_CITY_IDS_CODEC = UUID_CODEC.listOf().xmap(LinkedHashSet::new, set -> new ArrayList<>(set));
    private static final Codec<CityArrivalPosition> CITY_ARRIVAL_POSITION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("blockX").forGetter(CityArrivalPosition::blockX), Codec.INT.fieldOf("y").forGetter(CityArrivalPosition::y), Codec.INT.fieldOf("blockZ").forGetter(CityArrivalPosition::blockZ)
    ).apply(instance, CityArrivalPosition::new));
    private static final Codec<Map<String, CityArrivalPosition>> CITY_ARRIVAL_POSITIONS_CODEC = Codec.unboundedMap(Codec.STRING, CITY_ARRIVAL_POSITION_CODEC);
    private static final Codec<PregenerationState> PREGENERATION_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("generatedChunks", 0L).forGetter(PregenerationState::generatedChunks), Codec.BOOL.optionalFieldOf("completed", false).forGetter(PregenerationState::completed)
    ).apply(instance, PregenerationState::new));
    private static final Codec<Map<String, PregenerationState>> PREGENERATION_STATES_CODEC = Codec.unboundedMap(Codec.STRING, PREGENERATION_STATE_CODEC);

    public static final SavedDataType<CitySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("njw_after_the_end", "city_data"),
            CitySavedData::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    PLAYER_POSITIONS_CODEC.fieldOf("playerPositions").forGetter(CitySavedData::serializePlayerPositions),
                    PENDING_RETURNS_CODEC.optionalFieldOf("pendingReturns", Set.of()).forGetter(data -> data.pendingReturns),
                    Codec.BOOL.optionalFieldOf("structureRequirementsInitialized", false).forGetter(data -> data.structureRequirementsInitialized),
                    PREGENERATION_STATES_CODEC.optionalFieldOf("pregenerationStates", Map.of()).forGetter(CitySavedData::serializePregenerationStates),
                    CITIES_CODEC.optionalFieldOf("cities", Map.of()).forGetter(data -> data.cities),
                    ACCESSIBLE_CITY_IDS_CODEC.optionalFieldOf("accessibleCityIds", Set.of()).forGetter(data -> data.accessibleCityIds),
                    CITY_ARRIVAL_POSITIONS_CODEC.optionalFieldOf("cityArrivalPositions", Map.of()).forGetter(CitySavedData::serializeCityArrivalPositions),
                    Codec.INT.optionalFieldOf("maxCityCount", DEFAULT_MAX_CITY_COUNT).forGetter(data -> data.maxCityCount)
            ).apply(instance, CitySavedData::new)), null);

    private final Map<PlayerDimensionKey, SafePosition> playerPositions = new HashMap<>();
    private final Set<UUID> pendingReturns;
    private boolean structureRequirementsInitialized;
    private final Map<CityDimensionKey, PregenerationState> pregenerationStates = new HashMap<>();
    private final Map<UUID, City> cities;
    private final Set<UUID> accessibleCityIds;
    private final Map<CityDimensionKey, CityArrivalPosition> cityArrivalPositions = new HashMap<>();
    private int maxCityCount;

    public CitySavedData() {
        pendingReturns = new LinkedHashSet<>();
        cities = new LinkedHashMap<>();
        accessibleCityIds = new LinkedHashSet<>();
        maxCityCount = DEFAULT_MAX_CITY_COUNT;
        initializeStartingCity();
    }

    private CitySavedData(Map<String, SafePosition> positions, Set<UUID> pendingReturns, boolean structureRequirementsInitialized,
                          Map<String, PregenerationState> pregenerationStates, Map<UUID, City> cities, Set<UUID> accessibleCityIds,
                          Map<String, CityArrivalPosition> cityArrivalPositions, int maxCityCount) {
        this.pendingReturns = new LinkedHashSet<>(pendingReturns);
        this.structureRequirementsInitialized = structureRequirementsInitialized;
        this.cities = new LinkedHashMap<>(cities);
        this.accessibleCityIds = new LinkedHashSet<>(accessibleCityIds);
        this.maxCityCount = maxCityCount;
        deserializePlayerPositions(positions);
        deserializePregenerationStates(pregenerationStates);
        deserializeCityArrivalPositions(cityArrivalPositions);
        normalizeCityData();
    }

    private void initializeStartingCity() { cities.put(CityRegistry.STARTING_CITY_ID, CityRegistry.STARTING_CITY_TEMPLATE); accessibleCityIds.add(CityRegistry.STARTING_CITY_ID); }
    private void normalizeCityData() {
        accessibleCityIds.retainAll(cities.keySet());
        cities.putIfAbsent(CityRegistry.STARTING_CITY_ID, CityRegistry.STARTING_CITY_TEMPLATE);
        accessibleCityIds.add(CityRegistry.STARTING_CITY_ID);
        maxCityCount = Math.max(Math.max(1, maxCityCount), accessibleCityIds.size());
    }

    public Collection<City> getCities() { return List.copyOf(cities.values()); }
    public City getCity(UUID cityId) { return cities.get(cityId); }
    public boolean hasCity(UUID cityId) { return cities.containsKey(cityId); }
    public void addCity(City city) { addCity(city, false); }
    public void addAccessibleCity(City city) { addCity(city, true); }
    private void addCity(City city, boolean accessible) {
        Objects.requireNonNull(city, "city");
        if (cities.containsKey(city.id())) throw new IllegalArgumentException("City already exists: " + city.id());
        cities.put(city.id(), city);
        if (accessible) accessibleCityIds.add(city.id());
        setDirty();
    }

    public Collection<City> getAccessibleCities() {
        List<City> result = new ArrayList<>(accessibleCityIds.size());
        for (UUID cityId : accessibleCityIds) { City city = cities.get(cityId); if (city != null) result.add(city); }
        return List.copyOf(result);
    }

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

    public City findCityContaining(ResourceKey<Level> dimension, int blockX, int blockZ, boolean accessibleOnly) {
        if (accessibleOnly) {
            for (UUID cityId : accessibleCityIds) { City city = cities.get(cityId); if (city != null && city.contains(dimension, blockX, blockZ)) return city; }
        } else {
            for (City city : cities.values()) if (city.contains(dimension, blockX, blockZ)) return city;
        }
        return null;
    }

    public boolean isCityAccessible(UUID cityId) { return accessibleCityIds.contains(cityId); }
    public void unlockCity(UUID cityId) {
        if (!cities.containsKey(cityId)) throw new IllegalArgumentException("Unknown city: " + cityId);
        if (accessibleCityIds.add(cityId)) setDirty();
    }
    public int getMaxCityCount() { return maxCityCount; }
    public void setMaxCityCount(int maxCityCount) {
        if (maxCityCount < accessibleCityIds.size()) throw new IllegalArgumentException("Maximum city count cannot be lower than current unlocked city count.");
        if (maxCityCount < 1) throw new IllegalArgumentException("Maximum city count must be at least 1.");
        if (this.maxCityCount != maxCityCount) { this.maxCityCount = maxCityCount; setDirty(); }
    }

    public void setLastValidPosition(UUID playerId, ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {
        playerPositions.put(new PlayerDimensionKey(playerId, dimension), new SafePosition(dimension, x, y, z, yRot, xRot));
        setDirty();
    }
    public SafePosition getLastValidPosition(UUID playerId, ResourceKey<Level> dimension) { return playerPositions.get(new PlayerDimensionKey(playerId, dimension)); }
    public void markPendingReturn(UUID playerId) { if (pendingReturns.add(playerId)) setDirty(); }
    public boolean hasPendingReturn(UUID playerId) { return pendingReturns.contains(playerId); }
    public void clearPendingReturn(UUID playerId) { if (pendingReturns.remove(playerId)) setDirty(); }
    public boolean areStructureRequirementsInitialized() { return structureRequirementsInitialized; }
    public void markStructureRequirementsInitialized() { if (!structureRequirementsInitialized) { structureRequirementsInitialized = true; setDirty(); } }

    public CityArrivalPosition getCityArrivalPosition(UUID cityId, ResourceKey<Level> dimension) { return cityArrivalPositions.get(new CityDimensionKey(cityId, dimension)); }
    public void setCityArrivalPosition(UUID cityId, ResourceKey<Level> dimension, int blockX, int y, int blockZ) {
        City city = cities.get(cityId);
        if (city == null) throw new IllegalArgumentException("Unknown city: " + cityId);
        CityRegion region = city.getRegion(dimension).orElseThrow(() -> new IllegalArgumentException("City does not exist in dimension: " + cityId + " / " + dimension.identifier()));
        if (!region.containsBlock(blockX, blockZ)) throw new IllegalArgumentException("Arrival position is outside city region: " + cityId);
        CityArrivalPosition position = new CityArrivalPosition(blockX, y, blockZ);
        if (!position.equals(cityArrivalPositions.put(new CityDimensionKey(cityId, dimension), position))) setDirty();
    }
    public void clearCityArrivalPosition(UUID cityId, ResourceKey<Level> dimension) { if (cityArrivalPositions.remove(new CityDimensionKey(cityId, dimension)) != null) setDirty(); }
    public void clearCityArrivalPositions(UUID cityId) { if (cityArrivalPositions.keySet().removeIf(key -> key.cityId().equals(cityId))) setDirty(); }

    public PregenerationState getPregenerationState(UUID cityId, ResourceKey<Level> dimension) { return pregenerationStates.getOrDefault(new CityDimensionKey(cityId, dimension), PregenerationState.EMPTY); }
    public long getPregeneratedChunks(UUID cityId, ResourceKey<Level> dimension) { return getPregenerationState(cityId, dimension).generatedChunks(); }
    public boolean isPregenerationCompleted(UUID cityId, ResourceKey<Level> dimension) { return getPregenerationState(cityId, dimension).completed(); }
    public void setPregeneratedChunks(UUID cityId, ResourceKey<Level> dimension, long generatedChunks) {
        if (generatedChunks < 0L) throw new IllegalArgumentException("generatedChunks must be greater than or equal to 0.");
        CityDimensionKey key = new CityDimensionKey(cityId, dimension);
        PregenerationState current = pregenerationStates.getOrDefault(key, PregenerationState.EMPTY);
        if (current.generatedChunks() == generatedChunks) return;
        pregenerationStates.put(key, new PregenerationState(generatedChunks, current.completed())); setDirty();
    }
    public void markPregenerationCompleted(UUID cityId, ResourceKey<Level> dimension) {
        CityDimensionKey key = new CityDimensionKey(cityId, dimension);
        PregenerationState current = pregenerationStates.getOrDefault(key, PregenerationState.EMPTY);
        if (!current.completed()) { pregenerationStates.put(key, new PregenerationState(current.generatedChunks(), true)); setDirty(); }
    }
    public void resetPregenerationState(UUID cityId, ResourceKey<Level> dimension) { if (pregenerationStates.remove(new CityDimensionKey(cityId, dimension)) != null) setDirty(); }

    public void removeCity(UUID cityId) {
        Objects.requireNonNull(cityId, "cityId");
        if (CityRegistry.STARTING_CITY_ID.equals(cityId)) throw new IllegalArgumentException("Starting city cannot be deleted.");
        City removedCity = cities.remove(cityId);
        if (removedCity == null) throw new IllegalArgumentException("Unknown city: " + cityId);
        accessibleCityIds.remove(cityId);
        pregenerationStates.keySet().removeIf(key -> key.cityId().equals(cityId));
        cityArrivalPositions.keySet().removeIf(key -> key.cityId().equals(cityId));
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        playerPositions.entrySet().removeIf(entry -> {
            SafePosition position = entry.getValue();
            boolean inside = removedCity.contains(position.dimension(), (int) Math.floor(position.x()), (int) Math.floor(position.z()));
            if (inside) affectedPlayers.add(entry.getKey().playerId());
            return inside;
        });
        pendingReturns.removeAll(affectedPlayers);
        setDirty();
    }

    private Map<String, SafePosition> serializePlayerPositions() {
        Map<String, SafePosition> result = new LinkedHashMap<>();
        playerPositions.forEach((key, value) -> result.put(key.playerId() + "|" + key.dimension().identifier(), value));
        return result;
    }
    private void deserializePlayerPositions(Map<String, SafePosition> serialized) {
        for (Map.Entry<String, SafePosition> entry : serialized.entrySet()) {
            int separator = entry.getKey().indexOf('|');
            if (separator <= 0) continue;
            try { playerPositions.put(new PlayerDimensionKey(UUID.fromString(entry.getKey().substring(0, separator)), entry.getValue().dimension()), entry.getValue()); } catch (IllegalArgumentException ignored) { }
        }
    }
    private Map<String, PregenerationState> serializePregenerationStates() { return serializeCityDimensionMap(pregenerationStates); }
    private Map<String, CityArrivalPosition> serializeCityArrivalPositions() { return serializeCityDimensionMap(cityArrivalPositions); }
    private <T> Map<String, T> serializeCityDimensionMap(Map<CityDimensionKey, T> source) {
        Map<String, T> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key.cityId() + "|" + key.dimension().identifier(), value));
        return result;
    }
    private void deserializePregenerationStates(Map<String, PregenerationState> serialized) { deserializeCityDimensionMap(serialized, pregenerationStates); }
    private void deserializeCityArrivalPositions(Map<String, CityArrivalPosition> serialized) { deserializeCityDimensionMap(serialized, cityArrivalPositions); }
    private <T> void deserializeCityDimensionMap(Map<String, T> serialized, Map<CityDimensionKey, T> target) {
        for (Map.Entry<String, T> entry : serialized.entrySet()) {
            int separator = entry.getKey().indexOf('|');
            if (separator <= 0) continue;
            UUID cityId = UUID.fromString(entry.getKey().substring(0, separator));
            String dimensionId = entry.getKey().substring(separator + 1);
            for (City city : cities.values()) {
                for (ResourceKey<Level> dimension : city.regions().keySet()) {
                    if (dimension.identifier().toString().equals(dimensionId)) { target.put(new CityDimensionKey(cityId, dimension), entry.getValue()); break; }
                }
            }
        }
    }

    private record PlayerDimensionKey(UUID playerId, ResourceKey<Level> dimension) { }
    private record CityDimensionKey(UUID cityId, ResourceKey<Level> dimension) { }
    public record SafePosition(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) { }
    public record PregenerationState(long generatedChunks, boolean completed) {
        public static final PregenerationState EMPTY = new PregenerationState(0L, false);
        public PregenerationState { if (generatedChunks < 0L) throw new IllegalArgumentException("generatedChunks must be greater than or equal to 0."); }
    }
    public record CityArrivalPosition(int blockX, int y, int blockZ) { }
}
