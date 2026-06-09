package ru.milk.maxbot.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Numbers {
    private static final DecimalFormat ONE_DECIMAL;
    private static final DecimalFormat TWO_DECIMALS;

    static {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        ONE_DECIMAL = new DecimalFormat("0.0", symbols);
        TWO_DECIMALS = new DecimalFormat("0.00", symbols);
    }

    private Numbers() {
    }

    public static String oneDecimal(double value) {
        return ONE_DECIMAL.format(value);
    }

    public static String twoDecimals(double value) {
        return TWO_DECIMALS.format(value);
    }
}
