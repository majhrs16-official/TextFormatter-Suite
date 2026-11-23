package me.majhrs16.suite.iflow.vm.rom;

import me.majhrs16.suite.iflow.vm.isa.v3.Instruction;
import me.majhrs16.suite.iflow.vm.isa.v3.InstructionDecoder;
import me.majhrs16.suite.iflow.vm.isa.v3.InstructionForm;
import me.majhrs16.suite.iflow.vm.isa.v3.Opcode;
import me.majhrs16.suite.iflow.vm.isa.v3.Register;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the ROM emitted by {@code iflow-as -f iflow-32} and decodes its
 * bytes with the real v3 decoder.
 *
 * <p>The fixture is regenerated with {@code iflow-as -f iflow-32} from the sibling
 * {@code .flowasm} source; the test proves that assembler output is accepted by
 * the ROM parser and decoded without hand-written byte patching. Copying the
 * segments to their {@code loadAddress} belongs to the host loader, not to the
 * assembler.</p>
 */
class AssemblerRomIntegrationTest {

    private static byte[] fixture() {
        try (InputStream input = AssemblerRomIntegrationTest.class
                .getResourceAsStream("/rom/v3/assembler-minimal.rom")) {
            assertNotNull(input, "assembler fixture is missing");
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read assembler fixture", exception);
        }
    }

    @Test
    void parsesTheAssemblerOutput() {
        RomImage image = RomParser.parse(fixture());

        assertEquals(4, image.header().tocCount());
        assertEquals(4, image.segments().size());
        assertEquals(0x1000, image.header().resetVector());
        assertTrue(image.segment(RomSegmentType.BIOS).isPresent());
        assertTrue(image.segment(RomSegmentType.BOOT).isPresent());
        assertTrue(image.segment(RomSegmentType.KERNEL).isPresent());
        assertTrue(image.segment(RomSegmentType.PROGRAM).isPresent());

        RomProgram program = image.programs().get(0);
        assertEquals(List.of("iflow.property.read"), program.capabilities());
        assertEquals(Prg3Header.HEADER_SIZE, program.header().codeOffset());
        assertTrue(program.header().codeEnd() > program.header().codeOffset());
        assertEquals(0x0200_0020, program.segment().entry().entryPoint());
        assertEquals(0x0200_0000, program.segment().entry().loadAddress());
    }

    @Test
    void decodesTheAssemblerOutputWithTheV3Decoder() {
        RomImage image = RomParser.parse(fixture());
        byte[] bios = image.segment(RomSegmentType.BIOS).orElseThrow().data();
        InstructionDecoder decoder = new InstructionDecoder();

        Instruction entry = decoder.decode(0, address -> bios[address] & 0xFF);
        assertEquals(Opcode.MOV, entry.opcode());
        assertEquals(InstructionForm.IMMEDIATE, entry.form());
        assertEquals(Register.R1, entry.rd());
        assertEquals(0x1234, entry.immediate());

        RomProgram program = image.programs().get(0);
        byte[] code = program.segment().data();
        Instruction programEntry = decoder.decode(
                program.header().codeOffset(), address -> code[address] & 0xFF);
        assertEquals(Opcode.MOV, programEntry.opcode());
        assertEquals(Register.R2, programEntry.rd());
        assertEquals(7, programEntry.immediate());
    }
}
