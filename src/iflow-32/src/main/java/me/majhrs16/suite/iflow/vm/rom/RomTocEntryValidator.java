package me.majhrs16.suite.iflow.vm.rom;

/**
 * Applies the per-type permission and address rules of ADR-037 §8.5.
 */
final class RomTocEntryValidator {

    private RomTocEntryValidator() {
    }

    static void validate(TocEntry entry) {
        long load = u(entry.loadAddress());
        long end = load + u(entry.memSize());
        if (end > RomParser.ADDRESS_SPACE_END) {
            throw new InvalidRomException("loadAddress + memSize overflows 32 bits");
        }
        if (entry.writable() && entry.executable()) {
            throw new InvalidRomException("W^X violation in " + entry.type() + " segment");
        }

        switch (entry.type()) {
            case BIOS -> validateBios(entry, load, end);
            case BOOT, KERNEL -> validateBootOrKernel(entry, load, end);
            case PROGRAM -> validateProgram(entry, load, end);
            case DATA -> validateData(entry, load, end);
            case FONT, CONFIG -> validateFontOrConfig(entry, load, end);
        }

        if (entry.executable()) {
            long entryPoint = u(entry.entryPoint());
            if (entryPoint < load || entryPoint >= load + u(entry.size())) {
                throw new InvalidRomException("entryPoint is outside the " + entry.type() + " segment");
            }
        } else if (entry.entryPoint() != 0) {
            throw new InvalidRomException("non-executable " + entry.type() + " has a non-zero entryPoint");
        }
    }

    private static void validateBios(TocEntry entry, long load, long end) {
        if (load != u(entry.offset())
                || end > RomParser.ROM_END
                || !entry.readable()
                || !entry.executable()
                || entry.writable()
                || entry.userAccessible()) {
            throw new InvalidRomException(
                    "BIOS must be mapped at its ROM offset, R/X and supervisor-only");
        }
    }

    private static void validateBootOrKernel(TocEntry entry, long load, long end) {
        if (load < RomParser.KERNEL_BASE
                || end > RomParser.ADDRESS_SPACE_END
                || !entry.readable()
                || entry.executable() == entry.writable()
                || entry.userAccessible()) {
            throw new InvalidRomException(entry.type() + " must be in kernel space, R/X or R/W and supervisor-only");
        }
    }

    private static void validateProgram(TocEntry entry, long load, long end) {
        if (load < RomParser.USER_BASE
                || load >= RomParser.USER_END
                || end > RomParser.USER_END
                || !entry.userAccessible()
                || !entry.readable()
                || !entry.executable()
                || entry.writable()) {
            throw new InvalidRomException("PROGRAM must be in user space, U=1, R/X and W=0");
        }
    }

    private static void validateData(TocEntry entry, long load, long end) {
        if (!entry.readable()
                || entry.executable()
                || entry.userAccessible()
                || (!withinRom(load, end) && !withinKernel(load, end))) {
            throw new InvalidRomException("DATA must be supervisor firmware/kernel data");
        }
    }

    private static void validateFontOrConfig(TocEntry entry, long load, long end) {
        if (!entry.readable()
                || entry.executable()
                || (!withinRom(load, end) && !withinUser(load, end) && !withinKernel(load, end))) {
            throw new InvalidRomException(entry.type() + " must be data in ROM, user or kernel space");
        }
    }

    private static boolean withinRom(long load, long end) {
        return load >= 0 && end <= RomParser.ROM_END;
    }

    private static boolean withinUser(long load, long end) {
        return load >= RomParser.USER_BASE && end <= RomParser.USER_END;
    }

    private static boolean withinKernel(long load, long end) {
        return load >= RomParser.KERNEL_BASE && end <= RomParser.ADDRESS_SPACE_END;
    }

    private static long u(int value) {
        return Integer.toUnsignedLong(value);
    }
}
