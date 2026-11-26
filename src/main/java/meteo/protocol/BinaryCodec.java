package meteo.protocol;

import meteo.sensor.SensorType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

public class BinaryCodec {
    private static final int REGISTER_CODE = 0;
    private static final int ACK_CODE = 1;
    private static final int DATA_CODE = 2;
    private static final int RETRY_CODE = 3;
    private static final int KEEPALIVE_CODE = 4;
    private static final int KEEPALIVE_ACK_CODE = 5;
    private static final int REJECT_CODE = 6;

    private static final int HEADER_BYTES = 2;
    private static final int MAX_ID_LENGTH = 16;
    private static final int CRC_BYTES = Integer.BYTES;

    public record CorruptedData(SensorType sensorType, String sensorId, long sequence, long token) {
    }

    public record Decoded(Message message, boolean valid, long crc, CorruptedData corruptedData) {
        public Decoded(Message message, boolean valid, long crc) {
            this(message, valid, crc, !valid && message.kind == Message.Kind.DATA
                    ? new CorruptedData(message.sensorType, message.sensorId, message.seq, message.token)
                    : null);
        }
    }

    private static long crc32(byte[] body) {
        CRC32 checksum = new CRC32();
        checksum.update(body);
        return checksum.getValue();
    }

    public byte[] encode(Message message, boolean corrupt) {
        byte[] body = encodeBody(message);
        long checksum = crc32(body);

        if (corrupt) {
            // flip one checksum bit
            checksum ^= 1;
        }

        ByteBuffer buffer = ByteBuffer.allocate(body.length + CRC_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(body);
        buffer.putInt((int) checksum);

        return buffer.array();
    }

    public Decoded decode(byte[] packet) {
        int bodyLength = packet.length - CRC_BYTES;
        if (bodyLength < HEADER_BYTES + 1) {
            throw new IllegalArgumentException("Príliš krátka správa");
        }

        byte[] body = Arrays.copyOf(packet, bodyLength);

        ByteBuffer checksumBuffer = ByteBuffer.wrap(packet, bodyLength, CRC_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        long receivedChecksum = Integer.toUnsignedLong(checksumBuffer.getInt());

        boolean valid = receivedChecksum == crc32(body);
        try {
            return new Decoded(decodeBody(body), valid, receivedChecksum);
        } catch (IllegalArgumentException exception) {
            if (!valid) {
                CorruptedData data = readCorruptedDataHeader(body);
                if (data != null) {
                    return new Decoded(null, false, receivedChecksum, data);
                }
            }
            throw exception;
        }
    }

    private static CorruptedData readCorruptedDataHeader(byte[] body) {
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        int header = Byte.toUnsignedInt(buffer.get());
        int idLength = Byte.toUnsignedInt(buffer.get()) + 1;
        if ((header >>> 5) != DATA_CODE || idLength > MAX_ID_LENGTH
                || buffer.remaining() < idLength) {
            return null;
        }

        SensorType type = sensorType((header >>> 3) & 0b11);
        String id = new String(body, HEADER_BYTES, idLength, StandardCharsets.US_ASCII);
        buffer.position(HEADER_BYTES + idLength);
        if (buffer.remaining() < Integer.BYTES + Long.BYTES) {
            return new CorruptedData(type, id, 0, 0);
        }
        long sequence = Integer.toUnsignedLong(buffer.getInt());
        long token = buffer.getLong();
        return new CorruptedData(type, id, sequence, token);
    }

    private static int extraBodyBytes(Message.Kind kind, SensorType type) {
        int size = 0;

        if (kind != Message.Kind.REGISTER) {
            size += Long.BYTES;
        }
        if (Message.hasReference(kind)) {
            size += Integer.BYTES;
        }
        if (kind == Message.Kind.DATA) {
            size += BinaryMeasurements.byteCount(type);
        }

        return size;
    }

    private byte[] encodeBody(Message message) {
        int kindCode = switch (message.kind) {
            case REGISTER -> REGISTER_CODE;
            case ACK -> ACK_CODE;
            case DATA -> DATA_CODE;
            case RETRY -> RETRY_CODE;
            case KEEPALIVE -> KEEPALIVE_CODE;
            case KEEPALIVE_ACK -> KEEPALIVE_ACK_CODE;
            case REJECT -> REJECT_CODE;
        };

        byte[] id = message.sensorId.getBytes(StandardCharsets.US_ASCII);
        // pack kind, sensor and reason into one byte
        int header = (kindCode << 5)
                | (sensorCode(message.sensorType) << 3)
                | message.reason;
        int extraBytes = extraBodyBytes(message.kind, message.sensorType);

        ByteBuffer buffer = ByteBuffer.allocate(
                HEADER_BYTES + id.length + Integer.BYTES + extraBytes
        ).order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) header);
        buffer.put((byte) (id.length - 1));
        buffer.put(id);
        buffer.putInt((int) message.seq);

        if (message.kind != Message.Kind.REGISTER) {
            buffer.putLong(message.token);
        }
        if (Message.hasReference(message.kind)) {
            buffer.putInt((int) message.reference);
        }
        if (message.kind == Message.Kind.DATA) {
            byte[] measurements = BinaryMeasurements.encode(message.sensorType, message.values);
            buffer.put(measurements);
        }

        return buffer.array();
    }

    private Message decodeBody(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int header = Byte.toUnsignedInt(buffer.get());

        Message.Kind kind = switch (header >>> 5) {
            case REGISTER_CODE -> Message.Kind.REGISTER;
            case ACK_CODE -> Message.Kind.ACK;
            case DATA_CODE -> Message.Kind.DATA;
            case RETRY_CODE -> Message.Kind.RETRY;
            case KEEPALIVE_CODE -> Message.Kind.KEEPALIVE;
            case KEEPALIVE_ACK_CODE -> Message.Kind.KEEPALIVE_ACK;
            case REJECT_CODE -> Message.Kind.REJECT;
            default -> throw new IllegalArgumentException("Nepodporovaný typ správy");
        };

        int reason = header & 0b111;
        if (kind != Message.Kind.REJECT && reason != 0) {
            throw new IllegalArgumentException("Neplatné bity hlavičky");
        }

        SensorType type = sensorType((header >>> 3) & 0b11);
        int idLength = Byte.toUnsignedInt(buffer.get()) + 1;
        int extraBytes = extraBodyBytes(kind, type);

        if (idLength > MAX_ID_LENGTH
                || buffer.remaining() != idLength + Integer.BYTES + extraBytes) {
            throw new IllegalArgumentException("Neplatná dĺžka správy");
        }

        byte[] id = new byte[idLength];
        buffer.get(id);
        String sensorId = new String(id, StandardCharsets.US_ASCII);
        // read the full unsigned sequence range
        long sequence = Integer.toUnsignedLong(buffer.getInt());

        if (kind == Message.Kind.REGISTER) {
            return Message.register(type, sensorId, sequence);
        }

        long token = buffer.getLong();
        if (kind == Message.Kind.DATA) {
            byte[] measurements = new byte[BinaryMeasurements.byteCount(type)];
            buffer.get(measurements);

            int[] values = BinaryMeasurements.decode(type, measurements);
            return Message.data(type, sensorId, sequence, token, values);
        }

        long reference = 0;
        if (Message.hasReference(kind)) {
            reference = Integer.toUnsignedLong(buffer.getInt());
        }

        return new Message(kind, type, sensorId, sequence, token, reference, reason, new int[0]);
    }

    private static int sensorCode(SensorType type) {
        return switch (type) {
            case THERMO -> 0;
            case WIND -> 1;
            case RAIN -> 2;
            case AIR -> 3;
        };
    }

    private static SensorType sensorType(int code) {
        return switch (code) {
            case 0 -> SensorType.THERMO;
            case 1 -> SensorType.WIND;
            case 2 -> SensorType.RAIN;
            case 3 -> SensorType.AIR;
            default -> throw new IllegalArgumentException("Neznámy typ senzora");
        };
    }
}
