package meteo.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Nastavenie SLF4J a Logback pre jeden súbor a voliteľnú konzolu.
public class Log implements AutoCloseable {
    public static final Marker SYSTEM = MarkerFactory.getMarker("SYSTEM");
    public static final Marker MESSAGE = MarkerFactory.getMarker("MESSAGE");

    private final LoggerContext context = new LoggerContext();
    private final ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();

    public Log(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        FileAppender<ILoggingEvent> file = new FileAppender<>();
        file.setContext(context);
        file.setName("FILE");
        file.setFile(path.toString());
        file.setAppend(true);
        file.setEncoder(encoder());
        file.start();
        if (!file.isStarted()) {
            context.stop();
            throw new IOException("Nedá sa otvoriť súbor s logmi: " + path);
        }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(file);

        console.setContext(context);
        console.setName("CONSOLE");
        console.setEncoder(encoder());
        console.start();
        context.start();
    }

    private PatternLayoutEncoder encoder() {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.setPattern("%d{yyyy-MM-dd'T'HH:mm:ss.SSS'Z',UTC}"
                + " | %marker | %replace(%level){'^WARN$','WARNING'} | %logger | %msg%n%ex");
        encoder.start();
        return encoder;
    }

    public Logger logger(String source) {
        return context.getLogger(source);
    }

    public synchronized void setConsole(boolean enabled) {
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (enabled) {
            if (!root.isAttached(console)) {
                root.addAppender(console);
            }
        } else {
            root.detachAppender(console);
        }
    }

    @Override
    public synchronized void close() {
        context.stop();
        console.stop();
    }
}
