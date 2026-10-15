package me.majhrs16.suite.iflow.vm.memory;

/**
 * Memory region permissions.
 */
public enum RegionPerm {
    READ(1),
    WRITE(2),
    EXECUTE(4);

    private final int bit;

    RegionPerm(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public static int combine(RegionPerm... perms) {
        int result = 0;
        for (RegionPerm p : perms) {
            if (p != null) {
                result |= p.bit();
            }
        }
        return result;
    }

    public static boolean has(int combined, RegionPerm perm) {
        return (combined & perm.bit()) != 0;
    }
}