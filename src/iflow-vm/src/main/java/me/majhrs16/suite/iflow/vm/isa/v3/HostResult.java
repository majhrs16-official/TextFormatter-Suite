package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Result returned by a {@code HOST} handler.
 *
 * <p>The handler writes the result buffer itself, if any. The length is the
 * value installed in {@code R1}; when it exceeds the caller's capacity the
 * handler must return {@link HostStatus#LIMIT} and leave the buffer untouched.</p>
 */
public record HostResult(int status, int resultLength) {

    public HostResult {
        if (!HostStatus.isValid(status)) {
            throw new IllegalArgumentException("Invalid HOST status: " + status);
        }
        if (resultLength < 0) {
            throw new IllegalArgumentException("Negative HOST result length: " + resultLength);
        }
    }
}
