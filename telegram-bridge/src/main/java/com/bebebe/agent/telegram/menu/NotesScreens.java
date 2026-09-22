package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.notes.ChecklistItem;
import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NoteKind;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.util.ArrayList;
import java.util.List;

public final class NotesScreens {

    static final int PAGE_SIZE = 6;

    static final int ITEMS_PER_PAGE = 8;
    private static final int BUTTON_CHARS = 40;

    private NotesScreens() {
    }

    public static MenuScreen root(int lists, int notes, String dir) {
        String text = Messages.t("""
                <b>%s</b>

                📋 Lists: %d
                📝 Notes: %d

                <i>Files: <code>%s</code> -- can be opened as a vault in Obsidian.
                By words: «запиши в список покупок молоко», «покажи заметку про отпуск».</i>""")
                .formatted(MenuSection.NOTES.title(), lists, notes, TelegramApi.escapeHtml(dir));
        return new MenuScreen(MenuSection.NOTES, text, InlineKeyboardMarkup.of(List.of(
                List.of(InlineKeyboardButton.of("➕ Create", CallbackData.notesCreate().encode()),
                        InlineKeyboardButton.of("📂 Open", CallbackData.notesList(0).encode())),
                backRow(CallbackData.root()))));
    }

    public static MenuScreen createChoice() {
        return new MenuScreen(MenuSection.NOTES, "<b>➕ Create</b>\n\nWhat to create?",
                InlineKeyboardMarkup.of(List.of(
                        List.of(InlineKeyboardButton.of("📋 List", CallbackData.notesCreate("list").encode()),
                                InlineKeyboardButton.of("📝 Note", CallbackData.notesCreate("note").encode())),
                        backRow(CallbackData.section(MenuSection.NOTES)))));
    }

    public static MenuScreen awaitingTitle(NoteKind kind) {
        return new MenuScreen(MenuSection.NOTES, Messages.t("""
                <b>%s</b>

                Send the title in the next message.%s

                Cancel: /cancel""").formatted(kind.title(),
                        kind == NoteKind.NOTE ? " The text can be added later with the \"✏️ Append\" button." : ""),
                InlineKeyboardMarkup.of(List.of(backRow(CallbackData.section(MenuSection.NOTES)))));
    }

    public static MenuScreen awaitingText(NoteDocument doc, int page) {
        return new MenuScreen(MenuSection.NOTES, Messages.t("""
                <b>%s</b>

                %s

                Cancel: /cancel""").formatted(TelegramApi.escapeHtml(doc.title()),
                        doc.kind() == NoteKind.LIST
                                ? "Send the text of the new item in the next message."
                                : "Send the text -- it will be appended to the end of the note."),
                InlineKeyboardMarkup.of(List.of(backRow(CallbackData.noteOpen(doc.id(), page)))));
    }

    public static MenuScreen list(List<NoteDocument> docs, int page) {
        if (docs.isEmpty()) {
            return new MenuScreen(MenuSection.NOTES, "<b>📂 Open</b>\n\nNothing yet. Create the first list or note.",
                    InlineKeyboardMarkup.of(List.of(
                            List.of(InlineKeyboardButton.of("➕ Create", CallbackData.notesCreate().encode())),
                            backRow(CallbackData.section(MenuSection.NOTES)))));
        }
        int pages = (docs.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * PAGE_SIZE;
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (NoteDocument doc : docs.subList(from, Math.min(from + PAGE_SIZE, docs.size()))) {
            rows.add(List.of(InlineKeyboardButton.of(cut(doc.displayLine()), CallbackData.noteOpen(doc.id(), current).encode())));
        }
        if (pages > 1) {
            List<InlineKeyboardButton> pager = new ArrayList<>();
            if (current > 0) {
                pager.add(InlineKeyboardButton.of("◀️", CallbackData.notesList(current - 1).encode()));
            }
            pager.add(InlineKeyboardButton.of(Messages.t("%d / %d").formatted(current + 1, pages), CallbackData.noop().encode()));
            if (current < pages - 1) {
                pager.add(InlineKeyboardButton.of("▶️", CallbackData.notesList(current + 1).encode()));
            }
            rows.add(pager);
        }
        rows.add(backRow(CallbackData.section(MenuSection.NOTES)));
        return new MenuScreen(MenuSection.NOTES, Messages.t("<b>📂 Open</b>\n\nTotal: %d. Recent first.").formatted(docs.size()),
                InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen open(NoteDocument doc, int page, boolean removeMode) {
        return open(doc, page, 0, removeMode);
    }

    public static MenuScreen open(NoteDocument doc, int page, int itemPage, boolean removeMode) {
        StringBuilder text = new StringBuilder("<b>").append(TelegramApi.escapeHtml(doc.title())).append("</b>");
        if (!doc.tags().isEmpty()) {
            text.append("\n<i>#").append(TelegramApi.escapeHtml(String.join(" #", doc.tags()))).append("</i>");
        }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (doc.kind() == NoteKind.LIST) {
            List<ChecklistItem> items = doc.items();
            if (items.isEmpty()) {
                text.append("\n\nThe list is empty.");
            } else if (removeMode) {
                text.append("\n\n<i>Tap an item to delete it.</i>");
            } else {
                text.append("\n\nTap an item to check or uncheck it.");
            }
            int pages = Math.max(1, (items.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
            int ip = Math.max(0, Math.min(itemPage, pages - 1));
            int from = ip * ITEMS_PER_PAGE;
            for (ChecklistItem item : items.subList(from, Math.min(from + ITEMS_PER_PAGE, items.size()))) {
                String label = removeMode
                        ? "✖️ " + item.text()
                        : (item.done() ? "☑ " : "☐ ") + item.text() + (item.jobId() != null ? " ⏰" : "");
                CallbackData data = removeMode
                        ? CallbackData.noteRemove(doc.id(), item.index(), page, ip)
                        : CallbackData.noteToggle(doc.id(), item.index(), page, ip);
                rows.add(List.of(InlineKeyboardButton.of(cut(label), data.encode())));
            }
            if (pages > 1) {
                List<InlineKeyboardButton> pager = new ArrayList<>();
                if (ip > 0) {
                    pager.add(InlineKeyboardButton.of("◀️", (removeMode
                            ? CallbackData.noteRemoveMode(doc.id(), page, ip - 1)
                            : CallbackData.noteOpen(doc.id(), page, ip - 1)).encode()));
                }
                pager.add(InlineKeyboardButton.of(Messages.t("%d / %d").formatted(ip + 1, pages), CallbackData.noop().encode()));
                if (ip < pages - 1) {
                    pager.add(InlineKeyboardButton.of("▶️", (removeMode
                            ? CallbackData.noteRemoveMode(doc.id(), page, ip + 1)
                            : CallbackData.noteOpen(doc.id(), page, ip + 1)).encode()));
                }
                rows.add(pager);
            }
            if (removeMode) {
                rows.add(List.of(InlineKeyboardButton.of("Done", CallbackData.noteOpen(doc.id(), page, itemPage).encode())));
            } else {
                List<InlineKeyboardButton> actions = new ArrayList<>();
                actions.add(InlineKeyboardButton.of("➕ Item", CallbackData.noteAdd(doc.id(), page).encode()));
                if (!items.isEmpty()) {
                    actions.add(InlineKeyboardButton.of("🗑 Items", CallbackData.noteRemoveMode(doc.id(), page, itemPage).encode()));
                }
                rows.add(actions);
            }
        } else {
            String body = doc.body().strip();
            if (body.length() > 3000) {
                body = body.substring(0, 2999) + "…";
            }
            text.append("\n\n").append(body.isEmpty() ? "<i>Empty.</i>" : TelegramApi.escapeHtml(body));
            rows.add(List.of(InlineKeyboardButton.of("✏️ Append", CallbackData.noteAdd(doc.id(), page).encode())));
        }
        if (!removeMode) {
            rows.add(List.of(InlineKeyboardButton.of("🗑 Delete " + (doc.kind() == NoteKind.LIST ? "list" : "note"),
                    CallbackData.noteDelete(doc.id(), page, false).encode())));
        }
        rows.add(backRow(CallbackData.notesList(page)));
        return new MenuScreen(MenuSection.NOTES, text.toString(), InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen deleteConfirm(NoteDocument doc, int page) {
        return new MenuScreen(MenuSection.NOTES,
                Messages.t("Delete «%s»? The file will be removed but stays in git history.").formatted(TelegramApi.escapeHtml(doc.title())),
                InlineKeyboardMarkup.of(List.of(List.of(
                        InlineKeyboardButton.of("🗑 Yes", CallbackData.noteDelete(doc.id(), page, true).encode()),
                        InlineKeyboardButton.of("Cancel", CallbackData.noteOpen(doc.id(), page).encode())))));
    }

    private static String cut(String s) {
        return s.length() <= BUTTON_CHARS ? s : s.substring(0, BUTTON_CHARS - 1) + "…";
    }

    private static List<InlineKeyboardButton> backRow(CallbackData back) {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", back.encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }
}
