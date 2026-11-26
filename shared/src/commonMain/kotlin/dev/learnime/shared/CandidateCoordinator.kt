package dev.learnime.shared

data class CandidateRequest(
    val generation: Long,
    val revision: Long,
    val input: String,
    val mode: KeyboardMode,
    val locks: String,
    val corrections: Boolean,
)

enum class CompositionBoundary { SPACE, ENTER, LITERAL }
enum class BoundaryResolution { DIRECT, SELECT_FIRST, PRESERVE }

/** Platform adapters schedule work; this class owns request identity and boundary rules. */
class CandidateCoordinator {
    var generation: Long = 0; private set
    fun invalidate() { generation++ }
    fun snapshot(session: KeyboardSession): CandidateRequest = CandidateRequest(
        generation, session.revision, session.composition, session.mode,
        session.lockSpecification, session.autoCorrection)

    fun isCurrent(request: CandidateRequest, session: KeyboardSession): Boolean =
        request.generation == generation && request.revision == session.revision &&
            request.input == session.composition && request.mode == session.mode &&
            request.locks == session.lockSpecification && request.corrections == session.autoCorrection

    fun canChoose(request: CandidateRequest, session: KeyboardSession, candidate: PinyinCandidate, index: Int): Boolean =
        isCurrent(request, session) && session.candidates.getOrNull(index) == candidate

    fun boundary(session: KeyboardSession, boundary: CompositionBoundary): BoundaryResolution {
        if (session.composition.isEmpty()) return BoundaryResolution.DIRECT
        val first = session.candidates.firstOrNull() ?: return BoundaryResolution.PRESERVE
        return if (boundary != CompositionBoundary.LITERAL ||
            (first.consumed == session.composition.length && first.correction.isEmpty()))
            BoundaryResolution.SELECT_FIRST else BoundaryResolution.PRESERVE
    }
}
