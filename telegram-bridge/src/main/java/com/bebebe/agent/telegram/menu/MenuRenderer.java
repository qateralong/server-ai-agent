package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.core.AgentState;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;

public final class MenuRenderer {

    private static final int ROOT_COLUMNS = 2;

    private MenuRenderer() {
    }

    public static MenuScreen root(AgentState state) {
        return root(state, List.of(MenuSection.values()));
    }

    public static MenuScreen root(AgentState state, List<MenuSection> sections) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (MenuSection section : sections) {
            row.add(InlineKeyboardButton.of(section.title(), CallbackData.section(section).encode()));
            if (row.size() == ROOT_COLUMNS) {
                rows.add(List.copyOf(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) {
            rows.add(List.copyOf(row));
        }
        rows.add(List.of(InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode())));

        String text = """
                <b>Server AI Agent</b>
                State: %s

                Choose a section:""".formatted(badge(state));

        return MenuScreen.of(text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen section(MenuSection section, AgentState state) {
        return section == MenuSection.POWER ? power(state) : stub(section);
    }

    public static MenuScreen power(AgentState state) {
        boolean on = state.isOn();

        InlineKeyboardButton toggle = InlineKeyboardButton.of(
                on ? "🔴 Switch off" : "🟢 Switch on",
                CallbackData.power(!on).encode());

        String text = """
                <b>%s</b>

                State: %s

                %s

                The same flag is toggled by the button in the application window -- \
                it is one state, not two independent settings.""".formatted(
                MenuSection.POWER.title(),
                badge(state),
                on
                        ? "The agent is processing messages."
                        : "Messages are not processed. The menu still works.");

        return new MenuScreen(MenuSection.POWER, text,
                InlineKeyboardMarkup.of(List.of(List.of(toggle),
                        List.of(InlineKeyboardButton.of("🪟 Show window", CallbackData.showWindow().encode())),
                        navigationRow())));
    }

    public static MenuScreen stub(MenuSection section) {
        String text = """
                <b>%s</b>

                %s

                <i>This section is not implemented yet.</i>""".formatted(section.title(), section.description());

        return new MenuScreen(section, text, InlineKeyboardMarkup.of(List.of(navigationRow())));
    }

    public static MenuScreen closed() {
        return MenuScreen.of("Menu closed. Reopen with /menu", InlineKeyboardMarkup.empty());
    }

    static List<InlineKeyboardButton> navigationRow() {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", CallbackData.root().encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }

    private static String badge(AgentState state) {
        return state.isOn() ? "🟢 on" : "🔴 off";
    }
}
