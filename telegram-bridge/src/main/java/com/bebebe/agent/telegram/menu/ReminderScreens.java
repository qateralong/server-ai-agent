package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.scheduler.JobStatus;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class ReminderScreens {

    static final int PAGE_SIZE = 6;

    private ReminderScreens() {
    }

    public static MenuScreen root(int pending, int history) {
        String text = Messages.t("""
                <b>%s</b>

                📅 Active: %d
                🗄 In history: %d

                <i>To set one, just write «напомни через час ...» or \
                «каждое утро в 9 ...».</i>""").formatted(MenuSection.REMINDERS.title(), pending, history);

        return new MenuScreen(MenuSection.REMINDERS, text, InlineKeyboardMarkup.of(List.of(
                List.of(InlineKeyboardButton.of("📅 Active", CallbackData.remindersActive(0).encode())),
                List.of(InlineKeyboardButton.of("🗄 History", CallbackData.remindersHistory(0).encode())),
                navigationRow())));
    }

    public static MenuScreen active(List<Job> jobs, int page, ZoneId zone) {
        return list(jobs, page, zone, true);
    }

    public static MenuScreen history(List<Job> jobs, int page, ZoneId zone) {
        return list(jobs, page, zone, false);
    }

    private static MenuScreen list(List<Job> jobs, int page, ZoneId zone, boolean active) {
        String title = active ? "📅 Active" : "🗄 History";
        if (jobs.isEmpty()) {
            return new MenuScreen(MenuSection.REMINDERS,
                    Messages.t("<b>%s</b>\n\n%s").formatted(title, active ? "Nothing scheduled." : "History is empty."),
                    InlineKeyboardMarkup.of(List.of(backRow(CallbackData.section(MenuSection.REMINDERS)))));
        }

        int pages = (jobs.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * PAGE_SIZE;
        List<Job> shown = jobs.subList(from, Math.min(from + PAGE_SIZE, jobs.size()));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Job job : shown) {
            String mark = active ? "" : statusMark(job.status()) + " ";
            rows.add(List.of(InlineKeyboardButton.of(mark + job.displayLine(zone),
                    CallbackData.reminderView(job.id(), current).encode())));
        }
        if (pages > 1) {
            List<InlineKeyboardButton> pager = new ArrayList<>();
            if (current > 0) {
                pager.add(InlineKeyboardButton.of("◀️",
                        (active ? CallbackData.remindersActive(current - 1) : CallbackData.remindersHistory(current - 1)).encode()));
            }
            pager.add(InlineKeyboardButton.of(Messages.t("%d / %d").formatted(current + 1, pages), CallbackData.noop().encode()));
            if (current < pages - 1) {
                pager.add(InlineKeyboardButton.of("▶️",
                        (active ? CallbackData.remindersActive(current + 1) : CallbackData.remindersHistory(current + 1)).encode()));
            }
            rows.add(pager);
        }
        if (!active) {
            rows.add(List.of(InlineKeyboardButton.of("🗑 Clear history",
                    CallbackData.remindersClearHistory(false).encode())));
        }
        rows.add(backRow(CallbackData.section(MenuSection.REMINDERS)));

        String legend = active ? "" : "\n\n✅ fired · ⏰ missed, caught up · ✖️ cancelled";
        return new MenuScreen(MenuSection.REMINDERS,
                Messages.t("<b>%s</b>\n\nTotal: %d.%s").formatted(title, jobs.size(), legend),
                InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen view(Job job, int page, ZoneId zone) {
        StringBuilder text = new StringBuilder("<b>")
                .append(TelegramApi.escapeHtml(job.summary().isEmpty() ? "Reminder" : job.summary()))
                .append("</b>\n\n");
        text.append("When: ").append(job.fireAtHuman(zone)).append('\n');
        text.append("Repeat: ").append(job.repeat().describe()).append('\n');
        text.append("Status: ").append(job.status().title()).append('\n');
        if (job.firedAt() != null) {
            text.append("Fired: ").append(java.time.ZonedDateTime.ofInstant(job.firedAt(), zone)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.of("ru")))).append('\n');
        }
        text.append("\n<i>Note for the agent:</i>\n").append(TelegramApi.escapeHtml(job.prompt()));

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (job.status() == JobStatus.PENDING) {
            rows.add(List.of(InlineKeyboardButton.of("❌ Cancel",
                    CallbackData.reminderCancel(job.id(), page).encode())));
        }
        CallbackData back = job.status() == JobStatus.PENDING
                ? CallbackData.remindersActive(page)
                : CallbackData.remindersHistory(page);
        rows.add(backRow(back));
        return new MenuScreen(MenuSection.REMINDERS, text.toString(), InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen clearConfirm(int count) {
        return new MenuScreen(MenuSection.REMINDERS,
                Messages.t("Delete history (%d entries)? Active reminders are not affected.").formatted(count),
                InlineKeyboardMarkup.of(List.of(List.of(
                        InlineKeyboardButton.of("🗑 Yes", CallbackData.remindersClearHistory(true).encode()),
                        InlineKeyboardButton.of("Cancel", CallbackData.remindersHistory(0).encode())))));
    }

    private static String statusMark(JobStatus status) {
        return switch (status) {
            case FIRED -> "✅";
            case MISSED -> "⏰";
            case CANCELLED -> "✖️";
            case PENDING -> "•";
        };
    }

    private static List<InlineKeyboardButton> backRow(CallbackData back) {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", back.encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }

    private static List<InlineKeyboardButton> navigationRow() {
        return backRow(CallbackData.root());
    }
}
