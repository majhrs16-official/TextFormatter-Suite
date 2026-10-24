package me.majhrs16.suite.observability;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class MockitoTest {

    @Test
    void testMockitoWorks() {
        Runnable mock = Mockito.mock(Runnable.class);
        mock.run();
        Mockito.verify(mock).run();
    }
}