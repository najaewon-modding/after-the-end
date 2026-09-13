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
    private static final String[] ENGLISH_PAGES = buildEnglishPages();
    private static final String[] KOREAN_PAGES = buildKoreanPages();

    private GuideBookService() { }

    public static void giveLatest(ServerPlayer player) {
        removeExistingGuideBooks(player);
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

    private static void removeExistingGuideBooks(ServerPlayer player) {
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!isGuideBook(player.getInventory().getItem(slot))) continue;
            player.getInventory().setItem(slot, ItemStack.EMPTY);
            changed = true;
        }
        if (changed) player.getInventory().setChanged();
    }

    private static ItemStack create(ServerPlayer player) {
        boolean korean = player.clientInformation().language().toLowerCase(Locale.ROOT).startsWith("ko_");
        String title = korean ? KOREAN_TITLE : ENGLISH_TITLE;
        String[] sourcePages = korean ? KOREAN_PAGES : ENGLISH_PAGES;
        List<Filterable<Component>> pages = new ArrayList<>(sourcePages.length);
        for (String sourcePage : sourcePages) {
            String renderedPage = korean ? sourcePage.replace(' ', '\u00A0') : sourcePage;
            pages.add(Filterable.passThrough(Component.literal(renderedPage).withStyle(ChatFormatting.BLACK)));
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), AUTHOR, 0, pages, false
        ));
        book.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.GOLD));
        return book;
    }

    private static String[] buildKoreanPages() {
        return new String[] {
                page(
                        "After the End",
                        "제단을 활성화하고 새로운 도시를 열어 세계를 확장하세요.",
                        "이 책에는 beta 버전의 핵심 진행 방법이 정리되어 있습니다."
                ),
                page(
                        "1. 도시와 경계",
                        "처음에는 시작 도시만 활성화되어 있습니다.",
                        "도시 외부와는 블록 설치 및 파괴, 아이템 획득, 기타 상호작용 등이 제한되며, 활성화된 도시 밖으로는 나갈 수 없습니다."
                ),
                page(
                        "2. 제단 활성화",
                        "2-1. 공명 수정",
                        "제작 재료:\n유리 x7\n엔더의 눈 x1\n셜커 코어 x1",
                        "셜커 코어는 셜커를 처치하여 획득할 수 있습니다."
                ),
                page(
                        "제단 하나를 활성화하기 위해서는 4개의 공명 수정이 필요합니다.",
                        "안정화되지 않은 공명 수정은 위험할지도 모릅니다.",
                        "2-2. 드래곤 알",
                        "드래곤 알은 이제 매 드래곤을 처치할 때마다 획득할 수 있습니다."
                ),
                page(
                        "이제 드래곤 알에서는 월드에서 몇 번째로 소환된 드래곤이었는지, 누가 얼마만큼의 데미지를 입혔고 누가 마지막 일격을 가했는지를 확인할 수 있습니다.",
                        "첫 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다."
                ),
                page(
                        "두 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 또는 두 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다.",
                        "2-3. 제단 활성화",
                        "제단의 중앙을 기준으로 동, 서, 남, 북의 각 위치에 공명 수정을 설치한 뒤, 제단의 중앙에는 드래곤 알을 놓습니다."
                ),
                page(
                        "정확한 위치에 설치된 공명 수정은 안정화된 상태가 됩니다. 만약 공명 수정이 안정화되지 않았다면, 위치를 수정해보세요.",
                        "제단은 한 도시에서 최대 3개까지 활성화가 가능하고, 첫 제단을 활성화했을 때에만 다음 도시가 열립니다."
                ),
                page(
                        "3. 도시 이동",
                        "C 키를 눌러 도시 목록을 확인할 수 있습니다.",
                        "활성화된 제단 근처에서 V키를 누르면 해당 제단을 도시의 도착 지점으로 설정할 수 있습니다."
                ),
                page(
                        "상위 도시로의 이동은 활성화된 제단 근처에서만 가능합니다. 하위 도시로의 이동은 어디서나 가능합니다.",
                        "참고로, 도시 목록에서 도시 이름을 더블클릭하여 도시 이름을 변경할 수도 있습니다.",
                        "4. 숨겨진 보상",
                        "각 제단 내부에는 숨겨진 보물 상자가 하나 존재합니다."
                ),
                page(
                        "5. 가이드 책 설정",
                        "이 책의 자동 지급은 모드 설정에서 켜거나 끌 수 있습니다.",
                        "Mods → After the End\n→ Config\n→ 접속 시 가이드 책 지급",
                        "끄면 이후 접속 시 자동 지급되지 않습니다."
                )
        };
    }

    private static String[] buildEnglishPages() {
        return new String[] {
                page(
                        "After the End",
                        "Activate Altars and unlock new cities to expand your world.",
                        "This book summarizes the core progression of the beta version."
                ),
                page(
                        "1. Cities & Borders",
                        "At first, only the starting city is active."
                ),
                page(
                        "Outside cities, block placement and breaking, item pickup, and other interactions are restricted, and you cannot leave the boundaries of active cities.",
                        "2. Altar Activation"
                ),
                page(
                        "2-1. Resonance Crystal",
                        "Recipe:\nGlass x7\nEye of Ender x1\nShulker Core x1",
                        "Shulker Cores can be obtained by defeating Shulkers."
                ),
                page(
                        "Four Resonance Crystals are required to activate one Altar.",
                        "An unstabilized Resonance Crystal may be dangerous.",
                        "2-2. Dragon Egg"
                ),
                page(
                        "Dragon Eggs can now be obtained each time you defeat the dragon.",
                        "Dragon Eggs now show which numbered dragon they came from in the world, how much damage each player dealt, and who landed the final blow."
                ),
                page(
                        "An egg obtained after defeating the first dragon can be used to activate an Altar in the first city.",
                        "An egg obtained after defeating the second dragon can be used to activate an Altar in the first or second city."
                ),
                page(
                        "2-3. Altar Activation",
                        "Place Resonance Crystals to the east, west, south, and north of the Altar center, then place a Dragon Egg in the center."
                ),
                page(
                        "Resonance Crystals placed in the correct positions become stabilized. If a crystal is not stabilized, try adjusting its position."
                ),
                page(
                        "Up to three Altars can be activated in one city, and only activating the first Altar unlocks the next city.",
                        "3. City Travel",
                        "Press C to view the City List."
                ),
                page(
                        "Press V near an activated Altar to set that Altar as the city's arrival point.",
                        "Travel to a higher city is only possible near an activated Altar. Travel to a lower city is possible from anywhere."
                ),
                page(
                        "You can also double-click a city name in the City List to rename it.",
                        "4. Hidden Rewards",
                        "Each Altar contains one hidden treasure chest.",
                        "5. Guide Book Settings"
                ),
                page(
                        "Automatic guide book delivery can be changed in:",
                        "Mods → After the End\n→ Config\n→ Give Guide Book on Join",
                        "Turn it off to stop future deliveries."
                )
        };
    }

    private static String page(String... paragraphs) {
        return String.join("\n\n", paragraphs);
    }
}
