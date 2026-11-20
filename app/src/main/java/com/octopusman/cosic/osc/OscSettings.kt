package com.octopusman.cosic.osc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Where `.osc()` sends haps that name no `oschost` or `oscport` of their own: the defaults of the bridge in Strudel. */
const val DEFAULT_OSC_HOST = "127.0.0.1"
const val DEFAULT_OSC_PORT = 57120

/**
 * The settings of the OSC output, in the app's preferences: the [host] and [port] of the SuperDirt (read by the sink for every message, so a change
 * applies at once), and [routeAll], the audio engine target of Strudel's settings: every sound goes to OSC instead of the speakers.
 * The values are mirrored in memory, so reading them never waits.
 */
class OscSettings(private val prefs: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val _host = MutableStateFlow(DEFAULT_OSC_HOST)
    private val _port = MutableStateFlow(DEFAULT_OSC_PORT)
    private val _routeAll = MutableStateFlow(false)

    val host: StateFlow<String> = _host.asStateFlow()
    val port: StateFlow<Int> = _port.asStateFlow()
    val routeAll: StateFlow<Boolean> = _routeAll.asStateFlow()

    init {
        prefs.data.onEach { p ->
            _host.value = p[HOST] ?: DEFAULT_OSC_HOST
            _port.value = p[PORT] ?: DEFAULT_OSC_PORT
            _routeAll.value = p[ROUTE_ALL] ?: false
        }.launchIn(scope)
    }

    /** An empty host is the default host. */
    fun setHost(value: String) {
        val host = value.trim().ifEmpty { DEFAULT_OSC_HOST }
        _host.value = host
        scope.launch { prefs.edit { it[HOST] = host } }
    }

    /** A port outside 1..65535 is not taken. Returns whether it was. */
    fun setPort(value: Int): Boolean {
        if (value !in 1..65535) return false
        _port.value = value
        scope.launch { prefs.edit { it[PORT] = value } }
        return true
    }

    fun setRouteAll(value: Boolean) {
        _routeAll.value = value
        scope.launch { prefs.edit { it[ROUTE_ALL] = value } }
    }

    private companion object {
        val HOST = stringPreferencesKey("osc_host")
        val PORT = intPreferencesKey("osc_port")
        val ROUTE_ALL = booleanPreferencesKey("osc_route_all")
    }
}
