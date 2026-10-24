package me.majhrs16.suite.iflow.vm.memory;

/**
 * Privilege level of a bus master (design.md §14, implement.md §12).
 * <p>
 * The machine distinguishes two authority domains rather than reproducing the
 * x86 rings literally:
 * </p>
 * <ul>
 *   <li>{@link #PROGRAM} (ring 3): the CPU executing bytecode. It is bounded by
 *       the per-page permissions (R/W/X) and by the capabilities granted to the
 *       execution.</li>
 *   <li>{@link #SUPERVISOR} (ring 0): the host runtime, boot loader and DMA
 *       controller. It may additionally write pages owned by the kernel (ROM,
 *       constants) that a program can only read or execute.</li>
 * </ul>
 * <p>
 * Carrying the privilege on the {@link VmBus} is what makes the boundary
 * hardware-like: each bus master issues transactions with its own mode and the
 * MMU (slave) enforces the page-ownership rule.
 * </p>
 */
public enum Privilege {

    /** The program domain (ring 3): firmware and user bytecode. */
    PROGRAM,
    /** The supervisor domain (ring 0): host runtime, loader and DMA. */
    SUPERVISOR;

    /** True for the supervisor domain, which may write kernel-owned pages. */
    public boolean isSupervisor() {
        return this == SUPERVISOR;
    }
}
