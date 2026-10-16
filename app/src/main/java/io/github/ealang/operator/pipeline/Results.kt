package io.github.ealang.operator.pipeline

/**
 * What one step did with one item. Steps only report; the worker owns what
 * the answers mean (`failed/`, ordering, stopping on a blocked head).
 */
sealed interface StepResult {
    data object Success : StepResult

    /** Will succeed later — the item stays at the head and blocks the queue. */
    data class Transient(val why: String) : StepResult

    /** Will never succeed — the item moves to `failed/` and the queue moves on. */
    data class Permanent(val why: String) : StepResult
}

/**
 * What became of a handed-off item, answered by reconcile. Deliberately not
 * [StepResult]: Pending must not block the queue — a handed-off item is out
 * of the ordering contract, waiting only for its confirmation, and the items
 * behind it flow past.
 */
sealed interface ReconcileResult {
    /** Confirmed at the server — the item can finally be deleted. */
    data object Delivered : ReconcileResult

    /** Still in flight, or unanswerable right now. Look again next drain. */
    data object Pending : ReconcileResult

    /** Given up for good — the item moves to `failed/`. */
    data class Failed(val why: String) : ReconcileResult
}

/**
 * What one drain pass did, handed to the orchestrator's `onDrainFinished`.
 * The worker owns no retry timer; whether and when to try again is decided
 * from this report.
 */
data class DrainReport(
    val completed: Int,
    /** The item blocking the head, with the transient reason, or null. */
    val blockedOn: String?,
    /** Items moved to `failed/` during this pass. */
    val newlyFailed: Int,
)
