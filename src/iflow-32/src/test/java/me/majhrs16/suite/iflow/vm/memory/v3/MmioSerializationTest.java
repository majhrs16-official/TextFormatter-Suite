package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

class MmioSerializationTest {

    private static final List<Class<?>> MUTABLE_DEVICES = List.of(
            Timer.class,
            Uart.class,
            Display.class,
            Nvram.class,
            HostMailbox.class);

    @Test
    void mutableDevicesSerializeTheirPublicSurface() {
        for (Class<?> type : MUTABLE_DEVICES) {
            assertSynchronized(type, "readRegister", int.class);
            assertSynchronized(type, "writeRegister", int.class, int.class);
        }

        assertSynchronized(Timer.class, "tick");
        assertSynchronized(Uart.class, "receive", int.class);
        assertSynchronized(Display.class, "clear");
        assertSynchronized(Nvram.class, "commit");
        assertSynchronized(HostMailbox.class, "publish", int.class, int.class);
    }

    private static void assertSynchronized(
            Class<?> type, String name, Class<?>... parameterTypes) {
        Method method;
        try {
            method = type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException error) {
            throw new AssertionError(
                    type.getSimpleName() + "." + name + " no existe", error);
        }
        assertTrue(Modifier.isSynchronized(method.getModifiers()),
                type.getSimpleName() + "." + name + " debe serializar sus transacciones");
    }
}
