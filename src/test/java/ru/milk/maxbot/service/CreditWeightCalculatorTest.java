package ru.milk.maxbot.service;

import org.junit.jupiter.api.Test;
import ru.milk.maxbot.util.CreditWeightCalculator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditWeightCalculatorTest {

    @Test
    void calculatesCreditWeightAgainstBaseFatAndProtein() {
        double result = CreditWeightCalculator.calculate(10_000, 3.6, 3.2, 3.4, 3.0);
        assertEquals(10627.45, result, 0.01);
    }

    @Test
    void returnsSameWeightWhenIndicatorsMatchBasis() {
        double result = CreditWeightCalculator.calculate(5_500, 3.4, 3.0, 3.4, 3.0);
        assertEquals(5_500, result, 0.0001);
    }
}
