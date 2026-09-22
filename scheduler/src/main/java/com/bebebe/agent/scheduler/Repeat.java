package com.bebebe.agent.scheduler;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record Repeat(Kind kind, Set<DayOfWeek> days) {

    public enum Kind { ONCE, DAILY, WEEKLY }

    public static final Repeat ONCE = new Repeat(Kind.ONCE, Set.of());

    public Repeat {
        days = days == null ? Set.of() : Set.copyOf(days);
        if (kind == Kind.WEEKLY && days.isEmpty()) {
            throw new IllegalArgumentException("Weekly repeat without days");
        }
    }

    public static Repeat daily() {
        return new Repeat(Kind.DAILY, Set.of());
    }

    public static Repeat weekly(Set<DayOfWeek> days) {
        return new Repeat(Kind.WEEKLY, days);
    }

    public boolean isRecurring() {
        return kind != Kind.ONCE;
    }

    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        return switch (kind) {
            case ONCE -> Optional.empty();
            case DAILY -> Optional.of(after.plusDays(1));
            case WEEKLY -> {
                ZonedDateTime candidate = after.plusDays(1);
                for (int i = 0; i < 7; i++) {
                    if (days.contains(candidate.getDayOfWeek())) {
                        yield Optional.of(candidate);
                    }
                    candidate = candidate.plusDays(1);
                }
                yield Optional.empty();
            }
        };
    }

    public String toWire() {
        return switch (kind) {
            case ONCE -> "";
            case DAILY -> "daily";
            case WEEKLY -> "weekly:" + String.join(",",
                    new TreeSet<>(days).stream().map(d -> d.name().substring(0, 3)).toList());
        };
    }

    public static Repeat fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return ONCE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("daily") || value.equals("ежедневно") || value.equals("каждый день")) {
            return daily();
        }
        if (value.startsWith("weekly")) {
            String list = value.contains(":") ? value.substring(value.indexOf(':') + 1) : "";
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String token : list.split("[,\\s]+")) {
                parseDay(token).ifPresent(days::add);
            }
            return days.isEmpty() ? ONCE : weekly(days);
        }
        return ONCE;
    }

    static Optional<DayOfWeek> parseDay(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return Optional.empty();
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().toLowerCase(Locale.ROOT).startsWith(t.length() >= 3 ? t.substring(0, 3) : t)) {
                return Optional.of(day);
            }
        }
        return switch (t) {
            case "пн", "понедельник" -> Optional.of(DayOfWeek.MONDAY);
            case "вт", "вторник" -> Optional.of(DayOfWeek.TUESDAY);
            case "ср", "среда" -> Optional.of(DayOfWeek.WEDNESDAY);
            case "чт", "четверг" -> Optional.of(DayOfWeek.THURSDAY);
            case "пт", "пятница" -> Optional.of(DayOfWeek.FRIDAY);
            case "сб", "суббота" -> Optional.of(DayOfWeek.SATURDAY);
            case "вс", "воскресенье" -> Optional.of(DayOfWeek.SUNDAY);
            default -> Optional.empty();
        };
    }

    public String describe() {
        return switch (kind) {
            case ONCE -> "once";
            case DAILY -> "daily";
            case WEEKLY -> "on days: " + String.join(", ", new TreeSet<>(days).stream()
                    .map(d -> d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.of("ru"))).toList());
        };
    }
}
