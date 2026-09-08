package net.njw.aftertheend.city.basecamp;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public BasecampSavedData() {
        generatedCityIds = new LinkedHashSet<>();
        placements = new ArrayList<>();
    }

    private BasecampSavedData(Set<String> generatedCityIds, List<BasecampPlacement> placements) {
        this.generatedCityIds = new LinkedHashSet<>(generatedCityIds);
        this.placements = new ArrayList<>(placements);
    }

    public boolean isGenerated(String cityId) {
        return generatedCityIds.contains(cityId);
    }

    public List<BasecampPlacement> getPlacements(String cityId) {
        return placements.stream().filter(placement -> placement.cityId().equals(cityId)).toList();
    }

    public void markGenerated(String cityId, List<BasecampPlacement> cityPlacements) {
        placements.removeIf(placement -> placement.cityId().equals(cityId));
        placements.addAll(cityPlacements);
        generatedCityIds.add(cityId);
        setDirty();
    }

    public void removeCity(String cityId) {
        boolean changed = generatedCityIds.remove(cityId);
        changed |= placements.removeIf(placement -> placement.cityId().equals(cityId));
        if (changed) setDirty();
    }
}
