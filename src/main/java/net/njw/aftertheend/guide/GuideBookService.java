package net.njw.aftertheend.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

public final class GuideBookService {
    private static final String AUTHOR = "After the End";
    private static final String ENGLISH_TITLE = "After the End Guide";
    private static final String KOREAN_TITLE = "After the End 안내서";
    private static final String[] ENGLISH_PAGES = {
            "After the End\n\nActivate Altars and\nunlock new cities to\nexpand your world.\n\nThis book summarizes\nthe core progression\nof the beta version.",
            "1. Cities & Borders\n\nAt first, only the\nstarting city is\nactive.\n\nOutside cities, block\nplacing and breaking,\nitem pickup, and other\ninteractions are\nrestricted. You cannot\nleave an active city's\nboundaries.",
            "2. Altar Activation\n\n2-1. Resonance Crystal\n\nRecipe:\nGlass x7\nEye of Ender x1\nShulker Core x1\n\nShulker Cores are\nobtained by defeating\nShulkers.",
            "2-1. Resonance Crystal\n\nFour Resonance\nCrystals are required\nto activate one Altar.\n\nAn unstable Resonance\nCrystal may be\ndangerous.",
            "2-2. Dragon Egg\n\nDragon Eggs can now\nbe obtained each time\nyou defeat the dragon.\n\nEach egg now shows\nwhich numbered dragon\nit was in the world,\nhow much damage each\nplayer dealt, and who\nlanded the final blow.",
            "2-2. Dragon Egg\n\nAn egg from the first\ndragon can activate\nan Altar in the first\ncity.\n\nAn egg from the second\ndragon can activate\nan Altar in either the\nfirst or second city.",
            "2-3. Altar Activation\n\nPlace Resonance\nCrystals east, west,\nsouth, and north of\nthe Altar center, then\nplace a Dragon Egg in\nthe center.\n\nCorrectly placed\nCrystals stabilize.\nIf not, adjust their\nposition.",
            "Up to 3 Altars can be\nactivated per city.\nOnly the first one\nunlocks the next city.\n3. City Travel\n\nPress C to view the\nCity List.\n\nPress V near an\nactivated Altar to set\nit as that city's\narrival point.",
            "Travel to higher cities\nrequires a nearby\nactivated Altar. Travel\nto lower cities works\nfrom anywhere.\n\nDouble-click a city\nname in the City List\nto rename it.\n4. Hidden Rewards\n\nEach Altar contains\none hidden treasure\nchest.",
            "5. Guide Book\n\nAutomatic guide book\ndelivery can be\nchanged in:\nMods\n→ After the End\n→ Config\n→ Give Guide Book\n   on Join\n\nTurn it off to stop\nfuture deliveries."
    };
    private static final String[] KOREAN_PAGES = koreanCharacterWrap(
            "After the End\n\n제단을 활성화하고 새로운 도시를 열어 세계를 확장하세요.\n\n이 책에는 beta 버전의 핵심 진행 방법이 정리되어 있습니다.",
            "1. 도시와 경계\n\n처음에는 시작 도시만 활성화되어 있습니다.\n\n도시 외부와는 블록 설치 및 파괴, 아이템 획득, 기타 상호작용 등이 제한되며, 활성화된 도시 밖으로는 나갈 수 없습니다.",
            "2. 제단 활성화\n\n2-1. 공명 수정\n\n제작 재료:\n유리 x7\n엔더의 눈 x1\n셜커 코어 x1\n\n셜커 코어는 셜커를 처치하여 획득할 수 있습니다.",
            "2-1. 공명 수정\n\n제단 하나를 활성화하기 위해서는 4개의 공명 수정이 필요합니다.\n\n안정화되지 않은 공명 수정은 위험할지도 모릅니다.",
            "2-2. 드래곤 알\n\n드래곤 알은 이제 매 드래곤을 처치할 때마다 획득할 수 있습니다.\n\n이제 드래곤 알에서는 월드에서 몇 번째로 소환된 드래곤이었는지, 누가 얼마만큼의 데미지를 입혔고 누가 마지막 일격을 가했는지를 확인할 수 있습니다.",
            "2-2. 드래곤 알\n\n첫 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다.\n\n두 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 또는 두 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다.",
            "2-3. 제단 활성화\n\n제단의 중앙을 기준으로 동, 서, 남, 북의 각 위치에 공명 수정을 설치한 뒤, 제단의 중앙에는 드래곤 알을 놓습니다.\n\n정확한 위치에 설치된 공명 수정은 안정화된 상태가 됩니다. 만약 공명 수정이 안정화되지 않았다면, 위치를 수정해보세요.",
            "제단은 한 도시에서 최대 3개까지 활성화가 가능하고, 첫 제단을 활성화했을 때에만 다음 도시가 열립니다.\n3. 도시 이동\n\nC 키를 눌러 도시 목록을 확인할 수 있습니다.\n\n활성화된 제단 근처에서 V키를 누르면 해당 제단을 도시의 도착 지점으로 설정할 수 있습니다.",
            "상위 도시로의 이동은 활성화된 제단 근처에서만 가능합니다. 하위 도시로의 이동은 어디서나 가능합니다.\n\n참고로, 도시 목록에서 도시 이름을 더블클릭하여 도시 이름을 변경할 수도 있습니다\n4. 숨겨진 보상\n\n각 제단 내부에는 숨겨진 보물 상자가 하나 존재합니다.",
            "5. 가이드 책 설정\n\n이 책의 자동 지급은\n모드 설정에서\n켜거나 끌 수 있습니다.\n\nMods → After the End\n→ Config\n→ 접속 시 가이드 책\n   지급\n\n끄면 이후 접속 시\n자동 지급되지 않습니다."
    );

    private GuideBookService() { }

    public static void giveIfMissing(ServerPlayer player) {
        if (hasGuideBook(player)) return;
        ItemStack book = create(player);
        if (!player.getInventory().add(book)) player.drop(book, false);
    }

    public static boolean isGuideBook(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null || !AUTHOR.equals(content.author())) return false;
        String title = content.title().raw();
        return ENGLISH_TITLE.equals(title) || KOREAN_TITLE.equals(title);
    }

    private static boolean hasGuideBook(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isGuideBook(player.getInventory().getItem(slot))) return true;
        }
        return false;
    }

    private static ItemStack create(ServerPlayer player) {
        boolean korean = player.clientInformation().language().toLowerCase(Locale.ROOT).startsWith("ko_");
        String title = korean ? KOREAN_TITLE : ENGLISH_TITLE;
        String[] sourcePages = korean ? KOREAN_PAGES : ENGLISH_PAGES;
        List<Filterable<Component>> pages = new ArrayList<>(sourcePages.length);
        for (String sourcePage : sourcePages) {
            pages.add(Filterable.passThrough(Component.literal(sourcePage).withStyle(ChatFormatting.BLACK)));
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), AUTHOR, 0, pages, false
        ));
        book.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.GOLD));
        return book;
    }

    private static String[] koreanCharacterWrap(String... pages) {
        for (int i = 0; i < pages.length; i++) pages[i] = pages[i].replace(' ', '\u00A0');
        return pages;
    }
}
