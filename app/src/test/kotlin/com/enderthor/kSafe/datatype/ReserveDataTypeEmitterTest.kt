package com.enderthor.kSafe.datatype

import android.os.Bundle
import android.os.DeadObjectException
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReserveDataTypeEmitterTest {
    /** The ride app died: every call into its handler throws. */
    private val deadHandler = object : IHandler.Stub() {
        override fun onNext(bundle: Bundle?) = throw DeadObjectException()
        override fun onError(msg: String?) = throw DeadObjectException()
        override fun onComplete() = throw DeadObjectException()
    }

    @Test fun `a dead binder at startView does not throw`() {
        val app = RuntimeEnvironment.getApplication()
        val emitter = ViewEmitter(app.packageName, deadHandler)
        ReserveDataType("pacing-reserve", app, KarooSystemService(app))
            .startView(app, ViewConfig(gridSize = 30 to 15, viewSize = 240 to 120, textSize = 30), emitter)
        emitter.cancel()   // the cancellable is in place before any job runs
    }
}
