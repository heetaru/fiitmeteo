package meteo.client;

import meteo.sensor.SensorType;

public class VirtualSensor {
    public final SensorType type;
    public final String id;

    public long token;
    public long registrationSequence;
    public long nextDataTime;
    public long lastServerContactTime;
    public boolean active;
    public boolean automatic = true;
    public boolean corruptNext;

    public VirtualSensor(SensorType type, String id) {
        this.type = type;
        this.id = id;
    }

    public String source() {
        return "TESTER:" + type.label + ":" + id;
    }
}
