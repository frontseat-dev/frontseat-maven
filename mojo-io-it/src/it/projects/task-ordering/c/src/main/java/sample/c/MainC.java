package sample.c;

public final class MainC {
    public String name() {
        return "c";
    }

    /** d is a runtime dependency: only there when the code runs. */
    public String runtimeName() throws ReflectiveOperationException {
        Object d = Class.forName("sample.d.MainD").getDeclaredConstructor().newInstance();
        return (String) d.getClass().getMethod("name").invoke(d);
    }
}
