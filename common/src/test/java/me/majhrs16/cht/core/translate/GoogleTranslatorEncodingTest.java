package me.majhrs16.cht.core.translate;

import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GoogleTranslatorEncodingTest {

    private final GoogleTranslator translator = new GoogleTranslator(false);

    @Test
    void encoderProtectsDoubleSpace() throws UnsupportedEncodingException {
        String decoded = java.net.URLDecoder.decode(translator.encoder("  "), "UTF-8");
        assertEquals("[20][20]", decoded);
        assertEquals("  ", translator.decoder(decoded));
    }

    @Test
    void encoderEscapesColorCode() throws UnsupportedEncodingException {
        String encoded = java.net.URLDecoder.decode(translator.encoder("&a"), "UTF-8");
        assertEquals("[26]a", encoded);
    }

    @Test
    void decoderRestoresEscapedChars() throws UnsupportedEncodingException {
        String original = "cash 100% &x";
        String encoded = translator.encoder(original);
        String decoded = translator.decoder(java.net.URLDecoder.decode(encoded, "UTF-8"));
        assertEquals(original, decoded);
    }

    @Test
    void decoderFixesBrokenColorCodes() throws UnsupportedEncodingException {
        assertEquals("&aHola", translator.decoder("& aHola"));
    }

    @Test
    void roundTripKeepsSpecialCharacters() throws UnsupportedEncodingException {
        String original = "&aHola %mundo% #1!";
        String encoded = translator.encoder(original);
        String decoded = translator.decoder(java.net.URLDecoder.decode(encoded, "UTF-8"));
        assertEquals(original, decoded);
        assertNotNull(decoded);
    }
}