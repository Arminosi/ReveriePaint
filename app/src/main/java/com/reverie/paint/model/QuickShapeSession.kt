/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

internal enum class QuickShapePhase {
    OriginalOpen,
    PreviewOpen,
    RestoreOpen,
    StashOnly,
}

internal sealed class QuickShapeAction {
    data object StashCancelOriginalReplay : QuickShapeAction()
    data object CancelPreviewReplay : QuickShapeAction()
    data object RevertRestoreReplay : QuickShapeAction()
    data object ReplayOnly : QuickShapeAction()
    data object EndOriginal : QuickShapeAction()
    data object EndPreview : QuickShapeAction()
    data object CancelPreviewCommitStash : QuickShapeAction()
    data object CommitRestore : QuickShapeAction()
    data object RestoreAndCommitStash : QuickShapeAction()
    data object ReplayThenEnd : QuickShapeAction()
    data object RefuseAndKeepOriginal : QuickShapeAction()
}

internal class QuickShapeSession {
    @Volatile
    var phase: QuickShapePhase = QuickShapePhase.OriginalOpen
        private set
    @Volatile
    var appliedGeneration: Int = 0
        private set
    @Volatile
    var isStashBlocked: Boolean = false
        private set

    fun begin() {
        phase = QuickShapePhase.OriginalOpen
        appliedGeneration = 0
        isStashBlocked = false
    }

    fun preview(generation: Int): QuickShapeAction {
        if (isStashBlocked) return QuickShapeAction.RefuseAndKeepOriginal
        val action = when (phase) {
            QuickShapePhase.OriginalOpen -> QuickShapeAction.StashCancelOriginalReplay
            QuickShapePhase.PreviewOpen -> QuickShapeAction.CancelPreviewReplay
            QuickShapePhase.RestoreOpen -> QuickShapeAction.RevertRestoreReplay
            QuickShapePhase.StashOnly -> QuickShapeAction.ReplayOnly
        }
        if (action == QuickShapeAction.StashCancelOriginalReplay) {
            // 一旦开始替换原手绘笔触，立即脱离 OriginalOpen 阶段，避免并发取消返回 EndOriginal
            phase = QuickShapePhase.PreviewOpen
            appliedGeneration = 0
        }
        return action
    }

    fun commit(latestGeneration: Int): QuickShapeAction {
        if (isStashBlocked) return QuickShapeAction.EndOriginal
        return when (phase) {
            QuickShapePhase.OriginalOpen -> QuickShapeAction.ReplayThenEnd
            QuickShapePhase.PreviewOpen -> {
                if (appliedGeneration == latestGeneration) QuickShapeAction.EndPreview
                else QuickShapeAction.ReplayThenEnd
            }
            QuickShapePhase.RestoreOpen -> QuickShapeAction.ReplayThenEnd
            QuickShapePhase.StashOnly -> QuickShapeAction.ReplayThenEnd
        }
    }

    fun cancel(): QuickShapeAction = when (phase) {
        QuickShapePhase.OriginalOpen -> QuickShapeAction.EndOriginal
        QuickShapePhase.PreviewOpen -> QuickShapeAction.CancelPreviewCommitStash
        QuickShapePhase.RestoreOpen -> QuickShapeAction.CommitRestore
        QuickShapePhase.StashOnly -> QuickShapeAction.RestoreAndCommitStash
    }

    fun contextLost(): QuickShapeAction = cancel()

    fun replayFinished(hadInk: Boolean, generation: Int) {
        if (hadInk) {
            phase = QuickShapePhase.PreviewOpen
            appliedGeneration = generation
        }
    }

    fun restoreSucceeded(generation: Int) {
        phase = QuickShapePhase.RestoreOpen
        appliedGeneration = generation
    }

    fun restoreFailed() {
        phase = QuickShapePhase.StashOnly
    }

    fun stashFailed() {
        isStashBlocked = true
        phase = QuickShapePhase.OriginalOpen
    }
}
