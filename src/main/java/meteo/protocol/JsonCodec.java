package meteo.protocol;

import meteo.sensor.Parameter;
import meteo.sensor.SensorType;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Prevod správ na JSON a kontrola CRC32.
public class JsonCodec {

    public record Decoded(Message message, boolean valid, long crc) {
    }

    public static long crc(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    // Protokol používa iba jednoduché reťazce a čísla bez vnorených objektov.
    private static final Pattern FIELD = Pattern.compile(
            "\\s*\"([a-z][a-z0-9_]*)\"\\s*:\\s*"
                    + "(\"[A-Za-z0-9_-]+\"|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?)\\s*");

    public static String body(Message message) {
        StringBuilder json = new StringBuilder("{\"type\":\"").append(message.kind)
                .append("\",\"sensor_type\":\"").append(message.sensorType.label)
                .append("\",\"sensor_id\":\"").append(message.sensorId)
                .append("\",\"seq\":").append(message.seq);
        if (message.kind != Message.Kind.REGISTER) {
            json.append(",\"token\":").append(message.token);
        }
        if (Message.hasReference(message.kind)) {
            json.append(",\"ack_for\":").append(message.reference);
        }
        if (message.kind == Message.Kind.REJECT) {
            json.append(",\"reason\":").append(message.reason);
        }
        for (int index = 0; index < message.values.length; index++) {
            Parameter parameter = message.sensorType.parameters[index];
            json.append(",\"").append(parameter.name()).append("\":")
                    .append(parameter.format(message.values[index]));
        }
        return json.append('}').toString();
    }

    public static String render(Message message, long crc) {
        String body = body(message);
        return body.substring(0, body.length() - 1) + ",\"crc\":" + crc + "}";
    }

    public byte[] encode(Message message, boolean corrupt) {
        long checksum = crc(body(message).getBytes(StandardCharsets.UTF_8));
        return render(message, corrupt ? checksum ^ 1 : checksum).getBytes(StandardCharsets.UTF_8);
    }

    public Decoded decode(byte[] bytes) {
        if (bytes.length > 2048) {
            throw new IllegalArgumentException("JSON packet too large");
        }
        String json = new String(bytes, StandardCharsets.UTF_8).trim();
        Map<String, String> fields = parse(json);
        Message.Kind kind;
        try {
            kind = Message.Kind.valueOf(text(fields, "type"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown message type");
        }
        SensorType sensorType = SensorType.fromLabel(text(fields, "sensor_type"));
        String sensorId = text(fields, "sensor_id");
        long sequence = number(fields, "seq");
        long token = kind == Message.Kind.REGISTER ? 0 : number(fields, "token");
        long reference = Message.hasReference(kind) ? number(fields, "ack_for") : 0;
        int reason = kind == Message.Kind.REJECT ? Math.toIntExact(number(fields, "reason")) : 0;
        int[] values = new int[kind == Message.Kind.DATA ? sensorType.parameters.length : 0];
        for (int index = 0; index < values.length; index++) {
            Parameter parameter = sensorType.parameters[index];
            values[index] = parameter.parse(take(fields, parameter.name()));
        }
        long checksum = number(fields, "crc");
        if (checksum < 0 || checksum > 0xffff_ffffL || !fields.isEmpty()) {
            throw new IllegalArgumentException("Invalid checksum or unknown fields");
        }
        Message message = new Message(kind, sensorType, sensorId, sequence, token, reference, reason, values);
        long expectedChecksum = crc(body(message).getBytes(StandardCharsets.UTF_8));
        return new Decoded(message, checksum == expectedChecksum, checksum);
    }

    private static Map<String, String> parse(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid JSON object");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        int position = 1;
        while (position < json.length() - 1) {
            Matcher matcher = FIELD.matcher(json).region(position, json.length() - 1);
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("Invalid JSON field");
            }
            if (fields.put(matcher.group(1), matcher.group(2)) != null) {
                throw new IllegalArgumentException("Duplicate JSON field");
            }
            position = matcher.end();
            if (position == json.length() - 1) {
                break;
            }
            if (json.charAt(position++) != ',' || position == json.length() - 1) {
                throw new IllegalArgumentException("Invalid JSON separator");
            }
        }
        return fields;
    }

    private static String take(Map<String, String> fields, String key) {
        String value = fields.remove(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }

    private static String text(Map<String, String> fields, String key) {
        String value = take(fields, key);
        if (!value.startsWith("\"")) {
            throw new IllegalArgumentException("Expected string: " + key);
        }
        return value.substring(1, value.length() - 1);
    }

    private static long number(Map<String, String> fields, String key) {
        try {
            return Long.parseLong(take(fields, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected integer: " + key);
        }
    }
}
