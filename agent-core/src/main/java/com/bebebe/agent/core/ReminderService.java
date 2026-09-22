package com.bebebe.agent.core;

import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.scheduler.DesktopNotifier;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.scheduler.JobRunner;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.scheduler.Repeat;
import com.bebebe.agent.scheduler.SchedulerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ReminderService implements AgentLifecycleHook {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);

    private final JobStore store;
    private final JobRunner runner;
    private final DesktopNotifier notifier;
    private final Clock clock;
    private final Consumer<UserMessage> agent;
    private final Consumer<AgentCore.Outbound> outbound;

    private final ThreadLocal<String> currentConversation = new ThreadLocal<>();

    public ReminderService(SchedulerConfig config,
                           JobStore store,
                           Clock clock,
                           Consumer<UserMessage> agent,
                           Consumer<AgentCore.Outbound> outbound) {
        this.store = store;
        this.clock = clock;
        this.agent = agent;
        this.outbound = outbound;
        this.notifier = new DesktopNotifier(config.desktopNotifications());
        this.runner = new JobRunner(store, config, clock, this::onFire);
    }

    public JobStore store() {
        return store;
    }

    public JobRunner runner() {
        return runner;
    }

    public void bindConversation(String conversationKey) {
        currentConversation.set(conversationKey);
    }

    public void unbindConversation() {
        currentConversation.remove();
    }

    public long schedule(Instant fireAt, String prompt, String summary, String repeat) {
        String conversation = currentConversation.get() == null ? "" : currentConversation.get();
        Job job = store.add(fireAt, prompt, summary, conversation, Repeat.fromWire(repeat),
                TraceContext.current().orElse(null));
        return job.id();
    }

    private void onFire(Job job, boolean late, Instant now) {

        try (TraceContext.Scope ignored = TraceContext.open("job-" + job.id() + "-" + TraceContext.newId().substring(0, 4))) {
            String text = late ? lateInjection(job, now) : injection(job, now);
            log.atInfo()
                    .addKeyValue("event", "reminder.inject")
                    .addKeyValue("job_id", job.id())
                    .addKeyValue("late", late)
                    .addKeyValue("conversation", job.conversationKey())
                    .log("Injecting reminder #{}{}", job.id(), late ? " (catch-up)" : "");
            agent.accept(UserMessage.system(text, job.conversationKey(), TraceContext.current().orElse(null)));
        }
    }

    private String injection(Job job, Instant now) {
        return """
                [System message from the scheduler]
                It is now %s. Reminder #%d, which you set at the user's request, has fired.
                Your note to yourself: %s

                Remind the user briefly and naturally, in one or two sentences, in Russian. \
                Do not explain that this is a "system message" and do not mention the job number."""
                .formatted(human(now), job.id(), job.prompt());
    }

    private String lateInjection(Job job, Instant now) {
        return """
                [System message from the scheduler]
                It is now %s. Reminder #%d should have fired at %s but did not: \
                the agent was off or the computer was asleep. It is late by %s.
                Note to yourself: %s

                Remind the user now, in Russian, briefly apologising for the delay, and if the \
                time of the event has already passed, say so honestly. One or two sentences, naturally."""
                .formatted(human(now), job.id(), human(job.fireAt()),
                        com.bebebe.agent.tools.reminder.SetReminderTool.humanDuration(
                                java.time.Duration.between(job.fireAt(), now)),
                        job.prompt());
    }

    public void deliver(UserMessage source, AgentReply reply) {
        if (reply instanceof AgentReply.Silence) {
            return;
        }
        String conversation = source.replyTarget().map(id -> "TELEGRAM:" + id).orElse("");
        outbound.accept(new AgentCore.Outbound(conversation, reply));

        if (reply instanceof AgentReply.Text text && notifier.isAvailable()) {
            boolean shown = notifier.notify("⏰ Напоминание", text.text());
            log.atInfo().addKeyValue("event", "reminder.desktop").addKeyValue("shown", shown)
                    .log("Desktop notification: {}", shown ? "shown" : "failed");
        }
    }

    public String promptBlock() {
        List<Job> pending = store.pending();
        if (pending.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\nActive reminders (id -- when -- what); "
                + "cancel or reschedule with the manage_reminders tool:\n");
        for (Job job : pending.subList(0, Math.min(10, pending.size()))) {
            sb.append("• #").append(job.id()).append(" — ").append(human(job.fireAt()));
            if (job.repeat().isRecurring()) {
                sb.append(", ").append(job.repeat().describe());
            }
            sb.append(" — ").append(job.summary().isEmpty() ? job.prompt() : job.summary()).append('\n');
        }
        if (pending.size() > 10) {
            sb.append("... and ").append(pending.size() - 10).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "reminders";
    }

    @Override
    public void onAfterStart() {

        int caught = runner.catchUp();
        if (caught > 0) {
            log.info("[hook {}] caught up missed reminders: {}", name(), caught);
        }
        runner.start();
    }

    @Override
    public void onBeforeStop() {
        runner.stop();
        log.info("[hook {}] scheduler stopped, pending jobs: {}", name(), store.countPending());
    }

    private String human(Instant instant) {
        return ZonedDateTime.ofInstant(instant, clock.getZone()).format(HUMAN);
    }
}
