package com.flashskip.experimental

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.flashskip.SkipAdService
import com.flashskip.a11y.ActionExecutor
import com.flashskip.core.ForegroundWindow
import com.flashskip.core.VerifyOutcome
import com.flashskip.data.SkipLog
import com.flashskip.data.StatsStore
import com.flashskip.ui.SkipSuccessFeedback
import rikka.shizuku.Shizuku

internal object ExperimentalSkip {
    enum class Status { OFF, PAUSED, MISSING, DISCONNECTED, UNAUTHORIZED, CONNECTING, ACTIVE, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private val gate = SkipActionGate()
    private val legacy = Any()
    private lateinit var app: Context
    private lateinit var settings: StatsStore
    private var session: Session? = null
    private var retiring: Session? = null
    private var failed = false
    private var requesting = false

    @Volatile
    private var active: Session? = null

    var status = Status.OFF
        private set

    fun initialize(context: Context) {
        app = context.applicationContext
        settings = StatsStore(app)
        settings.observeSkipSettings { main.post(::refresh) }
        Shizuku.addBinderReceivedListenerSticky {
            main.post {
                failed = false
                refresh()
            }
        }
        Shizuku.addBinderDeadListener {
            main.post {
                requesting = false
                failed = false
                stop()
                refresh()
            }
        }
        Shizuku.addRequestPermissionResultListener { code, _ ->
            if (code == PERMISSION_REQUEST) main.post {
                requesting = false
                refresh()
            }
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        settings.experimentalSkipEnabled = enabled
        failed = false
        refresh()
        if (enabled && status == Status.UNAUTHORIZED && !requesting) {
            requesting = true
            if (runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }.isFailure) {
                requesting = false
                refresh()
            }
        }
    }

    fun refresh() {
        val next = when {
            !settings.experimentalSkipEnabled -> Status.OFF
            !settings.masterEnabled -> Status.PAUSED
            !Shizuku.pingBinder() -> if (managerInstalled()) Status.DISCONNECTED else Status.MISSING
            !hasPermission() -> Status.UNAUTHORIZED
            failed -> Status.UNAVAILABLE
            active != null -> Status.ACTIVE
            else -> Status.CONNECTING
        }
        setStatus(next)
        when (next) {
            Status.ACTIVE, Status.CONNECTING -> {
                val current = session
                if (current == null) {
                    if (retiring != null) return
                    val started = Session()
                    session = started
                    if (runCatching { Shizuku.bindUserService(started.args, started) }.isFailure) {
                        fail(started)
                    } else {
                        main.postDelayed({ if (session === started && active !== started) fail(started) }, 7_000L)
                    }
                } else {
                    current.configure()
                }
            }
            else -> stop()
        }
    }

    fun allowsLegacy(): Boolean = active == null || !settings.experimentalSkipEnabled

    fun beginLegacy(packageName: String, windowId: Int): Boolean =
        gate.begin(legacy, ForegroundWindow(packageName, windowId), SystemClock.uptimeMillis(), ::allowsLegacy)

    fun endLegacy() = gate.finish(legacy, SystemClock.uptimeMillis())

    private fun managerInstalled(): Boolean =
        runCatching { app.packageManager.getApplicationInfo(MANAGER_PACKAGE, 0) }.isSuccess

    private fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun fail(current: Session) {
        if (session !== current) return
        failed = true
        stop()
        if (settings.experimentalSkipEnabled) setStatus(Status.UNAVAILABLE)
    }

    private fun setStatus(next: Status) {
        if (next != status) {
            status = next
            SkipLog.log("EXPERIMENTAL status=$next")
        }
    }

    private fun stop() {
        val previous = session ?: return
        val hadActiveSession = active != null
        active = null
        session = null
        if (hadActiveSession) SkipAdService.onActionSourceChanged()
        previous.closeRunner()
        runCatching { Shizuku.unbindUserService(previous.args, previous, true) }
        // A remote dispatch might already be in progress. Release only after its own
        // completion or after the remote binder has died.
    }

    private class Session : ServiceConnection {
        val args = Shizuku.UserServiceArgs(ComponentName(app, UiAutomationRunner::class.java))
            .tag("experimental-skip")
            .processNameSuffix("experimental_skip")
            .daemon(false)
            .version(2)

        private var runner: ISkipRunner? = null
        private val died = IBinder.DeathRecipient {
            gate.finish(this, SystemClock.uptimeMillis())
            main.post {
                if (retiring === this) {
                    retiring = null
                    refresh()
                } else {
                    fail(this)
                }
            }
        }
        private val host = object : ISkipHost.Stub() {
            override fun onReady(connected: Boolean) {
                main.post {
                    if (session !== this@Session) return@post
                    if (connected && settings.experimentalSkipEnabled && settings.masterEnabled && hasPermission()) {
                        active = this@Session
                        SkipAdService.onActionSourceChanged()
                        setStatus(Status.ACTIVE)
                    } else {
                        fail(this@Session)
                    }
                }
            }

            override fun beginAction(packageName: String, windowId: Int): Boolean {
                val allowed = gate.begin(this@Session, ForegroundWindow(packageName, windowId), SystemClock.uptimeMillis()) {
                    active === this@Session && settings.experimentalSkipEnabled && settings.masterEnabled &&
                        !settings.observeOnly && packageName !in settings.getUserDisabledPackages()
                }
                if (allowed) SkipLog.log("EXPERIMENTAL action windowId=$windowId state=lease_granted")
                return allowed
            }

            override fun endAction() = gate.finish(this@Session, SystemClock.uptimeMillis())

            override fun onInput(sessionId: Long, attemptId: Long, accepted: Boolean, reason: String) {
                if (active === this@Session) {
                    val rejection = ActionExecutor.Rejection.entries.firstOrNull { it.name == reason }
                    SkipLog.log("EXPERIMENTAL input session=$sessionId attempt=$attemptId accepted=$accepted reason=${rejection ?: "-"}")
                }
            }

            override fun onResult(outcome: String, durationMs: Long) {
                main.post {
                    if (active !== this@Session || !settings.experimentalSkipEnabled || !settings.masterEnabled) return@post
                    val result = VerifyOutcome.entries.firstOrNull { it.name == outcome } ?: return@post
                    SkipLog.log("EXPERIMENTAL result=$result durationMs=$durationMs")
                    if (!settings.observeOnly) {
                        settings.recordOutcome(result, durationMs.takeIf { result == VerifyOutcome.VERIFIED_CLOSED })
                        if (result == VerifyOutcome.VERIFIED_CLOSED) SkipSuccessFeedback.onVerifiedClosed(app)
                    }
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.post {
                if (session !== this) return@post
                if (runCatching { binder.linkToDeath(died, 0) }.isFailure) {
                    fail(this)
                    return@post
                }
                runner = ISkipRunner.Stub.asInterface(binder)
                configure()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            main.post { fail(this) }
        }

        fun closeRunner() {
            val service = runner ?: return
            if (!service.asBinder().isBinderAlive) return
            retiring = this
            // The shell process can survive a crash of the Shizuku server. Its own binder
            // is still usable and releases the platform's single UiAutomation connection.
            runCatching { service.destroy() }
            main.postDelayed({
                if (retiring === this) {
                    retiring = null
                    refresh()
                }
            }, 2_500L)
        }

        fun configure() {
            val service = runner ?: return
            if (runCatching {
                service.configure(host, settings.masterEnabled, settings.observeOnly, settings.windowMs,
                    settings.getUserDisabledPackages().toTypedArray())
            }.isFailure) fail(this)
        }
    }

    private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST = 701
}
