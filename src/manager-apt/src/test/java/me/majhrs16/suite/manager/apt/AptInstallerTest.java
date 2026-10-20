package me.majhrs16.suite.manager.apt;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AptInstallerTest {

    @Test
    void buildsANormalPackageFileName() throws IOException {
        assertEquals("iflow-1.0.0.zip", AptInstaller.packageFileName("iflow", "1.0.0"));
    }

    @Test
    void rejectsTraversalInName() {
        assertThrows(IOException.class, () -> AptInstaller.packageFileName("../../etc/passwd", "1.0.0"));
        assertThrows(IOException.class, () -> AptInstaller.packageFileName("..", "1.0.0"));
        assertThrows(IOException.class, () -> AptInstaller.packageFileName("a/b", "1.0.0"));
    }

    @Test
    void rejectsTraversalInVersion() {
        assertThrows(IOException.class, () -> AptInstaller.packageFileName("iflow", "../../1.0.0"));
        assertThrows(IOException.class, () -> AptInstaller.packageFileName("iflow", "1.0\\0"));
    }
}
