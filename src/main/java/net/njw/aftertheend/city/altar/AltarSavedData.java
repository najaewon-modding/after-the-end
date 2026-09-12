package net.njw.aftertheend.city.altar;

import java.util.UUID;
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

public final class AltarSavedData extends SavedData {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<Set<UUID>> GENERATED_CITY_IDS_CODEC = UUID_CODEC.listOf().xmap(
            LinkedHashSet::new,
            set -> new ArrayList<>(set)
    );
    private static final Codec<List<AltarPlacement>> PLACEMENTS_CODEC = AltarPlacement.CODEC.listOf();

    public static final SavedDataType<AltarSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("njw_after_the_end", "altar_data"),
            AltarSavedData::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    GENERATED_CITY_IDS_CODEC.optionalFieldOf("generatedCityIds", Set.of()).forGetter(data -> data.generatedCityIds),
                    PLACEMENTS_CODEC.optionalFieldOf("placements", List.of()).forGetter(data -> data.placements)
            ).apply(instance, AltarSavedData::new)),
            null
    );

    private final Set<UUID> generatedCityIds;
    private final List<AltarPlacement> placements;
    private final Map<UUID, List<AltarPlacement>> placementsByCity;

    public AltarSavedData() {
        generatedCityIds = new LinkedHashSet<>();
        placements = new ArrayList<>();
        placementsByCity = new HashMap<>();
    }

    private AltarSavedData(Set<UUID> generatedCityIds, List<AltarPlacement> placements) {
        this.generatedCityIds = new LinkedHashSet<>(generatedCityIds);
        this.placements = new ArrayList<>(placements);
        this.placementsByCity = buildPlacementIndex(this.placements);
    }

    public boolean isGenerated(UUID cityId) {
        return generatedCityIds.contains(cityId);
    }

    public List<AltarPlacement> getPlacements(UUID cityId) {
        return placementsByCity.getOrDefault(cityId, List.of());
    }

    public int getActivatedCount(UUID cityId) {
        int count = 0;
        for (AltarPlacement placement : getPlacements(cityId)) if (placement.activated()) count++;
        return count;
    }

    public ActivationClaim claimActivation(UUID cityId, int blockX, int y, int blockZ, int maximumActivated) {
        int previousActivatedCount = getActivatedCount(cityId);
        if (previousActivatedCount >= maximumActivated) return new ActivationClaim(false, previousActivatedCount);
        for (int index = 0; index < placements.size(); index++) {
            AltarPlacement placement = placements.get(index);
            if (!sameSite(placement, cityId, blockX, y, blockZ)) continue;
            if (placement.activated()) return new ActivationClaim(false, previousActivatedCount);
            replacePlacement(index, placement.withActivated(true));
            return new ActivationClaim(true, previousActivatedCount);
        }
        return new ActivationClaim(false, previousActivatedCount);
    }

    public boolean setActivated(UUID cityId, int blockX, int y, int blockZ, boolean activated) {
        for (int index = 0; index < placements.size(); index++) {
            AltarPlacement placement = placements.get(index);
            if (!sameSite(placement, cityId, blockX, y, blockZ)) continue;
            if (placement.activated() == activated) return true;
            replacePlacement(index, placement.withActivated(activated));
            return true;
        }
        return false;
    }

    private void replacePlacement(int index, AltarPlacement updated) {
        placements.set(index, updated);
        UUID cityId = updated.cityId();
        List<AltarPlacement> cityPlacements = new ArrayList<>(placementsByCity.getOrDefault(cityId, List.of()));
        for (int cityIndex = 0; cityIndex < cityPlacements.size(); cityIndex++) {
            AltarPlacement current = cityPlacements.get(cityIndex);
            if (sameSite(current, cityId, updated.blockX(), updated.y(), updated.blockZ())) {
                cityPlacements.set(cityIndex, updated);
                break;
            }
        }
        placementsByCity.put(cityId, List.copyOf(cityPlacements));
        setDirty();
    }

    public void markGenerated(UUID cityId, List<AltarPlacement> cityPlacements) {
        placements.removeIf(placement -> placement.cityId().equals(cityId));
        placements.addAll(cityPlacements);
        placementsByCity.put(cityId, List.copyOf(cityPlacements));
        generatedCityIds.add(cityId);
        setDirty();
    }

    public void removeCity(UUID cityId) {
        boolean changed = generatedCityIds.remove(cityId);
        changed |= placements.removeIf(placement -> placement.cityId().equals(cityId));
        placementsByCity.remove(cityId);
        if (changed) setDirty();
    }

    private static boolean sameSite(AltarPlacement placement, UUID cityId, int blockX, int y, int blockZ) {
        return placement.cityId().equals(cityId)
                && placement.blockX() == blockX
                && placement.y() == y
                && placement.blockZ() == blockZ;
    }

    private static Map<UUID, List<AltarPlacement>> buildPlacementIndex(List<AltarPlacement> placements) {
        Map<UUID, List<AltarPlacement>> grouped = new HashMap<>();
        for (AltarPlacement placement : placements) {
            grouped.computeIfAbsent(placement.cityId(), ignored -> new ArrayList<>()).add(placement);
        }
        grouped.replaceAll((cityId, cityPlacements) -> List.copyOf(cityPlacements));
        return grouped;
    }

    public record ActivationClaim(boolean claimed, int previousActivatedCount) { }
}
