package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.logging.LogEntry;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

final class LogScreens {

    static final int PAGE = 12;
    static final int LINE_LIMIT = 160;

    enum Filter {
        ERRORS("e", "ERROR", e -> e.isError()),
        WARNINGS("w", "WARN+", e -> e.isError() || "WARN".equals(e.level())),
        ALL("a", "all", e -> true);

        final String code;
        final String title;
        final Predicate<LogEntry> matcher;

        Filter(String code, String title, Predicate<LogEntry> matcher) {
            this.code = code;
            this.title = title;
            this.matcher = matcher;
        }

        static Filter byCode(String code) {
            for (Filter f : values()) {
                if (f.code.equals(code)) {
                    return f;
                }
            }
            return WARNINGS;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private LogScreens() {
    }

    static MenuScreen page(LogBuffer buffer, Filter filter, int page, String logDir) {
        List<LogEntry> matching = buffer.snapshot(filter.matcher, Integer.MAX_VALUE);
        int total = matching.size();
        int pages = Math.max(1, (total + PAGE - 1) / PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));

        int end = total - current * PAGE;
        int start = Math.max(0, end - PAGE);
        List<LogEntry> slice = matching.subList(start, end);

        StringBuilder text = new StringBuilder();
        text.append("<b>").append(MenuSection.LOGS.title()).append("</b> — ").append(filter.title)
                .append(", ").append(total).append(" lines in memory");
        if (pages > 1) {
            text.append(", page ").append(current + 1).append('/').append(pages);
        }
        text.append("\n\n");
        if (slice.isEmpty()) {
            text.append("<i>nothing</i>\n");
        } else {
            text.append("<pre>");
            for (int i = slice.size() - 1; i >= 0; i--) {
                text.append(TelegramApi.escapeHtml(line(slice.get(i)))).append('\n');
            }
            text.append("</pre>");
        }
        text.append("\n<i>Full logs are files on the server:</i>\n<code>tail -f ")
                .append(TelegramApi.escapeHtml(logDir)).append("/agent.log | jq -r '.msg'</code>");

        List<List<InlineKeyboardButton>> rows = new java.util.ArrayList<>();
        rows.add(List.of(
                button(Filter.ERRORS, filter, 0), button(Filter.WARNINGS, filter, 0), button(Filter.ALL, filter, 0)));
        if (pages > 1) {
            List<InlineKeyboardButton> nav = new java.util.ArrayList<>();
            if (current + 1 < pages) {
                nav.add(InlineKeyboardButton.of("◀️ older", CallbackData.logs(filter.code, current + 1).encode()));
            }
            if (current > 0) {
                nav.add(InlineKeyboardButton.of("newer ▶️", CallbackData.logs(filter.code, current - 1).encode()));
            }
            rows.add(nav);
        }
        rows.add(List.of(InlineKeyboardButton.of("🔄 Refresh", CallbackData.logs(filter.code, 0).encode())));
        rows.add(MenuRenderer.navigationRow());
        return new MenuScreen(MenuSection.LOGS, text.toString(), InlineKeyboardMarkup.of(rows));
    }

    static MenuScreen desktopStub() {
        String text = Messages.t("""
                <b>%s</b>

                The live log feed is on the "Logs" screen of the application window; files are in logs/.

                <i>In server mode (server-app) this section works here.</i>""").formatted(MenuSection.LOGS.title());
        return new MenuScreen(MenuSection.LOGS, text, InlineKeyboardMarkup.of(List.of(MenuRenderer.navigationRow())));
    }

    private static InlineKeyboardButton button(Filter f, Filter active, int page) {
        String title = (f == active ? "• " : "") + f.title;
        return InlineKeyboardButton.of(title, CallbackData.logs(f.code, page).encode());
    }

    static String line(LogEntry e) {
        String msg = e.message() == null ? "" : e.message().replace('\n', ' ');
        String s = TIME.format(e.ts()) + " " + e.level().charAt(0) + " " + e.subsystem() + " " + msg
                + (e.error() == null || e.error().isBlank() ? "" : " — " + e.error().replace('\n', ' '));
        return s.length() <= LINE_LIMIT ? s : s.substring(0, LINE_LIMIT - 1) + "…";
    }
}
