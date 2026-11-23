package app.calmlauncher.engine

import app.calmlauncher.engine.ForegroundFilter.Result.Ignore
import app.calmlauncher.engine.ForegroundFilter.Result.InFront
import app.calmlauncher.engine.ForegroundFilter.Result.Own
import app.calmlauncher.engine.Fx.T0
import app.calmlauncher.engine.Fx.sec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T086: which accessibility window events count as "this app came to the front" (research R14,
 * R15). Package and class names are the ones the Samsung phone sent in the T084 device check.
 */
class ForegroundFilterTest {

    private val own = "app.calmlauncher.debug"
    private val instagram = "com.instagram.android"
    private val facebook = "com.facebook.katana"
    private val keyboard = "com.samsung.android.honeyboard"
    private val samsungLauncher = "com.sec.android.app.launcher"

    private fun filter() = ForegroundFilter(
        ownPkg = own,
        ignoredPkgs = setOf("com.android.systemui", keyboard),
        ignoredClasses = setOf("com.android.quickstep.RecentsActivity"),
    )

    private fun ForegroundFilter.activity(pkg: String?, now: Long = T0, cls: String = "$pkg.MainActivity") =
        accept(pkg, cls, isActivity = true, now = now)

    @Test
    fun theFirstActivityOfAnAppIsInFront() {
        assertEquals(InFront(instagram), filter().activity(instagram))
    }

    @Test
    fun windowsThatAreNotActivitiesAreIgnored() {
        val f = filter()
        // splash windows, dialogs, pop-ups (T084: FrameLayout, ImageView, LinearLayout)
        assertEquals(Ignore, f.accept("com.facebook.orca", "android.widget.FrameLayout", isActivity = false, now = T0))
        assertEquals(Ignore, f.accept("com.samsung.android.forest", "android.widget.LinearLayout", isActivity = false, now = T0))
        assertEquals(null, f.inFront)
    }

    @Test
    fun theNotificationShadeAndKeyboardsAreIgnoredEvenIfTheyLookLikeActivities() {
        val f = filter()
        f.activity(instagram)
        assertEquals(Ignore, f.activity("com.android.systemui"))
        assertEquals(Ignore, f.activity(keyboard, cls = "android.inputmethodservice.SoftInputWindow"))
        assertEquals(instagram, f.inFront)
    }

    @Test
    fun theRecentAppsScreenIsIgnoredSoPassingThroughItIsNotLeaving() {
        val f = filter()
        f.activity(instagram)
        assertEquals(Ignore, f.activity(samsungLauncher, cls = "com.android.quickstep.RecentsActivity"))
        assertEquals(instagram, f.inFront)
    }

    @Test
    fun moreWindowsOfTheSameAppAreIgnored() {
        val f = filter()
        f.activity(instagram, cls = "com.instagram.mainactivity.InstagramMainActivity")
        // the duplicate event, then in-app navigation
        assertEquals(Ignore, f.activity(instagram, cls = "com.instagram.mainactivity.InstagramMainActivity"))
        assertEquals(Ignore, f.activity(instagram, cls = "com.instagram.modal.ModalActivity"))
    }

    @Test
    fun ourOwnScreensAreReportedAsOwnAndRemembered() {
        val f = filter()
        f.activity(instagram)
        assertEquals(Own, f.activity(own, cls = "app.calmlauncher.prompt.PromptActivity"))
        assertEquals(own, f.inFront)
        // back to the same app after our prompt or launcher: that is a change
        assertEquals(InFront(instagram), f.activity(instagram))
    }

    @Test
    fun switchingToAnotherAppIsReported() {
        val f = filter()
        f.activity("com.facebook.orca")
        assertEquals(InFront(facebook), f.activity(facebook, cls = "com.facebook.deeplinking.activity.StoryDeepLinkLoadingActivity"))
        assertEquals(facebook, f.inFront)
    }

    @Test
    fun aMissingPackageIsIgnored() {
        assertEquals(Ignore, filter().accept(null, "x", isActivity = true, now = T0))
        assertEquals(Ignore, filter().accept("", "x", isActivity = true, now = T0))
    }

    @Test
    fun afterACancelledPromptTheAppIsIgnoredForThreeSeconds() {
        val f = filter()
        f.activity(instagram)
        f.activity(own, now = T0 + sec(1))                     // our prompt
        f.noteCancelled(instagram, now = T0 + sec(2))          // Cancel: on the way home
        assertEquals(Ignore, f.activity(instagram, now = T0 + sec(3)))
        assertEquals(own, f.inFront)                           // a quiet event does not count as in front
        assertEquals(InFront(instagram), f.activity(instagram, now = T0 + sec(5)))
    }

    @Test
    fun theQuietTimeAfterACancelOnlyAppliesToThatApp() {
        val f = filter()
        f.noteCancelled(instagram, now = T0)
        assertEquals(InFront(facebook), f.activity(facebook, now = T0 + sec(1)))
    }
}
