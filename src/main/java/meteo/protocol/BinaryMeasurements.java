package meteo.protocol;

import meteo.sensor.Parameter;
import meteo.sensor.SensorType;

final class BinaryMeasurements {
    private BinaryMeasurements() {
    }

    private static int width(Parameter parameter) {
        // find the bits needed for the largest offset
        int maxOffset = parameter.max() - parameter.min();
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxOffset);
    }

    private static int bitCount(SensorType type) {
        int count = 0;
        for (Parameter parameter : type.parameters) {
            count += width(parameter);
        }
        return count;
    }

    static int byteCount(SensorType type) {
        return (bitCount(type) + Byte.SIZE - 1) / Byte.SIZE;
    }

    static byte[] encode(SensorType type, int[] values) {
        if (values.length != type.parameters.length) {
            throw new IllegalArgumentException("Neplatný počet meraní");
        }

        long packed = 0;
        for (int index = 0; index < values.length; index++) {
            Parameter parameter = type.parameters[index];
            parameter.validate(values[index]);
            // store the distance from the minimum
            int offset = values[index] - parameter.min();
            packed = (packed << width(parameter)) | offset;
        }

        byte[] bytes = new byte[byteCount(type)];
        int padding = bytes.length * Byte.SIZE - bitCount(type);
        // fill unused low bits with zeros
        packed <<= padding;

        for (int index = bytes.length - 1; index >= 0; index--) {
            bytes[index] = (byte) packed;
            packed >>>= Byte.SIZE;
        }

        return bytes;
    }

    static int[] decode(SensorType type, byte[] bytes) {
        if (bytes.length != byteCount(type)) {
            throw new IllegalArgumentException("Neplatná dĺžka meraní");
        }

        long packed = 0;
        for (byte nextByte : bytes) {
            packed = (packed << Byte.SIZE) | Byte.toUnsignedInt(nextByte);
        }

        int padding = bytes.length * Byte.SIZE - bitCount(type);
        long paddingMask = (1L << padding) - 1;
        if ((packed & paddingMask) != 0) {
            throw new IllegalArgumentException("Neplatné výplňové bity");
        }
        packed >>>= padding;

        int[] values = new int[type.parameters.length];
        // read the last packed field first
        for (int index = values.length - 1; index >= 0; index--) {
            Parameter parameter = type.parameters[index];
            int width = width(parameter);
            long mask = (1L << width) - 1;
            int offset = (int) (packed & mask);

            int value = offset + parameter.min();
            parameter.validate(value);
            values[index] = value;
            packed >>>= width;
        }

        return values;
    }
}
