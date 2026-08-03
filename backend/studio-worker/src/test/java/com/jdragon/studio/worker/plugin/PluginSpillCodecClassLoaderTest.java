package com.jdragon.studio.worker.plugin;

import com.jdragon.aggregation.core.streaming.AppendOnlySpillList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PluginSpillCodecClassLoaderTest {

    private static final String FIXTURE_PACKAGE = "com.jdragon.studio.worker.plugin.fixture.";
    private static final String VALUE_CLASS = FIXTURE_PACKAGE + "HotReloadSpillValue";
    private static final String STATUS_CLASS = FIXTURE_PACKAGE + "HotReloadSpillStatus";

    @Test
    void spillReadersAreIsolatedAcrossPluginClassLoaders(@TempDir Path temp) throws Exception {
        Map<String, byte[]> definitions = fixtureDefinitions();
        ClassLoader firstLoader = new ChildFirstFixtureLoader(getClass().getClassLoader(), definitions);
        ClassLoader secondLoader = new ChildFirstFixtureLoader(getClass().getClassLoader(), definitions);

        Class<?> firstType = firstLoader.loadClass(VALUE_CLASS);
        Class<?> secondType = secondLoader.loadClass(VALUE_CLASS);
        assertNotSame(firstType, secondType);

        Object firstDecoded = spillRoundTrip(firstType, firstLoader, temp.resolve("first"));
        Object secondDecoded = spillRoundTrip(secondType, secondLoader, temp.resolve("second"));

        assertSame(firstType, firstDecoded.getClass());
        assertSame(secondType, secondDecoded.getClass());
        assertNotSame(status(firstDecoded).getClass(), status(secondDecoded).getClass());
        assertEquals("READY", status(secondDecoded).toString());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object spillRoundTrip(Class<?> valueType, ClassLoader loader, Path directory) throws Exception {
        Object value = valueType.getDeclaredConstructor().newInstance();
        Class<? extends Enum> statusType = (Class<? extends Enum>) loader.loadClass(STATUS_CLASS);
        Method setter = valueType.getMethod("setStatus", statusType);
        setter.invoke(value, Enum.valueOf(statusType, "READY"));

        try (AppendOnlySpillList list = new AppendOnlySpillList("hot-reload", valueType,
                directory.toString())) {
            list.add(value);
            return list.get(0);
        }
    }

    private Object status(Object value) throws Exception {
        return value.getClass().getMethod("getStatus").invoke(value);
    }

    private Map<String, byte[]> fixtureDefinitions() throws IOException {
        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(VALUE_CLASS, classBytes(VALUE_CLASS));
        definitions.put(STATUS_CLASS, classBytes(STATUS_CLASS));
        return definitions;
    }

    private byte[] classBytes(String className) throws IOException {
        String resource = className.replace('.', '/') + ".class";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing fixture class resource: " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static final class ChildFirstFixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        private ChildFirstFixtureLoader(ClassLoader parent, Map<String, byte[]> definitions) {
            super(parent);
            this.definitions = definitions;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && definitions.containsKey(name)) {
                    byte[] bytes = definitions.get(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
