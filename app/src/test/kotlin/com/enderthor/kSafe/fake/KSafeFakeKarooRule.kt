package com.enderthor.kSafe.fake

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.edit
import com.enderthor.kSafe.activity.dataStore
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.data.SenderConfig
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import fi.nikosavola.karooext.testing.FakeKarooHost
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Wraps [FakeKarooRule] with the KSafe specifics: persisted settings go in before the service
 * starts, and [startExtension] only returns once the extension has read them.
 */
class KSafeFakeKarooRule(val karoo: FakeKarooRule) : ExternalResource() {
    private val app: Application get() = karoo.app

    override fun before() {
        // The shared config flow and the DataStore instance are process-wide statics that survive
        // from one test to the next in the same Robolectric class loader.
        ConfigurationManager.resetSharedConfigStateForTesting()
        runBlocking { app.dataStore.edit { it.clear() } }
    }

    override fun after() {
        ConfigurationManager.resetSharedConfigStateForTesting()
    }

    fun seed(config: KSafeConfig, vararg senders: SenderConfig) = runBlocking {
        val manager = ConfigurationManager(app)
        manager.saveConfig(config)
        if (senders.isNotEmpty()) manager.saveSenderConfigs(senders.toList())
    }

    /** Sends the broadcast a tappable data field fires, through the manifest-declared receiver. */
    fun tap(action: String) {
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
        RobolectricPump.pumpMainLooper()
    }

    /**
     * Starts the service and waits for its ride-state subscription. That collector only
     * subscribes after the persisted config was loaded, so a button press after this sees the
     * seeded config instead of the defaults.
     */
    fun startExtension(): FakeKarooHost {
        val host = karoo.host<KSafeExtension>()
        karoo.awaitValue { true.takeIf { RideState.Params in karoo.system.consumerParams } }
        return host
    }
}
