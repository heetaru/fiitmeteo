package meteo.server;

import meteo.protocol.Message;
import meteo.sensor.SensorType;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;

// Stav jedného senzora na serveri.
public class SensorSession {
    public final SensorType type;
    public final String id;
    public final InetSocketAddress address;
    public final long token;
    public final long registrationSequence;
    public final LinkedHashMap<Long, String> acceptedMeasurements = new LinkedHashMap<>();
    public long lastDataTime;
    public long nextHeartbeatTime;
    public long nextProbeTime;
    public long probeSequence;
    public int unansweredProbes;
    public int suppressedAcks;
    public boolean idle;
    public boolean monitoring;

    public SensorSession(Message registration, InetSocketAddress address, long token, long now) {
        type = registration.sensorType;
        id = registration.sensorId;
        registrationSequence = registration.seq;
        this.address = address;
        this.token = token;
        lastDataTime = now;
        nextHeartbeatTime = now + 15_000;
    }
}
