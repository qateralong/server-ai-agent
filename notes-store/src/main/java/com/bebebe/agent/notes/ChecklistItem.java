package com.bebebe.agent.notes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ChecklistItem(int index, boolean done, String text, Long jobId) {

    private static final Pattern LINE = Pattern.compile("^\\s*[-*]\\s+\\[([ xX])\\]\\s?(.*)$");
    private static final Pattern JOB = Pattern.compile("\\s*<!--\\s*job:(\\d+)\\s*-->\\s*");

    public static ChecklistItem parse(int index, String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        boolean done = !m.group(1).isBlank();
        String rest = m.group(2);
        Long jobId = null;
        Matcher j = JOB.matcher(rest);
        if (j.find()) {
            jobId = Long.parseLong(j.group(1));
            rest = j.replaceAll(" ").strip();
        }
        return new ChecklistItem(index, done, rest.strip(), jobId);
    }

    public static boolean isItemLine(String line) {
        return LINE.matcher(line).matches();
    }

    public String render() {
        StringBuilder sb = new StringBuilder("- [").append(done ? 'x' : ' ').append("] ").append(text);
        if (jobId != null) {
            sb.append(" <!-- job:").append(jobId).append(" -->");
        }
        return sb.toString();
    }

    public ChecklistItem withDone(boolean value) {
        return new ChecklistItem(index, value, text, jobId);
    }

    public ChecklistItem withJob(Long id) {
        return new ChecklistItem(index, done, text, id);
    }

    public String displayLine() {
        return (index + 1) + ". " + (done ? "☑" : "☐") + " " + text + (jobId != null ? " ⏰" : "");
    }
}
