package me.majhrs16.suite.iflow.vm.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModificationJournalTest {

    @Test
    void recordAndEntries() {
        ModificationJournal journal = new ModificationJournal();

        journal.record(new ModificationJournal.Entry(ModificationJournal.OpType.SET_TEXT, "hello", "test"));
        journal.record(new ModificationJournal.Entry(ModificationJournal.OpType.SET_CHANNEL, "chat", "redirect"));

        assertEquals(2, journal.size());
        assertFalse(journal.isEmpty());
    }

    @Test
    void convenienceMethods() {
        ModificationJournal journal = new ModificationJournal();

        journal.setText("hello");
        journal.setChannel("chat");
        journal.setLangSource("en");
        journal.setLangTarget("es");
        journal.setColorMode("GRADIENT");
        journal.setFormatPapi(true);
        journal.setSleepMillis(100);
        journal.addSounds(java.util.List.of("a.mp3"));
        journal.removeSounds(java.util.List.of("b.mp3"));
        journal.cancelMessage();
        journal.setProcessed();
        journal.redirectChannel("private");

        assertEquals(12, journal.size());

        ModificationJournal.Entry e = journal.entries().get(0);
        assertEquals(ModificationJournal.OpType.SET_TEXT, e.type);
        assertEquals("hello", e.value);
    }

    @Test
    void commitAndDiscard() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");

        journal.commit();
        assertTrue(journal.isCommitted());
        assertFalse(journal.isDiscarded());

        ModificationJournal journal2 = new ModificationJournal();
        journal2.setText("test");
        journal2.discard();
        assertTrue(journal2.isDiscarded());
        assertTrue(journal2.isEmpty());
    }

    @Test
    void commitTwiceThrows() {
        ModificationJournal journal = new ModificationJournal();
        journal.commit();
        assertThrows(IllegalStateException.class, () -> journal.commit());
        assertThrows(IllegalStateException.class, () -> journal.discard());
    }

    @Test
    void discardTwiceThrows() {
        ModificationJournal journal = new ModificationJournal();
        journal.discard();
        assertThrows(IllegalStateException.class, () -> journal.discard());
        assertThrows(IllegalStateException.class, () -> journal.commit());
    }

    @Test
    void clearBeforeCommit() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");
        journal.clear();
        assertTrue(journal.isEmpty());
        journal.commit(); // Should work after clear
    }

    @Test
    void clearAfterCommitThrows() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");
        journal.commit();
        assertThrows(IllegalStateException.class, () -> journal.clear());
    }

    @Test
    void resetAfterCommitAllowsReuse() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");
        journal.commit();

        journal.reset();

        assertFalse(journal.isCommitted());
        assertFalse(journal.isDiscarded());
        assertTrue(journal.isEmpty());
        journal.setText("next"); // must accept new entries again
        assertEquals(1, journal.size());
    }

    @Test
    void resetAfterDiscardAllowsReuse() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");
        journal.discard();

        journal.reset();

        assertFalse(journal.isDiscarded());
        assertTrue(journal.isEmpty());
    }

    @Test
    void entriesAreImmutableCopy() {
        ModificationJournal journal = new ModificationJournal();
        journal.setText("test");
        var entries = journal.entries();
        assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
    }
}