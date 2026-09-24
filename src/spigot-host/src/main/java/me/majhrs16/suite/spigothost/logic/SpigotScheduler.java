package me.majhrs16.suite.spigothost.logic;

public final class SpigotScheduler {

    private static final int TICKS_PER_SECOND = 20;
    private static final long MS_PER_TICK = 50L;

    private SpigotScheduler() {
    }

    /**
     * Convierte milisegundos a ticks de Minecraft.
     * <p>
     * Redondea al tick más cercano en lugar de truncar, para que delays
     * menores a 1 segundo no se reduzcan artificialmente a un solo tick.
     *
     * @param millis delay en milisegundos (puede ser cualquier valor >= 0)
     * @return número de ticks (entero >= 0)
     */
    public static int ticks(long millis) {
        if (millis <= 0) return 0;
        // Conversión: ms / (1000/20) = ms * 20 / 1000
        // Usamos redondeo en lugar de truncado para evitar que delays
        // pequeños queden en un solo tick (50ms).
        long ticks = millis * TICKS_PER_SECOND / 1000;
        // Redondeo nearest: si la fracción es >= 0.5, sube al siguiente tick
        long remainder = millis * TICKS_PER_SECOND % 1000;
        if (remainder >= 500) {
            ticks++;
        }
        return (int) ticks;
    }

    /**
     * Convierte ticks de Minecraft a milisegundos.
     *
     * @param ticks número de ticks
     * @return delay en milisegundos
     */
    public static long millis(int ticks) {
        return ticks * MS_PER_TICK;
    }

    /**
     * Retorna la duración en milisegundos de un solo tick de Minecraft.
     *
     * @return 50ms
     */
    public static long msPerTick() {
        return MS_PER_TICK;
    }

    /**
     * Retorna la cantidad de ticks por segundo.
     *
     * @return 20
     */
    public static int ticksPerSecond() {
        return TICKS_PER_SECOND;
    }
}