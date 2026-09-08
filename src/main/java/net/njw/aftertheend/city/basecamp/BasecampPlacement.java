package net.njw.aftertheend.city.basecamp;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record BasecampPlacement(
        String cityId,
        String templateId,
        int blockX,
        int y,
        int blockZ,
        boolean large,
        boolean ruined,
        String color
) {
    public static final Codec<BasecampPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("cityId").forGetter(BasecampPlacement::cityId),
            Codec.STRING.fieldOf("templateId").forGetter(BasecampPlacement::templateId),
            Codec.INT.fieldOf("blockX").forGetter(BasecampPlacement::blockX),
            Codec.INT.fieldOf("y").forGetter(BasecampPlacement::y),
            Codec.INT.fieldOf("blockZ").forGetter(BasecampPlacement::blockZ),
            Codec.BOOL.fieldOf("large").forGetter(BasecampPlacement::large),
            Codec.BOOL.fieldOf("ruined").forGetter(BasecampPlacement::ruined),
            Codec.STRING.fieldOf("color").forGetter(BasecampPlacement::color)
    ).apply(instance, BasecampPlacement::new));
}
