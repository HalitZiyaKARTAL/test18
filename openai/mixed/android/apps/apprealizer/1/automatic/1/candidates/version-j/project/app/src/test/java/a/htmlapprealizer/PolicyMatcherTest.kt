package a.htmlapprealizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyMatcherTest {
    private val request = BridgeRequest(
        engine = "java",
        operation = "method",
        className = "android.content.Intent",
        member = "putExtra",
        signature = "public android.content.Intent android.content.Intent.putExtra(java.lang.String,int)"
    )

    @Test fun blacklistWinsOverWhitelist() {
        assertEquals(
            RuleDecision.BLACK,
            PolicyMatcher.decide(
                request,
                black = setOf("member:android.content.intent#putextra"),
                gray = emptySet(),
                white = setOf("class:android.content.intent")
            )
        )
    }

    @Test fun packageRuleHonorsBoundaries() {
        assertTrue(PolicyMatcher.matches("package:android.content", request))
        assertFalse(PolicyMatcher.matches("package:android.con", request))
        assertFalse(PolicyMatcher.matches("package:ndroid.content", request))
    }

    @Test fun unmatchedAndGrayBothPromptButRemainDistinct() {
        assertEquals(
            RuleDecision.GRAY,
            PolicyMatcher.decide(request, emptySet(), setOf("class:android.content.intent"), emptySet())
        )
        assertEquals(
            RuleDecision.UNMATCHED,
            PolicyMatcher.decide(request, emptySet(), emptySet(), emptySet())
        )
    }

    @Test fun unscopedRulesAreExplicitLegacySubstringRules() {
        assertEquals("legacy:putextra", PolicyMatcher.normalizeRule("putExtra"))
        assertTrue(PolicyMatcher.matches("legacy:putextra", request))
    }
}
