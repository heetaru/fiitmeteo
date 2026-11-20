package meteo.server;

import meteo.logging.Log;
import meteo.network.UdpTransport;
import meteo.protocol.JsonCodec;
import meteo.protocol.Message;
import meteo.util.Utils;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// Spracovanie správ a časovačov servera.
public class Server implements AutoCloseable {
    private final UdpTransport udp;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, SensorSession> sensors = new LinkedHashMap<>();
    private ScheduledExecutorService timer;
    private boolean running;
    private long sequence;

    public Server(InetSocketAddress bind, JsonCodec codec, Log log) throws SocketException {
        this(bind, codec, log, () -> System.nanoTime() / 1_000_000);
    }

    public Server(InetSocketAddress bind, JsonCodec codec, Log log, LongSupplier clock)
            throws SocketException {
        logger = log.logger("SERVER");
        this.clock = clock;
        udp = new UdpTransport(bind, codec, log, logger.getName());
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
        timer.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (RuntimeException exception) {
                logger.error(Log.SYSTEM, "Timer error", exception);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        logger.info(Log.SYSTEM, "Server listening on {}", address());
    }

    private long nextSequence() {
        sequence = Utils.nextSequence(sequence);
        return sequence;
    }

    private void reply(Message original, Message.Kind kind, long token, int reason,
                       InetSocketAddress target) {
        Message answer = new Message(kind, original.sensorType, original.sensorId,
                nextSequence(), token, original.seq, reason, new int[0]);
        udp.send(answer, target, false);
    }

    private synchronized void receive(JsonCodec.Decoded decoded, InetSocketAddress sender) {
        if (!running) {
            return;
        }

        Message message = decoded.message();
        if (message.kind == Message.Kind.REGISTER) {
            if (decoded.valid()) {
                handleRegister(message, sender);
            }
            return;
        }

        SensorSession session = sensors.get(message.sensorId);
        if (!matchesSession(message, sender, session)) {
            if (decoded.valid()) {
                reply(message, Message.Kind.REJECT, message.token, 1, sender);
            }
            return;
        }

        if (!decoded.valid()) {
            if (message.kind == Message.Kind.DATA) {
                logger.warn(Log.SYSTEM,
                        "Integrity check failed for {}:{}; retransmission requested",
                        session.type.label, session.id);
                reply(message, Message.Kind.RETRY, session.token, 0, sender);
            }
            return;
        }

        switch (message.kind) {
            case DATA -> handleData(message, session);
            case KEEPALIVE_ACK -> handleKeepaliveAck(message, session);
            default -> {
                // Ostatné typy správ server v tejto relácii nespracúva.
            }
        }
    }

    private boolean matchesSession(Message message, InetSocketAddress sender, SensorSession session) {
        return session != null
                && session.type == message.sensorType
                && session.token == message.token
                && session.address.equals(sender);
    }

    private void handleRegister(Message message, InetSocketAddress sender) {
        SensorSession previousSession = sensors.get(message.sensorId);
        long previousToken = 0;

        if (previousSession != null) {
            if (!previousSession.address.equals(sender) || previousSession.type != message.sensorType) {
                reply(message, Message.Kind.REJECT, 0, 2, sender);
                return;
            }
            if (previousSession.registrationSequence == message.seq) {
                reply(message, Message.Kind.ACK, previousSession.token, 0, sender);
                return;
            }
            if (!Utils.isNewerSequence(message.seq, previousSession.registrationSequence)) {
                reply(message, Message.Kind.REJECT, 0, 2, sender);
                return;
            }
            previousToken = previousSession.token;
        }

        long token = Utils.generateToken(previousToken);
        SensorSession session = new SensorSession(message, sender, token, clock.getAsLong());
        sensors.put(message.sensorId, session);
        reply(message, Message.Kind.ACK, token, 0, sender);
        logger.info(Log.SYSTEM, "Registered {}:{}", session.type.label, session.id);
    }

    private void handleData(Message message, SensorSession session) {
        String measurements = Arrays.toString(message.values);
        String previousMeasurements = session.acceptedMeasurements.get(message.seq);
        if (previousMeasurements != null && !previousMeasurements.equals(measurements)) {
            reply(message, Message.Kind.REJECT, session.token, 5, session.address);
            return;
        }

        session.lastDataTime = clock.getAsLong();
        session.idle = false;
        session.monitoring = false;
        session.unansweredProbes = 0;
        session.probeSequence = 0;

        if (previousMeasurements == null) {
            session.acceptedMeasurements.put(message.seq, measurements);
            if (session.acceptedMeasurements.size() > 256) {
                session.acceptedMeasurements.pollFirstEntry();
            }
            logger.info(Log.SYSTEM, "Measurement accepted: {}:{} seq={}",
                    session.type.label, session.id, message.seq);
        }

        if (session.suppressedAcks > 0) {
            session.suppressedAcks--;
            logger.info(Log.SYSTEM, "ACK suppressed for {}; remaining={}",
                    session.id, session.suppressedAcks);
        } else {
            reply(message, Message.Kind.ACK, session.token, 0, session.address);
            session.nextHeartbeatTime = session.lastDataTime + 15_000;
        }
    }

    private void handleKeepaliveAck(Message message, SensorSession session) {
        if (session.probeSequence != 0 && message.reference == session.probeSequence) {
            session.unansweredProbes = 0;
            session.probeSequence = 0;
        }
    }

    public synchronized void tick() {
        if (!running) {
            return;
        }

        long now = clock.getAsLong();
        Iterator<SensorSession> iterator = sensors.values().iterator();
        while (iterator.hasNext()) {
            SensorSession session = iterator.next();
            if (!session.idle && now - session.lastDataTime >= 15_000) {
                session.idle = true;
                logger.info(Log.SYSTEM, "SENSOR IDLE: {}:{}", session.type.label, session.id);
                if (!session.monitoring) {
                    session.monitoring = true;
                    session.nextProbeTime = session.lastDataTime + 15_000;
                }
            }

            if (!session.monitoring) {
                // Pri vynechaných ACK musí aj tester vedieť, že server stále pracuje.
                if (now >= session.nextHeartbeatTime) {
                    sendKeepalive(session, now);
                }
                continue;
            }
            if (now < session.nextProbeTime) {
                continue;
            }
            if (session.unansweredProbes == 3) {
                iterator.remove();
                logger.info(Log.SYSTEM, "SENSOR DISCONNECTED: {}:{}", session.type.label, session.id);
                continue;
            }

            sendKeepalive(session, now);
            session.unansweredProbes++;
            session.nextProbeTime = now + 5_000;
        }
    }

    private void sendKeepalive(SensorSession session, long now) {
        session.probeSequence = nextSequence();
        Message probe = new Message(Message.Kind.KEEPALIVE, session.type, session.id,
                session.probeSequence, session.token, 0, 0, new int[0]);
        udp.send(probe, session.address, false);
        session.nextHeartbeatTime = now + 15_000;
    }

    public synchronized void suppressAcks(String id, int count) {
        SensorSession sensor = sensors.get(id);
        if (sensor == null) {
            throw new IllegalArgumentException("Neznáme ID senzora");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Počet nemôže byť záporný");
        }
        sensor.suppressedAcks = count;
        logger.info(Log.SYSTEM, "Will suppress {} DATA ACKs for {}", count, id);
    }

    public synchronized List<String> sessions() {
        List<String> result = new ArrayList<>();
        for (SensorSession session : sensors.values()) {
            String status = session.idle ? " NEČINNÝ" : " AKTÍVNY";
            result.add(session.type.label + ":" + session.id + " " + session.address + status);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        running = false;
        if (timer != null) {
            timer.shutdownNow();
        }
        udp.close();
        sensors.clear();
        logger.info(Log.SYSTEM, "Server stopped");
    }
}
