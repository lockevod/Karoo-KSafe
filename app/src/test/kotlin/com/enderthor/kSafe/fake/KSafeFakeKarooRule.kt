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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.rules.ExternalResource

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

    // Rewrites the config while the extension is running, as the Settings UI would. The write
    // runs off the test thread so it cannot deadlock a DataStore edit behind the extension's
    // own read flow.
    fun reseed(config: KSafeConfig) {
        val pending = CoroutineScope(Dispatchers.Default).async {
            ConfigurationManager(app).saveConfig(config)
        }
        karoo.awaitValue(timeoutMs = 10_000) { pending.takeIf { it.isCompleted } }
        settle(2.seconds)
    }

    // advanceBy does not wait for work resumed from Dispatchers.IO, so each step waits for a
    // DataStore write queued behind the extension's own. Without it a "nothing was sent" check
    // can pass before the send even started.
    fun settle(duration: Duration, steps: Int = 10) {
        repeat(steps) {
            RobolectricPump.advanceBy(duration / steps)
            awaitWriteBarrier()
        }
        RobolectricPump.advanceBy(1.seconds)
    }

    // Steps stay under the extension's 15 s request timeout so a response still in flight is not
    // mistaken for a timeout.
    fun <T : Any> settleUntil(limit: Duration, step: Duration = 10.seconds, done: () -> T?): T {
        var elapsed = Duration.ZERO
        while (elapsed < limit) {
            done()?.let { return it }
            settle(step, steps = 1)
            elapsed += step
        }
        return done() ?: error("not reached within $limit of looper time:\n${karoo.system.describe()}")
    }

    private fun awaitWriteBarrier() {
        val barrier = CoroutineScope(Dispatchers.Default).async { app.dataStore.edit { } }
        karoo.awaitValue(timeoutMs = 10_000) { barrier.takeIf { it.isCompleted } }
    }

    fun tap(action: String) {
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
        RobolectricPump.pumpMainLooper()
    }

    // The ride-state collector only subscribes after the persisted config was loaded, so waiting
    // for it means a button press sees the seeded config and not the defaults.
    fun startExtension(): FakeKarooHost {
        val started = karoo.host<KSafeExtension>()
        karoo.awaitValue { true.takeIf { RideState.Params in karoo.system.consumerParams } }
        host = started
        return started
    }

    lateinit var host: FakeKarooHost
        private set
}
