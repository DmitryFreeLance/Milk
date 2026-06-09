package ru.milk.maxbot.domain;

public enum UserRole {
    PENDING,
    EMPLOYEE,
    DIRECTOR,
    GENERAL_DIRECTOR,
    ADMINISTRATOR;

    public boolean canViewReports() {
        return this == DIRECTOR || this == GENERAL_DIRECTOR || this == ADMINISTRATOR;
    }

    public boolean isAdmin() {
        return this == ADMINISTRATOR;
    }
}
