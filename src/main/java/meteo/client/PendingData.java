package meteo.client;

import meteo.protocol.Message;

// Správa čakajúca na potvrdenie.
public class PendingData {
    public final Message message;

    public long lastSentTime;
    public int attempts = 1;

    public PendingData(Message message, long now) {
        this.message = message;
        this.lastSentTime = now;
    }
}
