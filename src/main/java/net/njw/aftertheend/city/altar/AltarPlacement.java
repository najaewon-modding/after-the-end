package net.njw.aftertheend.city.altar;

import java.util.UUID;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record AltarPlacement(
        UUID cityId,
        String templateId,
        int blockX,
        int y,
        int blockZ,
        boolean large,
        boolean ruined,
        String color,
        boolean activated
) {
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public AltarPlacement(UUID cityId, String templateId, int blockX, int y, int blockZ,
                          boolean large, boolean ruined, String color) {
        this(cityId, templateId, blockX, y, blockZ, large, ruined, color, false);
    }

    public static final Codec<AltarPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.fieldOf("cityId").forGetter(AltarPlacement::cityId),
            Codec.STRING.fieldOf("templateId").forGetter(AltarPlacement::templateId),
            Codec.INT.fieldOf("blockX").forGetter(AltarPlacement::blockX),
            Codec.INT.fieldOf("y").forGetter(AltarPlacement::y),
            Codec.INT.fieldOf("blockZ").forGetter(AltarPlacement::blockZ),
            Codec.BOOL.fieldOf("large").forGetter(AltarPlacement::large),
            Codec.BOOL.fieldOf("ruined").forGetter(AltarPlacement::ruined),
            Codec.STRING.fieldOf("color").forGetter(AltarPlacement::color),
            Codec.BOOL.optionalFieldOf("activated", false).forGetter(AltarPlacement::activated)
    ).apply(instance, AltarPlacement::new));

    public AltarPlacement withActivated(boolean activated) {
        return new AltarPlacement(cityId, templateId, blockX, y, blockZ, large, ruined, color, activated);
    }
}
