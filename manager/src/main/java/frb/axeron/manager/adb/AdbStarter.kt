package frb.axeron.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbMdns
import frb.axeron.adb.PreferenceAdbKeyStore
import frb.axeron.adb.util.AdbEnvironment
import frb.axeron.adb.util.AdbWifiGate
import frb.axeron.adb.util.WifiReadyGate
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

object AdbStarter {
    private const val TAG = "AdbStarter"

    /**
     * Prevents concurrent starter invocations (e.g. pairing notification vs UI start button)
     * from racing and killing the newly spawned axeron_server.
     */
    private val starterMutex = Mutex()

    fun adbKeyStore(context: Context) = PreferenceAdbKeyStore(
        AxeronSettings.getPreferences(),
        Settings.Global.getString(context.contentResolver, Starter.KEY_PAIR)
    )

    /**
     * Enables Developer options, USB debugging, and Wireless debugging in strict dependency order.
     */
    private fun enableAdbSettings(cr: ContentResolver, wireless: Boolean) {
        Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        if (wireless) Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun startAdbWireless(
        context: Context, result: (AdbStateInfo) -> Unit = {}
    ) {
        Log.d(TAG, "startAdb")

        val tcpPort = AdbEnvironment.getAdbTcpPort()
        if (tcpPort > 0 && !AxeronSettings.getTcpMode()) {
            ActivationLog.info("ADB over TCP is on (port $tcpPort) but TCP mode is off — turning it off first")
            stopTcp(context, tcpPort)
        }

        Log.d(TAG, "tcpPort: $tcpPort")

        val port = runCatching {
            Log.d(TAG, "awaitAdbPort: enter")

            if (!AdbEnvironment.isWifiRequired()) {
                Log.d(TAG, "awaitAdbPort: wifi NOT required, use tcpPort=$tcpPort")
                return@runCatching tcpPort
            }

            ActivationLog.info("Waiting for Wi-Fi…")

            callbackFlow {
                Log.d(TAG, "callbackFlow: start")

                val closed = AtomicBoolean(false)

                fun safeClose(cause: Throwable? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (cause != null) {
                            Log.e(TAG, "callbackFlow: closing with error", cause)
                            close(cause)
                        } else {
                            Log.d(TAG, "callbackFlow: closing normally")
                            close()
                        }
                    } else {
                        Log.w(TAG, "callbackFlow: safeClose called but already closed")
                    }
                }

                val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p ->
                    Log.d(TAG, "AdbMdns callback: port=$p")

                    if (p <= 0) {
                        Log.e(TAG, "AdbMdns callback: invalid port")
                        safeClose(IllegalStateException(context.getString(R.string.adb_failed_to_get_port)))
                        return@AdbMdns
                    }

                    val sendResult = trySend(p)
                    Log.d(
                        TAG,
                        "callbackFlow: trySend($p) success=${sendResult.isSuccess}"
                    )
                    if (sendResult.isSuccess) {
                        ActivationLog.success("Found wireless debugging port $p")
                    } else {
                        safeClose(
                            IllegalStateException(context.getString(R.string.failed_to_emit_port))
                        )
                        return@AdbMdns
                    }

                    safeClose()
                }

                Log.d(TAG, "callbackFlow: start WifiReadyGate")

                WifiReadyGate(
                    context,
                    onReady = {
                        Log.d(TAG, "WifiReadyGate: onReady")
                        ActivationLog.success("Wi-Fi is connected")

                        val cr = context.contentResolver
                        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.d(TAG, "WRITE_SECURE_SETTINGS granted, enabling adb")
                            ActivationLog.info("Turning on wireless debugging (WRITE_SECURE_SETTINGS granted)")
                            enableAdbSettings(cr, wireless = true)
                        } else {
                            Log.w(TAG, "WRITE_SECURE_SETTINGS NOT granted")
                            ActivationLog.warn("No WRITE_SECURE_SETTINGS — wireless debugging must already be on")
                        }

                        Log.d(TAG, "callbackFlow: start AdbWifiGate")

                        AdbWifiGate(
                            context,
                            onReady = {
                                Log.d(TAG, "AdbWifiGate: onReady")
                                ActivationLog.success("Wireless debugging is on")
                                ActivationLog.info("Looking up the ADB port (mDNS)…")
                                adbMdns.runCatching {
                                    Log.d(TAG, "AdbMdns: start()")
                                    start()
                                }.onFailure {
                                    Log.e(TAG, "AdbMdns: start failed", it)
                                    ActivationLog.error("Couldn't start the mDNS lookup", it)
                                    safeClose(it)
                                }
                            },
                            onFail = {
                                Log.e(TAG, "AdbWifiGate: onFail")
                                ActivationLog.error("Wireless debugging didn't turn on")
                                safeClose(
                                    IllegalStateException(context.getString(R.string.adb_wifi_failed_to_auto_active))
                                )
                            }
                        ).start()
                    },
                    onFail = {
                        Log.e(TAG, "WifiReadyGate: onFail")
                        ActivationLog.error("Wi-Fi isn't connected")
                        safeClose(
                            IllegalStateException(context.getString(R.string.wifi_failed_to_connect))
                        )
                    }
                ).start()

                awaitClose {
                    Log.d(TAG, "callbackFlow: awaitClose -> stop mdns")
                    adbMdns.stop()
                }
            }.first().also {
                Log.d(TAG, "callbackFlow: first() received port=$it")
            }
        }.getOrElse {
            Log.e(TAG, "awaitAdbPort: FAILED", it)
            ActivationLog.error("Couldn't get the ADB port", it)
            result(AdbStateInfo.Failed(it.message ?: ""))
            return
        }

        Log.d(TAG, "awaitAdbPort: SUCCESS port=$port")

        startAdbClient(context, port, result)
    }

    suspend fun startAdbClient(context: Context, port: Int, result: (AdbStateInfo) -> Unit = {}) {
        starterMutex.withLock {
            if (port <= 0) {
                ActivationLog.error("No valid ADB port")
                result(AdbStateInfo.Failed(context.getString(R.string.adb_failed_to_get_port)))
                return
            }

            val keyStore = adbKeyStore(context)
            val key = runCatching {
                AdbKey(
                    keyStore,
                    "axeron"
                )
            }.getOrElse {
                ActivationLog.error("Couldn't load this app's ADB key", it)
                result(AdbStateInfo.Failed(context.getString(R.string.adb_failed_to_auto_active)))
                return
            }

            var activePort = port
            val tcpMode = AxeronSettings.getTcpMode()
            val tcpPort = AxeronSettings.getTcpPort()
            if (tcpMode && activePort != tcpPort) {
                Log.d(TAG, "Try to switching to TCP mode")
                ActivationLog.info("TCP mode is on — switching adbd to TCP port $tcpPort")

                runCatching {
                    withContext(Dispatchers.IO) {
                        AdbClient(key, activePort).use { client ->
                            client.connect()
                            Log.d(TAG, "Restarting in TCP mode port: $tcpPort")
                            client.command("tcpip:$tcpPort")
                        }
                    }

                    ActivationLog.info("Waiting for adbd on TCP port $tcpPort…")
                    waitTcpReady(context, key, tcpPort)
                    ActivationLog.success("adbd is listening on TCP port $tcpPort")
                    activePort = tcpPort
                }.onFailure {
                    if (it !is EOFException && it !is SocketException) {
                        ActivationLog.error("adbd didn't come back on TCP port $tcpPort", it)
                        result(AdbStateInfo.Failed(context.getString(R.string.adb_failed_to_switch_tcp)))
                    }
                    return
                }
            }

            Log.d(TAG, "Connecting on port $activePort...")
            ActivationLog.info("Connecting to adbd on 127.0.0.1:$activePort…")

            runCatching {
                withContext(Dispatchers.IO) {
                    AdbClient(
                        key,
                        activePort
                    ).use { client ->
                        Log.d(TAG, "AdbClient running")
                        client.connect()
                        ActivationLog.success("Connected to adbd")
                        ActivationLog.info("Running the fOxygen starter…")
                        client.shellCommand(Starter.internalAdbCommand(keyStore.getBase64())) { chunk ->
                            ActivationLog.output(String(chunk, Charsets.UTF_8))
                        }
                    }
                }
            }.onSuccess {
                Log.d(TAG, "AdbClient success")
                ActivationLog.success("Starter finished")
                AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.ADB)
                result(AdbStateInfo.Success(context.getString(R.string.adb_client_connected)))
            }.onFailure {
                Log.e(TAG, "AdbClient failed", it)
                ActivationLog.error("ADB connection failed", it)
                result(AdbStateInfo.Failed(context.getString(R.string.failed_to_connect_adb_client)))
            }
        }
    }

    suspend fun stopTcp(context: Context, port: Int, result: (AdbStateInfo) -> Unit = {}) {
        runCatching {
            ActivationLog.info("Switching adbd from TCP port $port back to USB…")
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                enableAdbSettings(cr, wireless = false)
            }

            val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            if (adbEnabled == 0) throw IllegalStateException(context.getString(R.string.adb_is_not_enabled))

            val keyStore = adbKeyStore(context)
            val key = AdbKey(keyStore, "axeron")
            waitTcpReady(context, key, port)
            withContext(Dispatchers.IO) {
                AdbClient(key, port).use { client ->
                    client.connect()
                    client.command("usb:")
                }
            }
        }.onSuccess {
            Log.d(TAG, "Stop TCP success")
            ActivationLog.success("ADB over TCP stopped")
            result(AdbStateInfo.Success(context.getString(R.string.adb_tcp_stopped)))
        }.onFailure {
            Log.e(TAG, "Stop TCP failed", it)
            ActivationLog.error("Couldn't stop ADB over TCP", it)
            result(AdbStateInfo.Failed(context.getString(R.string.failed_to_stop_adb_tcp) + ": " + it.message))
        }
    }

    suspend fun waitTcpReady(
        context: Context,
        key: AdbKey,
        port: Int,
        timeoutMs: Long = 5_000,
        intervalMs: Long = 200
    ) {
        val start = System.currentTimeMillis()
        var lastError: Throwable? = null

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                withContext(Dispatchers.IO) {
                    AdbClient(key, port).use { it.connect() }
                }
                return
            } catch (e: Throwable) {
                lastError = e
                delay(intervalMs)
            }
        }

        throw IllegalStateException(
            context.getString(R.string.adb_tcp_not_ready, port),
            lastError
        ) as Throwable
    }
}


sealed class AdbStateInfo(val message: String, val cause: Throwable? = null) {
    class Success(message: String) : AdbStateInfo(message)

    class Process(message: String, cause: Throwable? = null) :
        AdbStateInfo(message, cause)

    class Failed(message: String, cause: Throwable? = null) :
        AdbStateInfo(message, cause)
}
