package meteo.client;

import meteo.protocol.Message;

public class PendingData {
    public final Message message;

    public long lastSentTime;
    // count the original send
    public int attempts = 1;

    public PendingData(Message message, long now) {
        this.message = message;
        this.lastSentTime = now;
    }
}
