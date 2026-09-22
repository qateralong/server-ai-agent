package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.time.Duration;
import java.util.List;

final class StatusScreens {

    private StatusScreens() {
    }

    static MenuScreen root(ServerStatus s) {
        StringBuilder text = new StringBuilder();
        text.append("<b>").append(MenuSection.STATUS.title()).append("</b>\n\n");
        text.append("🤖 Agent: <b>").append(s.agentOn() ? "on" : "off").append("</b>\n");
        text.append("⏱ Uptime: ").append(humanDuration(s.uptime())).append('\n');
        text.append('\n');
        text.append("🧠 Model: ").append(esc(s.providerLine())).append('\n');
        text.append(s.modelAvailable() ? "🟢 " : "🔴 ").append(esc(s.modelState())).append('\n');
        text.append("📈 This session: ").append(s.sessionCalls()).append(" calls, ")
                .append(s.sessionTokens()).append(" tokens, failures ").append(s.sessionFailures())
                .append("; total calls: ").append(s.totalCalls()).append('\n');
        text.append('\n');
        text.append("🖥 Clients: ");
        if (s.clients().isEmpty()) {
            text.append("<b>none</b> -- scripts, clipboard and voice unavailable\n");
        } else {
            text.append(s.clients().size()).append('\n');
            for (String c : s.clients()) {
                text.append("  • ").append(esc(c)).append('\n');
            }
        }
        text.append('\n');
        text.append("🐶 Watchdog: ").append(s.watchdogRestarts() == 0 ? "no hangs"
                : "worker thread restarts: " + s.watchdogRestarts()).append('\n');
        text.append("💾 Disk: ").append(s.diskLines().isEmpty() ? "--" : esc(String.join("; ", s.diskLines()))).append('\n');
        text.append("🏷 Version: ").append(esc(s.version())).append('\n');
        text.append("🔄 Updates: ").append(esc(s.updates())).append('\n');
        text.append("⚠️ Last error: ").append(s.lastError().map(StatusScreens::esc).orElse("none")).append('\n');
        text.append('\n');
        text.append("<i>Config: <code>").append(esc(s.configPath())).append("</code> -- edited over SSH, "
                + "changes are read when the server restarts.</i>");

        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(InlineKeyboardButton.of("🔄 Refresh", CallbackData.section(MenuSection.STATUS).encode())),
                MenuRenderer.navigationRow());
        return new MenuScreen(MenuSection.STATUS, text.toString(), InlineKeyboardMarkup.of(rows));
    }

    static MenuScreen desktopStub() {
        String text = """
                <b>%s</b>

                In the standalone build the status is shown in the application window: the main \
                thing there is "is there a network", and without a network this message would not arrive.

                <i>In server mode (server-app) this section works here.</i>""".formatted(MenuSection.STATUS.title());
        return new MenuScreen(MenuSection.STATUS, text, InlineKeyboardMarkup.of(List.of(MenuRenderer.navigationRow())));
    }

    static String humanDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        return Math.max(0, minutes) + " min";
    }

    private static String esc(String s) {
        return TelegramApi.escapeHtml(s == null ? "" : s);
    }
}
