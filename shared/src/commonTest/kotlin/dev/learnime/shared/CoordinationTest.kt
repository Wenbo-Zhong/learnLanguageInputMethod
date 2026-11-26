package dev.learnime.shared

import kotlin.test.*

class CoordinationTest {
    @Test fun handwritingBoundaryDoesNotWaitForMarking() {
        val state = HandwritingSession()
        state.beginStroke()
        val request = state.beginRecognition(true)
        assertTrue(state.accept(request, "学"))
        assertEquals(HandwritingPhase.READY, state.phase)
        assertEquals("", state.marked)
        assertEquals("学", state.finish())
        assertFalse(state.mark(request))
        assertFalse(state.hasText)
    }

    @Test fun handwritingOnlyConfirmsMarkedWordOnNextStroke() {
        val state = HandwritingSession()
        var request = state.beginRecognition(true)
        state.accept(request, "学")
        assertEquals("", state.beginStroke())
        assertFalse(state.accept(request, "旧"))
        request = state.beginRecognition(true)
        state.accept(request, "习")
        assertTrue(state.mark(request))
        assertEquals("习", state.beginStroke())
        assertEquals(HandwritingPhase.DRAWING, state.phase)
    }

    @Test fun rebuildingHandwritingKeepsReadyTextButInvalidatesCallbacks() {
        val state = HandwritingSession()
        val request = state.beginRecognition(true)
        state.accept(request, "文")
        state.invalidateRequests()
        assertFalse(state.accept(request, "旧"))
        assertEquals("文", state.finish())
    }

    @Test fun translationIdentityIncludesDirectionModelAndLifecycle() {
        val policy = TranslationPolicy()
        val first = policy.update("学习", true, TranslationDirection.CHINESE_TO_ENGLISH, "M2M100", false)!!
        assertNull(policy.update("学习", true, TranslationDirection.CHINESE_TO_ENGLISH, "m2m100", false))
        val second = policy.update("学习", true, TranslationDirection.CHINESE_TO_ENGLISH, "OPUS_TINY", false)!!
        assertFalse(policy.isCurrent(first))
        assertTrue(policy.isCurrent(second))
        policy.reset()
        assertFalse(policy.isCurrent(second))
        assertNotNull(policy.update("学习", true, TranslationDirection.CHINESE_TO_ENGLISH, "opus", false))
    }

    @Test fun translationDelayAndModelCapabilityFollowAndroidRules() {
        val policy = TranslationPolicy()
        assertEquals(700L, policy.update("学习", true, TranslationDirection.CHINESE_TO_ENGLISH, "opus", false)!!.delayMillis)
        assertEquals(150L, policy.update("学习。", true, TranslationDirection.CHINESE_TO_ENGLISH, "opus", false)!!.delayMillis)
        assertEquals("", policy.update("Learn", true, TranslationDirection.ENGLISH_TO_CHINESE, "opus", false)!!.source)
        val reverse = policy.update("Learn", true, TranslationDirection.ENGLISH_TO_CHINESE, "opus", true)!!
        assertEquals("m2m100", reverse.model)
        assertEquals("Learn", reverse.source)
    }

    @Test fun emptyTranslationRefreshesWhenEnabledStateChanges() {
        val policy = TranslationPolicy()
        val disabled = policy.update("", false, TranslationDirection.CHINESE_TO_ENGLISH, "m2m100", false)!!
        val enabled = policy.update("", true, TranslationDirection.CHINESE_TO_ENGLISH, "m2m100", false)!!
        assertFalse(policy.isCurrent(disabled))
        assertTrue(enabled.enabled)
        assertEquals("", enabled.source)
        assertNull(policy.update("", true, TranslationDirection.CHINESE_TO_ENGLISH, "m2m100", false))
        assertNotNull(policy.update("", false, TranslationDirection.CHINESE_TO_ENGLISH, "m2m100", false))
    }

    @Test fun candidateIdentityRejectsEditsAndNewRequests() {
        val session = KeyboardSession()
        val coordinator = CandidateCoordinator()
        session.key("n")
        val request = coordinator.snapshot(session)
        val candidate = PinyinCandidate("你", "ni", 1)
        session.applyCandidates(request.input, request.mode, listOf(candidate), request.revision)
        assertTrue(coordinator.canChoose(request, session, candidate, 0))
        assertFalse(coordinator.canChoose(request, session, candidate.copy(text = "拟"), 0))
        assertFalse(coordinator.canChoose(request, session, candidate, 1))
        session.key("i")
        assertFalse(coordinator.canChoose(request, session, candidate, 0))
        val next = coordinator.snapshot(session)
        coordinator.invalidate()
        assertFalse(coordinator.isCurrent(next, session))
    }

    @Test fun boundariesPreserveUnknownAndCorrectedComposition() {
        val session = KeyboardSession()
        val coordinator = CandidateCoordinator()
        assertEquals(BoundaryResolution.DIRECT, coordinator.boundary(session, CompositionBoundary.SPACE))
        session.key("n"); session.key("i")
        assertEquals(BoundaryResolution.PRESERVE, coordinator.boundary(session, CompositionBoundary.SPACE))
        session.applyCandidates("ni", session.mode, listOf(PinyinCandidate("你", "ni", 2, correction = "纠正")), session.revision)
        assertEquals(BoundaryResolution.SELECT_FIRST, coordinator.boundary(session, CompositionBoundary.SPACE))
        assertEquals(BoundaryResolution.PRESERVE, coordinator.boundary(session, CompositionBoundary.LITERAL))
        session.applyCandidates("ni", session.mode, listOf(PinyinCandidate("你", "ni", 2)), session.revision)
        assertEquals(BoundaryResolution.SELECT_FIRST, coordinator.boundary(session, CompositionBoundary.LITERAL))
    }

    @Test fun settingsKeepPlatformDefaultsAndRejectUnknownChoices() {
        val android = KeyboardSettings.all(KeyboardPlatform.ANDROID)
        val ios = KeyboardSettings.all(KeyboardPlatform.IOS)
        assertEquals(android.size, android.map { it.key }.distinct().size)
        assertEquals(ios.size, ios.map { it.key }.distinct().size)
        assertEquals(54, KeyboardSettings.get("keyHeight", KeyboardPlatform.ANDROID).numberDefault)
        assertEquals(44, KeyboardSettings.get("keyHeight", KeyboardPlatform.IOS).numberDefault)
        assertEquals(68, KeyboardSettings.get("keyHeight", KeyboardPlatform.IOS).validateNumber(100))
        assertEquals("system", KeyboardSettings.get("theme", KeyboardPlatform.IOS).validateChoice("unknown"))
        assertFalse(ios.any { it.key == "vibrationMs" })
        assertFalse(android.any { it.key == "gpuTranslation" })
    }
}
