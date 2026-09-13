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
    private static final int BOOK_TEXT_WIDTH = 112;
    private static final int BOOK_MAX_LINES = 14;
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
            pages.add(Filterable.passThrough(Component.literal(sourcePage).withStyle(ChatFormatting.BLACK)));
        }

        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), AUTHOR, 0, pages, false
        ));
        book.set(DataComponents.CUSTOM_NAME, Component.literal(title).withStyle(ChatFormatting.GOLD));
        return book;
    }

    private static String[] buildKoreanPages() {
        BookPages pages = new BookPages(true);
        pages.addStandalonePage(
                "After the End",
                "제단을 활성화하고 새로운 도시를 열어 세계를 확장하세요.",
                "이 책에는 beta 버전의 핵심 진행 방법이 정리되어 있습니다."
        );
        pages.addStandalonePage(
                "1. 도시와 경계",
                "처음에는 시작 도시만 활성화되어 있습니다.",
                "도시 외부와는 블록 설치 및 파괴, 아이템 획득, 기타 상호작용 등이 제한되며, 활성화된 도시 밖으로는 나갈 수 없습니다."
        );

        pages.addHeading("2. 제단 활성화");
        pages.addSection("2-1. 공명 수정", "제작 재료:\n유리 x7\n엔더의 눈 x1\n셜커 코어 x1");
        pages.addParagraph("셜커 코어는 셜커를 처치하여 획득할 수 있습니다.");
        pages.addParagraph("제단 하나를 활성화하기 위해서는 4개의 공명 수정이 필요합니다.");
        pages.addParagraph("안정화되지 않은 공명 수정은 위험할지도 모릅니다.");

        pages.addSection("2-2. 드래곤 알", "드래곤 알은 이제 매 드래곤을 처치할 때마다 획득할 수 있습니다.");
        pages.addParagraph("이제 드래곤 알에서는 월드에서 몇 번째로 소환된 드래곤이었는지, 누가 얼마만큼의 데미지를 입혔고 누가 마지막 일격을 가했는지를 확인할 수 있습니다.");
        pages.addParagraph("첫 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다.");
        pages.addParagraph("두 번째 드래곤을 처치한 뒤 획득한 드래곤 알은 첫 번째 또는 두 번째 도시에 있는 제단을 활성화하는데 사용할 수 있습니다.");

        pages.addSectionGroup("2-3. 제단 활성화", List.of(
                "제단의 중앙을 기준으로 동, 서, 남, 북의 각 위치에 공명 수정을 설치한 뒤, 제단의 중앙에는 드래곤 알을 놓습니다.",
                "정확한 위치에 설치된 공명 수정은 안정화된 상태가 됩니다. 만약 공명 수정이 안정화되지 않았다면, 위치를 수정해보세요."
        ));
        pages.addParagraphKeepingTogether("제단은 한 도시에서 최대 3개까지 활성화가 가능하고, 첫 제단을 활성화했을 때에만 다음 도시가 열립니다.");

        pages.addSection("3. 도시 이동", "C 키를 눌러 도시 목록을 확인할 수 있습니다.");
        pages.addParagraph("활성화된 제단 근처에서 V키를 누르면 해당 제단을 도시의 도착 지점으로 설정할 수 있습니다.");
        pages.addParagraph("상위 도시로의 이동은 활성화된 제단 근처에서만 가능합니다. 하위 도시로의 이동은 어디서나 가능합니다.");
        pages.addParagraph("참고로, 도시 목록에서 도시 이름을 더블클릭하여 도시 이름을 변경할 수도 있습니다.");

        pages.addSection("4. 숨겨진 보상", "각 제단 내부에는 숨겨진 보물 상자가 하나 존재합니다.");

        pages.addSection("5. 가이드 책 설정", "이 책의 자동 지급은 모드 설정에서 켜거나 끌 수 있습니다.");
        pages.addParagraph("Mods → After the End\n→ Config\n→ 접속 시 가이드 책 지급");
        pages.addParagraph("끄면 이후 접속 시 자동 지급되지 않습니다.");
        return pages.finish();
    }

    private static String[] buildEnglishPages() {
        BookPages pages = new BookPages(false);
        pages.addStandalonePage(
                "After the End",
                "Activate Altars and unlock new cities to expand your world.",
                "This book summarizes the core progression of the beta version."
        );
        pages.addStandalonePage(
                "1. Cities & Borders",
                "At first, only the starting city is active.",
                "Outside cities, block placement and breaking, item pickup, and other interactions are restricted, and you cannot leave the boundaries of active cities."
        );

        pages.addHeading("2. Altar Activation");
        pages.addSection("2-1. Resonance Crystal", "Recipe:\nGlass x7\nEye of Ender x1\nShulker Core x1");
        pages.addParagraph("Shulker Cores can be obtained by defeating Shulkers.");
        pages.addParagraph("Four Resonance Crystals are required to activate one Altar.");
        pages.addParagraph("An unstabilized Resonance Crystal may be dangerous.");

        pages.addSection("2-2. Dragon Egg", "Dragon Eggs can now be obtained each time you defeat the dragon.");
        pages.addParagraph("Dragon Eggs now show which numbered dragon they came from in the world, how much damage each player dealt, and who landed the final blow.");
        pages.addParagraph("An egg obtained after defeating the first dragon can be used to activate an Altar in the first city.");
        pages.addParagraph("An egg obtained after defeating the second dragon can be used to activate an Altar in the first or second city.");

        pages.addSectionGroup("2-3. Altar Activation", List.of(
                "Place Resonance Crystals to the east, west, south, and north of the Altar center, then place a Dragon Egg in the center.",
                "Resonance Crystals placed in the correct positions become stabilized. If a crystal is not stabilized, try adjusting its position."
        ));
        pages.addParagraphKeepingTogether("Up to three Altars can be activated in one city, and only activating the first Altar unlocks the next city.");

        pages.addSection("3. City Travel", "Press C to view the City List.");
        pages.addParagraph("Press V near an activated Altar to set that Altar as the city's arrival point.");
        pages.addParagraph("Travel to a higher city is only possible near an activated Altar. Travel to a lower city is possible from anywhere.");
        pages.addParagraph("You can also double-click a city name in the City List to rename it.");

        pages.addSection("4. Hidden Rewards", "Each Altar contains one hidden treasure chest.");

        pages.addSection("5. Guide Book Settings", "Automatic guide book delivery can be changed in:");
        pages.addParagraph("Mods → After the End\n→ Config\n→ Give Guide Book on Join");
        pages.addParagraph("Turn it off to stop future deliveries.");
        return pages.finish();
    }

    private static List<String> wrapText(String text, boolean korean) {
        List<String> lines = new ArrayList<>();
        String[] physicalLines = text.split("\\n", -1);
        for (String physicalLine : physicalLines) {
            if (physicalLine.isEmpty()) {
                lines.add("");
            } else if (korean) {
                wrapKoreanLine(physicalLine, lines);
            } else {
                wrapEnglishLine(physicalLine, lines);
            }
        }
        return lines;
    }

    private static void wrapKoreanLine(String text, List<String> lines) {
        int start = 0;
        while (start < text.length()) {
            while (start < text.length() && text.charAt(start) == ' ') start++;
            if (start >= text.length()) break;

            int end = start;
            int width = 0;
            while (end < text.length()) {
                char character = text.charAt(end);
                int characterWidth = glyphWidth(character);
                if (width + characterWidth > BOOK_TEXT_WIDTH && end > start) {
                    if (isClosingPunctuation(character) && width + characterWidth <= BOOK_TEXT_WIDTH + 4) end++;
                    break;
                }
                width += characterWidth;
                end++;
            }
            lines.add(text.substring(start, end).stripTrailing());
            start = end;
        }
    }

    private static void wrapEnglishLine(String text, List<String> lines) {
        String[] words = text.trim().split(" +");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(candidate) <= BOOK_TEXT_WIDTH) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (textWidth(word) <= BOOK_TEXT_WIDTH) {
                line.append(word);
            } else {
                wrapLongWord(word, lines, line);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
    }

    private static void wrapLongWord(String word, List<String> lines, StringBuilder remainder) {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char character = word.charAt(i);
            if (!chunk.isEmpty() && textWidth(chunk.toString() + character) > BOOK_TEXT_WIDTH) {
                lines.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(character);
        }
        remainder.append(chunk);
    }

    private static int textWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) width += glyphWidth(text.charAt(i));
        return width;
    }

    private static int glyphWidth(char character) {
        if (character == ' ' || character == '\u00A0') return 4;
        if (isHangul(character)) return 8;
        if (".,:;!|'`".indexOf(character) >= 0) return 3;
        if ("[](){}".indexOf(character) >= 0) return 5;
        if ("ilI1".indexOf(character) >= 0) return 4;
        if ("mwMW@#%&".indexOf(character) >= 0) return 7;
        return character < 128 ? 6 : 8;
    }

    private static boolean isHangul(char character) {
        return character >= 0xAC00 && character <= 0xD7A3
                || character >= 0x1100 && character <= 0x11FF
                || character >= 0x3130 && character <= 0x318F;
    }

    private static boolean isClosingPunctuation(char character) {
        return ".,!?;:".indexOf(character) >= 0;
    }

    private static final class BookPages {
        private final boolean korean;
        private final List<String> pages = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        private BookPages(boolean korean) {
            this.korean = korean;
        }

        private void addStandalonePage(String... paragraphs) {
            flushPage();
            for (int i = 0; i < paragraphs.length; i++) {
                if (i > 0) addGap();
                appendWrapped(paragraphs[i]);
            }
            flushPage();
        }

        private void addHeading(String heading) {
            addGap();
            appendWrapped(heading);
        }

        private void addSection(String heading, String firstParagraph) {
            List<String> headingLines = wrapText(heading, korean);
            List<String> paragraphLines = wrapText(firstParagraph, korean);
            int leadingGap = lines.isEmpty() || lines.getLast().isEmpty() ? 0 : 1;
            int required = leadingGap + headingLines.size() + 1 + paragraphLines.size();
            if (!lines.isEmpty() && lines.size() + required > BOOK_MAX_LINES) flushPage();
            addGap();
            appendLines(headingLines);
            addGap();
            appendLines(paragraphLines);
        }

        private void addSectionGroup(String heading, List<String> paragraphs) {
            List<String> headingLines = wrapText(heading, korean);
            List<List<String>> paragraphLines = new ArrayList<>(paragraphs.size());
            int leadingGap = lines.isEmpty() || lines.getLast().isEmpty() ? 0 : 1;
            int required = leadingGap + headingLines.size() + 1;
            for (int i = 0; i < paragraphs.size(); i++) {
                List<String> wrapped = wrapText(paragraphs.get(i), korean);
                paragraphLines.add(wrapped);
                required += wrapped.size();
                if (i + 1 < paragraphs.size()) required++;
            }
            if (required <= BOOK_MAX_LINES && !lines.isEmpty() && lines.size() + required > BOOK_MAX_LINES) flushPage();
            addGap();
            appendLines(headingLines);
            addGap();
            for (int i = 0; i < paragraphLines.size(); i++) {
                appendLines(paragraphLines.get(i));
                if (i + 1 < paragraphLines.size()) addGap();
            }
        }

        private void addParagraph(String paragraph) {
            addGap();
            appendWrapped(paragraph);
        }

        private void addParagraphKeepingTogether(String paragraph) {
            List<String> paragraphLines = wrapText(paragraph, korean);
            int leadingGap = lines.isEmpty() || lines.getLast().isEmpty() ? 0 : 1;
            if (!lines.isEmpty() && paragraphLines.size() <= BOOK_MAX_LINES
                    && lines.size() + leadingGap + paragraphLines.size() > BOOK_MAX_LINES) {
                flushPage();
            }
            addGap();
            appendLines(paragraphLines);
        }

        private void appendWrapped(String text) {
            appendLines(wrapText(text, korean));
        }

        private void appendLines(List<String> wrappedLines) {
            for (String line : wrappedLines) {
                if (lines.size() >= BOOK_MAX_LINES) flushPage();
                lines.add(line);
            }
        }

        private void addGap() {
            if (lines.isEmpty() || lines.getLast().isEmpty()) return;
            if (lines.size() >= BOOK_MAX_LINES) {
                flushPage();
            } else {
                lines.add("");
            }
        }

        private void flushPage() {
            while (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
            if (lines.isEmpty()) return;
            String page = String.join("\n", lines);
            pages.add(korean ? page.replace(' ', '\u00A0') : page);
            lines.clear();
        }

        private String[] finish() {
            flushPage();
            return pages.toArray(String[]::new);
        }
    }
}
