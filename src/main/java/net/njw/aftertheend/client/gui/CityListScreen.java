package net.njw.aftertheend.client.gui;

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
    private static final int GUI_WIDTH = 224;
    private static final int GUI_HEIGHT = 184;
    private static final float GUI_SCALE = 0.90F;

    private static final int BACKGROUND_COLOR = 0xEE17171B;
    private static final int PANEL_COLOR = 0xEE25252B;
    private static final int PANEL_BORDER_COLOR = 0xFF62626C;
    private static final int SELECTED_COLOR = 0xFF315D89;
    private static final int BUTTON_COLOR = 0xFF3A3A42;
    private static final int BUTTON_HOVER_COLOR = 0xFF3F7650;
    private static final int DISABLED_COLOR = 0xFF29292F;

    private static final int LIST_X = 10;
    private static final int LIST_Y = 31;
    private static final int LIST_WIDTH = 98;
    private static final int LIST_ROW_HEIGHT = 26;
    private static final int LIST_ROW_GAP = 2;
    private static final int VISIBLE_CITY_COUNT = 5;
    private static final int LIST_VIEWPORT_HEIGHT = VISIBLE_CITY_COUNT * LIST_ROW_HEIGHT + (VISIBLE_CITY_COUNT - 1) * LIST_ROW_GAP;

    private static final int DETAIL_X = 118;
    private static final int DETAIL_Y = 36;
    private static final int DETAIL_WIDTH = 96;

    private static final int BUTTON_WIDTH = 76;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MOVE_BUTTON_X = 34;
    private static final int CLOSE_BUTTON_X = 114;
    private static final int BOTTOM_BUTTON_Y = 157;

    private int selectedIndex;
    private int scrollOffset;

    public CityListScreen() {
        super(Component.translatable("gui.njw_after_the_end.city_list.title"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int screenLeft = getGuiLeft();
        int screenTop = getGuiTop();
        double logicalMouseX = screenToGuiX(mouseX);
        double logicalMouseY = screenToGuiY(mouseY);

        graphics.pose().pushMatrix();
        graphics.pose().translate(screenLeft, screenTop);
        graphics.pose().scale(GUI_SCALE, GUI_SCALE);

        graphics.fill(0, 0, GUI_WIDTH, GUI_HEIGHT, BACKGROUND_COLOR);
        graphics.outline(0, 0, GUI_WIDTH, GUI_HEIGHT, 0xFF8A8A96);
        graphics.centeredText(font, title, GUI_WIDTH / 2, 11, 0xFFFFFFFF);

        graphics.fill(7, 27, 111, 146, PANEL_COLOR);
        graphics.outline(7, 27, 104, 119, PANEL_BORDER_COLOR);
        graphics.fill(114, 27, 217, 146, PANEL_COLOR);
        graphics.outline(114, 27, 103, 119, PANEL_BORDER_COLOR);

        renderCityList(graphics);
        renderCityDetails(graphics);
        renderBottomButtons(graphics, logicalMouseX, logicalMouseY);
        graphics.pose().popMatrix();
    }

    private void renderCityList(GuiGraphicsExtractor graphics) {
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        normalizeState(cities);
        int endIndex = Math.min(cities.size(), scrollOffset + VISIBLE_CITY_COUNT);
        for (int index = scrollOffset; index < endIndex; index++) {
            ClientCityManager.ClientCity city = cities.get(index);
            int row = index - scrollOffset;
            int y = LIST_Y + row * (LIST_ROW_HEIGHT + LIST_ROW_GAP);
            boolean selected = index == selectedIndex;
            int fillColor = selected ? SELECTED_COLOR : BUTTON_COLOR;
            int textColor = city.unlocked() ? 0xFFFFFFFF : 0xFFAAAAAA;
            graphics.fill(LIST_X, y, LIST_X + LIST_WIDTH, y + LIST_ROW_HEIGHT, fillColor);
            graphics.outline(LIST_X, y, LIST_WIDTH, LIST_ROW_HEIGHT, selected ? 0xFFA9D4FF : 0xFF55555F);
            graphics.centeredText(font, Component.literal(city.name()), LIST_X + LIST_WIDTH / 2, y + 5, textColor);
            graphics.centeredText(font, Component.translatable(city.unlocked() ? "gui.njw_after_the_end.city_list.status.unlocked" : "gui.njw_after_the_end.city_list.status.locked"), LIST_X + LIST_WIDTH / 2, y + 15, city.unlocked() ? 0xFF7FD35A : 0xFFFF7777);
        }
    }

    private void renderCityDetails(GuiGraphicsExtractor graphics) {
        ClientCityManager.ClientCity city = getSelectedCity();
        if (city == null) {
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.empty"), DETAIL_X, DETAIL_Y, 0xFFAAAAAA, false);
            return;
        }

        graphics.centeredText(font, Component.literal(city.name()), DETAIL_X + DETAIL_WIDTH / 2, DETAIL_Y, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(city.unlocked() ? "gui.njw_after_the_end.city_list.status.unlocked" : "gui.njw_after_the_end.city_list.status.locked"), DETAIL_X + DETAIL_WIDTH / 2, DETAIL_Y + 13, city.unlocked() ? 0xFF7FD35A : 0xFFFF5555);

        int y = DETAIL_Y + 34;
        CityRegion overworldRegion = city.getRegion(Level.OVERWORLD.identifier());
        if (overworldRegion != null) {
            long blockX = (long) overworldRegion.centerChunkX() * 16L;
            long blockZ = (long) overworldRegion.centerChunkZ() * 16L;
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.dimension.overworld"), DETAIL_X, y, 0xFFBDBDC7, false);
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.coordinates", blockX, blockZ), DETAIL_X, y + 11, 0xFFFFFFFF, false);
            y += 31;
        }

        CityRegion netherRegion = city.getRegion(Level.NETHER.identifier());
        if (netherRegion != null) {
            long blockX = (long) netherRegion.centerChunkX() * 16L;
            long blockZ = (long) netherRegion.centerChunkZ() * 16L;
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.dimension.nether"), DETAIL_X, y, 0xFFBDBDC7, false);
            graphics.text(font, Component.translatable("gui.njw_after_the_end.city_list.coordinates", blockX, blockZ), DETAIL_X, y + 11, 0xFFFFFFFF, false);
        }
    }

    private void renderBottomButtons(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        ClientCityManager.ClientCity city = getSelectedCity();
        boolean moveEnabled = city != null && city.unlocked();
        boolean moveHovered = moveEnabled && isInside(mouseX, mouseY, MOVE_BUTTON_X, BOTTOM_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        drawButton(graphics, MOVE_BUTTON_X, BOTTOM_BUTTON_Y, Component.translatable("gui.njw_after_the_end.city_list.move"), moveEnabled, moveHovered);

        boolean closeHovered = isInside(mouseX, mouseY, CLOSE_BUTTON_X, BOTTOM_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        drawButton(graphics, CLOSE_BUTTON_X, BOTTOM_BUTTON_Y, Component.translatable("gui.njw_after_the_end.city_list.close"), true, closeHovered);
    }

    private void drawButton(GuiGraphicsExtractor graphics, int x, int y, Component label, boolean enabled, boolean hovered) {
        int color = !enabled ? DISABLED_COLOR : hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR;
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, color);
        graphics.outline(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, hovered ? 0xFF9BD0A8 : 0xFF666670);
        graphics.centeredText(font, label, x + BUTTON_WIDTH / 2, y + 6, enabled ? 0xFFFFFFFF : 0xFF777777);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = screenToGuiX(click.x());
        double mouseY = screenToGuiY(click.y());
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();

        if (isInside(mouseX, mouseY, LIST_X, LIST_Y, LIST_WIDTH, LIST_VIEWPORT_HEIGHT)) {
            int endIndex = Math.min(cities.size(), scrollOffset + VISIBLE_CITY_COUNT);
            for (int index = scrollOffset; index < endIndex; index++) {
                int row = index - scrollOffset;
                int y = LIST_Y + row * (LIST_ROW_HEIGHT + LIST_ROW_GAP);
                if (isInside(mouseX, mouseY, LIST_X, y, LIST_WIDTH, LIST_ROW_HEIGHT)) {
                    selectedIndex = index;
                    return true;
                }
            }
        }

        ClientCityManager.ClientCity selectedCity = getSelectedCity();
        if (selectedCity != null && selectedCity.unlocked() && isInside(mouseX, mouseY, MOVE_BUTTON_X, BOTTOM_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            ClientPacketDistributor.sendToServer(new CityTeleportRequestPayload(selectedCity.id()));
            onClose();
            return true;
        }

        if (isInside(mouseX, mouseY, CLOSE_BUTTON_X, BOTTOM_BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
            onClose();
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<ClientCityManager.ClientCity> cities = ClientCityManager.getCities();
        if (cities.size() <= VISIBLE_CITY_COUNT) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double logicalMouseX = screenToGuiX(mouseX);
        double logicalMouseY = screenToGuiY(mouseY);
        if (!isInside(logicalMouseX, logicalMouseY, LIST_X, LIST_Y, LIST_WIDTH, LIST_VIEWPORT_HEIGHT)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int maxScroll = Math.max(0, cities.size() - VISIBLE_CITY_COUNT);
        if (scrollY > 0.0D) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return true;
        }
        if (scrollY < 0.0D) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    private int getGuiLeft() { return (width - Math.round(GUI_WIDTH * GUI_SCALE)) / 2; }
    private int getGuiTop() { return (height - Math.round(GUI_HEIGHT * GUI_SCALE)) / 2; }
    private double screenToGuiX(double screenX) { return (screenX - getGuiLeft()) / GUI_SCALE; }
    private double screenToGuiY(double screenY) { return (screenY - getGuiTop()) / GUI_SCALE; }
    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) { return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height; }
}
