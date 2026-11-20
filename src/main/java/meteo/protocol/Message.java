package meteo.protocol;

import meteo.sensor.SensorType;

// Obsah jednej správy.
public class Message {
    public static final long MAX_SEQUENCE = 0xffff_ffffL;

    public enum Kind {
        REGISTER, ACK, DATA, RETRY, KEEPALIVE, KEEPALIVE_ACK, REJECT
    }

    public final Kind kind;
    public final SensorType sensorType;
    public final String sensorId;
    public final long seq;
    public final long token;
    public final long reference;
    public final int reason;
    public final int[] values;

    public Message(Kind kind, SensorType sensorType, String sensorId, long seq,
                   long token, long reference, int reason, int[] values) {
        if (kind == null || sensorType == null || sensorId == null
                || !sensorId.matches("[A-Za-z0-9_-]{1,16}")) {
            throw new IllegalArgumentException("ID musí obsahovať 1 až 16 písmen, číslic, _ alebo -");
        }
        if (seq < 1 || seq > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Neplatné poradové číslo");
        }
        if (token < 0 || (kind != Kind.REGISTER && kind != Kind.REJECT && token == 0)) {
            throw new IllegalArgumentException("Neplatný token");
        }
        boolean referenced = hasReference(kind);
        if ((referenced && (reference < 1 || reference > MAX_SEQUENCE))
                || (!referenced && reference != 0)) {
            throw new IllegalArgumentException("Neplatné číslo potvrdzovanej správy");
        }
        if ((kind == Kind.REJECT && (reason < 1 || reason > 5)) || (kind != Kind.REJECT && reason != 0)) {
            throw new IllegalArgumentException("Neplatný dôvod odmietnutia");
        }
        if (kind == Kind.REGISTER && token != 0) {
            throw new IllegalArgumentException("REGISTER nemá token");
        }
        if (values == null || values.length != (kind == Kind.DATA ? sensorType.parameters.length : 0)) {
            throw new IllegalArgumentException("Neplatný počet parametrov");
        }
        for (int index = 0; index < values.length; index++) {
            sensorType.parameters[index].validate(values[index]);
        }
        this.kind = kind;
        this.sensorType = sensorType;
        this.sensorId = sensorId;
        this.seq = seq;
        this.token = token;
        this.reference = reference;
        this.reason = reason;
        this.values = values.clone();
    }

    public static boolean hasReference(Kind kind) {
        return switch (kind) {
            case ACK, RETRY, KEEPALIVE_ACK, REJECT -> true;
            case null -> false;
            default -> false;
        };
    }

    public static Message register(SensorType sensorType, String sensorId, long sequence) {
        return new Message(Kind.REGISTER, sensorType, sensorId, sequence, 0, 0, 0, new int[0]);
    }

    public static Message data(SensorType sensorType, String sensorId, long sequence,
                               long token, int[] values) {
        return new Message(Kind.DATA, sensorType, sensorId, sequence, token, 0, 0, values);
    }
}
