package com.smartwealth.user.event;

import org.springframework.context.ApplicationEvent;

public class AccountCancelEvent extends ApplicationEvent {
    private final Long userId;

    public AccountCancelEvent(Object source, Long userId) {
        super(source);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}