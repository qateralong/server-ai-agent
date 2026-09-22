package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.util.ArrayList;
import java.util.List;

public final class MemoryScreens {

    static final int PAGE_SIZE = 6;

    static final int FACTS_PAGE_SIZE = 5;

    private MemoryScreens() {
    }

    public static MenuScreen root(MemoryStore memory) {
        String text = Messages.t("""
                <b>%s</b>

                👥 People: %d
                📌 Facts: %d
                💬 Messages in session logs: %d

                <i>People and facts are extracted from the conversation automatically, \
                every few messages and when the agent is switched off.</i>""").formatted(
                MenuSection.MEMORY.title(),
                memory.countEntities(), memory.countFacts(), memory.countMessages());

        return new MenuScreen(MenuSection.MEMORY, text, InlineKeyboardMarkup.of(List.of(
                List.of(InlineKeyboardButton.of("👥 People", CallbackData.memoryPeople(0).encode())),
                List.of(InlineKeyboardButton.of("🗑 Forget", CallbackData.memoryForget().encode())),
                navigationRow())));
    }

    public static MenuScreen people(List<Entity> all, int page) {
        if (all.isEmpty()) {
            return new MenuScreen(MenuSection.MEMORY, Messages.t("""
                    <b>👥 People</b>

                    No one yet. The agent will remember people once you mention them in conversation."""),
                    InlineKeyboardMarkup.of(List.of(backRow(CallbackData.section(MenuSection.MEMORY)))));
        }

        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * PAGE_SIZE;
        List<Entity> shown = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Entity entity : shown) {
            rows.add(List.of(InlineKeyboardButton.of(
                    trim(entity.displayName()), CallbackData.memoryPerson(entity.id(), 0).encode())));
        }
        if (pages > 1) {
            rows.add(pager(current, pages, p -> CallbackData.memoryPeople(p)));
        }
        rows.add(backRow(CallbackData.section(MenuSection.MEMORY)));

        return new MenuScreen(MenuSection.MEMORY,
                Messages.t("<b>👥 People</b>\n\nTotal: %d. Tap to open a card.").formatted(all.size()),
                InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen person(Entity entity, List<Fact> facts, int page) {
        int pages = Math.max(1, (facts.size() + FACTS_PAGE_SIZE - 1) / FACTS_PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * FACTS_PAGE_SIZE;
        List<Fact> shown = facts.subList(Math.min(from, facts.size()), Math.min(from + FACTS_PAGE_SIZE, facts.size()));

        StringBuilder text = new StringBuilder("<b>").append(TelegramApi.escapeHtml(entity.canonicalName())).append("</b>\n");
        if (!entity.relation().isEmpty()) {
            text.append(TelegramApi.escapeHtml(entity.relation())).append('\n');
        }
        if (!entity.aliases().isEmpty()) {
            text.append("<i>also: ").append(TelegramApi.escapeHtml(String.join(", ", entity.aliases()))).append("</i>\n");
        }
        text.append('\n');
        if (facts.isEmpty()) {
            text.append("No facts yet.");
        } else {
            text.append("Facts (").append(facts.size()).append("):\n");
            for (int i = 0; i < shown.size(); i++) {
                Fact fact = shown.get(i);
                text.append(from + i + 1).append(". ")
                        .append(TelegramApi.escapeHtml(fact.describeForModel().substring(2)))
                        .append(" <i>[").append(fact.category().title()).append("]</i>\n");
            }
            text.append("\nThe bin with a number deletes the fact.");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> deleteRow = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            deleteRow.add(InlineKeyboardButton.of("🗑 " + (from + i + 1),
                    CallbackData.memoryFactDelete(shown.get(i).id(), entity.id(), current).encode()));
        }
        if (!deleteRow.isEmpty()) {
            rows.add(deleteRow);
        }
        if (pages > 1) {
            rows.add(pager(current, pages, p -> CallbackData.memoryPerson(entity.id(), p)));
        }
        rows.add(List.of(InlineKeyboardButton.of("🗑 Delete person",
                CallbackData.memoryPersonDelete(entity.id()).encode())));
        rows.add(backRow(CallbackData.memoryPeople(0)));

        return new MenuScreen(MenuSection.MEMORY, text.toString(), InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen personDeleteConfirm(Entity entity, int factCount) {
        String text = Messages.t("""
                Delete <b>%s</b> and %d fact(s) about them only?

                Facts that also mention other people will stay with them.""").formatted(
                TelegramApi.escapeHtml(entity.canonicalName()), factCount);

        return new MenuScreen(MenuSection.MEMORY, text, InlineKeyboardMarkup.of(List.of(
                List.of(
                        InlineKeyboardButton.of("🗑 Yes, delete",
                                CallbackData.memoryPersonDeleteConfirmed(entity.id()).encode()),
                        InlineKeyboardButton.of("Cancel",
                                CallbackData.memoryPerson(entity.id(), 0).encode())))));
    }

    public static MenuScreen forget(MemoryStore memory) {
        String text = """
                <b>🗑 Forget</b>

                Targeted cleanup. Every action asks for confirmation.

                • <b>Current conversation</b> -- the active session log of this chat; \
                facts remain.
                • <b>All conversations</b> -- logs of all sessions; facts remain.
                • <b>All facts</b> -- facts about everyone; people remain.
                • <b>Everything</b> -- people, facts, conversations.""";

        return new MenuScreen(MenuSection.MEMORY, text, InlineKeyboardMarkup.of(List.of(
                List.of(InlineKeyboardButton.of("💬 Current conversation", CallbackData.memoryForget("session", false).encode())),
                List.of(InlineKeyboardButton.of("💬 All conversations", CallbackData.memoryForget("sessions", false).encode())),
                List.of(InlineKeyboardButton.of("📌 All facts", CallbackData.memoryForget("facts", false).encode())),
                List.of(InlineKeyboardButton.of("🧨 Everything", CallbackData.memoryForget("all", false).encode())),
                backRow(CallbackData.section(MenuSection.MEMORY)))));
    }

    public static MenuScreen forgetConfirm(String what, String description) {
        return new MenuScreen(MenuSection.MEMORY,
                Messages.t("Really forget <b>%s</b>? This cannot be undone.").formatted(TelegramApi.escapeHtml(description)),
                InlineKeyboardMarkup.of(List.of(List.of(
                        InlineKeyboardButton.of("🗑 Yes", CallbackData.memoryForget(what, true).encode()),
                        InlineKeyboardButton.of("Cancel", CallbackData.memoryForget().encode())))));
    }

    public static MenuScreen entityQuestion(String token, String mention, Entity candidate, int heldFacts) {
        String text = Messages.t("""
                🧠 <b>Memory clarification</b>

                The conversation mentions <b>%s</b>. Is this %s%s or a different person?

                <i>%s</i>""").formatted(
                TelegramApi.escapeHtml(mention),
                TelegramApi.escapeHtml(candidate.canonicalName()),
                candidate.relation().isEmpty() ? "" : " (" + TelegramApi.escapeHtml(candidate.relation()) + ")",
                heldFacts == 0 ? "No facts recorded yet." : "Waiting for the answer: " + heldFacts + " fact(s).");

        return MenuScreen.of(text, InlineKeyboardMarkup.of(List.of(List.of(
                InlineKeyboardButton.of("Same person", CallbackData.memoryResolve(token, true).encode()),
                InlineKeyboardButton.of("Different", CallbackData.memoryResolve(token, false).encode())))));
    }

    private static List<InlineKeyboardButton> pager(int current, int pages,
                                                    java.util.function.IntFunction<CallbackData> target) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (current > 0) {
            row.add(InlineKeyboardButton.of("◀️", target.apply(current - 1).encode()));
        }
        row.add(InlineKeyboardButton.of(Messages.t("%d / %d").formatted(current + 1, pages), CallbackData.noop().encode()));
        if (current < pages - 1) {
            row.add(InlineKeyboardButton.of("▶️", target.apply(current + 1).encode()));
        }
        return row;
    }

    private static List<InlineKeyboardButton> backRow(CallbackData back) {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", back.encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }

    private static List<InlineKeyboardButton> navigationRow() {
        return backRow(CallbackData.root());
    }

    private static String trim(String name) {
        return name.length() <= 40 ? name : name.substring(0, 39) + "…";
    }
}
