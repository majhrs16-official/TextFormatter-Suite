package me.majhrs16.suite.manager.apt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void confirmationResultBuildsAptStylePrompt() {
        RepositoryManager.PackageCandidate candidate = new RepositoryManager.PackageCandidate(
                "iflow-vm", "2.1.0", "stable", "local", "https://example.com/iflow-vm.zip",
                100, "sha256", 0);
        PackageEntry installed = new PackageEntry(
                "iflow-firewall", "2.1.0", "stable", PackageType.PACKAGE,
                List.of(), List.of("iflow-vm"), List.of("iflow"),
                "none", "common", "", "none", "sha256", 100,
                Map.of(), "", "", Map.of());
        DependencyResolver.ResolutionPlan plan = new DependencyResolver.ResolutionPlan(
                List.of(new DependencyResolver.PlanStep(DependencyResolver.PlanStep.Type.INSTALL, candidate)),
                true, null, List.of(installed));

        AptInstaller.InstallResult result = AptInstaller.InstallResult.confirmation(plan);

        assertTrue(result.requiresConfirmation());
        assertEquals("Deseas instalar estos paquetes?: iflow-vm 2.1.0\n"
                + "  Reemplazarán a: iflow-firewall 2.1.0", result.confirmationMessage());
    }

    @Test
    void runtimeFailureKeepsTheInstallationAndReportsActivationFailed() {
        AptInstaller.InstallResult installed = new AptInstaller.InstallResult(
                true, List.of("iflow-vm"), null);

        AptInstaller.InstallResult failed = installed.withRuntimeFailure(
                ModuleState.ACTIVATION_FAILED, "iflow-vm", "start failed", null);

        assertFalse(failed.success());
        assertTrue(failed.installed(), "a runtime failure must not undo the commit");
        assertEquals(ModuleState.ACTIVATION_FAILED, failed.activation());
        assertEquals(AptInstaller.InstallResult.FailedStage.RUNTIME, failed.failedStage());
        assertEquals("iflow-vm", failed.moduleId());
    }

    @Test
    void commitFailureReportsCommitStageAndNoInstallation() {
        AptInstaller.InstallResult failed = AptInstaller.InstallResult.failedCommit("boom", null);

        assertFalse(failed.success());
        assertFalse(failed.installed());
        assertEquals(AptInstaller.InstallResult.FailedStage.COMMIT, failed.failedStage());
        assertNull(failed.activation());
    }
}
