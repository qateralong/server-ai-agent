package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;

public record MenuScreen(MenuSection section, String text, InlineKeyboardMarkup keyboard) {

    public static MenuScreen of(String text, InlineKeyboardMarkup keyboard) {
        return new MenuScreen(null, text, keyboard);
    }

    public boolean reflectsAgentState() {
        return section == MenuSection.POWER || section == null && keyboard.buttonCount() > 0;
    }
}
