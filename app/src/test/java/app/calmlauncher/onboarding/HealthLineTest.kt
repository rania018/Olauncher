package app.calmlauncher.onboarding

import app.calmlauncher.data.SettingsStore.HealthNotice
import app.calmlauncher.onboarding.HealthLine.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T025: permission statuses -> the single home health line. */
class HealthLineTest {

    private val now = 1_700_000_000_000L
    private val all = PermissionStatus(
        notifications = true, overlay = true, usageAccess = true, exactAlarms = true, battery = true, accessibility = true,
    )
    private val none = PermissionStatus(
        notifications = false, overlay = false, usageAccess = false, exactAlarms = false, battery = false, accessibility = false,
    )

    private fun pick(
        status: PermissionStatus = all,
        notice: HealthNotice? = null,
        loopGuardUntil: Long? = null,
        trackingInUse: Boolean = true,
    ) = HealthLine.pick(status, notice, loopGuardUntil, trackingInUse, now)

    @Test
    fun nothingToSayWhenEverythingIsGranted() {
        assertEquals(null, pick())
    }

    @Test
    fun eachMissingPermissionNamesItsReduction() {
        assertEquals(Item.NOTIFICATIONS, pick(all.copy(notifications = false)))
        assertEquals(Item.OVERLAY, pick(all.copy(overlay = false)))
        assertEquals(Item.USAGE_ACCESS, pick(all.copy(usageAccess = false)))
        assertEquals(Item.BATTERY, pick(all.copy(battery = false)))
    }

    /** T099: with the app-limits toggle off, only opens from this launcher are limited (FR-023, FR-031). */
    @Test
    fun theAppLimitsToggleBeingOffIsNamed() {
        assertEquals(Item.ACCESSIBILITY, pick(all.copy(accessibility = false)))
        assertEquals(OnboardingPage.ACCESSIBILITY, Item.ACCESSIBILITY.page)
        assertEquals(false, all.copy(accessibility = false).isGranted(OnboardingPage.ACCESSIBILITY))
        assertEquals(true, all.isGranted(OnboardingPage.ACCESSIBILITY))
    }

    @Test
    fun theAppLimitsLineComesAfterTheOverlayLine() {
        assertEquals(Item.OVERLAY, pick(all.copy(overlay = false, accessibility = false)))
        assertEquals(Item.ACCESSIBILITY, pick(all.copy(accessibility = false, usageAccess = false, battery = false)))
    }

    @Test
    fun theAppLimitsPageComesBeforeTheExemptApps() {
        val pages = OnboardingPage.entries
        assertEquals(pages.indexOf(OnboardingPage.BATTERY) + 1, pages.indexOf(OnboardingPage.ACCESSIBILITY))
        assertEquals(OnboardingPage.EXEMPT_APPS, pages.last())
    }

    @Test
    fun exactAlarmsAreOptionalAndNeverShown() {
        assertEquals(null, pick(all.copy(exactAlarms = false)))
    }

    @Test
    fun onlyOneLineMostImportantFirst() {
        assertEquals(Item.NOTIFICATIONS, pick(none))
        assertEquals(Item.OVERLAY, pick(none.copy(notifications = true)))
        assertEquals(Item.ACCESSIBILITY, pick(none.copy(notifications = true, overlay = true)))
        assertEquals(Item.USAGE_ACCESS, pick(none.copy(notifications = true, overlay = true, accessibility = true)))
    }

    @Test
    fun permissionsDoNotNagBeforeAnythingIsTracked() {
        assertEquals(null, pick(none, trackingInUse = false))
    }

    @Test
    fun interruptedTimerNoticeComesFirstAndFades() {
        val fresh = HealthNotice(HealthLine.NOTICE_INTERRUPTED, now - 60_000L)
        assertEquals(Item.INTERRUPTED, pick(all.copy(notifications = false), notice = fresh))
        assertEquals("Timer was interrupted, check battery settings", Item.INTERRUPTED.text)
        assertTrue(Item.INTERRUPTED.clearsNotice)
        val old = HealthNotice(HealthLine.NOTICE_INTERRUPTED, now - HealthLine.NOTICE_MAX_AGE_MS)
        assertEquals(null, pick(notice = old))
    }

    @Test
    fun loopGuardNoticeShowsWhilePaused() {
        assertEquals(Item.LOOP_GUARD, pick(loopGuardUntil = now + 1))
        assertEquals(null, pick(loopGuardUntil = now))
    }

    @Test
    fun linesAreShortAndCalm() {
        Item.entries.forEach { item ->
            assertTrue(item.text, item.text.length <= 70)
            assertTrue(item.text, !item.text.contains('!'))
        }
    }
}
