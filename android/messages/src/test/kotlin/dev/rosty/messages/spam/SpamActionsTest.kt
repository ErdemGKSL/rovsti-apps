package dev.rosty.messages.spam

import dev.rosty.core.RustError
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.messages.data.MessagesStore
import dev.rosty.platform.calllog.FakeBlocklistRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E6.11: the spam changes go through the Rust ops and land in `messages-prefs.json`'s `spam` field. */
class SpamActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private class Rig(
        val core: SpamCore,
        val store: MessagesStore,
        val blocklist: FakeBlocklistRepository,
        val actions: SpamActions,
    ) {
        val spam: SpamRules get() = store.prefs.value!!.spam
    }

    private fun TestScope.rig(
        spam: SpamRules = SpamRules(rules = listOf(SpamRule.UnknownWithLink, SpamRule.Keyword("win"))),
        allowed: Boolean = true,
    ): Rig {
        val core = SpamCore(prefsWith(spam))
        val store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0)
        val blocklist = FakeBlocklistRepository(allowed = allowed)
        return Rig(core, store, blocklist, SpamActions(store, core.ops, blocklist))
    }

    @Test fun aNewRuleIsAddedAtTheEndAndSaved() =
        scope.runTest {
            val r = rig()
            assertNull(r.actions.saveRule(null, RuleKind.Sender.build("  PROMO* ")))
            assertEquals(SpamRule.Sender("PROMO*"), r.spam.rules.last())
            assertTrue(r.store.flush())
            assertEquals(r.spam, r.core.saved?.spam)
        }

    @Test fun refusedRulesSayWhy() =
        scope.runTest {
            val r = rig()
            assertEquals(RuleProblem.Duplicate, r.actions.saveRule(null, SpamRule.Keyword("WIN")))
            assertEquals(RuleProblem.Empty, r.actions.saveRule(null, SpamRule.Sender(" * ")))
            val full = rig(SpamRules(rules = (1..SpamCore.MAX).map { SpamRule.Keyword("w$it") }))
            assertEquals(RuleProblem.TooMany, full.actions.saveRule(null, SpamRule.Keyword("new")))
            assertEquals(2, r.spam.rules.size)
        }

    @Test fun anEditKeepsItsPlaceAndMayKeepItsText() =
        scope.runTest {
            val r = rig(SpamRules(rules = listOf(SpamRule.Keyword("a"), SpamRule.Keyword("b"), SpamRule.Keyword("c"))))
            assertNull(r.actions.saveRule(1, SpamRule.Sender("BANKA")))
            assertEquals(listOf(SpamRule.Keyword("a"), SpamRule.Sender("BANKA"), SpamRule.Keyword("c")), r.spam.rules)
            // Saving a rule unchanged is not a duplicate of itself.
            assertNull(r.actions.saveRule(1, SpamRule.Sender("BANKA")))
            // But it may not become a copy of another one.
            assertEquals(RuleProblem.Duplicate, r.actions.saveRule(1, SpamRule.Keyword("C")))
            assertEquals(SpamRule.Sender("BANKA"), r.spam.rules[1])
        }

    @Test fun aDeletedRuleComesBackInItsPlace() =
        scope.runTest {
            val r = rig()
            val removed = r.actions.deleteRule(0)
            assertEquals(SpamRule.UnknownWithLink, removed)
            assertEquals(listOf(SpamRule.Keyword("win")), r.spam.rules)
            assertTrue(r.actions.restoreRule(0, removed!!))
            assertEquals(listOf(SpamRule.UnknownWithLink, SpamRule.Keyword("win")), r.spam.rules)
            assertNull(r.actions.deleteRule(9))
        }

    @Test fun theAllowListAddsOnceAndRemovesByPlace() =
        scope.runTest {
            val r = rig()
            assertTrue(r.actions.allow("BANK"))
            assertFalse(r.actions.allow("bank"))
            assertFalse(r.actions.allow("  "))
            assertTrue(r.actions.allow("+90 555 000 00 01"))
            assertEquals("BANK", r.actions.removeAllowed(0))
            assertEquals(listOf("+90 555 000 00 01"), r.spam.allow)
            assertNull(r.actions.removeAllowed(5))
        }

    @Test fun moveToSpamAndItsUndo() =
        scope.runTest {
            val r = rig(SpamRules(allow = listOf("PROMO")))
            val outcome = r.actions.markSpam("PROMO") as MarkOutcome.Marked
            assertTrue(outcome.mark.ruleAdded && outcome.mark.wasAllowed)
            assertEquals(listOf(SpamRule.Sender("PROMO")), r.spam.rules)
            assertTrue(r.spam.allow.isEmpty())
            r.actions.unmarkSpam("PROMO", outcome.mark)
            assertTrue(r.spam.rules.isEmpty())
            assertEquals(listOf("PROMO"), r.spam.allow)
            val full = rig(SpamRules(rules = (1..SpamCore.MAX).map { SpamRule.Keyword("w$it") }))
            assertEquals(MarkOutcome.Full, full.actions.markSpam("PROMO"))
        }

    @Test fun theSwitchTurnsTheFilterOff() =
        scope.runTest {
            val r = rig()
            r.actions.setEnabled(false)
            assertFalse(r.spam.enabled)
        }

    @Test fun blockingAsksTheSystemBlocklist() =
        scope.runTest {
            val r = rig()
            assertEquals(BlockOutcome.Done, r.actions.block("+90 555 000 00 02"))
            assertTrue(r.blocklist.isBlocked("+905550000002"))
            assertEquals(BlockOutcome.Refused, r.actions.block("112"))
            assertEquals(BlockOutcome.Done, r.actions.unblock("+90 555 000 00 02"))
            val off = rig(allowed = false)
            assertEquals(BlockOutcome.Unavailable, off.actions.block("+90 555 000 00 02"))
            assertFalse(off.actions.canBlock())
        }

    @Test fun ruleErrorsAreReadFromTheRustSentence() {
        assertEquals(RuleProblem.Duplicate, SpamActions.problemOf(RustError.Invalid("the rule already exists")))
        assertEquals(RuleProblem.TooMany, SpamActions.problemOf(RustError.Invalid("at most 64 rules are allowed")))
        assertEquals(RuleProblem.Empty, SpamActions.problemOf(RustError.Invalid("the rule is empty")))
        assertEquals(RuleProblem.Failed, SpamActions.problemOf(RustError.Io("disk")))
    }
}
