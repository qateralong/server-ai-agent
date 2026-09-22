package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.util.ArrayList;
import java.util.List;

public final class ConfirmScreens {

    static final int PAGE_SIZE = 6;

    private ConfirmScreens() {
    }

    public static MenuScreen request(ScriptEntry script, String description, String token) {
        String versionNote = script.version() > 1
                ? "\n\n<i>This is a new version -- the code changed, so I am asking again.</i>"
                : "";

        String text = """
                ⚠️ <b>Run the script?</b>

                <b>%s</b>
                %s

                <i>%s</i>%s""".formatted(
                TelegramApi.escapeHtml(script.displayName()),
                TelegramApi.escapeHtml(description),
                statistics(script),
                versionNote);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.of(List.of(
                List.of(
                        InlineKeyboardButton.of("✅ Run", CallbackData.confirmRun(token).encode()),
                        InlineKeyboardButton.of("❌ Cancel", CallbackData.confirmCancel(token).encode()))));

        return MenuScreen.of(text, keyboard);
    }

    public static MenuScreen trustOffer(ScriptEntry script) {
        String text = "Run «%s» without asking next time?"
                .formatted(TelegramApi.escapeHtml(script.displayName()));

        return MenuScreen.of(text, InlineKeyboardMarkup.of(List.of(List.of(
                InlineKeyboardButton.of("🔕 Don't ask again",
                        CallbackData.confirmTrust(script.id()).encode())))));
    }

    public static MenuScreen list(List<ScriptEntry> all, int page) {
        if (all.isEmpty()) {
            return new MenuScreen(MenuSection.CONFIRMATIONS, """
                    <b>%s</b>

                    The catalog is empty. Scripts will appear here after the agent \
                    performs its first action.

                    New scripts ask for confirmation by default."""
                    .formatted(MenuSection.CONFIRMATIONS.title()),
                    InlineKeyboardMarkup.of(List.of(navigationRow())));
        }

        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * PAGE_SIZE;
        List<ScriptEntry> shown = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ScriptEntry entry : shown) {
            rows.add(List.of(InlineKeyboardButton.of(
                    (entry.requiresConfirmation() ? "🔔 " : "🔕 ") + trim(entry.displayName()),
                    CallbackData.confirmToggle(entry.id(), current).encode())));
        }

        if (pages > 1) {
            List<InlineKeyboardButton> pager = new ArrayList<>();
            if (current > 0) {
                pager.add(InlineKeyboardButton.of("◀️",
                        CallbackData.confirmList(current - 1).encode()));
            }
            pager.add(InlineKeyboardButton.of(
                    "%d / %d".formatted(current + 1, pages), CallbackData.noop().encode()));
            if (current < pages - 1) {
                pager.add(InlineKeyboardButton.of("▶️",
                        CallbackData.confirmList(current + 1).encode()));
            }
            rows.add(pager);
        }
        rows.add(navigationRow());

        String text = """
                <b>%s</b>

                🔔 -- ask before running
                🔕 -- run immediately

                Tap to toggle. Total scripts: %d."""
                .formatted(MenuSection.CONFIRMATIONS.title(), all.size());

        return new MenuScreen(MenuSection.CONFIRMATIONS, text, InlineKeyboardMarkup.of(rows));
    }

    private static String statistics(ScriptEntry script) {
        if (script.totalRuns() == 0) {
            return "Running for the first time.";
        }
        return "Ran %d times before, %d successfully."
                .formatted(script.totalRuns(), script.successCount());
    }

    private static String trim(String name) {
        return name.length() <= 40 ? name : name.substring(0, 39) + "…";
    }

    private static List<InlineKeyboardButton> navigationRow() {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", CallbackData.root().encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }
}
