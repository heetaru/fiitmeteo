package meteo.client;

import meteo.logging.Log;
import meteo.network.UdpTransport;
import meteo.protocol.JsonCodec;
import meteo.protocol.Message;
import meteo.sensor.SensorType;
import meteo.util.Utils;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// Registrácia senzorov, merania a opakovanie správ.
public class Tester implements AutoCloseable {
    private static final long DATA_INTERVAL_MS = 10_000;
    private static final int MAX_SEND_ATTEMPTS = 3;
    private static final int INVALID_SESSION_REASON = 1;

    private final UdpTransport udp;
    private final InetSocketAddress server;
    private final Log log;
    private final Logger logger;
    private final LongSupplier clock;
    private final Random random = new Random();
    private final Map<String, VirtualSensor> sensors = new LinkedHashMap<>();
    private final Map<Long, PendingData> pending = new LinkedHashMap<>();

    private ScheduledExecutorService timer;
    private boolean running;
    private long sequence = Utils.initialSequence();

    public Tester(InetSocketAddress bind, InetSocketAddress server, JsonCodec codec, Log log)
            throws SocketException {
        this(bind, server, codec, log, () -> System.nanoTime() / 1_000_000);
    }

    public Tester(InetSocketAddress bind, InetSocketAddress server, JsonCodec codec, Log log,
                  LongSupplier clock) throws SocketException {
        this.server = server;
        this.log = log;
        this.logger = log.logger("TESTER");
        this.clock = clock;
        this.udp = new UdpTransport(bind, codec, log, "TESTER");
    }

    public InetSocketAddress address() {
        return udp.address();
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        udp.start(this::receive);
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(this::tickSafely, 50, 50, TimeUnit.MILLISECONDS);
        logger.info(Log.SYSTEM, "Tester bound to {}; server={}", address(), server);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException exception) {
            logger.error(Log.SYSTEM, "Timer error", exception);
        }
    }

    private long nextSequence() {
        sequence = Utils.nextSequence(sequence);
        return sequence;
    }

    public synchronized void register(SensorType type, String id) {
        if (!running) {
            throw new IllegalArgumentException("Najprv spustite tester");
        }

        Message registration = Message.register(type, id, nextSequence());
        removePending(id);

        VirtualSensor sensor = new VirtualSensor(type, id);
        sensor.registrationSequence = registration.seq;
        sensors.put(id, sensor);
        queue(registration, false);
    }

    private void queue(Message message, boolean corrupt) {
        pending.put(message.seq, new PendingData(message, clock.getAsLong()));
        udp.send(message, server, corrupt);
    }

    private void resend(PendingData pendingData, long now) {
        udp.send(pendingData.message, server, false);
        pendingData.attempts++;
        pendingData.lastSentTime = now;
    }

    private synchronized void receive(JsonCodec.Decoded decoded, InetSocketAddress sender) {
        if (!running || !sender.equals(server) || !decoded.valid()) {
            return;
        }

        Message message = decoded.message();
        VirtualSensor sensor = sensors.get(message.sensorId);
        if (sensor == null || sensor.type != message.sensorType) {
            return;
        }

        PendingData pendingData = pending.get(message.reference);
        if (pendingData != null && !pendingData.message.sensorId.equals(sensor.id)) {
            pendingData = null;
        }

        long now = clock.getAsLong();
        switch (message.kind) {
            case ACK -> handleAck(sensor, message, pendingData, now);
            case REJECT -> handleReject(sensor, message, pendingData);
            case RETRY -> handleRetry(sensor, message, pendingData, now);
            case KEEPALIVE -> handleKeepalive(sensor, message, now);
            default -> {
                // Ostatné správy tester nespracúva.
            }
        }
    }

    private void handleAck(VirtualSensor sensor, Message message, PendingData pendingData, long now) {
        if (pendingData == null) {
            return;
        }

        if (pendingData.message.kind == Message.Kind.REGISTER) {
            if (sensor.registrationSequence != message.reference) {
                return;
            }

            pending.remove(message.reference);
            sensor.registrationSequence = 0;
            sensor.token = message.token;
            sensor.active = true;
            sensor.nextDataTime = now + DATA_INTERVAL_MS;
            sensor.lastServerContactTime = now;
            sensorLogger(sensor).info(Log.SYSTEM, "Registration accepted; automatic DATA enabled");
        } else if (pendingData.message.kind == Message.Kind.DATA && isCurrentSession(sensor, message)) {
            pending.remove(message.reference);
            sensor.lastServerContactTime = now;
        }
    }

    private void handleReject(VirtualSensor sensor, Message message, PendingData pendingData) {
        if (pendingData == null) {
            return;
        }

        boolean registrationRejected = sensor.registrationSequence == message.reference && message.token == 0;
        if (!registrationRejected && !isCurrentSession(sensor, message)) {
            return;
        }

        pending.remove(message.reference);
        sensorLogger(sensor).warn(Log.SYSTEM, "Message rejected; reason={}", message.reason);
        if (message.reason == INVALID_SESSION_REASON || pendingData.message.kind == Message.Kind.REGISTER) {
            deactivate(sensor);
        }
    }

    private void handleRetry(VirtualSensor sensor, Message message, PendingData pendingData, long now) {
        if (isCurrentSession(sensor, message) && pendingData != null
                && pendingData.message.kind == Message.Kind.DATA
                && pendingData.attempts < MAX_SEND_ATTEMPTS) {
            resend(pendingData, now);
        }
    }

    private void handleKeepalive(VirtualSensor sensor, Message message, long now) {
        if (!isCurrentSession(sensor, message)) {
            return;
        }

        sensor.lastServerContactTime = now;
        Message response = new Message(Message.Kind.KEEPALIVE_ACK, sensor.type, sensor.id,
                nextSequence(), sensor.token, message.seq, 0, new int[0]);
        udp.send(response, server, false);
    }

    private boolean isCurrentSession(VirtualSensor sensor, Message message) {
        return sensor.active && sensor.token == message.token;
    }

    public synchronized void tick() {
        if (!running) {
            return;
        }

        long now = clock.getAsLong();
        resendPending(now);
        updateSensors(now);
    }

    private void resendPending(long now) {
        Iterator<PendingData> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            PendingData pendingData = iterator.next();
            if (now - pendingData.lastSentTime < 3_000) {
                continue;
            }

            if (pendingData.attempts < MAX_SEND_ATTEMPTS) {
                resend(pendingData, now);
                continue;
            }

            iterator.remove();
            VirtualSensor sensor = sensors.get(pendingData.message.sensorId);
            if (pendingData.message.kind == Message.Kind.REGISTER) {
                sensor.registrationSequence = 0;
            }
            sensorLogger(sensor).warn(Log.SYSTEM,
                    "Message dropped after three attempts; seq={}", pendingData.message.seq);
        }
    }

    private void updateSensors(long now) {
        for (VirtualSensor sensor : sensors.values()) {
            if (!sensor.active) {
                continue;
            }

            if (now - sensor.lastServerContactTime >= 30_000) {
                deactivate(sensor);
                sensorLogger(sensor).info(Log.SYSTEM,
                        "Local session expired after 30 seconds without server activity");
            } else if (sensor.automatic && now >= sensor.nextDataTime) {
                sendCustom(sensor.id, sensor.type.randomValues(random));
                sensor.nextDataTime = now + DATA_INTERVAL_MS;
            }
        }
    }

    private VirtualSensor requireActive(String id) {
        VirtualSensor sensor = sensors.get(id);
        if (sensor == null || !sensor.active) {
            throw new IllegalArgumentException("Senzor nie je zaregistrovaný");
        }
        return sensor;
    }

    public synchronized void sendCustom(String id, int[] values) {
        VirtualSensor sensor = requireActive(id);
        Message message = Message.data(sensor.type, id, nextSequence(), sensor.token, values);
        queue(message, sensor.corruptNext);
        sensor.corruptNext = false;
    }

    public synchronized void setAutomatic(String id, boolean enabled) {
        VirtualSensor sensor = requireActive(id);
        sensor.automatic = enabled;
        if (enabled) {
            sensor.nextDataTime = clock.getAsLong() + DATA_INTERVAL_MS;
        }
        sensorLogger(sensor).info(Log.SYSTEM, "Automatic DATA {}", enabled ? "enabled" : "disabled");
    }

    public synchronized void corruptNext(String id) {
        VirtualSensor sensor = requireActive(id);
        sensor.corruptNext = true;
        sensorLogger(sensor).info(Log.SYSTEM, "Next DATA checksum will be corrupted");
    }

    private void removePending(String id) {
        pending.values().removeIf(pendingData -> pendingData.message.sensorId.equals(id));
    }

    private void deactivate(VirtualSensor sensor) {
        sensor.active = false;
        sensor.token = 0;
        sensor.registrationSequence = 0;
        removePending(sensor.id);
    }

    public synchronized void disconnect(String id) {
        VirtualSensor sensor = requireActive(id);
        deactivate(sensor);
        sensorLogger(sensor).info(Log.SYSTEM, "Local sensor disconnected");
    }

    public synchronized SensorType sensorType(String id) {
        return requireActive(id).type;
    }

    public synchronized boolean isActive(String id) {
        VirtualSensor sensor = sensors.get(id);
        return sensor != null && sensor.active;
    }

    public synchronized int activeCount() {
        int count = 0;
        for (VirtualSensor sensor : sensors.values()) {
            if (sensor.active) {
                count++;
            }
        }
        return count;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized List<String> sensors() {
        List<String> result = new ArrayList<>();
        for (VirtualSensor sensor : sensors.values()) {
            result.add(sensor.type.label + ":" + sensor.id + " " + sensorStatus(sensor));
        }
        return result;
    }

    private String sensorStatus(VirtualSensor sensor) {
        if (!sensor.active) {
            return sensor.registrationSequence != 0 ? "REGISTRUJE SA" : "ODPOJENÝ";
        }
        return sensor.automatic
                ? "AKTÍVNY, automatické odosielanie zapnuté"
                : "AKTÍVNY, automatické odosielanie vypnuté";
    }

    private Logger sensorLogger(VirtualSensor sensor) {
        return log.logger(sensor.source());
    }

    @Override
    public synchronized void close() {
        running = false;
        if (timer != null) {
            timer.shutdownNow();
        }
        udp.close();
        pending.clear();
        sensors.clear();
        logger.info(Log.SYSTEM, "Tester stopped");
    }
}
