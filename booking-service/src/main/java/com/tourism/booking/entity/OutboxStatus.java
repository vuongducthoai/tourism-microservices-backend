package com.tourism.booking.entity;

public enum OutboxStatus {
    NEW,
    SENDING,
    SENT,
    DEAD
}
