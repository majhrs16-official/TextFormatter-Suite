package me.majhrs16.suite.iflow.vm.rom;

/**
 * Decoded 32-byte {@code PRG3} subheader of a {@code PROGRAM} segment.
 *
 * @param codeOffset        offset of the executable range, relative to the segment
 * @param codeEnd           exclusive end of the executable range
 * @param dataOffset        offset of the initialized data, or 0 when there is none
 * @param dataEnd           exclusive end of the initialized data, or 0 when there is none
 * @param capabilitiesOffset offset of the capability blob, or 0 when there is none
 * @param capabilitiesLength length of the capability blob, or 0 when there is none
 */
public record Prg3Header(
        int codeOffset,
        int codeEnd,
        int dataOffset,
        int dataEnd,
        int capabilitiesOffset,
        int capabilitiesLength
) {

    public static final int HEADER_SIZE = 32;

    public static final byte[] MAGIC = {'P', 'R', 'G', '3'};
}
