package com.xrdroid

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

// The app's one connection to ShellHelper, which Shizuku runs as the shell user. It creates the spatial
// screens, launches the glasses view fullscreen and delivers the trackpad's touches. Once started, it
// connects whenever Shizuku is running and allows this app, including after Shizuku restarts. Listeners
// hear about it connecting or dropping on the main thread.
object Helper {
    @Volatile var service: IShellHelper? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var appContext: Context? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        // Shizuku repeats this whenever another connection binds, so listeners must cope with repeats
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val helper = binder?.let { IShellHelper.Stub.asInterface(it) }
            service = helper
            // Usage access first, so home screens opened once listeners hear about it can rank the dock
            thread(name = "UsageAccess") {
                if (helper != null && !hasUsageAccess()) {
                    val granted = runCatching { helper.allowUsageAccess() }.getOrDefault(false)
                    Log.i("XRdroid", "Usage access granted through the helper: $granted")
                }
                notifyListeners()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            notifyListeners()
        }
    }

    // Shizuku is running and has allowed this app
    fun shizukuReady() = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    // Connects now if Shizuku is ready, and otherwise as soon as it is. Safe to call repeatedly.
    fun start(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // Shizuku hands its binder over shortly after the process starts, and again after it restarts
            Shizuku.addBinderReceivedListenerSticky { mainHandler.post { connect() } }
            Shizuku.addBinderDeadListener {
                mainHandler.post {
                    service = null
                    bound = false
                    notifyListeners()
                }
            }
            Shizuku.addRequestPermissionResultListener { _, _ -> mainHandler.post { connect() } }
        }
        connect()
    }

    // Whether this app may read app usage, which ranks the dock's apps
    fun hasUsageAccess(): Boolean {
        val context = appContext ?: return false
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    // The helper stops when this app's process does
    private fun connect() {
        val context = appContext ?: return
        if (bound || !shizukuReady()) return
        bound = true
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellHelper::class.java.name))
            .daemon(false)
            .processNameSuffix("helper")
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(args, connection)
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.toList().forEach { it() } }
    }
}
