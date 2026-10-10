package me.majhrs16.suite.manager.spi;

/**
 * Represents a single file entry in a package's inventory.
 */
public final class FileEntry {

    private final String path;        // Relative path within installation
    private final long size;          // Expected size in bytes
    private final String sha256;      // SHA-256 of file content
    private final String ownerPackage; // Package that owns this file

    public FileEntry(String path, long size, String sha256, String ownerPackage) {
        this.path = path;
        this.size = size;
        this.sha256 = sha256;
        this.ownerPackage = ownerPackage;
    }

    public String path() { return path; }
    public long size() { return size; }
    public String sha256() { return sha256; }
    public String ownerPackage() { return ownerPackage; }

    @Override
    public String toString() {
        return "FileEntry{" +
                "path='" + path + '\'' +
                ", size=" + size +
                ", sha256='" + sha256 + '\'' +
                ", ownerPackage='" + ownerPackage + '\'' +
                '}';
    }
}