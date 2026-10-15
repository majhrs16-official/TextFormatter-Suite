package me.majhrs16.suite.iflow.vm.isa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgramImageTest {

    @Test
    void builderCreatesValidImage() {
        ProgramImage img = ProgramImage.builder()
            .formatVersion(1)
            .isaVersion(1)
            .minAbiVersion(1)
            .minFirmwareVersion(1)
            .entryPoint(0x100)
            .code(new byte[]{0x00, 0x00, 0x01, 0x00, 0x68})
            .constants(new int[]{42, 100})
            .capabilities(new String[]{"routing.decide", "property.read"})
            .hash(new byte[]{1,2,3,4})
            .debugInfo("test")
            .build();

        assertEquals(1, img.formatVersion());
        assertEquals(1, img.isaVersion());
        assertEquals(1, img.minAbiVersion());
        assertEquals(1, img.minFirmwareVersion());
        assertEquals(0x100, img.entryPoint());
        assertArrayEquals(new byte[]{0x00, 0x00, 0x01, 0x00, 0x68}, img.code());
        assertArrayEquals(new int[]{42, 100}, img.constants());
        assertArrayEquals(new String[]{"routing.decide", "property.read"}, img.capabilities());
        assertArrayEquals(new byte[]{1,2,3,4}, img.hash());
        assertEquals("test", img.debugInfo());
    }

    @Test
    void codeAndConstantsAreDefensiveCopies() {
        byte[] code = new byte[]{1,2,3};
        int[] consts = new int[]{10, 20};
        ProgramImage img = ProgramImage.builder().code(code).constants(consts).build();

        code[0] = 99;
        consts[0] = 999;

        assertEquals(1, img.code()[0]);
        assertEquals(10, img.constants()[0]);
    }

    @Test
    void capabilitiesReturnsCopy() {
        ProgramImage img = ProgramImage.builder().capabilities(new String[]{"a", "b"}).build();
        String[] caps = img.capabilities();
        caps[0] = "modified";
        assertEquals("a", img.capabilities()[0]);
    }

    @Test
    void hashReturnsCopy() {
        byte[] hash = new byte[]{1,2,3};
        ProgramImage img = ProgramImage.builder().hash(hash).build();
        hash[0] = 99;
        assertEquals(1, img.hash()[0]);
    }

    @Test
    void hasCapabilityChecksCorrectly() {
        ProgramImage img = ProgramImage.builder()
            .capabilities(new String[]{"routing.decide", "property.read", "property.write"})
            .build();

        assertTrue(img.hasCapability("routing.decide"));
        assertTrue(img.hasCapability("property.read"));
        assertTrue(img.hasCapability("property.write"));
        assertFalse(img.hasCapability("state.write"));
        assertFalse(img.hasCapability(""));
    }

    @Test
    void emptyCapabilitiesReturnsFalse() {
        ProgramImage img = ProgramImage.builder().capabilities(new String[]{}).build();
        assertFalse(img.hasCapability("anything"));
    }

    @Test
    void codeSizeAndConstantsCount() {
        ProgramImage img = ProgramImage.builder()
            .code(new byte[100])
            .constants(new int[5])
            .build();

        assertEquals(100, img.codeSize());
        assertEquals(5, img.constantsCount());
    }

    @Test
    void equalsAndHashCode() {
        ProgramImage img1 = ProgramImage.builder()
            .code(new byte[]{1,2})
            .constants(new int[]{3})
            .capabilities(new String[]{"a"})
            .build();

        ProgramImage img2 = ProgramImage.builder()
            .code(new byte[]{1,2})
            .constants(new int[]{3})
            .capabilities(new String[]{"a"})
            .build();

        ProgramImage img3 = ProgramImage.builder()
            .code(new byte[]{9,9})
            .constants(new int[]{3})
            .capabilities(new String[]{"a"})
            .build();

        assertEquals(img1, img2);
        assertEquals(img1.hashCode(), img2.hashCode());
        assertNotEquals(img1, img3);
        assertNotEquals(img1, null);
        assertNotEquals(img1, "string");
    }

    @Test
    void toStringContainsKeyInfo() {
        ProgramImage img = ProgramImage.builder()
            .entryPoint(0x100)
            .code(new byte[50])
            .constants(new int[3])
            .capabilities(new String[]{"a", "b"})
            .build();

        String str = img.toString();
        assertTrue(str.contains("entry=0x100"));
        assertTrue(str.contains("code=50B"));
        assertTrue(str.contains("consts=3"));
        assertTrue(str.contains("caps=2"));
    }

    @Test
    void nullArraysHandledGracefully() {
        ProgramImage img = ProgramImage.builder()
            .code(null)
            .constants(null)
            .capabilities(null)
            .hash(null)
            .build();

        assertEquals(0, img.codeSize());
        assertEquals(0, img.constantsCount());
        assertEquals(0, img.capabilities().length);
        assertEquals(0, img.hash().length);
    }
}