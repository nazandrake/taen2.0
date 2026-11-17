// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.privacy.AppLock
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.SecureWindow
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.privacy.SelfDestruct
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Asks for the app-lock PIN (design 7.4 item 7). Started by the settings activity and by the keyboard's clipboard key; it ends
 * with RESULT_OK once the PIN was right, otherwise with RESULT_CANCELED. Not shown in recents, and not captured (P12).
 */
class UnlockActivity : ComponentActivity() {
    // internal var: the wiring test runs the check inline
    internal var executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "taen-unlock").apply { isDaemon = true } }

    private sealed interface Message {
        object None : Message
        class Wrong(val failed: Int) : Message
        class Wait(val seconds: Long) : Message
        object NotAccepted : Message
        object Unavailable : Message
    }

    private var message by mutableStateOf<Message>(Message.None)
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(window)
        setContent {
            Theme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val text = when (val m = message) {
                            Message.None -> null
                            is Message.Wrong -> stringResource(R.string.app_lock_wrong, m.failed)
                            is Message.Wait -> stringResource(R.string.app_lock_wait, m.seconds)
                            Message.NotAccepted -> stringResource(R.string.app_lock_not_accepted)
                            Message.Unavailable -> stringResource(R.string.app_lock_unavailable)
                        }
                        PinPad(
                            title = stringResource(R.string.app_lock_enter_pin),
                            message = text,
                            enabled = !busy,
                            onSubmit = ::submit,
                            onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                        )
                    }
                }
            }
        }
    }

    // Argon2id takes a few hundred milliseconds: not on the main thread
    internal fun submit(pin: CharArray) {
        busy = true
        val lock = Privacy.appLock(this)
        val app = applicationContext
        executor.execute {
            val result = try {
                lock.verify(pin)
            } finally {
                pin.fill('\u0000')
            }
            // self-destruct: the duress PIN, or the count of wrong PINs reaching its limit, may wipe; the screen shows nothing of it
            runCatching { SelfDestruct.engine(app).onPin(result) }
            runOnUiThread {
                busy = false
                when (result) {
                    AppLock.Verify.Ok -> { setResult(Activity.RESULT_OK); finish() }
                    is AppLock.Verify.Wrong -> message = Message.Wrong(result.failedAttempts)
                    is AppLock.Verify.Wait -> message = Message.Wait((result.millis + 999) / 1000)
                    AppLock.Verify.Duress -> message = Message.NotAccepted
                    AppLock.Verify.Corrupt, AppLock.Verify.Unavailable -> message = Message.Unavailable
                }
            }
        }
    }

    override fun onDestroy() {
        (executor as? java.util.concurrent.ExecutorService)?.shutdown()
        super.onDestroy()
    }
}
