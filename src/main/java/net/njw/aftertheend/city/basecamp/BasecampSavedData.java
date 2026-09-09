package net.njw.aftertheend.city.basecamp;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BasecampSavedData extends SavedData {
    private static final Codec<Set<String>> GENERATED_CITY_IDS_CODEC = Codec.STRING.listOf().xmap(
            LinkedHashSet::new,
            set -> new ArrayList<>(set)
    );
    private static final Codec<List<BasecampPlacement>> PLACEMENTS_CODEC = BasecampPlacement.CODEC.listOf();

    public static final SavedDataType<BasecampSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("njw_after_the_end", "basecamp_data"),
            BasecampSavedData::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    GENERATED_CITY_IDS_CODEC.optionalFieldOf("generatedCityIds", Set.of()).forGetter(data -> data.generatedCityIds),
                    PLACEMENTS_CODEC.optionalFieldOf("placements", List.of()).forGetter(data -> data.placements)
            ).apply(instance, BasecampSavedData::new)),
            null
    );

    private final Set<String> generatedCityIds;
    private final List<BasecampPlacement> placements;
    private final Map<String, List<BasecampPlacement>> placementsByCity;

    public BasecampSavedData() {
        generatedCityIds = new LinkedHashSet<>();
        placements = new ArrayList<>();
        placementsByCity = new HashMap<>();
    }

    private BasecampSavedData(Set<String> generatedCityIds, List<BasecampPlacement> placements) {
        this.generatedCityIds = new LinkedHashSet<>(generatedCityIds);
        this.placements = new ArrayList<>(placements);
        this.placementsByCity = buildPlacementIndex(this.placements);
    }

    public boolean isGenerated(String cityId) {
        return generatedCityIds.contains(cityId);
    }

    public List<BasecampPlacement> getPlacements(String cityId) {
        return placementsByCity.getOrDefault(cityId, List.of());
    }

    public void markGenerated(String cityId, List<BasecampPlacement> cityPlacements) {
        placements.removeIf(placement -> placement.cityId().equals(cityId));
        placements.addAll(cityPlacements);
        placementsByCity.put(cityId, List.copyOf(cityPlacements));
        generatedCityIds.add(cityId);
        setDirty();
    }

    public void removeCity(String cityId) {
        boolean changed = generatedCityIds.remove(cityId);
        changed |= placements.removeIf(placement -> placement.cityId().equals(cityId));
        placementsByCity.remove(cityId);
        if (changed) setDirty();
    }

    private static Map<String, List<BasecampPlacement>> buildPlacementIndex(List<BasecampPlacement> placements) {
        Map<String, List<BasecampPlacement>> grouped = new HashMap<>();
        for (BasecampPlacement placement : placements) {
            grouped.computeIfAbsent(placement.cityId(), ignored -> new ArrayList<>()).add(placement);
        }
        grouped.replaceAll((cityId, cityPlacements) -> List.copyOf(cityPlacements));
        return grouped;
    }
}
