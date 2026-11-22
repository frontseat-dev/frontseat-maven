package sample.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerIT {

    @Test
    void serverStartedByThePreIntegrationTestHookAnswers() throws Exception {
        int port = Integer.parseInt(Files.readString(Path.of("target/server/port")).trim());
        try (Socket s = new Socket("localhost", port); InputStream in = s.getInputStream()) {
            assertEquals("pong", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("pong", Service.ping());
    }
}
