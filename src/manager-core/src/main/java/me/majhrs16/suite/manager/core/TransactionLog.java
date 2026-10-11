package me.majhrs16.suite.manager.core;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Transaction log for package operations to support recovery from interrupted operations.
 * Each operation is logged with its state, allowing recovery after interruption.
 */
public final class TransactionLog {

    private final Path logFile;
    private final PluginLogger logger;
    
    public enum OperationType {
        INSTALL,
        REMOVE,
        UPGRADE
    }
    
    public enum OperationState {
        STARTED,
        EXTRACTING,
        EXTRACTED,
        VALIDATING,
        VALIDATED,
        MOVING,
        MOVED,
        REGISTERING,
        REGISTERED,
        COMPLETED,
        FAILED,
        ROLLED_BACK
    }
    
    public record LogEntry(
        String operationId,
        OperationType type,
        String packageName,
        String version,
        String channel,
        OperationState state,
        Instant timestamp,
        String details
    ) {}
    
    public TransactionLog(Path managerDir, PluginLogger logger) {
        this.logFile = managerDir.resolve("library/transactions.log");
        this.logger = logger;
        try {
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to create transaction log: " + e.getMessage());
        }
    }
    
    /**
     * Logs an operation state change.
     */
    public synchronized void log(String operationId, OperationType type, String packageName, 
                                 String version, String channel, OperationState state, String details) {
        try {
            LogEntry entry = new LogEntry(operationId, type, packageName, version, channel, state, Instant.now(), details);
            String line = String.format("%s|%s|%s|%s|%s|%s|%s|%s%n",
                    operationId, type, packageName, version, channel, state, entry.timestamp(), details);
            Files.writeString(logFile, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Failed to write transaction log: " + e.getMessage());
        }
    }
    
    /**
     * Gets all incomplete operations that need recovery.
     * Groups by operationId and returns only operations whose LAST state is non-terminal.
     */
    public List<LogEntry> getIncompleteOperations() {
        Map<String, LogEntry> latestByOperation = new LinkedHashMap<>();
        try {
            if (!Files.exists(logFile)) return Collections.emptyList();
            
            String content = Files.readString(logFile);
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", 8);
                if (parts.length >= 8) {
                    String operationId = parts[0];
                    OperationState state = OperationState.valueOf(parts[5]);
                    LogEntry entry = new LogEntry(
                            operationId,
                            OperationType.valueOf(parts[1]),
                            parts[2],
                            parts[3],
                            parts[4],
                            state,
                            Instant.parse(parts[6]),
                            parts[7]
                    );
                    // Keep the latest entry for each operationId (log is append-only, so last wins)
                    latestByOperation.put(operationId, entry);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read transaction log: " + e.getMessage());
        }
        
        // Filter to only operations whose FINAL state is non-terminal
        return latestByOperation.values().stream()
                .filter(e -> e.state() != OperationState.COMPLETED 
                          && e.state() != OperationState.FAILED 
                          && e.state() != OperationState.ROLLED_BACK)
                .toList();
    }
    
    /**
     * Recovers incomplete transactions on startup.
     * This should be called during initialization to clean up any interrupted operations.
     * Recovery strategy depends on the last recorded state.
     */
    public void recoverIncompleteTransactions(LocalPackageDatabase database, Path installRoot, PluginLogger logger) {
        List<LogEntry> incomplete = getIncompleteOperations();
        for (LogEntry entry : incomplete) {
            logger.warn("Found incomplete transaction: " + entry.operationId() + " for " + entry.packageName() 
                    + " " + entry.version() + " (" + entry.channel() + ") at state " + entry.state());
            
            switch (entry.state()) {
                case STARTED, EXTRACTING -> {
                    // Download/extract phase interrupted - nothing to clean up on disk yet
                    // Package not installed, just mark as failed
                    markFailed(entry.operationId(), "Interrupted during download/extract: " + entry.state());
                }
                case EXTRACTED, VALIDATING, VALIDATED -> {
                    // Files extracted but not moved/registered - clean up temp extract dir if exists
                    cleanUpTempExtract(installRoot, entry.packageName(), entry.version(), entry.channel());
                    markFailed(entry.operationId(), "Interrupted during validation: " + entry.state());
                }
                case MOVING, MOVED -> {
                    // Files moved to install root but not registered - attempt to remove orphaned files
                    removeOrphanedFiles(database, installRoot, entry.packageName(), entry.version(), entry.channel());
                    markFailed(entry.operationId(), "Interrupted during move: " + entry.state());
                }
                case REGISTERING, REGISTERED -> {
                    // Package registered in DB but operation not marked complete
                    // Check if package is actually in DB and remove if so
                    try {
                        if (database.getPackage(entry.packageName(), entry.version(), entry.channel()).isPresent()) {
                            database.unregisterPackage(entry.packageName(), entry.version(), entry.channel());
                            logger.info("Unregistered partially installed package: " + entry.packageName() + " " + entry.version());
                        }
                    } catch (IOException e) {
                        logger.error("Failed to unregister package during recovery: " + e.getMessage());
                    }
                    markFailed(entry.operationId(), "Interrupted during registration: " + entry.state());
                }
                default -> {
                    markFailed(entry.operationId(), "Unknown incomplete state: " + entry.state());
                }
            }
        }
    }
    
    private void cleanUpTempExtract(Path installRoot, String name, String version, String channel) {
        Path tempDir = installRoot.resolve("library/tmp/" + name + "-" + version);
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            logger.warn("Failed to delete temp file during recovery: " + e.getMessage());
                        }
                    });
                logger.debug("Cleaned up temp extract directory: " + tempDir);
            }
        } catch (IOException e) {
            logger.warn("Failed to walk temp directory during recovery: " + e.getMessage());
        }
    }
    
    private void removeOrphanedFiles(LocalPackageDatabase database, Path installRoot, String name, String version, String channel) {
        try {
            var pkgOpt = database.getPackage(name, version, channel);
            if (pkgOpt.isPresent()) {
                var pkg = pkgOpt.get();
                for (var fileEntry : pkg.files().entrySet()) {
                    Path filePath = installRoot.resolve(fileEntry.getKey());
                    if (Files.exists(filePath)) {
                        // Only delete if no other package owns this file
                        var owners = database.getFileOwners(fileEntry.getKey());
                        if (owners.size() == 1 && owners.contains(pkg.id())) {
                            try {
                                Files.delete(filePath);
                                logger.debug("Removed orphaned file during recovery: " + fileEntry.getKey());
                            } catch (IOException e) {
                                logger.warn("Failed to remove orphaned file during recovery: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error during orphaned file cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Marks an operation as completed.
     */
    public synchronized void markCompleted(String operationId) {
        log(operationId, null, null, null, null, OperationState.COMPLETED, "Operation completed");
    }
    
    /**
     * Marks an operation as failed.
     */
    public synchronized void markFailed(String operationId, String error) {
        log(operationId, null, null, null, null, OperationState.FAILED, error);
    }
    
    /**
     * Marks an operation as rolled back.
     */
    public synchronized void markRolledBack(String operationId) {
        log(operationId, null, null, null, null, OperationState.ROLLED_BACK, "Operation rolled back");
    }
    
    /**
     * Generates a unique operation ID.
     */
    public static String generateOperationId() {
        return UUID.randomUUID().toString();
    }
}