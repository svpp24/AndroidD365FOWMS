package com.d365fo.mobileops.presentation.common.components

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The handheld's F1-F4 keys, as [FlowAction]s (redesign brief §5): Back, Lines, Undo, Keypad -
 * so a worker with a carton in one hand works the flow without touching the glass.
 *
 * MainActivity hands every key event to [onKeyEvent] first. A key is consumed only while a flow
 * screen is listening ([FlowKeyHandler]); everywhere else F1-F4 reach the view hierarchy as
 * before.
 */
object FlowKeys {
    private val listeners = AtomicInteger(0)
    private val events = MutableSharedFlow<FlowAction>(extraBufferCapacity = 8)
    internal val actions = events.asSharedFlow()

    /** @return true when the key was an F1-F4 press a flow screen is listening for. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_F1 -> FlowAction.Back
            KeyEvent.KEYCODE_F2 -> FlowAction.Lines
            KeyEvent.KEYCODE_F3 -> FlowAction.Undo
            KeyEvent.KEYCODE_F4 -> FlowAction.Keypad
            else -> return false
        }
        if (listeners.get() == 0) return false
        // Down only, and not the auto-repeat of a held key: one press is one action.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) events.tryEmit(action)
        return true
    }

    internal fun listen() = listeners.incrementAndGet()
    internal fun stop() = listeners.decrementAndGet()
}

/**
 * Routes F1-F4 to [onAction] while this composable is on screen - the same handler the
 * [FlowActionBar]'s buttons call, so a key and a tap always do exactly the same thing.
 */
@Composable
fun FlowKeyHandler(onAction: (FlowAction) -> Unit) {
    val latest = rememberUpdatedState(onAction)
    DisposableEffect(Unit) {
        FlowKeys.listen()
        onDispose { FlowKeys.stop() }
    }
    LaunchedEffect(Unit) {
        FlowKeys.actions.collect { latest.value(it) }
    }
}
