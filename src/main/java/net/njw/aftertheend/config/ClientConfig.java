package net.njw.aftertheend.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue GIVE_GUIDE_BOOK_ON_JOIN;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        GIVE_GUIDE_BOOK_ON_JOIN = builder
                .comment("Give the After the End guide book when joining a world if the player does not already carry one.")
                .translation("config.njw_after_the_end.give_guide_book_on_join")
                .define("giveGuideBookOnJoin", true);
        SPEC = builder.build();
    }

    private ClientConfig() { }
}
