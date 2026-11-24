package meteo.util;

import meteo.protocol.Message;

import java.security.SecureRandom;

public final class Utils {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Utils() {
    }

    public static long generateToken(long previousToken) {
        long candidate;
        do {
            candidate = RANDOM.nextLong(1, Long.MAX_VALUE);
        } while (candidate == previousToken);
        return candidate;
    }

    public static long nextSequence(long sequence) {
        return sequence == Message.MAX_SEQUENCE ? 1 : sequence + 1;
    }

    public static long initialSequence() {
        // use the clock to seed sequences across restarts
        return System.currentTimeMillis() & Message.MAX_SEQUENCE;
    }

    public static boolean isNewerSequence(long candidate, long previous) {
        // compare sequence numbers across wraparound
        long distance = (candidate - previous) & Message.MAX_SEQUENCE;
        long halfSequenceRange = (Message.MAX_SEQUENCE + 1) / 2;
        return distance > 0 && distance < halfSequenceRange;
    }
}
