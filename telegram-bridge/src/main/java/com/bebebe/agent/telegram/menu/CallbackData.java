package com.bebebe.agent.telegram.menu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record CallbackData(String namespace, List<String> parts) {

    public static final int MAX_BYTES = 64;

    public static final int WARN_BYTES = 48;

    public static final char SEPARATOR = ':';

    public static final String NS_NAV = "nav";

    public static final String NS_MENU = "menu";

    public static final String NS_POWER = "pwr";

    public static final String NS_SETTINGS = "set";

    public static final String NS_CONFIRM = "cfm";

    public static final String NS_MEMORY = "mem";

    public static final String NS_REMINDERS = "rem";

    public static final String NS_NOTES = "nt";

    public static final String NS_PERSONAS = "per";

    public static final String NS_LOGS = "log";

    public CallbackData {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("callback_data: empty namespace");
        }
        parts = List.copyOf(parts);
        for (String segment : allSegments(namespace, parts)) {
            if (segment.indexOf(SEPARATOR) >= 0) {
                throw new IllegalArgumentException(
                        "callback_data: segment '" + segment + "' contains the separator '" + SEPARATOR + "'");
            }
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("callback_data: empty segment in " + namespace);
            }
        }
        int size = byteSize(namespace, parts);
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "callback_data takes " + size + " bytes with a limit of " + MAX_BYTES
                            + ": " + namespace + SEPARATOR + String.join(String.valueOf(SEPARATOR), parts)
                            + ". Put a short id into the button and keep the value in the process.");
        }
    }

    public static CallbackData of(String namespace, String... parts) {
        return new CallbackData(namespace, List.of(parts));
    }

    public static CallbackData root() {
        return of(NS_NAV, "root");
    }

    public static CallbackData close() {
        return of(NS_NAV, "close");
    }

    public static CallbackData noop() {
        return of(NS_NAV, "noop");
    }

    public static CallbackData section(MenuSection section) {
        return of(NS_MENU, section.id());
    }

    public static CallbackData power(boolean turnOn) {
        return of(NS_POWER, "set", turnOn ? "on" : "off");
    }

    public static CallbackData showWindow() {
        return of(NS_POWER, "window");
    }

    public static CallbackData modelList() {
        return of(NS_SETTINGS, "model");
    }

    public static CallbackData modelPick(int index) {
        return of(NS_SETTINGS, "model", "pick", Integer.toString(index));
    }

    public static CallbackData userList() {
        return of(NS_SETTINGS, "user");
    }

    public static CallbackData userRemove(int index) {
        return of(NS_SETTINGS, "user", "rm", Integer.toString(index));
    }

    public static CallbackData userAdd() {
        return of(NS_SETTINGS, "user", "add");
    }

    public static CallbackData backup() {
        return of(NS_SETTINGS, "backup");
    }

    public static CallbackData provider(String providerId) {
        return of(NS_SETTINGS, "prov", providerId);
    }

    public static CallbackData logs(String filterCode, int page) {
        return of(NS_LOGS, filterCode, String.valueOf(page));
    }

    public static CallbackData liveReplies(boolean enable) {
        return of(NS_SETTINGS, "live", enable ? "on" : "off");
    }

    public static CallbackData scriptsEnabled(boolean enable) {
        return of(NS_SETTINGS, "scripts", enable ? "on" : "off");
    }

    public static CallbackData typingIndicator(boolean enable) {
        return of(NS_SETTINGS, "typing", enable ? "on" : "off");
    }

    public static CallbackData timezone() {
        return of(NS_SETTINGS, "tz");
    }

    public static CallbackData language(String code) {
        return of(NS_SETTINGS, "lang", code);
    }

    public static CallbackData voiceInput(boolean enable) {
        return of(NS_SETTINGS, "vin", enable ? "on" : "off");
    }

    public static CallbackData voiceReplies(boolean enable) {
        return of(NS_SETTINGS, "voice", enable ? "on" : "off");
    }

    public static CallbackData hints(boolean enable) {
        return of(NS_SETTINGS, "hints", enable ? "on" : "off");
    }

    public static CallbackData confirmRun(String token) {
        return of(NS_CONFIRM, "run", token);
    }

    public static CallbackData confirmCancel(String token) {
        return of(NS_CONFIRM, "no", token);
    }

    public static CallbackData confirmTrust(long scriptId) {
        return of(NS_CONFIRM, "trust", Long.toString(scriptId));
    }

    public static CallbackData confirmList(int page) {
        return of(NS_CONFIRM, "list", Integer.toString(page));
    }

    public static CallbackData confirmToggle(long scriptId, int page) {
        return of(NS_CONFIRM, "tgl", Long.toString(scriptId), Integer.toString(page));
    }

    public static CallbackData memoryPeople(int page) {
        return of(NS_MEMORY, "people", Integer.toString(page));
    }

    public static CallbackData memoryPerson(long entityId, int page) {
        return of(NS_MEMORY, "person", Long.toString(entityId), Integer.toString(page));
    }

    public static CallbackData memoryFactDelete(long factId, long entityId, int page) {
        return of(NS_MEMORY, "fdel", Long.toString(factId), Long.toString(entityId), Integer.toString(page));
    }

    public static CallbackData memoryPersonDelete(long entityId) {
        return of(NS_MEMORY, "pdel", Long.toString(entityId));
    }

    public static CallbackData memoryPersonDeleteConfirmed(long entityId) {
        return of(NS_MEMORY, "pdel", Long.toString(entityId), "ok");
    }

    public static CallbackData memoryForget() {
        return of(NS_MEMORY, "forget");
    }

    public static CallbackData memoryForget(String what, boolean confirmed) {
        return confirmed ? of(NS_MEMORY, "forget", what, "ok") : of(NS_MEMORY, "forget", what);
    }

    public static CallbackData memoryResolve(String token, boolean same) {
        return of(NS_MEMORY, "res", token, same ? "same" : "new");
    }

    public static CallbackData remindersActive(int page) {
        return of(NS_REMINDERS, "active", Integer.toString(page));
    }

    public static CallbackData remindersHistory(int page) {
        return of(NS_REMINDERS, "history", Integer.toString(page));
    }

    public static CallbackData reminderView(long jobId, int page) {
        return of(NS_REMINDERS, "view", Long.toString(jobId), Integer.toString(page));
    }

    public static CallbackData reminderCancel(long jobId, int page) {
        return of(NS_REMINDERS, "cancel", Long.toString(jobId), Integer.toString(page));
    }

    public static CallbackData remindersClearHistory(boolean confirmed) {
        return confirmed ? of(NS_REMINDERS, "clear", "ok") : of(NS_REMINDERS, "clear");
    }

    public static CallbackData notesCreate() {
        return of(NS_NOTES, "new");
    }

    public static CallbackData notesCreate(String kind) {
        return of(NS_NOTES, "new", kind);
    }

    public static CallbackData notesList(int page) {
        return of(NS_NOTES, "list", Integer.toString(page));
    }

    public static CallbackData noteOpen(long id, int page) {
        return noteOpen(id, page, 0);
    }

    public static CallbackData noteOpen(long id, int page, int itemPage) {
        return of(NS_NOTES, "open", Long.toString(id), Integer.toString(page), Integer.toString(itemPage));
    }

    public static CallbackData noteToggle(long id, int item, int page) {
        return noteToggle(id, item, page, 0);
    }

    public static CallbackData noteToggle(long id, int item, int page, int itemPage) {
        return of(NS_NOTES, "tgl", Long.toString(id), Integer.toString(item), Integer.toString(page), Integer.toString(itemPage));
    }

    public static CallbackData noteRemoveMode(long id, int page) {
        return noteRemoveMode(id, page, 0);
    }

    public static CallbackData noteRemoveMode(long id, int page, int itemPage) {
        return of(NS_NOTES, "rmm", Long.toString(id), Integer.toString(page), Integer.toString(itemPage));
    }

    public static CallbackData noteRemove(long id, int item, int page) {
        return noteRemove(id, item, page, 0);
    }

    public static CallbackData noteRemove(long id, int item, int page, int itemPage) {
        return of(NS_NOTES, "rm", Long.toString(id), Integer.toString(item), Integer.toString(page), Integer.toString(itemPage));
    }

    public static CallbackData noteAdd(long id, int page) {
        return of(NS_NOTES, "add", Long.toString(id), Integer.toString(page));
    }

    public static CallbackData noteDelete(long id, int page, boolean confirmed) {
        return confirmed
                ? of(NS_NOTES, "drop", Long.toString(id), Integer.toString(page), "ok")
                : of(NS_NOTES, "drop", Long.toString(id), Integer.toString(page));
    }

    public static CallbackData personaActivate(long id) {
        return of(NS_PERSONAS, "act", Long.toString(id));
    }

    public static CallbackData personaView(long id) {
        return of(NS_PERSONAS, "view", Long.toString(id));
    }

    public static CallbackData personaCreate() {
        return of(NS_PERSONAS, "new");
    }

    public static CallbackData personaEdit(long id) {
        return of(NS_PERSONAS, "edit", Long.toString(id));
    }

    public static CallbackData personaDelete(long id, boolean confirmed) {
        return confirmed ? of(NS_PERSONAS, "del", Long.toString(id), "ok") : of(NS_PERSONAS, "del", Long.toString(id));
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(namespace);
        for (String part : parts) {
            sb.append(SEPARATOR).append(part);
        }
        return sb.toString();
    }

    public static CallbackData decode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("callback_data: empty string");
        }
        String[] segments = raw.split(String.valueOf(SEPARATOR), -1);
        List<String> parts = new ArrayList<>(segments.length - 1);
        for (int i = 1; i < segments.length; i++) {
            parts.add(segments[i]);
        }
        return new CallbackData(segments[0], parts);
    }

    public int byteSize() {
        return byteSize(namespace, parts);
    }

    private static int byteSize(String namespace, List<String> parts) {
        return encodeRaw(namespace, parts).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String encodeRaw(String namespace, List<String> parts) {
        StringBuilder sb = new StringBuilder(namespace);
        for (String part : parts) {
            sb.append(SEPARATOR).append(part);
        }
        return sb.toString();
    }

    private static List<String> allSegments(String namespace, List<String> parts) {
        List<String> all = new ArrayList<>(parts.size() + 1);
        all.add(namespace);
        all.addAll(parts);
        return all;
    }

    public String action() {
        return parts.isEmpty() ? "" : parts.getFirst();
    }

    public Optional<String> arg(int index) {
        return index < parts.size() ? Optional.of(parts.get(index)) : Optional.empty();
    }

    public boolean is(String namespace, String action) {
        return this.namespace.equals(namespace) && action().equals(action);
    }

    public boolean inNamespace(String namespace) {
        return this.namespace.equals(namespace);
    }

    @Override
    public String toString() {
        return encode();
    }
}
