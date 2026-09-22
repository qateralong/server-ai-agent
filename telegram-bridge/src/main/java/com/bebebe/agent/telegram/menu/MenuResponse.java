package com.bebebe.agent.telegram.menu;

public record MenuResponse(MenuScreen screen, String toast, InputRequest inputRequest) {

    public static MenuResponse show(MenuScreen screen) {
        return new MenuResponse(screen, null, null);
    }

    public static MenuResponse show(MenuScreen screen, String toast) {
        return new MenuResponse(screen, toast, null);
    }

    public static MenuResponse toast(String toast) {
        return new MenuResponse(null, toast, null);
    }

    public static MenuResponse awaitInput(MenuScreen screen, InputRequest request) {
        return new MenuResponse(screen, null, request);
    }
}
