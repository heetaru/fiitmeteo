package meteo.server;

import meteo.logging.Log;
import meteo.protocol.BinaryCodec;
import meteo.util.Console;
import org.slf4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public class ServerMain {
    public static void main(String[] args) {
        if (args.length > 0) {
            System.out.println("FIITMeteo server. Spustite bez argumentov. Vyžaduje sa Java 21.");
            return;
        }

        try (Log log = new Log(Path.of("logs/server.log"))) {
            runMenu(log);
        } catch (IOException exception) {
            System.err.println("Nedá sa otvoriť súbor s logmi: " + exception.getMessage());
        }
    }

    private static void runMenu(Log log) throws IOException {
        Logger logger = log.logger("SERVER");
        Server server = null;
        InetAddress bind = InetAddress.getByName("0.0.0.0");
        int port = 9000;
        boolean consoleLogs = false;
        boolean exit = false;

        try {
            while (!exit) {
                printMenu(bind, port, server != null);
                try {
                    int choice = Console.integer("> ", 0, 6);
                    switch (choice) {
                        case 0 -> exit = true;
                        case 1 -> {
                            if (server != null) {
                                throw new IllegalArgumentException("Pred nastavením zastavte server");
                            }
                            InetAddress newBind = Console.ip("Lokálna IP adresa: ");
                            int newPort = Console.integer("Lokálny port: ", 1, 65535);
                            bind = newBind;
                            port = newPort;
                        }
                        case 2 -> {
                            if (server != null) {
                                throw new IllegalArgumentException("Server už beží");
                            }
                            server = new Server(new InetSocketAddress(bind, port), new BinaryCodec(), log);
                            server.start();
                            System.out.println("Server bol spustený na adrese " + server.address());
                        }
                        case 3 -> {
                            requireServer(server);
                            Console.list(server.sessions());
                        }
                        case 4 -> {
                            requireServer(server);
                            String id = Console.line("ID senzora: ");
                            int count = Console.integer("Počet vynechaných potvrdení DATA: ",
                                    0, Integer.MAX_VALUE);
                            server.suppressAcks(id, count);
                        }
                        case 5 -> {
                            consoleLogs = !consoleLogs;
                            log.setConsole(consoleLogs);
                            System.out.println("Logy v konzole " + (consoleLogs ? "zapnuté" : "vypnuté"));
                        }
                        case 6 -> {
                            if (server != null) {
                                server.close();
                            }
                            server = null;
                        }
                    }
                } catch (EOFException exception) {
                    exit = true;
                } catch (BindException exception) {
                    System.out.println("IP adresa alebo port je obsadený alebo nedostupný.");
                    logger.error(Log.SYSTEM, "Bind failed", exception);
                } catch (IllegalArgumentException | IOException exception) {
                    System.out.println("Chyba: " + exception.getMessage());
                    logger.warn(Log.SYSTEM, "Invalid input or network error", exception);
                }
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    private static void requireServer(Server server) {
        if (server == null) {
            throw new IllegalArgumentException("Najprv spustite server");
        }
    }

    private static void printMenu(InetAddress bind, int port, boolean running) {
        String status = running ? "SPUSTENÝ" : "ZASTAVENÝ";
        System.out.printf("""

                SERVER | BINARY | %s:%d | %s
                1 Nastaviť IP a port
                2 Spustiť
                3 Zoznam senzorov
                4 Vynechať potvrdenia DATA
                5 Zapnúť alebo vypnúť logy v konzole
                6 Zastaviť
                0 Koniec
                """, bind.getHostAddress(), port, status);
    }
}
