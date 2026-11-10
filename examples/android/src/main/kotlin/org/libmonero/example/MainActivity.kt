package org.libmonero.example

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import monero.common.MoneroRpcConnection
import monero.common.MoneroUtils
import monero.daemon.model.MoneroNetworkType
import monero.wallet.MoneroWalletLight
import monero.wallet.model.MoneroWalletConfig
import java.math.BigInteger

/**
 * The example of the android module: opens the light wallet of an account on a light wallet
 * server, syncs it, and reads its balance, through the AAR — the JNA layer of the library into
 * the libmonero_c.so the AAR carries, and the REST API of the server behind it.
 *
 * The server, the account and its view key come from the build fields, which the defaults fill
 * with the throwaway regtest account of the integration tests and the light wallet server of the
 * compose of src/test/integration. An emulator reaches that server from the host with
 * `adb reverse tcp:8443 tcp:8443`; the fields are editable on screen to point the example at
 * another server or account. The calls run off the main thread, since the network on main is
 * forbidden on Android.
 */
class MainActivity : Activity() {

    private lateinit var open: Button
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val server = EditText(this)
        server.hint = "light wallet server"
        server.setText(BuildConfig.LWS_URI)
        val address = EditText(this)
        address.hint = "primary address of the account"
        address.setText(BuildConfig.WALLET_ADDRESS)
        val viewKey = EditText(this)
        viewKey.hint = "private view key of the account"
        viewKey.setText(BuildConfig.WALLET_VIEW_KEY)
        open = Button(this)
        open.text = "Open light wallet"
        report = TextView(this)
        report.text = "the account, the view key and the server are editable; the defaults are the regtest stack of the integration tests\n"
        report.textSize = 12f

        val pad = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        // the layout fits the windows of the system, which without this draws the first field under the action bar
        layout.fitsSystemWindows = true
        layout.setPadding(pad, pad, pad, pad)
        layout.addView(server)
        layout.addView(address)
        layout.addView(viewKey)
        layout.addView(open)
        val scroll = ScrollView(this)
        scroll.addView(report)
        layout.addView(scroll)
        setContentView(layout)

        // the report of a run survives a turn of the screen, which recreates the activity
        if (savedInstanceState != null) report.text = savedInstanceState.getCharSequence("report")

        open.setOnClickListener {
            open.isEnabled = false
            open.text = "Opening…"
            report.text = ""
            val serverUri = server.text.toString()
            val accountAddress = address.text.toString()
            val accountViewKey = viewKey.text.toString()
            report("opening the wallet on $serverUri")
            Thread { openLightWallet(serverUri, accountAddress, accountViewKey) }.start()
        }

        // focused from the start, so a keyboard (or an instrumented run sending the
        // enter key) can open the wallet without touching the screen
        open.isFocusableInTouchMode = true
        open.requestFocus()
    }

    /**
     * Open the light wallet of the account on the server, sync it, and read its balance. The
     * wallet is created when the server does not have the account, and opened when it does, which
     * is the flow of a real client: a first run registers the account, the next ones resume it.
     *
     * @param serverUri is the URI of the light wallet server
     * @param accountAddress is the primary address of the account
     * @param accountViewKey is the private view key of the account
     */
    private fun openLightWallet(serverUri: String, accountAddress: String, accountViewKey: String) {
        val config = MoneroWalletConfig()
        config.networkType = MoneroNetworkType.MAINNET
        config.primaryAddress = accountAddress
        config.privateViewKey = accountViewKey
        val connection = MoneroRpcConnection(serverUri)
        try {
            val exists = MoneroWalletLight.walletExists(config, connection)
            report("the server ${if (exists) "has" else "does not have"} the account")
            val wallet = if (exists) MoneroWalletLight.openWallet(config, connection) else MoneroWalletLight.createWallet(config, connection)
            wallet.use {
                report("opened ${wallet.getPrimaryAddress()}")
                report("view only: ${wallet.isViewOnly()}")
                val result = wallet.sync()
                report("synced: ${result.numBlocksFetched} blocks fetched, received money: ${result.receivedMoney}")
                val balance = wallet.getBalance()
                report("balance: $balance atomic units (~${MoneroUtils.atomicUnitsToXmr(balance)} XMR)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "the light wallet call failed", e)
            report("failed: $e")
        } finally {
            runOnUiThread {
                open.isEnabled = true
                open.text = "Open light wallet"
                open.requestFocus()
            }
        }
    }

    /**
     * Append a line to the report on screen and to the log, which is where an instrumented run
     * reads the proof from.
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
