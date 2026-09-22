package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.util.ArrayList;
import java.util.List;

public final class PersonaScreens {

    private PersonaScreens() {
    }

    public static MenuScreen list(List<Persona> personas) {
        Persona active = personas.stream().filter(Persona::active).findFirst().orElse(null);
        String text = Messages.t("""
                <b>%s</b>

                Active: <b>%s</b>
                Tap a persona to make it active -- the next reply will follow its instruction. \
                Open and edit with the ✏️ button on the right.""")
                .formatted(MenuSection.PERSONAS.title(), active == null ? "—" : TelegramApi.escapeHtml(active.name()));
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Persona p : personas) {
            rows.add(List.of(
                    InlineKeyboardButton.of(cut(p.displayLine()), CallbackData.personaActivate(p.id()).encode()),
                    InlineKeyboardButton.of("✏️", CallbackData.personaView(p.id()).encode())));
        }
        rows.add(List.of(InlineKeyboardButton.of("➕ Create", CallbackData.personaCreate().encode())));
        rows.add(backRow(CallbackData.root()));
        return new MenuScreen(MenuSection.PERSONAS, text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen view(Persona p) {
        String prompt = p.prompt().isBlank() ? Messages.t("<i>No instruction -- default behaviour.</i>")
                : TelegramApi.escapeHtml(p.prompt().length() > 3000 ? p.prompt().substring(0, 2999) + "…" : p.prompt());
        String text = Messages.t("<b>%s</b>%s\n\n%s").formatted(TelegramApi.escapeHtml(p.name()),
                p.active() ? Messages.t(" -- active") : "", prompt);
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (!p.active()) {
            rows.add(List.of(InlineKeyboardButton.of("✅ Make active", CallbackData.personaActivate(p.id()).encode())));
        }
        rows.add(List.of(
                InlineKeyboardButton.of("✏️ Instruction text", CallbackData.personaEdit(p.id()).encode()),
                InlineKeyboardButton.of("🗑 Delete", CallbackData.personaDelete(p.id(), false).encode())));
        rows.add(backRow(CallbackData.section(MenuSection.PERSONAS)));
        return new MenuScreen(MenuSection.PERSONAS, text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen awaitingName() {
        return new MenuScreen(MenuSection.PERSONAS, Messages.t("""
                <b>➕ New persona</b>

                Send the name in the next message (up to 60 characters). The instruction \
                text is set later with the "✏️ Instruction text" button.

                Cancel: /cancel"""),
                InlineKeyboardMarkup.of(List.of(backRow(CallbackData.section(MenuSection.PERSONAS)))));
    }

    public static MenuScreen awaitingPrompt(Persona p) {
        return new MenuScreen(MenuSection.PERSONAS, Messages.t("""
                <b>✏️ %s</b>

                Send the instruction text in the next message -- it fully replaces the previous \
                one and becomes the system prompt for this persona. Send "-" to clear.

                A long instruction can be attached as a .txt file instead of being typed.

                Cancel: /cancel""").formatted(TelegramApi.escapeHtml(p.name())),
                InlineKeyboardMarkup.of(List.of(backRow(CallbackData.personaView(p.id())))));
    }

    public static MenuScreen deleteConfirm(Persona p) {
        return new MenuScreen(MenuSection.PERSONAS,
                Messages.t("Delete persona «%s»?%s").formatted(TelegramApi.escapeHtml(p.name()),
                        p.active() ? Messages.t(" It is active -- another one will become active.") : ""),
                InlineKeyboardMarkup.of(List.of(List.of(
                        InlineKeyboardButton.of("🗑 Yes", CallbackData.personaDelete(p.id(), true).encode()),
                        InlineKeyboardButton.of("Cancel", CallbackData.personaView(p.id()).encode())))));
    }

    private static String cut(String s) {
        return s.length() <= 40 ? s : s.substring(0, 39) + "…";
    }

    private static List<InlineKeyboardButton> backRow(CallbackData back) {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", back.encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }
}
