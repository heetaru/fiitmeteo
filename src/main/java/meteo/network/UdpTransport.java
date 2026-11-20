package meteo.network;

import meteo.logging.Log;
import meteo.protocol.JsonCodec;
import meteo.protocol.Message;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.function.BiConsumer;

// Odosielanie a prijímanie cez jeden UDP socket.
public class UdpTransport implements AutoCloseable {
    private final DatagramSocket socket;
    private final JsonCodec codec;
    private final Log log;
    private final Logger logger;

    public UdpTransport(InetSocketAddress bind, JsonCodec codec, Log log, String role)
            throws SocketException {
        this.codec = codec;
        this.log = log;
        logger = log.logger(role);
        socket = new DatagramSocket(null);
        try {
            socket.bind(bind);
        } catch (SocketException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    public InetSocketAddress address() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    public void start(BiConsumer<JsonCodec.Decoded, InetSocketAddress> handler) {
        Thread receiver = new Thread(() -> receiveLoop(handler), logger.getName() + "-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receiveLoop(BiConsumer<JsonCodec.Decoded, InetSocketAddress> handler) {
        while (!socket.isClosed()) {
            try {
                // Jeden bajt navyše umožní odmietnuť paket prekračujúci limit protokolu.
                byte[] buffer = new byte[2049];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                JsonCodec.Decoded decoded = codec.decode(Arrays.copyOf(buffer, packet.getLength()));
                Logger messageLogger = messageLogger(decoded.message());
                String text = JsonCodec.render(decoded.message(), decoded.crc());
                if (decoded.valid()) {
                    messageLogger.info(Log.MESSAGE, "Received from {}: {}", packet.getSocketAddress(), text);
                } else {
                    messageLogger.warn(Log.MESSAGE, "Received from {}: {}", packet.getSocketAddress(), text);
                }
                handler.accept(decoded, (InetSocketAddress) packet.getSocketAddress());
            } catch (IOException exception) {
                if (!socket.isClosed()) {
                    logger.warn(Log.SYSTEM, "UDP receive failed", exception);
                }
            } catch (RuntimeException exception) {
                logger.warn(Log.SYSTEM, "Malformed or unsupported message", exception);
            }
        }
    }

    public void send(Message message, InetSocketAddress target, boolean corrupt) {
        byte[] bytes = codec.encode(message, corrupt);
        Logger messageLogger = messageLogger(message);
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, target));
            JsonCodec.Decoded decoded = codec.decode(bytes);
            String text = JsonCodec.render(message, decoded.crc());
            if (decoded.valid()) {
                messageLogger.info(Log.MESSAGE, "Sent to {}: {}", target, text);
            } else {
                messageLogger.warn(Log.MESSAGE, "Sent to {}: {}", target, text);
            }
        } catch (IOException exception) {
            messageLogger.warn(Log.SYSTEM, "UDP send failed to {}", target, exception);
        }
    }

    private Logger messageLogger(Message message) {
        if (logger.getName().equals("SERVER")) {
            return logger;
        }
        return log.logger(logger.getName() + ":" + message.sensorType.label + ":" + message.sensorId);
    }

    @Override
    public void close() {
        socket.close();
    }
}
