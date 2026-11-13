package com.follow.clash

import com.follow.clash.common.GlobalState
import com.follow.clash.models.SharedState
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

object ServiceState {
    private val machine = ServiceStateMachine(AndroidServiceStateHost)

    val runState = machine.runState

    init {
        // Remember whether the proxy should be up, for the root watchdog's revive.
        // The first value is the fresh process's STOPPED, not a wish: skip it.
        GlobalState.launch {
            runState.drop(1).collect { state ->
                when (state) {
                    RunState.STARTED -> RootAutomation.onRunState(GlobalState.application, true)
                    RunState.STOPPED -> RootAutomation.onRunState(GlobalState.application, false)
                    else -> Unit
                }
            }
        }
    }

    val isRunRequested: Boolean
        get() = machine.isRunRequested

    fun attachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.attachFlutterEngine(engine)

    fun detachFlutterEngine(engine: FlutterEngine) =
        AndroidServiceStateHost.detachFlutterEngine(engine)

    suspend fun handleToggleAction() = machine.handleToggleAction()

    suspend fun handleStartAction() = machine.handleStartAction()

    suspend fun handleStopAction() = machine.handleStopAction()

    suspend fun handleVpnRevokeAction() = machine.handleVpnRevokeAction()

    suspend fun awaitRunTime(): Long = machine.awaitRunTime()

    fun requestStart(): Deferred<Boolean> = machine.requestStart()

    fun requestStop(): Deferred<Boolean> = machine.requestStop()

    fun syncSharedState(state: SharedState) {
        machine.syncSharedState(state)
        RootAutomation.sync(GlobalState.application, state)
    }

    internal fun captureRequestToken(): RunRequest = machine.captureRequestToken()

    internal suspend fun handleServiceLost(token: RunRequest) = machine.handleServiceLost(token)
}
