package app.calmlauncher.engine

import app.calmlauncher.engine.Decision.Allow
import app.calmlauncher.engine.Decision.Blocked
import app.calmlauncher.engine.Fx.CFG
import app.calmlauncher.engine.Fx.DIALER
import app.calmlauncher.engine.Fx.INSTAGRAM
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** contracts/engine-api.md invariants 1-3, plus the "exception in reduce" row of the state table. */
class InvariantsTest {

    // Invariant 1: exempt always wins, whatever the other inputs.
    @Test
    fun exemptAlwaysWins() {
        val keys = listOf(DIALER, DIALER.copy(userSerial = 10))
        val trackedOptions = listOf(
            emptyMap(),
            Fx.tracked(DIALER, DIALER.copy(userSerial = 10)),
            Fx.tracked(DIALER, cooldownMin = 60),
        )
        val histories = listOf(
            emptyList(),
            listOf(Fx.active(DIALER, start = T0 - min(1), minutes = 5)),
            listOf(Fx.ended(DIALER, endedAt = T0 - min(1), reason = EndReason.EXPIRED)),
            listOf(Fx.active(DIALER, start = T0 - min(20), minutes = 5)),
            Fx.explodingList(),
        )
        val exemptionOptions = listOf(
            Exemptions(defaults = setOf(DIALER.pkg)),
            Exemptions(user = setOf(DIALER.pkg)),
            Exemptions(defaults = setOf(DIALER.pkg), user = setOf(DIALER.pkg, INSTAGRAM.pkg)),
        )
        val configs = listOf(CFG, Config(overrideWaitSec = 0, customMaxMin = -5), Config(maxExtensions = 99))
        val times = listOf(T0, T0 + min(3), T0 + min(30), 0L, Long.MAX_VALUE)

        for (key in keys) for (tracked in trackedOptions + listOf(Fx.explodingMap())) for (history in histories)
            for (ex in exemptionOptions) for (cfg in configs) for (now in times) {
                assertEquals(
                    "key=$key now=$now cfg=$cfg",
                    Allow(AllowReason.EXEMPT),
                    RulesEngine.decide(key, now, tracked, ex, history, cfg),
                )
            }
    }

    // Invariant 2: decide never throws; malformed input means Allow(FAIL_OPEN).
    @Test
    fun corruptHistoryFailsOpen() {
        val decision = RulesEngine.decide(INSTAGRAM, T0, Fx.tracked(INSTAGRAM), Fx.NO_EXEMPTIONS, Fx.explodingList(), CFG)
        assertEquals(Allow(AllowReason.FAIL_OPEN), decision)
    }

    @Test
    fun corruptTrackedMapFailsOpen() {
        val decision = RulesEngine.decide(INSTAGRAM, T0, Fx.explodingMap(), Fx.NO_EXEMPTIONS, emptyList(), CFG)
        assertEquals(Allow(AllowReason.FAIL_OPEN), decision)
    }

    @Test
    fun strangeButReadableHistoryNeverThrows() {
        val weird = listOf(
            SessionRecord(key = INSTAGRAM, startedAt = T0, declaredMs = -5, deadlineAt = T0 - min(10)),
            SessionRecord(key = INSTAGRAM, startedAt = T0, declaredMs = 0, deadlineAt = 0, endedAt = T0 - min(100), endReason = EndReason.EXPIRED),
            SessionRecord(key = INSTAGRAM, startedAt = Long.MAX_VALUE, declaredMs = Long.MAX_VALUE, deadlineAt = Long.MIN_VALUE),
            SessionRecord(key = INSTAGRAM, startedAt = T0, declaredMs = min(5), deadlineAt = T0 + min(5), endedAt = T0, endReason = null),
            SessionRecord(key = INSTAGRAM, startedAt = T0, declaredMs = min(5), deadlineAt = T0 + min(5), endedAt = Long.MAX_VALUE, endReason = EndReason.EXPIRED),
        )
        for (now in listOf(0L, T0, T0 + min(1000), Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertNotNull(RulesEngine.decide(INSTAGRAM, now, Fx.tracked(INSTAGRAM), Fx.NO_EXEMPTIONS, weird, CFG))
        }
    }

    // Invariant 3: every Blocked is overridable.
    @Test
    fun everyBlockIsOverridable() {
        BlockKind.entries.forEach { kind -> assertTrue(Blocked(kind, T0).overridable) }
        val history = listOf(Fx.ended(INSTAGRAM, endedAt = T0 - min(2), reason = EndReason.EXPIRED))
        val decision = RulesEngine.decide(INSTAGRAM, T0, Fx.tracked(INSTAGRAM), Fx.NO_EXEMPTIONS, history, CFG)
        assertTrue("expected a cooldown block, got $decision", decision is Blocked)
        assertTrue((decision as Blocked).overridable)
    }

    // data-model "State transitions": exception in reduce -> state unchanged, Log(ERROR) only.
    @Test
    fun reduceNeverThrowsAndKeepsTheState() {
        val corruptConfig = Config(extensionOptionsMin = Fx.explodingList())
        val states = listOf(
            SessionState.Idle,
            SessionState.Running(Fx.active(), 10),
            SessionState.Expired(Fx.active(), 10, cardShown = true),
        )
        val events = listOf(
            SessionEvent.Tick,
            SessionEvent.DurationChosen(5),
            SessionEvent.Extended(2),
            SessionEvent.Reconcile(),
        )
        for (state in states) for (event in events) {
            val t = SessionMachine.reduce(state, event, T0 + min(10), corruptConfig)
            assertEquals(state, t.state)
            assertEquals(1, t.effects.size)
            val log = t.effects.single() as Effect.Log
            assertEquals(EventType.ERROR, log.type)
        }
    }
}
