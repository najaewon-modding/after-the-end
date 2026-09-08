package net.njw.aftertheend.client.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.client.ClientCityManager;
import net.njw.aftertheend.network.CityTeleportRequestPayload;

import java.util.List;

public final class CityListScreen extends Screen {
    private static final int CONTENT_WIDTH = 320;
    private static final int CONTENT_HEIGHT = 166;
    private static final int LIST_WIDTH = 96;
    private static final int COLUMN_GAP = 12;
    private static final int ROW_HEIGHT = 20;
    private static final int VISIBLE_CITY_COUNT = 5;
    private static final int BUTTON_WIDTH = 72;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 8;

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFFAAAAAA;
    private static final int DIM_COLOR = 0xFF777777;
    private static final int HOVER_COLOR = 0xFFDDDDDD;
    private static final int UNLOCKED_COLOR = 0xFF55FF55;
    private static final int LOCKED_COLOR = 0xFFFF5555;
    private static final int BUTTON_BACKGROUND = 0x66000000;
    private static final int BUTTON_HOVER_BACKGROUND = 0x88000000;
    private static final int SEPARATOR_COLOR = 0x55FFFFFF;

    private int selectedIndex;
    private int scrollOffset;

    public CityListScreen() {
        super(Component.translatable("gui.njw_after_the_end.city_list.title"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();
        int listTop = top + 34;
        int detailLeft = left + LIST_WIDTH + COLUMN_GAP;

        graphics.centeredText(font, title, width / 2, top, TEXT_COLOR);
        graphics.horizontalLine(left, left + CONTENT_WIDTH, top + 18, SEPARATOR_COLOR);

        renderCityList(graphics, mouseX, mouseY, left, listTop);
        renderCityDetails(graphics, detailLeft, listTop);
        renderBottomButtons(graphics, mouseX, mouseY, left, top);

        if (isInteractive(mouseX, mouseY, left, listTop, top)) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void renderCityList(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int left, int listTop) {
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        normalizeState(cities);
        int endIndex = Math.min(cities.size(), scrollOffset + VISIBLE_CITY_COUNT);

        for (int index = scrollOffset; index < endIndex; index++) {
            ClientCityManager.ClientCity city = cities.get(index);
            int row = index - scrollOffset;
            int y = listTop + row * ROW_HEIGHT;
            boolean selected = index == selectedIndex;
            boolean hovered = isInside(mouseX, mouseY, left, y - 3, LIST_WIDTH, ROW_HEIGHT);
            int color = selected ? TEXT_COLOR : hovered ? HOVER_COLOR : MUTED_COLOR;
            Component marker = Component.literal(selected ? "> " : "  ");
            graphics.text(font, marker.copy().append(Component.literal(city.name())), left, y, color, false);
        }
    }

    private void renderCityDetails(GuiGraphicsExtractor graphics, int x, int y) {
        ClientCityManager.ClientCity city = getSelectedCity();
        if (city == null) {
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.empty"), x, y, MUTED_COLOR, false);
            return;
        }

        graphics.text(font, Component.literal(city.name()), x, y, TEXT_COLOR, false);
        Component status = Component.translatable(city.unlocked() ? "gui.njw_after_the_end.city_list.status.unlocked" : "gui.njw_after_the_end.city_list.status.locked");
        graphics.text(font, status, x, y + 14, city.unlocked() ? UNLOCKED_COLOR : LOCKED_COLOR, false);

        int detailY = y + 38;
        CityRegion overworldRegion = city.getRegion(Level.OVERWORLD.identifier());
        if (overworldRegion != null) {
            renderRegionBounds(graphics, x, detailY, Component.translatable("gui.njw_after_the_end.city_list.dimension.overworld"), overworldRegion);
            detailY += 34;
        }

        CityRegion netherRegion = city.getRegion(Level.NETHER.identifier());
        if (netherRegion != null) renderRegionBounds(graphics, x, detailY, Component.translatable("gui.njw_after_the_end.city_list.dimension.nether"), netherRegion);
    }

    private void renderRegionBounds(GuiGraphicsExtractor graphics, int x, int y, Component dimension, CityRegion region) {
        graphics.text(font, dimension, x, y, MUTED_COLOR, false);
        int rowY = y + 12;
        graphics.text(font, Component.literal("X"), x, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal(Integer.toString(region.minBlockX())), x + 10, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal("Z"), x + 50, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal(Integer.toString(region.minBlockZ())), x + 60, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal("~"), x + 101, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal("X"), x + 114, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal(Integer.toString(region.maxBlockX())), x + 124, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal("Z"), x + 164, rowY, TEXT_COLOR, false);
        graphics.text(font, Component.literal(Integer.toString(region.maxBlockZ())), x + 174, rowY, TEXT_COLOR, false);
    }

    private void renderBottomButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int left, int top) {
        int totalWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int buttonX = left + (CONTENT_WIDTH - totalWidth) / 2;
        int buttonY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        ClientCityManager.ClientCity city = getSelectedCity();
        boolean moveEnabled = city != null && city.unlocked();
        drawButton(graphics, buttonX, buttonY, Component.translatable("gui.njw_after_the_end.city_list.move"), moveEnabled, isInside(mouseX, mouseY, buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT));
        int closeX = buttonX + BUTTON_WIDTH + BUTTON_GAP;
        drawButton(graphics, closeX, buttonY, Component.translatable("gui.njw_after_the_end.city_list.close"), true, isInside(mouseX, mouseY, closeX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT));
    }

    private void drawButton(GuiGraphicsExtractor graphics, int x, int y, Component label, boolean enabled, boolean hovered) {
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, hovered && enabled ? BUTTON_HOVER_BACKGROUND : BUTTON_BACKGROUND);
        if (hovered && enabled) graphics.outline(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, 0x88FFFFFF);
        graphics.centeredText(font, label, x + BUTTON_WIDTH / 2, y + 5, enabled ? TEXT_COLOR : DIM_COLOR);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        int left = left();
        int top = top();
        int listTop = top + 34;
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        int endIndex = Math.min(cities.size(), scrollOffset + VISIBLE_CITY_COUNT);

        for (int index = scrollOffset; index < endIndex; index++) {
            int row = index - scrollOffset;
            int y = listTop + row * ROW_HEIGHT;
            if (isInside(click.x(), click.y(), left, y - 3, LIST_WIDTH, ROW_HEIGHT)) {
                selectedIndex = index;
                return true;
            }
        }

        int totalWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int moveX = left + (CONTENT_WIDTH - totalWidth) / 2;
        int buttonY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        ClientCityManager.ClientCity selectedCity = getSelectedCity();
        if (selectedCity != null && selectedCity.unlocked() && isInside(click.x(), click.y(), moveX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            ClientPacketDistributor.sendToServer(new CityTeleportRequestPayload(selectedCity.id()));
            onClose();
            return true;
        }

        int closeX = moveX + BUTTON_WIDTH + BUTTON_GAP;
        if (isInside(click.x(), click.y(), closeX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            onClose();
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        if (cities.size() <= VISIBLE_CITY_COUNT || scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int left = left();
        int listTop = top() + 34;
        if (!isInside(mouseX, mouseY, left, listTop - 3, LIST_WIDTH, VISIBLE_CITY_COUNT * ROW_HEIGHT)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int maxScroll = Math.max(0, cities.size() - VISIBLE_CITY_COUNT);
        scrollOffset = Math.clamp(scrollOffset + (scrollY < 0 ? 1 : -1), 0, maxScroll);
        return true;
    }

    private boolean isInteractive(double mouseX, double mouseY, int left, int listTop, int top) {
        int endIndex = Math.min(ClientCityManager.getCities().size(), scrollOffset + VISIBLE_CITY_COUNT);
        for (int index = scrollOffset; index < endIndex; index++) {
            int y = listTop + (index - scrollOffset) * ROW_HEIGHT;
            if (isInside(mouseX, mouseY, left, y - 3, LIST_WIDTH, ROW_HEIGHT)) return true;
        }
        int totalWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int moveX = left + (CONTENT_WIDTH - totalWidth) / 2;
        int buttonY = top + CONTENT_HEIGHT - BUTTON_HEIGHT;
        int closeX = moveX + BUTTON_WIDTH + BUTTON_GAP;
        ClientCityManager.ClientCity city = getSelectedCity();
        return city != null && city.unlocked() && isInside(mouseX, mouseY, moveX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT) || isInside(mouseX, mouseY, closeX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private ClientCityManager.ClientCity getSelectedCity() {
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        if (cities.isEmpty()) return null;
        selectedIndex = Math.max(0, Math.min(selectedIndex, cities.size() - 1));
        return cities.get(selectedIndex);
    }

    private void normalizeState(List<ClientCityManager.ClientCity> cities) {
        if (cities.isEmpty()) {
            selectedIndex = 0;
            scrollOffset = 0;
            return;
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, cities.size() - 1));
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, cities.size() - VISIBLE_CITY_COUNT)));
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + VISIBLE_CITY_COUNT) scrollOffset = selectedIndex - VISIBLE_CITY_COUNT + 1;
    }

    private int left() { return (width - CONTENT_WIDTH) / 2; }
    private int top() { return Math.max(24, height / 2 - CONTENT_HEIGHT / 2); }
    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) { return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height; }
}
