package ru.milk.maxbot.domain;

public record StatsSummary(
        long recordsCount,
        double totalWeightKg,
        double totalCreditWeightKg,
        double weightedFatPercent,
        double weightedProteinPercent
) {
    public static StatsSummary empty() {
        return new StatsSummary(0, 0, 0, 0, 0);
    }
}
