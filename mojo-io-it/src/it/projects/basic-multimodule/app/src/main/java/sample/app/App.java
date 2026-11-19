package sample.app;

import sample.core.Greeter;

public final class App {

    public static String run(String name) {
        return new Greeter().greet(name);
    }

    public static void main(String[] args) {
        System.out.println(run(args.length > 0 ? args[0] : "world"));
    }
}
