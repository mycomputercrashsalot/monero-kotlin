package org.libmonero.example

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import monero.common.MoneroError
import monero.common.MoneroRpcConnection
import monero.daemon.model.MoneroNetworkType
import monero.wallet.MoneroWalletFull
import monero.wallet.model.MoneroWalletConfig

/**
 * The example of the android module: creates a full wallet in memory, reads its version and its address, connects it to a daemon and reads
 * the height of the chain, through the AAR (the JNA layer of the library into the libmonero_c.so that the AAR carries).
 *
 * The daemon comes from the build field, which defaults to the regtest node of the docker-compose.yml of monero-c. An emulator reaches that
 * node from the host with `adb reverse tcp:18081 tcp:18081`; the field is editable on screen. The calls run off the main thread, since the
 * network on the main thread is forbidden on Android.
 *
 * The wallet has no files, so its seed is gone with it: the example never shows or logs it.
 */
class MainActivity : Activity() {

    private lateinit var create: Button
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val daemon = EditText(this)
        daemon.setText(BuildConfig.DAEMON_URI)
        create = Button(this)
        create.text = "Create wallet"
        report = TextView(this)

        // a rotation recreates the activity, so the report of the calls that ran before it is kept in the state
        report.text = savedInstanceState?.getCharSequence("report")
            ?: "the daemon is editable; the default is the regtest node of the compose of monero-c\n"
        report.textSize = 12f

        val pad = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad, pad, pad)
        layout.addView(daemon)
        layout.addView(create)
        val scroll = ScrollView(this)
        scroll.addView(report)
        layout.addView(scroll)
        setContentView(layout)

        create.setOnClickListener {
            create.isEnabled = false
            report.text = ""
            val daemonUri = daemon.text.toString()
            Thread { createWallet(daemonUri) }.start()
        }

        // focused from the start, so a keyboard (or an instrumented run sending the enter key) can create the wallet without touching the screen
        create.isFocusableInTouchMode = true
        create.requestFocus()
    }

    /**
     * Create a wallet in memory connected to a daemon, and read what it says.
     *
     * @param daemonUri is the URI of the daemon
     */
    private fun createWallet(daemonUri: String) {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.regtest = BuildConfig.REGTEST
        config.server = MoneroRpcConnection(daemonUri)
        try {
            MoneroWalletFull.createWallet(config).use { wallet ->
                report("version: ${wallet.getVersion().number}")
                report("address: ${wallet.getPrimaryAddress()}")
                report("seed languages: ${wallet.getSeedLanguages().size}")
                report("connected to the daemon: ${wallet.isConnectedToDaemon()}")
                report("height of the daemon: ${wallet.getDaemonHeight()}")
            }
        } catch (e: UnsatisfiedLinkError) {
            // a library that loads and lacks a function of the C ABI: a stale or hand-built one reaches the phone, since the build checks only
            // that the library is the C ABI of monero-c
            Log.e(TAG, "the library is missing a function", e)
            report("the library of the AAR is missing a function: $e")
        } catch (e: MoneroError) {
            // the calls of the wallet fail with this, e.g. a daemon that does not answer
            Log.e(TAG, "the wallet call failed", e)
            report("failed: $e")
        } finally {
            runOnUiThread {
                create.isEnabled = true
                create.requestFocus()
            }
        }
    }

    /**
     * Append a line to the report on screen and to the log, which is where an instrumented run reads the proof from.
     *
     * @param line is the line to report
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence("report", report.text)
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        runOnUiThread { report.text = report.text.toString() + line + "\n" }
    }

    companion object {
        private const val TAG = "MoneroExample"
    }
}
