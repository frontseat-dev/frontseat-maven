package sample.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class Greeter {

    private final Properties properties = new Properties();

    public Greeter() {
        try (InputStream in = Greeter.class.getResourceAsStream("/greeting.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String greet(String name) {
        return properties.getProperty("greeting") + ", " + name + "!";
    }

    public String version() {
        return properties.getProperty("version");
    }
}
