package com.flashskip.core

/**
 * Package-level safety exclusions and transient window classification.
 * Ensures banking/payment/system apps are never touched, and transient overlays
 * (IMEs, system dialogs, permission prompts) never reset active app windows.
 */
object PackagePolicy {

    val SYSTEM_PREFIXES: List<String> = listOf(
        "android",
        "com.android.",
        "com.hihonor.",
        "com.huawei.",
        "com.miui.",
        "com.xiaomi.",
        "com.oplus.",
        "com.coloros.",
        "com.heytap.",
        "com.oppo.",
        "com.vivo.",
        "com.iqoo.",
        "com.bbk.",
        "com.meizu.",
        "com.flyme.",
        "com.samsung.android.",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.inputmethod."
    )

    val IME_PACKAGES: Set<String> = setOf(
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.iflytek.inputmethod",
        "com.tencent.qqpinyin",
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey"
    )

    /**
     * Sensitive financial, banking, and payment packages excluded by default
     * to prevent misclicks and accessibility-detection conflicts.
     */
    val SENSITIVE_FINANCE_PACKAGES: Set<String> = setOf(
        "com.eg.android.AlipayGphone",   // Alipay
        "com.tencent.mm",                // WeChat (also holds wallet/payments)
        "com.unionpay",                  // UnionPay
        "com.icbc",                      // ICBC
        "com.chinamworld.main",          // CCB
        "com.android.bankabc",           // ABC
        "com.chinamworld.bocmbci",       // BOC
        "cmb.pb",                        // CMB
        "com.bankcomm.Bankcomm",         // BoCom
        "com.yitong.mbank.psbc",         // PSBC
        "com.pingan.paces.ccms",         // Ping An
        "com.ecitic.bank.mobile",        // CITIC
        "cn.com.spdb.mobilebank.per",    // SPDB
        "com.cmbc.cc.mbank",             // CMBC
        "com.cib.cibmb",                 // CIB
        "com.cebbank.mobile.cemb",       // CEB
        "com.hxb.mobile.client",         // HXB
        "com.jd.jrapp",                  // JD Finance
        "com.eastmoney.android.berlin"   // Eastmoney
    )

    private val TRANSIENT_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    ) + IME_PACKAGES

    private val TRANSIENT_CLASS_KEYWORDS: List<String> = listOf(
        "alertdialog",
        "popupwindow",
        "bottomsheetdialog",
        "toast",
        "snackbar",
        "inputmethod",
        "softinputwindow",
        "volumedialog"
    )

    fun isSystemPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == "android") return true
        return SYSTEM_PREFIXES.any { prefix ->
            if (prefix.endsWith(".")) pkg.startsWith(prefix) else pkg == prefix
        }
    }

    fun isFinancePackage(pkg: String): Boolean = pkg in SENSITIVE_FINANCE_PACKAGES

    fun isInputMethodPackage(pkg: String): Boolean =
        pkg in IME_PACKAGES || pkg.contains("inputmethod", ignoreCase = true)

    /**
     * Returns true if [pkg] must never be scanned or clicked.
     */
    fun isExcludedPackage(
        pkg: String,
        userDisabledPackages: Set<String> = emptySet(),
        userAllowedPackages: Set<String> = emptySet()
    ): Boolean {
        if (pkg.isBlank()) return true
        if (pkg in userDisabledPackages) return true
        if (isSystemPackage(pkg) || isInputMethodPackage(pkg)) return true
        if (pkg in SENSITIVE_FINANCE_PACKAGES && pkg !in userAllowedPackages) return true
        return false
    }

    /**
     * Returns true when a window change event comes from an overlay/IME/dialog
     * that should NOT reset the underlying app's launch window or attempt count.
     */
    fun isTransientOverlay(pkg: String, className: String, isFullScreen: Boolean): Boolean {
        if (pkg in TRANSIENT_PACKAGES || isInputMethodPackage(pkg)) return true
        val clsLower = className.lowercase()
        if (TRANSIENT_CLASS_KEYWORDS.any { clsLower.contains(it) }) return true
        if (!isFullScreen && clsLower.contains("dialog")) return true
        return false
    }
}
