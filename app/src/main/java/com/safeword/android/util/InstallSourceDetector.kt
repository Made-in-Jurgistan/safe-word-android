package com.safeword.android.util

import android.content.Context

/**
 * Detects whether the app was installed from a trusted store (Play Store, Samsung Galaxy Store, etc.)
 * vs. sideloaded. On sideloaded installs, Android 13+ blocks overlay and accessibility service
 * toggles behind "Restricted Settings" until the user explicitly allows them.
 */
object InstallSourceDetector {

    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",              // Google Play Store
        "com.sec.android.app.samsungapps",  // Samsung Galaxy Store
        "com.amazon.venezia",               // Amazon Appstore
        "com.huawei.appmarket",             // Huawei AppGallery
        "com.xiaomi.mipicks",               // Xiaomi GetApps
        "com.heytap.market",                // OPPO / OnePlus App Market
        "com.bbk.appstore",                 // Vivo App Store
        "org.fdroid.fdroid",                // F-Droid (OSS distribution)
        "org.fdroid.basic",                 // F-Droid Basic
        "app.accrescent.client",            // Accrescent (reproducible-build store)
    )

    fun isInstalledFromTrustedSource(context: Context): Boolean {
        // getInstallSourceInfo is always available — minSdk 33 >= API 30 (R) requirement
        val installer = runCatching {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        }.getOrNull()
        return installer in TRUSTED_INSTALLERS
    }

    fun isSideloaded(context: Context): Boolean = !isInstalledFromTrustedSource(context)
}
