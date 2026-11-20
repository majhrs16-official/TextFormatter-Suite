package me.majhrs16.suite.iflow.vm.memory.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SysInfoTest {

    @Test
    void reportsTheConfiguredCoreCountAndMulticoreFeature() {
        SysInfo single = sysInfo(1);
        assertEquals(1, single.readRegister(SysInfo.CORE_COUNT));
        assertEquals(0, single.readRegister(SysInfo.FEATURES) & SysInfo.FEATURE_MULTICORE);

        SysInfo multi = sysInfo(2);
        assertEquals(2, multi.readRegister(SysInfo.CORE_COUNT));
        assertEquals(SysInfo.FEATURE_MULTICORE,
                multi.readRegister(SysInfo.FEATURES) & SysInfo.FEATURE_MULTICORE);
        assertEquals(SysInfo.FEATURE_TIMER,
                multi.readRegister(SysInfo.FEATURES) & SysInfo.FEATURE_TIMER);
    }

    @Test
    void coreCountOutsideTheArchitecturalRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> sysInfo(0));
        assertThrows(IllegalArgumentException.class, () -> sysInfo(65));
    }

    private static SysInfo sysInfo(int coreCount) {
        return new SysInfo(16 * 1024 * 1024, 0, 0, 0, 3,
                SysInfo.FEATURE_TIMER, coreCount);
    }
}
