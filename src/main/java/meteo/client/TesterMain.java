package meteo.client;

import meteo.logging.Log;
import meteo.protocol.BinaryCodec;
import meteo.sensor.SensorType;
import meteo.util.Console;
import org.slf4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public class TesterMain {
    public static void main(String[] args) {
        if (args.length > 0) {
            System.out.println("FIITMeteo tester. Spustite bez argumentov. "
                    + "Vyžaduje sa Java 21.");
            return;
        }

        try (Log log = new Log(Path.of("logs/tester.log"))) {
            runMenu(log);
        } catch (IOException exception) {
            System.err.println("Nedá sa otvoriť súbor s logmi: " + exception.getMessage());
        }
    }

    private static void runMenu(Log log) throws IOException {
        Logger logger = log.logger("TESTER");
        Tester tester = null;
        InetAddress bindAddress = InetAddress.getByName("0.0.0.0");
        InetAddress serverAddress = InetAddress.getByName("127.0.0.1");
        int localPort = 0;
        int serverPort = 9000;
        boolean consoleLogs = false;
        boolean exit = false;

        try {
            while (!exit) {
                printMenu(serverAddress, serverPort, tester);
                try {
                    int choice = Console.integer("> ", 0, 11);
                    if (choice >= 3 && choice <= 9 && tester == null) {
                        throw new IllegalArgumentException("Najprv spustite tester");
                    }

                    switch (choice) {
                        case 0 -> exit = true;
                        case 1 -> {
                            if (tester != null) {
                                throw new IllegalArgumentException("Pred nastavením zastavte tester");
                            }

                            InetAddress newBindAddress = Console.ip("Lokálna IP adresa: ");
                            int newLocalPort = Console.integer(
                                    "Lokálny port (0 = voľný port): ", 0, 65535);
                            InetAddress newServerAddress = Console.ip("IP adresa servera: ");
                            if (newServerAddress.isAnyLocalAddress()
                                    || newServerAddress.isMulticastAddress()) {
                                throw new IllegalArgumentException("Zadajte konkrétnu IP adresu servera");
                            }
                            int newServerPort = Console.integer("Port servera: ", 1, 65535);

                            bindAddress = newBindAddress;
                            localPort = newLocalPort;
                            serverAddress = newServerAddress;
                            serverPort = newServerPort;
                        }
                        case 2 -> {
                            if (tester != null) {
                                throw new IllegalArgumentException("Tester už beží");
                            }

                            tester = new Tester(new InetSocketAddress(bindAddress, localPort),
                                    new InetSocketAddress(serverAddress, serverPort), new BinaryCodec(), log);
                            tester.start();
                            System.out.println("Tester bol spustený na adrese " + tester.address());
                        }
                        case 3 -> registerSensor(tester);
                        case 4 -> registerBasicSensors(tester);
                        case 5 -> sendCustomValues(tester);
                        case 6 -> setAutomatic(tester);
                        case 7 -> tester.corruptNext(Console.line("ID senzora: "));
                        case 8 -> tester.disconnect(Console.line("ID senzora: "));
                        case 9 -> Console.list(tester.sensors());
                        case 10 -> {
                            consoleLogs = !consoleLogs;
                            log.setConsole(consoleLogs);
                            System.out.println("Logy v konzole " + (consoleLogs ? "zapnuté" : "vypnuté"));
                        }
                        case 11 -> {
                            if (tester != null) {
                                tester.close();
                            }
                            tester = null;
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
            if (tester != null) {
                tester.close();
            }
        }
    }

    private static void printMenu(InetAddress serverAddress, int serverPort, Tester tester) {
        String status = tester == null ? "ZASTAVENÝ" : "SPUSTENÝ";
        System.out.println("\nTESTER | BINARY | server=" + serverAddress.getHostAddress()
                + ":" + serverPort + " | " + status);
        System.out.println("""
                1 Nastaviť IP a porty
                2 Spustiť
                3 Zaregistrovať senzor
                4 Zaregistrovať štyri základné senzory
                5 Odoslať vlastné hodnoty
                6 Nastaviť automatické odosielanie
                7 Poškodiť nasledujúcu DATA
                8 Odpojiť senzor
                9 Zoznam senzorov
                10 Zapnúť alebo vypnúť logy v konzole
                11 Zastaviť
                0 Koniec""");
    }

    private static void registerSensor(Tester tester) throws IOException {
        SensorType type = Console.type();
        String id = Console.line("ID senzora (1 až 16 písmen, číslic, _ alebo -): ");
        tester.register(type, id);
    }

    private static void registerBasicSensors(Tester tester) {
        tester.register(SensorType.THERMO, "T-01");
        tester.register(SensorType.WIND, "W-01");
        tester.register(SensorType.RAIN, "R-01");
        tester.register(SensorType.AIR, "A-01");
    }

    private static void sendCustomValues(Tester tester) throws IOException {
        String id = Console.line("ID senzora: ");
        int[] values = Console.measurements(tester.sensorType(id));
        tester.sendCustom(id, values);
    }

    private static void setAutomatic(Tester tester) throws IOException {
        String id = Console.line("ID senzora: ");
        boolean enabled = Console.integer("Automatické odosielanie (0 vypnúť, 1 zapnúť): ", 0, 1) == 1;
        tester.setAutomatic(id, enabled);
    }
}
