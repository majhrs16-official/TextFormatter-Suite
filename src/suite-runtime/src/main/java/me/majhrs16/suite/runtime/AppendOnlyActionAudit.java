package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.sync.ActionAudit;
import me.majhrs16.suite.api.sync.ActionAuditRecord;

/**
 * Append-only audit trail for sync actions.
 *
 * <p>One line per attempt, with fields separated by {@code |}. Values are
 * escaped so a newline can never forge a second entry. The file is opened with
 * {@link StandardOpenOption#APPEND} and every entry is forced to disk before
 * the call returns; there is no rotation, truncation or rewrite in this
 * class.</p>
 *
 * <p>The canonical path is {@code <data>/config/iflow/audit.log}, fixed by
 * {@code storage.md}.</p>
 */
final class AppendOnlyActionAudit implements ActionAudit, AutoCloseable {

    private static final char SEPARATOR = '|';

    private final Path file;
    private final PluginLogger logger;
    private final Object lock = new Object();
    private FileChannel channel;

    AppendOnlyActionAudit(Path file, PluginLogger logger) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void ensureWritable() {
        synchronized (lock) {
            channel();
        }
    }

    @Override
    public void record(ActionAuditRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] line = (format(record) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            FileChannel open = channel();
            try {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) {
                    open.write(buffer);
                }
                open.force(false);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "cannot append to audit log " + file + ": "
                                + failure.getMessage(), failure);
            }
        }
    }

    private FileChannel channel() {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            logger.debug("iflow audit: appending to " + file);
            return channel;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot open audit log " + file + ": " + failure.getMessage(), failure);
        }
    }

    private static String format(ActionAuditRecord record) {
        return escape(record.timestamp().toString()) + SEPARATOR
                + escape(record.program()) + SEPARATOR
                + escape(record.actor()) + SEPARATOR
                + escape(record.backend()) + SEPARATOR
                + escape(record.action()) + SEPARATOR
                + escape(record.argsHash()) + SEPARATOR
                + escape(record.status()) + SEPARATOR
                + record.durationMillis() + SEPARATOR
                + escape(record.detail());
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\p");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (channel == null) {
                return;
            }
            try {
                channel.close();
            } catch (IOException failure) {
                logger.warn("iflow audit: cannot close " + file + ": "
                        + failure.getMessage());
            } finally {
                channel = null;
            }
        }
    }
}
