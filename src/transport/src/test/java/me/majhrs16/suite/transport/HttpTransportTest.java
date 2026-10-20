package me.majhrs16.suite.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {

    @Test
    void rejectsUserInfoEvenWhenSsrfProtectionIsDisabled() {
        HttpTransport transport = new HttpTransport(Duration.ofSeconds(1), false);

        IOException error = assertThrows(IOException.class,
            () -> transport.get("http://user:secret@127.0.0.1:1/"));

        assertTrue(error.getMessage().contains("userinfo"), error.getMessage());
    }
}
