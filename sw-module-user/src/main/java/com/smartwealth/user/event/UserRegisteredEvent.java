package com.smartwealth.user.event;

public class UserRegisteredEvent {
    private final Long userId;
    public UserRegisteredEvent(Long userId) { this.userId = userId; }
    public Long getUserId() { return userId; }
}
