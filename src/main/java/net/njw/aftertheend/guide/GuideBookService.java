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
            "After the End\n\nExpand your world\nbeyond the Ender\nDragon by unlocking\nnew cities.\n\nThis book covers\nthe core beta\nprogression only.",
            "1. Cities & Borders\n\nAt first, only the\nstarting city is\naccessible.\n\nLocked areas limit\nplacing, breaking,\ninteraction, and\nitem pickup.\n\nIf you stay outside,\nyou return to a\nsafe location.",
            "2. Progression\n\nKill Ender Dragon\n↓\nExplore the End\n↓\nDefeat Shulkers\n↓\nCraft Resonance\nCrystals\n↓\nFind & activate\nan Altar\n↓\nUnlock next city",
            "3. Recorded Egg\n\nPlace a Recorded\nDragon Egg from\nJust Dragon Eggs\nat the Altar center.\n\nIts Dragon number\nmust be at least\nthe city's order.\n\nThe egg is returned\nafter the ritual.",
            "4. Resonance\nCrystal\n\nRecipe:\nGlass ×7\nEye of Ender ×1\nShulker Core ×1\n\nShulker Cores drop\nfrom Shulkers.\n\nEach Altar needs\n4 Crystals.",
            "5. Altar Ritual\n\nPlace 4 Resonance\nCrystals in the\nfour sockets.\n\nPlace a Recorded\nDragon Egg in the\ncenter.\n\nThe first Altar\nactivation in a city\nunlocks the next city.",
            "6. Hidden Rewards\n\nEvery Altar hides\none reward chest.\n\nIt holds loot from\nall dimensions,\ntreasure, and always\none villager upgrade\npill.\n\nYou can find it\nbefore activation.",
            "7. City Travel\n\nPress C to open the\nCity List.\n\nTravel between\nunlocked cities in\nthe Overworld.\n\nSome travel requires\nan activated Altar.\nMoving or taking\ndamage can cancel it.",
            "8. Guide Book\n\nAutomatic guide book\ndelivery can be\nchanged in:\n\nMods\n→ After the End\n→ Config\n→ Give Guide Book\n   on Join\n\nTurn it off to stop\nfuture deliveries."
    };
    private static final String[] KOREAN_PAGES = {
            "After the End\n\n엔더 드래곤 이후,\n새로운 도시를 열며\n세계를 확장합니다.\n\n이 책에는 베타의\n핵심 진행 방법만\n정리되어 있습니다.",
            "1. 도시와 경계\n\n처음에는 시작 도시만\n이용할 수 있습니다.\n\n잠긴 지역에서는\n설치·파괴·상호작용,\n아이템 획득 등이\n제한됩니다.\n\n경계를 벗어나면\n안전한 위치로\n돌아오게 됩니다.",
            "2. 진행 순서\n\n엔더 드래곤 처치\n↓\n엔드 탐험\n↓\n셜커 처치\n↓\n공명 수정 제작\n↓\n제단 발견·활성화\n↓\n다음 도시 해금",
            "3. 기록된 드래곤 알\n\n제단 중앙에는\nJust Dragon Eggs의\n기록된 드래곤 알이\n필요합니다.\n\n도시 순서 이상의\nDragon 번호를 가진\n알을 사용해야 합니다.\n\n의식 후 알은\n다시 회수됩니다.",
            "4. 공명 수정\n\n제작 재료\n유리 ×7\n엔더의 눈 ×1\n셜커 코어 ×1\n\n셜커 코어는\n셜커 처치 시\n획득할 수 있습니다.\n\n제단 하나에는\n공명 수정 ×4 필요",
            "5. 제단 활성화\n\n제단의 네 위치에\n공명 수정을 놓고,\n중앙에 기록된\n드래곤 알을 놓습니다.\n\n의식이 끝나면\n제단이 활성화됩니다.\n\n도시의 첫 활성화는\n다음 도시를 엽니다.",
            "6. 숨겨진 보상\n\n각 제단 내부에는\n숨겨진 보상 상자가\n하나 있습니다.\n\n세 차원의 자원과\n보물, 그리고 주민\n강화 알약 1종이\n반드시 들어 있습니다.\n\n제단 활성화 전에도\n찾을 수 있습니다.",
            "7. 도시 이동\n\nC 키로 도시 목록을\n열 수 있습니다.\n\n오버월드에서\n해금된 도시 사이를\n이동할 수 있습니다.\n\n상황에 따라 활성화된\n제단이 필요합니다.\n이동 중 움직임·피해는\n이동을 취소합니다.",
            "8. 가이드 책 설정\n\n이 책의 자동 지급은\n모드 설정에서\n켜거나 끌 수 있습니다.\n\nMods → After the End\n→ Config\n→ 접속 시 가이드 책\n   지급\n\n끄면 이후 접속 시\n자동 지급되지 않습니다."
    };

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
}
