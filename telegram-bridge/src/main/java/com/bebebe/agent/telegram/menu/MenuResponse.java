package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;

public record MenuResponse(MenuScreen screen, String toast, InputRequest inputRequest) {

    public static MenuResponse show(MenuScreen screen) {
        return new MenuResponse(screen, null, null);
    }

    public static MenuResponse show(MenuScreen screen, String toast) {
        return new MenuResponse(screen, Messages.t(toast), null);
    }

    public static MenuResponse toast(String toast) {
        return new MenuResponse(null, Messages.t(toast), null);
    }

    public static MenuResponse awaitInput(MenuScreen screen, InputRequest request) {
        return new MenuResponse(screen, null, request);
    }
}
