package ru.milk.maxbot.util;

public final class CreditWeightCalculator {
    private CreditWeightCalculator() {
    }

    public static double calculate(double weightKg, double fatPercent, double proteinPercent, double baseFatPercent, double baseProteinPercent) {
        double fatFactor = fatPercent / baseFatPercent;
        double proteinFactor = proteinPercent / baseProteinPercent;
        return weightKg * ((fatFactor + proteinFactor) / 2.0d);
    }
}
