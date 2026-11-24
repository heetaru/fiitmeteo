package meteo.sensor;

import java.math.BigDecimal;

public record Parameter(String name, int min, int max, int decimals) {
    public int parse(String text) {
        try {
            // store decimals as scaled integers
            int value = new BigDecimal(text.trim()).movePointRight(decimals).intValueExact();
            validate(value);
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Neplatná hodnota parametra " + name);
        }
    }

    public void validate(int value) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " musí byť od " + format(min) + " do " + format(max));
        }
    }

    public String format(int value) {
        return BigDecimal.valueOf(value, decimals).toPlainString();
    }
}
