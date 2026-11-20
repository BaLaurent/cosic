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
import com.octopusman.cosic.library.ProjectRepository
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.monetization.EntitlementRepository
import cosic.CosicEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app-wide settings store; there must be exactly one DataStore instance for this file. */
private val Context.cosicPrefs: DataStore<Preferences> by preferencesDataStore(
    name = "cosic_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Application object and manual dependency container: every singleton is created on first use. */
class CosicApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The sounds of the app. Created with the app, so that the default sounds are loading by the time a project is opened. */
    lateinit var sampleRepository: SampleRepository
        private set

    val projects by lazy { ProjectRepository(File(filesDir, "projects"), System::currentTimeMillis) }
    val engine by lazy { CosicEngine(AudioOutput.nativeSampleRate(this), sampleRepository) }
    val audioOutput by lazy { AudioOutput(engine, this) }
    val entitlements by lazy { EntitlementRepository(this, cosicPrefs, appScope) }
    val ads by lazy { AdsController(this, entitlements.adsRemoved, appScope) }

    override fun onCreate() {
        super.onCreate()
        sampleRepository = SampleRepository(this, cosicPrefs)
    }
}
