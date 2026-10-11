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
     */
    public List<LogEntry> getIncompleteOperations() {
        List<LogEntry> incomplete = new ArrayList<>();
        try {
            if (!Files.exists(logFile)) return incomplete;
            
            String content = Files.readString(logFile);
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", 8);
                if (parts.length >= 8) {
                    OperationState state = OperationState.valueOf(parts[5]);
                    if (state != OperationState.COMPLETED && state != OperationState.FAILED && state != OperationState.ROLLED_BACK) {
                        incomplete.add(new LogEntry(
                                parts[0],
                                OperationType.valueOf(parts[1]),
                                parts[2],
                                parts[3],
                                parts[4],
                                state,
                                Instant.parse(parts[6]),
                                parts[7]
                        ));
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to read transaction log: " + e.getMessage());
        }
        return incomplete;
    }
    
    /**
     * Recovers incomplete transactions on startup.
     * This should be called during initialization to clean up any interrupted operations.
     */
    public void recoverIncompleteTransactions(LocalPackageDatabase database, Path installRoot, PluginLogger logger) {
        List<LogEntry> incomplete = getIncompleteOperations();
        for (LogEntry entry : incomplete) {
            logger.warn("Found incomplete transaction: " + entry.operationId() + " for " + entry.packageName() + " at state " + entry.state());
            
            // For now, mark as failed and log warning
            // Full recovery would require more complex logic based on the state
            markFailed(entry.operationId(), "Recovered after interruption at state: " + entry.state());
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