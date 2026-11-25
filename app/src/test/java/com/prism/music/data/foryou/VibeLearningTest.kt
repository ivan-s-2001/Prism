package com.prism.music.data.foryou

import org.junit.Assert.*
import org.junit.Test

class VibeLearningTest {
    @Test fun failedOrUnheardPlaybackIsNotADislike() {
        assertNull(VibeLearning.outcome(0, 180_000, true))
        assertNull(VibeLearning.outcome(100, 180_000, true))
        assertNull(VibeLearning.outcome(5000, 180_000, false))
        assertEquals(VibeLearning.Outcome.SKIPPED, VibeLearning.outcome(5000, 180_000, true))
        assertEquals(VibeLearning.Outcome.ACCEPTED, VibeLearning.outcome(150_000, 180_000, true))
    }

    @Test fun sessionIsBoundedDecaysAndDoesNotCrossAccounts() {
        var clock = 0L
        val session = VibeLearning.Session { clock }
        session.note("account-a", "Artist", VibeLearning.Outcome.ACCEPTED)
        assertEquals(1.0, session.snapshot("account-a").getValue("artist").accepted, 1e-9)
        assertTrue(session.snapshot("account-b").isEmpty())
        clock += 30 * 60_000
        assertEquals(0.5, session.snapshot("account-a").getValue("artist").accepted, 1e-9)
        session.note("account-b", "Artist", VibeLearning.Outcome.SKIPPED)
        assertTrue(session.snapshot("account-a").isEmpty())
        assertEquals(1.0, session.snapshot("account-b").getValue("artist").skipped, 1e-9)
        clock += 46 * 60_000
        assertTrue(session.snapshot("account-b").isEmpty())
        repeat(100) { session.note("account-b", "Artist", VibeLearning.Outcome.ACCEPTED) }
        assertEquals(48.0, session.snapshot("account-b").getValue("artist").accepted, 1e-9)
        session.clear()
        assertTrue(session.snapshot("account-b").isEmpty())
    }

    @Test fun unknownEnergyAndSparseFeedbackRemainNeutral() {
        assertEquals(1.0, VibeLearning.sequenceWeight(null, 1.0), 1e-9)
        assertEquals(1.0, VibeLearning.sequenceWeight(Double.NaN, 0.5), 1e-9)
        assertTrue(VibeLearning.sequenceWeight(0.2, 0.3) > VibeLearning.sequenceWeight(0.2, 0.9))
        assertEquals(1.0, VibeLearning.sessionWeight(0.0, 0.0), 1e-9)
        assertTrue(VibeLearning.sessionWeight(2.0, 0.0) > 1.0)
        assertTrue(VibeLearning.sessionWeight(0.0, 2.0) < 1.0)
        assertTrue(VibeLearning.sessionWeight(100.0, 0.0) <= 1.35)
    }
}
