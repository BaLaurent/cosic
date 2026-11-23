package com.octopusman.cosic

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.octopusman.cosic.audio.AudioOutput
import com.octopusman.cosic.audio.SampleRepository
import com.octopusman.cosic.editor.EditorEnvironment
import com.octopusman.cosic.input.GamepadHub
import com.octopusman.cosic.input.KeyTracker
import com.octopusman.cosic.input.SensorHub
import com.octopusman.cosic.io.HttpRemoteJson
import com.octopusman.cosic.io.MqttSink
import com.octopusman.cosic.io.SerialLink
import com.octopusman.cosic.library.ProjectRepository
import com.octopusman.cosic.midi.MidiService
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.monetization.EntitlementRepository
import com.octopusman.cosic.osc.OscSettings
import com.octopusman.cosic.osc.SpeechSink
import com.octopusman.cosic.settings.EditorSettings
import com.octopusman.cosic.settings.ProgramEditorSettings
import com.octopusman.cosic.share.LegacyShareResolver
import com.octopusman.cosic.share.SharedImporter
import cosic.CosicEngine
import cosic.io.SettingsSink
import cosic.io.osc.UdpOscSink
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

/** The app-wide settings store; there must be exactly one DataStore instance for this file. */
private val Context.cosicPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "cosic_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The purchase state, kept apart from the settings: it is the one file that the backup rules leave out (see `data_extraction_rules.xml`). */
private val Context.entitlementPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "entitlement_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Application object and manual dependency container: every singleton is created on first use. */
class CosicApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The sounds of the app. Created with the app, so that the default sounds are loading by the time a project is opened. */
    lateinit var sampleRepository: SampleRepository
        private set

    val projects by lazy {
        ProjectRepository(
            File(filesDir, "projects"),
            System::currentTimeMillis,
            newName = { n -> getString(R.string.project_default_name, n) },
            recoveredName = { n -> getString(R.string.project_recovered_name, n) },
        )
    }

    /** The settings of the code editor (highlight, flash, autocomplete, documentation, the prebake script). */
    val editorSettings by lazy { EditorSettings(cosicPrefs, appScope) }

    /** What the programs ask of the editor (`fontSize()`, `fontFamily()`); the engine's settings sink writes it. */
    val programSettings = ProgramEditorSettings()

    /** The OSC output's settings: host, port and the switch that sends all sound to OSC. */
    val oscSettings by lazy { OscSettings(cosicPrefs, appScope) }

    /** The MIDI devices of the phone. Watching them starts with the first use (the engine's, or the screen that lists them). */
    val midi by lazy { MidiService(this).also { it.start() } }

    /** The MQTT connections `.mqtt()` sends through, and the question that asks for a password. */
    val mqtt by lazy { MqttSink.create(this) }

    private val oscSink by lazy {
        UdpOscSink(
            clock = { engineHolder.value.outputs.clock },
            defaultHost = { oscSettings.host.value },
            defaultPort = { oscSettings.port.value },
            log = { engineHolder.value.console.log(it, "error") },
        )
    }

    private val sensorHubHolder = lazy { SensorHub(this, engine.inputs) }
    private val gamepadHubHolder = lazy { GamepadHub(this) }
    private val keyTrackerHolder = lazy { KeyTracker(engine.inputs) }

    /** The motion sensors, started for the programs that read them. */
    val sensorHub: SensorHub by sensorHubHolder

    /** The game controllers, taken for the programs that read them; the activity hands it their events. */
    val gamepadHub: GamepadHub by gamepadHubHolder

    /** Tells the engine which keys of a hardware keyboard are held; the activity hands it every key event. */
    val keyTracker: KeyTracker by keyTrackerHolder

    /** The devices that cannot be reopened once closed: they are made again when the app is opened once more in this process. */
    private class Closable(val serial: SerialLink, val speech: SpeechSink)

    private var closable: Closable? = null

    private val engineHolder = lazy(::createEngine)

    /** The engine, made when first needed, with every device of the phone plugged into it. */
    val engine: CosicEngine get() = engineHolder.value

    /** Whether the engine exists yet. */
    val engineCreated: Boolean get() = engineHolder.isInitialized()

    val audioOutput by lazy { AudioOutput(engine, this) }

    /** What the editor screen needs of the app besides its view model. */
    val editorEnvironment by lazy {
        EditorEnvironment(editorSettings, programSettings, oscSettings.routeAll, midi, mqtt.prompt, sampleRepository)
    }

    val entitlements by lazy { EntitlementRepository(this, entitlementPrefs, appScope) }
    val ads by lazy { AdsController(this, entitlements.adsRemoved, appScope) }

    /** Imports the pattern of a shared link or of pasted code as a project, the way the intents and the editor's menu do. */
    internal val importer by lazy {
        val resolver = LegacyShareResolver()
        SharedImporter(create = { name, code -> projects.create(name, code).id }, legacy = resolver::fetch)
    }

    private val sharedTexts = Channel<String>(Channel.UNLIMITED)

    /** Text that other apps handed over (a link that was opened with the app, text that was shared to it) and that nobody has taken yet. */
    val incomingSharedTexts: Flow<String> = sharedTexts.receiveAsFlow()

    /** Offers [text] to whoever imports shared texts (the navigation host), which takes it as soon as it is there. */
    fun offerSharedText(text: String) {
        sharedTexts.trySend(text)
    }

    override fun onCreate() {
        super.onCreate()
        sampleRepository = SampleRepository(this, cosicPrefs)
        // What changes while the engine exists is told to it at once; the engine is made with the values that are current then.
        oscSettings.routeAll.onEach { if (engineCreated) engine.outputs.oscTarget = it }.launchIn(appScope)
        editorSettings.prebake.onEach { if (engineCreated) engine.prebake = it.takeIf { text -> text.isNotBlank() } }.launchIn(appScope)
    }

    private fun createEngine(): CosicEngine {
        val engine = CosicEngine(AudioOutput.nativeSampleRate(this), sampleRepository)
        engine.prebake = editorSettings.prebake.value.takeIf { it.isNotBlank() }
        engine.outputs.settings = SettingsSink { key, value -> programSettings.set(key, value) }
        engine.remote = HttpRemoteJson()
        // the pointer and the keys are followed for the signals (`mousex`, `keyDown`) of the engine of the editor
        engine.inputs.bindToSignals()
        midi.attachTo(engine)
        mqtt.attachTo(engine)
        engine.outputs.osc = oscSink
        engine.outputs.oscTarget = oscSettings.routeAll.value
        attachClosable(engine)
        return engine
    }

    private fun attachClosable(engine: CosicEngine) {
        val serial = SerialLink(this) { engine.console.log(it, "error") }
        val speech = SpeechSink(this) { engine.console.log(it, "error") }
        engine.outputs.serial = serial.output
        engine.outputs.speech = speech
        closable = Closable(serial, speech)
    }

    /** Plugs in again what [releaseDevices] closed, when the app is opened once more in the same process. */
    fun reattachDevices() {
        if (engineCreated && closable == null) attachClosable(engine)
    }

    /**
     * The app is being closed: lets go of what holds things open, which the process would otherwise keep until the system ends it. MQTT connections, the USB
     * serial port, the speech service, the OSC socket, motion sensors and controllers. MIDI is left as it is: the messages that are queued (a note off) still go out.
     * A program evaluated in the next start of the app finds them all again ([reattachDevices]).
     */
    fun releaseDevices() {
        if (sensorHubHolder.isInitialized()) sensorHub.stop()
        if (gamepadHubHolder.isInitialized()) gamepadHub.setEnabled(false)
        if (keyTrackerHolder.isInitialized()) keyTracker.releaseAll()
        if (!engineCreated) return
        closable?.let {
            it.serial.close()
            it.speech.close()
        }
        closable = null
        engine.outputs.serial = null
        engine.outputs.speech = null
        mqtt.stopAll()
        oscSink.close()
    }
}
