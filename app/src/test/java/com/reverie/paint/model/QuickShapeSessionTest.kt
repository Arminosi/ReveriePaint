/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class QuickShapeSessionTest {
    @Test
    fun `initial state is OriginalOpen and commit replays before ending`() {
        val session = QuickShapeSession()
        session.begin()
        assertEquals(QuickShapePhase.OriginalOpen, session.phase)
        assertEquals(0, session.appliedGeneration)
        assertFalse(session.isStashBlocked)

        // Prior to first preview, cancellation keeps original stroke
        assertEquals(QuickShapeAction.EndOriginal, session.cancel())
        assertEquals(QuickShapeAction.EndOriginal, session.contextLost())
        assertEquals(QuickShapeAction.ReplayThenEnd, session.commit(1))

        // Once preview triggers, phase leaves OriginalOpen to prevent racing cancel from calling EndOriginal
        assertEquals(QuickShapeAction.StashCancelOriginalReplay, session.preview(1))
        assertEquals(QuickShapePhase.PreviewOpen, session.phase)
        assertEquals(QuickShapeAction.CancelPreviewCommitStash, session.cancel())
    }

    @Test
    fun `successful replay moves to PreviewOpen and allows EndPreview or ReplayThenEnd`() {
        val session = QuickShapeSession()
        session.begin()
        session.replayFinished(hadInk = true, generation = 1)
        assertEquals(QuickShapePhase.PreviewOpen, session.phase)
        assertEquals(1, session.appliedGeneration)

        assertEquals(QuickShapeAction.CancelPreviewReplay, session.preview(2))
        assertEquals(QuickShapeAction.EndPreview, session.commit(1))
        assertEquals(QuickShapeAction.ReplayThenEnd, session.commit(2))
        assertEquals(QuickShapeAction.CancelPreviewCommitStash, session.cancel())
    }

    @Test
    fun `replay failure with restoreSucceeded moves to RestoreOpen and cancel commits restore`() {
        val session = QuickShapeSession()
        session.begin()
        session.restoreSucceeded(1)
        assertEquals(QuickShapePhase.RestoreOpen, session.phase)

        assertEquals(QuickShapeAction.RevertRestoreReplay, session.preview(2))
        assertEquals(QuickShapeAction.ReplayThenEnd, session.commit(2))
        assertEquals(QuickShapeAction.CommitRestore, session.cancel())
    }

    @Test
    fun `replay failure with restoreFailed moves to StashOnly and prevents second stash`() {
        val session = QuickShapeSession()
        session.begin()
        session.restoreFailed()
        assertEquals(QuickShapePhase.StashOnly, session.phase)

        assertEquals(QuickShapeAction.ReplayOnly, session.preview(2))
        assertEquals(QuickShapeAction.ReplayThenEnd, session.commit(2))
        assertEquals(QuickShapeAction.RestoreAndCommitStash, session.cancel())
    }

    @Test
    fun `stash failure locks in OriginalOpen and preserves freehand stroke`() {
        val session = QuickShapeSession()
        session.begin()
        session.stashFailed()
        assertEquals(QuickShapePhase.OriginalOpen, session.phase)
        assertTrue(session.isStashBlocked)

        assertEquals(QuickShapeAction.RefuseAndKeepOriginal, session.preview(1))
        assertEquals(QuickShapeAction.EndOriginal, session.commit(1))
        assertEquals(QuickShapeAction.EndOriginal, session.cancel())
    }
}
