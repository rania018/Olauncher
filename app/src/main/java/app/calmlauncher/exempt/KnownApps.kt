package app.calmlauncher.exempt

import app.calmlauncher.data.ExemptCategory

/**
 * Package names of common messaging, banking/payment, authenticator and transport apps. Onboarding
 * suggests the ones that are installed as exemptions; the user confirms each one (spec FR-017).
 * This is only a suggestion list: any app can be made exempt in Screen time settings.
 */
object KnownApps {

    val suggestions: Map<String, ExemptCategory> = buildMap {
        listOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.viber.voip", "com.microsoft.teams", "com.Slack",
            "ch.threema.app", "im.vector.app", "com.wire", "jp.naver.line.android", "com.tencent.mm",
            "com.kakao.talk",
        ).forEach { put(it, ExemptCategory.MESSAGING) }

        listOf(
            "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal.android.p2pmobile",
            "com.revolut.revolut", "com.transferwise.android", "de.number26.android", "com.bunq.android",
            "com.monzo.android", "com.starlingbank.android", "com.chase.sig.android", "com.wf.wellsfargomobile",
            "com.infonow.bofa", "com.squareup.cash", "com.venmo", "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm", "com.phonepe.app", "com.klarna.android", "com.ing.mobile", "com.db.pwcc.dbmobile",
        ).forEach { put(it, ExemptCategory.BANKING) }

        listOf(
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
            "com.duosecurity.duomobile", "org.fedorahosted.freeotp", "com.beemdevelopment.aegis",
            "com.x8bit.bitwarden", "com.okta.android.auth", "com.lastpass.authenticator",
            "com.yubico.yubioath", "proton.android.pass", "com.agilebits.onepassword",
        ).forEach { put(it, ExemptCategory.AUTH) }

        listOf(
            "com.ubercab", "ee.mtakso.client", "com.citymapper.app.release",
            "com.tranzmate", "de.hafas.android.db", "com.thetrainline", "com.waze", "com.grabtaxi.passenger",
            "com.olacabs.customer", "me.lyft.android", "com.google.android.apps.maps", "com.here.app.maps",
            "net.osmand", "net.osmand.plus", "app.organicmaps",
        ).forEach { put(it, ExemptCategory.TRANSPORT) }
    }

    /** The suggestions that are installed, given the installed package names. */
    fun installed(installedPackages: Set<String>): List<Pair<String, ExemptCategory>> =
        suggestions.filterKeys { it in installedPackages }.toList()
}
