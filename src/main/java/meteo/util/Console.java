package meteo.util;

import meteo.sensor.Parameter;
import meteo.sensor.SensorType;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class Console {
    private static final BufferedReader INPUT = new BufferedReader(new InputStreamReader(System.in));

    public static String line(String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String value = INPUT.readLine();
        if (value == null) {
            throw new EOFException();
        }
        return value.trim();
    }

    public static int integer(String prompt, int min, int max) throws IOException {
        while (true) {
            try {
                int value = Integer.parseInt(line(prompt));
                if (value >= min && value <= max) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
            System.out.println("Zadajte celé číslo od " + min + " do " + max + ".");
        }
    }

    public static InetAddress ip(String prompt) throws IOException {
        String value = line(prompt);
        // accept ip addresses only
        boolean ipv4 = value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean ipv6 = value.contains(":");
        if (!ipv4 && !ipv6) {
            throw new IllegalArgumentException("Zadajte číselnú IPv4 alebo IPv6 adresu");
        }
        if (ipv4) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("Neplatná IPv4 adresa");
                }
            }
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Neplatná IP adresa");
        }
    }

    public static SensorType type() throws IOException {
        SensorType[] types = SensorType.values();
        for (int index = 0; index < types.length; index++) {
            System.out.println((index + 1) + ". " + types[index].label);
        }
        return types[integer("Typ senzora: ", 1, types.length) - 1];
    }

    public static int[] measurements(SensorType sensorType) throws IOException {
        int[] values = new int[sensorType.parameters.length];
        for (int index = 0; index < values.length; index++) {
            Parameter parameter = sensorType.parameters[index];
            while (true) {
                try {
                    String prompt = parameter.name() + " [" + parameter.format(parameter.min())
                            + ".." + parameter.format(parameter.max()) + "]: ";
                    values[index] = parameter.parse(line(prompt));
                    break;
                } catch (IllegalArgumentException exception) {
                    System.out.println(exception.getMessage());
                }
            }
        }
        return values;
    }

    public static void list(List<String> items) {
        if (items.isEmpty()) {
            System.out.println("Žiadne senzory.");
        }
        for (String item : items) {
            System.out.println(item);
        }
    }
}
