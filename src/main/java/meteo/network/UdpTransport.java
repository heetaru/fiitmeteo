package meteo.network;

import meteo.logging.Log;
import meteo.protocol.BinaryCodec;
import meteo.protocol.Message;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.function.BiConsumer;

public class UdpTransport implements AutoCloseable {
    private final DatagramSocket socket;
    private final BinaryCodec codec;
    private final Log log;
    private final Logger logger;

    public UdpTransport(InetSocketAddress bind, BinaryCodec codec, Log log, String role)
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

    public void start(BiConsumer<BinaryCodec.Decoded, InetSocketAddress> handler) {
        Thread receiver = new Thread(() -> receiveLoop(handler), logger.getName() + "-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void receiveLoop(BiConsumer<BinaryCodec.Decoded, InetSocketAddress> handler) {
        while (!socket.isClosed()) {
            try {
                byte[] buffer = new byte[2049];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                // decode only the received bytes
                BinaryCodec.Decoded decoded = codec.decode(Arrays.copyOf(buffer, packet.getLength()));
                Message message = decoded.message();
                if (message == null) {
                    BinaryCodec.CorruptedData data = decoded.corruptedData();
                    logger.warn(Log.MESSAGE, "Received DATA frim {}: {}:{}",
                            packet.getSocketAddress(), data.sensorType().label, data.sensorId());
                } else {
                    Logger messageLogger = messageLogger(message);
                    String text = Log.renderMessage(message, decoded.crc());
                    if (decoded.valid()) {
                        messageLogger.info(Log.MESSAGE, "Received from {}: {}", packet.getSocketAddress(), text);
                    } else {
                        messageLogger.warn(Log.MESSAGE, "Received from {}: {}", packet.getSocketAddress(), text);
                    }
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
            // log the checksum actually sent
            BinaryCodec.Decoded decoded = codec.decode(bytes);
            String text = Log.renderMessage(message, decoded.crc());
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
