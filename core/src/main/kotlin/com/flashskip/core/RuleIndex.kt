package com.flashskip.core

/**
 * O(1) package-indexed repository of custom rules.
 */
class RuleIndex(rules: List<AppRule> = emptyList()) {

    private val byPackage: Map<String, List<AppRule>>
    private val globalRules: List<AppRule>

    init {
        val grouped = HashMap<String, MutableList<AppRule>>()
        val globals = mutableListOf<AppRule>()
        for (rule in rules) {
            if (rule.packageName == "*") {
                globals.add(rule)
            } else {
                grouped.getOrPut(rule.packageName) { mutableListOf() }.add(rule)
            }
        }
        byPackage = grouped
        globalRules = globals
    }

    fun rulesFor(packageName: String): List<AppRule> {
        val pkgSpecific = byPackage[packageName].orEmpty()
        return if (globalRules.isEmpty()) pkgSpecific else pkgSpecific + globalRules
    }

    companion object {
        /**
         * Built-in curated L2 rules for major domestic splash ad SDKs
         * (clean-room viewId definitions, guarded by [SkipMatcher] size & semantic checks).
         */
        fun defaultBuiltin(): RuleIndex = RuleIndex(
            listOf(
                AppRule(id = "sdk-meishu-skip", packageName = "*", viewIdContains = "ms_skip", preferGesture = true),
                AppRule(id = "sdk-csj-splash-skip", packageName = "*", viewIdContains = "tt_splash_skip", preferGesture = true),
                AppRule(id = "sdk-csj-splash-close", packageName = "*", viewIdContains = "tt_splash_close", preferGesture = true),
                AppRule(id = "sdk-gdt-splash-skip", packageName = "*", viewIdContains = "gdt_splash_skip", preferGesture = true),
                AppRule(id = "sdk-ks-splash-skip", packageName = "*", viewIdContains = "ksad_splash_skip", preferGesture = true),
                AppRule(id = "sdk-ks-circle-skip", packageName = "*", viewIdContains = "ksad_splash_circle_skip", preferGesture = true),
                AppRule(id = "sdk-baidu-splash-skip", packageName = "*", viewIdContains = "bd_splash_skip", preferGesture = true),
                AppRule(id = "sdk-mobads-skip", packageName = "*", viewIdContains = "mobads_skip", preferGesture = true),
                AppRule(id = "sdk-beizi-skip", packageName = "*", viewIdContains = "beizi_skip", preferGesture = true),
                AppRule(id = "sdk-sigmob-skip", packageName = "*", viewIdContains = "sig_skip", preferGesture = true),
                AppRule(id = "sdk-tobid-skip", packageName = "*", viewIdContains = "tobid_skip", preferGesture = true),
                AppRule(id = "sdk-zj-skip", packageName = "*", viewIdContains = "zj_skip", preferGesture = true),
                AppRule(id = "sdk-fl-skip", packageName = "*", viewIdContains = "fl_skip", preferGesture = true),
                AppRule(id = "sdk-ad-skip", packageName = "*", viewIdContains = "ad_skip", preferGesture = true),
                AppRule(id = "sdk-splash-skip", packageName = "*", viewIdContains = "splash_skip"),
                AppRule(id = "sdk-layout-skip", packageName = "*", viewIdContains = "layout_skip")
            )
        )
    }
}
