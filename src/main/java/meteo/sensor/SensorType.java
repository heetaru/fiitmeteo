package meteo.sensor;

import java.util.Random;

// Typy senzorov a ich merané hodnoty.
public enum SensorType {
    THERMO("ThermoNode", new Parameter[]{
            new Parameter("temperature", -500, 600, 1),
            new Parameter("humidity", 0, 1000, 1),
            new Parameter("dew_point", -500, 600, 1),
            new Parameter("pressure", 80000, 110000, 2)
    }),
    WIND("WindSense", new Parameter[]{
            new Parameter("wind_speed", 0, 500, 1),
            new Parameter("wind_gust", 0, 700, 1),
            new Parameter("wind_direction", 0, 359, 0),
            new Parameter("turbulence", 0, 10, 1)
    }),
    RAIN("RainDetect", new Parameter[]{
            new Parameter("rainfall", 0, 5000, 1),
            new Parameter("soil_moisture", 0, 1000, 1),
            new Parameter("flood_risk", 0, 3, 0),
            new Parameter("rain_duration", 0, 60, 0)
    }),
    AIR("AirQuality", new Parameter[]{
            new Parameter("co2", 300, 5000, 0),
            new Parameter("ozone", 0, 5000, 1),
            new Parameter("air_quality_index", 0, 500, 0)
    });

    public final String label;
    public final Parameter[] parameters;

    SensorType(String label, Parameter[] parameters) {
        this.label = label;
        this.parameters = parameters;
    }

    public int[] randomValues(Random random) {
        int[] values = new int[parameters.length];
        for (int index = 0; index < values.length; index++) {
            Parameter parameter = parameters[index];
            values[index] = random.nextInt(parameter.min(), parameter.max() + 1);
        }
        return values;
    }

    public static SensorType fromLabel(String label) {
        for (SensorType sensorType : values()) {
            if (sensorType.label.equals(label)) {
                return sensorType;
            }
        }
        throw new IllegalArgumentException("Neznámy typ senzora");
    }
}
