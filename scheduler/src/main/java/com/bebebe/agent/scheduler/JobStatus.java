package com.bebebe.agent.scheduler;

public enum JobStatus {

    PENDING,

    FIRED,

    MISSED,

    CANCELLED;

    public String title() {
        return switch (this) {
            case PENDING -> "pending";
            case FIRED -> "fired";
            case MISSED -> "missed";
            case CANCELLED -> "cancelled";
        };
    }

    public static JobStatus fromWire(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return PENDING;
        }
    }
}
