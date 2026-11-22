package sample.hooks;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A server that lives inside the JVM that starts it (like jetty:start or an embedded database):
 * answers "pong" on an ephemeral port, written to the file given as argument.
 */
public final class FakeServer {

    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket s = server.accept(); OutputStream out = s.getOutputStream()) {
                    out.write("pong".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-server");
        t.setDaemon(true);
        t.start();
        Path portFile = Path.of(args[0]);
        Files.createDirectories(portFile.getParent());
        Files.writeString(portFile, Integer.toString(server.getLocalPort()));
    }
}
