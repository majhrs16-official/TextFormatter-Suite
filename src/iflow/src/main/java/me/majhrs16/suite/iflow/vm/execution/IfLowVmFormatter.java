package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.logger.Logger;
import me.majhrs16.suite.iflow.vm.isa.RegisterFile;

/**
 * Module formatter for iFlow VM debug logging.
 * Provides structured output for VM cycle debug messages.
 */
public final class IfLowVmFormatter implements Logger.ModuleFormatter {

    /**
     * Formats a VM cycle debug message with full register state.
     * @param context the ExecutionContext (VM state)
     * @param format the format string (ignored, uses custom format)
     * @param args format arguments: [ip, opcode, mnemonic, fuel]
     * @return formatted debug message
     */
    @Override
    public String formatDebug(Object context, String format, Object... args) {
        if (!(context instanceof ExecutionContext)) {
            return String.format(format, args);
        }

        ExecutionContext ctx = (ExecutionContext) context;
        RegisterFile regs = ctx.registers();

        int ip = (args.length > 0 && args[0] instanceof Integer) ? (Integer) args[0] : regs.get(RegisterFile.IP);
        int opcode = (args.length > 1 && args[1] instanceof Integer) ? (Integer) args[1] : 0;
        String mnemonic = (args.length > 2 && args[2] instanceof String) ? (String) args[2] : "UNKNOWN";
        long fuel = (args.length > 3 && args[3] instanceof Long) ? (Long) args[3] : ctx.fuelRemaining();

        StringBuilder sb = new StringBuilder();
        sb.append("VM_CYCLE ");
        sb.append(String.format("IP=0x%04X ", ip));
        sb.append(String.format("OP=0x%02X(%s) ", opcode, mnemonic));
        sb.append("REGS=").append(formatRegisters(regs));
        sb.append(String.format(" FUEL=%d", fuel));

        return sb.toString();
    }

    private String formatRegisters(RegisterFile regs) {
        StringBuilder sb = new StringBuilder();
        sb.append("R0=0x").append(String.format("%04X", regs.get(0))).append(" ");
        sb.append("R1=0x").append(String.format("%04X", regs.get(1))).append(" ");
        sb.append("R2=0x").append(String.format("%04X", regs.get(2))).append(" ");
        sb.append("R3=0x").append(String.format("%04X", regs.get(3))).append(" ");
        sb.append("R4=0x").append(String.format("%04X", regs.get(4))).append(" ");
        sb.append("R5=0x").append(String.format("%04X", regs.get(5))).append(" ");
        sb.append("R6=0x").append(String.format("%04X", regs.get(6))).append(" ");
        sb.append("R7=0x").append(String.format("%04X", regs.get(7))).append(" ");
        sb.append("IP=0x").append(String.format("%04X", regs.get(8))).append(" ");
        sb.append("SP=0x").append(String.format("%04X", regs.get(9))).append(" ");
        sb.append("FP=0x").append(String.format("%04X", regs.get(10))).append(" ");
        sb.append("FL=0x").append(String.format("%04X", regs.get(11)));
        sb.append(String.format("(Z=%b N=%b C=%b V=%b)",
            regs.getZ(), regs.getN(), regs.getC(), regs.getV()));
        return sb.toString();
    }

    /**
     * Registers this formatter for the "iflow" module.
     * Should be called once at startup.
     */
    public static void register() {
        Logger.registerFormatter("iflow", new IfLowVmFormatter());
    }
}