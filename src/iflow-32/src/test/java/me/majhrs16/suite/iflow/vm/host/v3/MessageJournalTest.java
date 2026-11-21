package me.majhrs16.suite.iflow.vm.host.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.api.iflow.TlvCodec;

class MessageJournalTest {

    private static final int PIDENT = 3;
    private static final int MIDENT = 9;

    @Test
    void readYourWritesKeepsTheLatestValuePerProperty() {
        MessageJournal journal = new MessageJournal();
        journal.write(new PropertyWrite(PIDENT, MIDENT, "world", "name",
                TlvCodec.stringValue("Arena")));
        assertEquals("Arena", journal.read("world", "name").orElseThrow().asString());
        assertTrue(journal.contains("world", "name"));

        journal.write(new PropertyWrite(PIDENT, MIDENT, "world", "name",
                TlvCodec.stringValue("Nether")));
        assertEquals("Nether", journal.read("world", "name").orElseThrow().asString());
        assertEquals(1, journal.writes().size());
    }

    @Test
    void decisionIsSingleAndAbortClearsTheOpenUnit() throws Exception {
        MessageJournal journal = new MessageJournal();
        journal.write(new PropertyWrite(PIDENT, MIDENT, "world", "name",
                TlvCodec.stringValue("Arena")));
        journal.decide(PIDENT, MIDENT, HostAbi.TARGET_ERROR, "failed", "boom");

        assertTrue(journal.decision().isPresent());
        assertEquals(HostAbi.TARGET_ERROR, journal.decision().getAsInt());
        assertThrows(HostServiceException.class,
                () -> journal.decide(PIDENT, MIDENT, HostAbi.TARGET_DELIVER, "ok", null));

        journal.abort();
        assertEquals(MessageJournal.State.ABORTED, journal.state());
        assertTrue(journal.decision().isEmpty());
        assertTrue(journal.writes().isEmpty());
        assertFalse(journal.contains("world", "name"));
        assertThrows(IllegalStateException.class,
                () -> journal.write(new PropertyWrite(PIDENT, MIDENT, "world", "name",
                        TlvCodec.stringValue("late"))));
    }

    @Test
    void commitClosesTheJournalAndRejectsLateMutations() {
        MessageJournal journal = new MessageJournal();
        journal.commit();

        assertEquals(MessageJournal.State.COMMITTED, journal.state());
        assertThrows(IllegalStateException.class, journal::abort);
    }
}
