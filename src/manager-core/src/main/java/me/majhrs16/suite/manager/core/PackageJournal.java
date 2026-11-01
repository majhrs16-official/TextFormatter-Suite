package me.majhrs16.suite.manager.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Append-only, fsynced journal for one package transaction.
 *
 * <p>The journal is the recovery source for crashes and {@code kill -9}. It
 * stores the previous status bytes, the new status hash and every file action.
 * The atomic rename of {@code library/status} is the commit point: recovery
 * rolls back before it and only cleans up after it.</p>
 */
final class PackageJournal {

    enum OperationType { INSTALL, REMOVE }

    private enum RecordType { CREATE, REPLACE, REMOVE }

    private record Record(RecordType type, String path, String backup) {
    }

    private record Header(String operationId, OperationType operationType,
                          String oldSha, String newSha, byte[] oldStatus, byte[] newStatus) {
    }

    private record Parsed(Header header, List<Record> records,
                          boolean committing, boolean committed, boolean rolledBack) {
    }

    private final Path journalFile;
    private final Path installRoot;
    private final Path journalDir;
    private final String newSha;
    private final FileChannel channel;
    private boolean closed;

    private PackageJournal(Path installRoot, LocalPackageDatabase database,
                           String operationId, OperationType operationType,
                           LocalPackageDatabase.StatusImage oldImage,
                           LocalPackageDatabase.StatusImage newImage) throws IOException {
        if (operationId == null || !operationId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("Invalid package journal operation id");
        }
        this.installRoot = installRoot.toAbsolutePath().normalize();
        this.journalDir = database.getJournalDir().toAbsolutePath().normalize();
        byte[] oldStatus = oldImage.content();
        this.newSha = newImage.sha256();
        Files.createDirectories(journalDir);
        this.journalFile = journalDir.resolve(operationId + ".log");
        this.channel = FileChannel.open(journalFile, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            String header = String.join("\t",
                    "V1", operationId, operationType.name(), oldImage.sha256(), newSha,
                    b64(oldStatus), b64(newImage.content())) + "\n";
            append(header);
            IoSupport.fsyncDirectory(journalDir);
        } catch (IOException failure) {
            channel.close();
            Files.deleteIfExists(journalFile);
            throw failure;
        }
    }

    static PackageJournal begin(Path installRoot, LocalPackageDatabase database,
                                String operationId, OperationType operationType,
                                LocalPackageDatabase.StatusImage oldImage,
                                LocalPackageDatabase.StatusImage newImage) throws IOException {
        return new PackageJournal(installRoot, database, operationId, operationType,
                oldImage, newImage);
    }

    void created(Path path) throws IOException {
        records(RecordType.CREATE, path, null);
    }

    void replaced(Path path, Path backup) throws IOException {
        records(RecordType.REPLACE, path, backup);
    }

    void removed(Path path, Path trash) throws IOException {
        records(RecordType.REMOVE, path, trash);
    }

    void committing() throws IOException {
        append("COMMITTING\t" + newSha + "\n");
    }

    void committed() throws IOException {
        append("COMMITTED\n");
    }

    private void records(RecordType type, Path path, Path backup) throws IOException {
        String line = type.name() + "\t" + b64(relative(path));
        if (backup != null) {
            line += "\t" + b64(relative(backup));
        }
        append(line + "\n");
    }

    private void append(String line) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(installRoot)) {
            throw new IllegalArgumentException("Journal path outside install root: " + path);
        }
        return installRoot.relativize(normalized).toString().replace('\\', '/');
    }

    private static String b64(byte[] content) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content);
    }

    private static String b64(String value) {
        return b64(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /**
     * Recovers every journal before the runtime or manager continues.
     */
    static void recoverAll(LocalPackageDatabase database, Path installRoot, PluginLogger logger)
            throws IOException {
        Path journalDir = database.getJournalDir();
        Files.createDirectories(journalDir);
        List<Path> journals;
        try (Stream<Path> stream = Files.list(journalDir)) {
            journals = stream.filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .toList();
        }
        IOException recoveryFailure = null;
        for (Path journal : journals) {
            try {
                recover(journal, database, installRoot, logger);
            } catch (Exception failure) {
                logger.error("Cannot recover package journal " + journal + ": "
                        + failure.getMessage(), failure);
                IOException wrapped = new IOException("Cannot recover package journal "
                        + journal + ": " + failure.getMessage(), failure);
                if (recoveryFailure == null) {
                    recoveryFailure = wrapped;
                } else {
                    recoveryFailure.addSuppressed(wrapped);
                }
            }
        }
        if (recoveryFailure != null) {
            throw recoveryFailure;
        }
    }

    private static void recover(Path journalFile, LocalPackageDatabase database,
                                Path installRoot, PluginLogger logger) throws IOException {
        Parsed parsed = parse(journalFile);
        if (parsed == null) {
            // A crash can leave an empty file before the header is fsynced.
            // No mutation happened before the header was written.
            Files.deleteIfExists(journalFile);
            return;
        }
        if (parsed.rolledBack()) {
            cleanup(database, parsed.header().operationId());
            return;
        }

        // library/status is the commit point. A COMMITTED marker is only
        // trusted when the published status still matches the journal image;
        // a corrupt or stale marker must not delete the rollback backups.
        boolean statusMatchesNew = database.currentStatusSha256()
                .equalsIgnoreCase(parsed.header().newSha());
        boolean committed = parsed.committed() && statusMatchesNew;
        if (!committed && parsed.committing() && statusMatchesNew) {
            committed = true;
        }
        if (committed) {
            if (!parsed.committed()) {
                appendTerminal(journalFile, "COMMITTED");
            }
            cleanup(database, parsed.header().operationId());
            return;
        }

        logger.warn("Rolling back interrupted package operation " + parsed.header().operationId());
        rollbackRecords(parsed.records(), database, installRoot, logger);
        database.restoreStatus(parsed.header().oldStatus());
        appendTerminal(journalFile, "ROLLED_BACK");
        cleanup(database, parsed.header().operationId());
    }

    private static void appendTerminal(Path journalFile, String line) throws IOException {
        try (FileChannel channel = FileChannel.open(journalFile, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void rollbackRecords(List<Record> records, LocalPackageDatabase database,
                                        Path installRoot, PluginLogger logger) throws IOException {
        for (int i = records.size() - 1; i >= 0; i--) {
            Record record = records.get(i);
            Path path = PackageLayout.resolve(installRoot, record.path());
            Path backup = record.backup() == null
                    ? null : PackageLayout.resolve(installRoot, record.backup());
            switch (record.type()) {
                case CREATE -> Files.deleteIfExists(path);
                case REPLACE -> {
                    // The journal is written before the backup move. If the
                    // process dies in that window, the original file is still
                    // at its destination and must not be deleted.
                    if (backup != null && Files.exists(backup)) {
                        Files.deleteIfExists(path);
                        IoSupport.move(backup, path);
                    }
                }
                case REMOVE -> {
                    if (backup != null && !Files.exists(path) && Files.exists(backup)) {
                        IoSupport.move(backup, path);
                    }
                }
            }
            IoSupport.deleteEmptyParents(path.getParent(), installRoot);
            if (backup != null) {
                IoSupport.deleteEmptyParents(backup.getParent(), installRoot);
            }
        }
    }

    private static void cleanup(LocalPackageDatabase database, String operationId) throws IOException {
        IoSupport.deleteRecursively(database.getBackupDir().resolve(operationId));
        IoSupport.deleteRecursively(database.getTmpDir().resolve(operationId));
    }

    private static Parsed parse(Path journalFile) throws IOException {
        List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return null;
        }
        String[] headerFields = lines.get(0).split("\t", -1);
        if (headerFields.length < 7 || !headerFields[0].equals("V1")
                || !headerFields[1].matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("Malformed package journal header: " + journalFile);
        }
        Header header;
        try {
            header = new Header(headerFields[1], OperationType.valueOf(headerFields[2]),
                    headerFields[3], headerFields[4],
                    unb64(headerFields[5]), unb64(headerFields[6]));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Malformed package journal header: " + journalFile, failure);
        }

        List<Record> records = new ArrayList<>();
        boolean committing = false;
        boolean committed = false;
        boolean rolledBack = false;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "CREATE" -> records.add(new Record(RecordType.CREATE,
                        decode(fields, 1, journalFile), null));
                case "REPLACE" -> records.add(new Record(RecordType.REPLACE,
                        decode(fields, 1, journalFile), decode(fields, 2, journalFile)));
                case "REMOVE" -> records.add(new Record(RecordType.REMOVE,
                        decode(fields, 1, journalFile), decode(fields, 2, journalFile)));
                case "COMMITTING" -> committing = true;
                case "COMMITTED" -> committed = true;
                case "ROLLED_BACK" -> rolledBack = true;
                default -> throw new IOException(
                        "Malformed package journal record: " + journalFile);
            }
        }
        return new Parsed(header, records, committing, committed, rolledBack);
    }

    private static String decode(String[] fields, int index, Path journalFile) throws IOException {
        if (index >= fields.length) {
            throw new IOException("Malformed package journal record: " + journalFile);
        }
        try {
            return new String(unb64(fields[index]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Malformed package journal record: " + journalFile, failure);
        }
    }
}
