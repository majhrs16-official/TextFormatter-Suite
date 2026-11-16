#!/usr/bin/env python3
import sys

def disassemble(buf, start=0, length=None):
    """Simple disassembler for iFlow VM bytecode"""
    if length is None:
        length = len(buf) - start
    
    ip = start
    end = start + length
    
    opcodes = {
        0x00: ("LI", 4),
        0x01: ("MOV", 2),
        0x02: ("LDB", 4),
        0x03: ("LDW", 4),
        0x04: ("STB", 4),
        0x05: ("STW", 4),
        0x06: ("COPY", 8),
        0x07: ("MOVE", 8),
        0x20: ("ADD", 2),
        0x21: ("SUB", 2),
        0x22: ("MUL", 2),
        0x23: ("DIV", 2),
        0x24: ("MOD", 2),
        0x28: ("AND", 2),
        0x29: ("OR", 2),
        0x2A: ("XOR", 2),
        0x2B: ("NOT", 2),
        0x2C: ("SHL", 2),
        0x2D: ("SHR", 2),
        0x2E: ("SAR", 2),
        0x30: ("CMP", 2),
        0x31: ("CMPU", 2),
        0x40: ("JMP", 3),
        0x41: ("JZ", 3),
        0x42: ("JNZ", 3),
        0x43: ("JC", 3),
        0x44: ("JV", 3),
        0x48: ("CALL", 3),
        0x49: ("RET", 1),
        0x60: ("HOST", 2),
        0x68: ("HALT", 1),
        0x69: ("TRAP", 2),
    }
    
    regs = ["R0","R1","R2","R3","R4","R5","R6","R7","IP","SP","FP","FL"]
    
    while ip < end:
        if ip >= len(buf):
            break
        opcode = buf[ip]
        name, size = opcodes.get(opcode, ("UNKNOWN", 1))
        
        print(f"{ip:04x}: {opcode:02x} {name:5}", end="")
        
        if name == "LI":
            rd = buf[ip+1]
            imm = (buf[ip+2] << 8) | buf[ip+3]
            print(f" R{rd}, 0x{imm:04x} ({imm})")
        elif name in ("MOV", "ADD", "SUB", "MUL", "DIV", "MOD", "AND", "OR", "XOR", "SHL", "SHR", "SAR", "CMP", "CMPU"):
            packed = buf[ip+1]
            rd = (packed >> 4) & 0xF
            rs = packed & 0xF
            print(f" R{rd}, R{rs}")
        elif name == "NOT":
            rd = buf[ip+1]
            print(f" R{rd}")
        elif name in ("LDB", "LDW", "STB", "STW"):
            rd = buf[ip+1]
            vaddr = (buf[ip+2] << 8) | buf[ip+3]
            print(f" R{rd}, 0x{vaddr:04x}")
        elif name in ("COPY", "MOVE"):
            src = (buf[ip+1] << 8) | buf[ip+2]
            dst = (buf[ip+3] << 8) | buf[ip+4]
            length = (buf[ip+5] << 8) | buf[ip+6]
            print(f" 0x{src:04x}, 0x{dst:04x}, {length}")
        elif name in ("JMP", "JZ", "JNZ", "JC", "JV", "CALL"):
            offset = (buf[ip+1] << 8) | buf[ip+2]
            if offset & 0x8000:
                offset |= 0xFFFF0000
            target = ip + 3 + offset
            print(f" 0x{target:04x} (offset={offset})")
        elif name == "RET":
            print()
        elif name == "HOST":
            print(f" (service in R0)")
        elif name == "HALT":
            print()
        elif name == "TRAP":
            code = buf[ip+1]
            print(f" 0x{code:02x}")
        else:
            for i in range(1, size):
                print(f" {buf[ip+i]:02x}", end="")
            print()
        
        ip += size

# Read firmware binary (path relative to this script)
import os
here = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(here, "firmware.flow"), "rb") as f:
    data = f.read()

# Parse header (ROM2: 32-byte header + 16-byte TOC entries)
print("=== HEADER ===")
print(f"magic: 0x{int.from_bytes(data[0:4], 'big'):08x} (\"FLOW\")")
print(f"formatVersion: {data[4]}")
print(f"isaVersion: {data[5]}")
print(f"minAbiVersion: {data[6]}")
print(f"minFirmwareVersion: {data[7]}")
entryPoint = (data[8] << 8) | data[9]
tocOffset = (data[10] << 8) | data[11]
tocCount = (data[12] << 8) | data[13]
layoutMagic = int.from_bytes(data[16:20], 'big')
payloadSize = int.from_bytes(data[20:24], 'big')
print(f"entryPoint: 0x{entryPoint:04x}")
print(f"tocOffset: {tocOffset}")
print(f"tocCount: {tocCount}")
print(f"layoutMagic: 0x{layoutMagic:08x}")
print(f"payloadSize: {payloadSize}")

print("\n=== TOC ===")
segments = []
for i in range(tocCount):
    base = tocOffset + i * 16
    seg_type = data[base]
    flags = data[base + 1]
    vaddr = (data[base + 2] << 8) | data[base + 3]
    file_offset = int.from_bytes(data[base + 4:base + 8], 'big')
    size = (data[base + 8] << 8) | data[base + 9]
    mem_size = (data[base + 10] << 8) | data[base + 11]
    print(f"[{i}] type={seg_type} flags=0x{flags:02x} vaddr=0x{vaddr:04x} "
          f"offset={file_offset} size={size} memSize={mem_size}")
    segments.append((seg_type, vaddr, file_offset, size, mem_size))

program = next((s for s in segments if s[0] == 4), None)
if program is None:
    raise SystemExit("no PROGRAM segment")
_, program_vaddr, program_offset, program_size, _ = program
print(f"\n=== PROGRAM (0x{program_vaddr:04x}) ===")
disassemble(data, program_offset, program_size)

