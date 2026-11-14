package com.enderthor.kSafe.extension.pacing

import com.enderthor.kSafe.extension.managers.CalibrationLogger
import com.enderthor.kSafe.extension.managers.CalibrationLogger.Event
import com.enderthor.kSafe.extension.managers.CarbStatus
import io.hammerhead.karooext.models.OnNavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

data class PacingView(val state: PacingState, val recoverW: Boolean, val wPct: Double?, val trusted: Boolean, val nextBlockKm: Double?)

data class PacingAlert(
    val source: AlertSource, val state: PacingState?, val key: String, val blockKm: Double?, val blockLenKm: Double?,
    val blockGradePct: Double?, val ceilingW: Int?, val wPct: Double?, val grams: Int?, val withLogs: Boolean,
)

/**
 * Composes the pure pacing units (spec §3.9) and logs PACE_* (§4). Runs entirely on the caller's thread
 * (Main in production): every on*() and tick() must come from that one thread. Phase 1a is shadow:
 * the arbiter decides would_alert but nothing fires and nothing is recorded as fired.
 */
class PacingManager(
    private val scope: CoroutineScope,
    private val storeDir: File,
    private val calibLogger: CalibrationLogger?,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val alertSink: ((PacingAlert) -> Unit)? = null,
    private val kneeKjKgOverride: Double? = null,
) {
    /** Every candidate key sent to the arbiter, in order. Tests only. */
    internal val evaluatedCandidateKeys = ArrayList<String>()

    private val _view = MutableStateFlow<PacingView?>(null)
    val view: StateFlow<PacingView?> = _view.asStateFlow()

    private var loop: Job? = null
    private var started = false
    internal var gradePace = GradePaceModel()  // internal: tests swap the learned pace
    private var kind = BikeKind.ROAD

    // Route
    private var routeKey: String? = null
    private var path: PolylinePath? = null
    private var profile: ElevationProfile? = null
    private var climbs: List<ClimbSeg> = emptyList()
    private var projector: RouteProjector? = null
    private var rejoinActive = false
    internal var blocks: List<ClimbBlock> = emptyList(); private set
    private var blocksAtMs = Long.MIN_VALUE

    // Sensors
    private var fix: LatLng? = null; private var fixAccM: Double? = null; private var fixAtMs = 0L
    private var odometerM = 0.0; private var movingS = 0.0; private var lastSpeedAtMs: Long? = null; private var moving = false
    private var lastHr: Int? = null; private var lastHrAtMs: Long? = null; private var lastPowerAtMs: Long? = null
    private var ftp = 0; private var weightKg = 0.0; private var wOverrideJ: Double? = null; private var liveAlerts = false
    internal var wPrime: WPrimeBalance? = null; private set       // internal: tests read ride state
    internal var fatigue: FatigueSignals? = null; private set
    private var carbStatus: CarbStatus? = null; private var lastCarbAlertMs: Long? = null; private var lastMedicalMs: Long? = null
    private var emergencyActive = false

    // Per-ride evaluation state
    private var arbiter = AlertArbiter()
    private var prevState = PacingState.KEEP; private var prevRecoverW = false
    private var conserveSinceMs: Long? = null; private var condCandidateDone = false
    private var pacingCandBlock: Int? = null; private var keepCandBlock: Int? = null
    private var wCandBlock: Int? = null; private var fuelLoggedBlock: Int? = null; private var fuelFiredBlock: Int? = null
    private var tickN = 0; private var lastTickOdoM = 0.0; private var lastTickMovingS = 0.0
    private var firstProjFinish: Double? = null; private var lastProjFinish: Double? = null
    // PACE_CLIMB: prediction tracking for the next block, stats for the block being ridden
    private var predBlock: Int? = null; private var predFirstAtMs = 0L; private var predFirstS = 0.0
    private var predLastAtMs = 0L; private var predLastS = 0.0
    private var inBlock: ClimbBlock? = null; private var blockWMin: Double? = null
    private var blockKj0 = 0.0; private var blockMov0 = 0.0

    fun start() {
        if (started) return            // a second start must not reload and discard this ride's learning
        started = true
        arbiter = AlertArbiter()
        prevState = PacingState.KEEP; prevRecoverW = false
        conserveSinceMs = null; condCandidateDone = false
        pacingCandBlock = null; keepCandBlock = null; wCandBlock = null; fuelLoggedBlock = null; fuelFiredBlock = null
        odometerM = 0.0; movingS = 0.0; lastSpeedAtMs = null; moving = false; lastPowerAtMs = null
        tickN = 0; lastTickOdoM = 0.0; lastTickMovingS = 0.0; firstProjFinish = null; lastProjFinish = null
        predBlock = null; inBlock = null; blockWMin = null
        lastCarbAlertMs = null; lastMedicalMs = null
        path?.let { projector = RouteProjector(it) }
        if (wPrime != null) rebuildProfileUnits()
        gradePace = GradePaceStore(storeDir).load() ?: GradePaceModel()
        _view.value = null
        // A pacing bug must never escape into the service scope (crash / medical / SOS share it).
        loop = scope.launch { while (isActive) { delay(30_000); runCatching { tick() }.onFailure { Timber.e(it, "PACE tick failed") } } }
    }

    fun stop() {
        if (!started) return           // never save an unloaded (empty) model over the learned file
        started = false
        loop?.cancel(); loop = null
        // Runs inside the service teardown paths — must never throw into them.
        runCatching { saveOnStop() }.onFailure { Timber.e(it, "PACE stop failed") }
    }

    private fun saveOnStop() {
        val fs = fatigue
        calibLogger?.log(Event.PACE_END) {
            "proj_finish_first=${f(firstProjFinish)},proj_finish_last=${f(lastProjFinish)},actual_kjkg=${f(fs?.kjPerKg)}," +
                "kjkg_at_drift5=${f(fs?.kjPerKgAtFirstDrift5)},moving_s=${f(movingS, 0)},odo_m=${f(odometerM, 0)}," +
                "power_cov=${f(powerCoverage(), 2)}"
        }
        storeDir.mkdirs()
        GradePaceStore(storeDir).save(gradePace)
    }

    fun onNavigation(state: OnNavigationState.NavigationState) {
        runCatching { applyNavigation(state) }.onFailure { Timber.e(it, "PACE navigation failed") }
    }

    private fun applyNavigation(state: OnNavigationState.NavigationState) {
        if (state !is OnNavigationState.NavigationState.NavigatingRoute) {
            routeKey = null; path = null; profile = null; climbs = emptyList(); projector = null; blocks = emptyList()
            rejoinActive = false
            return
        }
        rejoinActive = state.rejoinPolyline != null || state.rejoinDistance != null
        val key = state.routePolyline + state.reversed + state.routeElevationPolyline + state.climbs.size
        if (key == routeKey) return
        routeKey = key
        val pts = Polyline.decode(state.routePolyline).let { if (state.reversed) it.reversed() else it }
        if (pts.size < 2) { path = null; projector = null; profile = null; climbs = emptyList(); blocks = emptyList(); return }
        val p = PolylinePath(pts)
        path = p; projector = RouteProjector(p)
        profile = ElevationProfile.fromKaroo(state.routeElevationPolyline)?.let { if (state.reversed) it.reversed() else it }
        val res = RouteClimbs.resolve(state.climbs, profile, state.reversed)
        climbs = res.climbs
        val sigN = recomputeBlocks()
        val ownN = profile?.let { RouteClimbs.detectOwn(it).size } ?: 0
        calibLogger?.log(Event.PACE_ROUTE) {
            "len_m=${f(p.totalM, 0)},reversed=${state.reversed},profile_pts=${profile != null},src=${res.source}," +
                "karoo_n=${res.karooCount},karoo_ok=${res.karooConfirmed},own_n=$ownN,sig_n=$sigN,blocks_n=${blocks.size}"
        }
    }

    fun onLocation(lat: Double, lng: Double, accuracyM: Double?) { fix = LatLng(lat, lng); fixAccM = accuracyM; fixAtMs = clock() }

    fun onSpeed(kmh: Double) {
        val now = clock()
        lastSpeedAtMs?.let { val dt = (now - it).coerceIn(0, 5_000) / 1000.0
            if (kmh.isFinite() && kmh > 0) odometerM += kmh / 3.6 * dt
            if (moving) movingS += dt }
        lastSpeedAtMs = now
        moving = kmh >= 3.0
    }

    fun onPower(w: Int) {
        val now = clock()
        wPrime?.onPower(w, now)
        // A strap that stopped sending is unknown HR, not the last bpm held forever.
        val hr = lastHr.takeIf { lastHrAtMs?.let { now - it <= HR_STALE_MS } == true }
        fatigue?.onSample(w, hr, now, moving)
        lastPowerAtMs = now
    }

    fun onHr(bpm: Int) { lastHr = bpm.takeIf { it > 0 }; lastHrAtMs = clock() }

    /** A mid-ride profile edit must not wipe the ride: weight rescales kJ/kg, FTP resets only the drift
     *  band/baseline and W′ (its CP changed); work and moving time are kept. */
    fun onUserProfile(ftp: Int, weightKg: Double) {
        val fs = fatigue
        if (fs == null || wPrime == null) { this.ftp = ftp; this.weightKg = weightKg; rebuildProfileUnits(); return }
        if (weightKg != this.weightKg) { this.weightKg = weightKg; fs.weightKg = weightKg; logReset("weight", wReset = false) }
        if (ftp != this.ftp) {
            this.ftp = ftp; fs.onFtpChanged(ftp)
            wPrime = WPrimeBalance(ftp, wOverrideJ ?: WPrimeBalance.defaultWPrimeJ(kind))
            logReset("ftp", wReset = true)
        }
    }

    fun onRoutingPreference(pref: String) {
        val k = BikeKind.of(pref)
        if (k == kind) return
        kind = k; blocksAtMs = Long.MIN_VALUE
        val wp = wPrime ?: return
        // Only an untouched balance takes the new kind's default; a riding one keeps its state and stale flag.
        val wReset = wOverrideJ == null && !wp.hasSamples
        if (wReset) wPrime = WPrimeBalance(ftp, WPrimeBalance.defaultWPrimeJ(kind))
        logReset("kind", wReset)
    }

    private fun logReset(reason: String, wReset: Boolean) = calibLogger?.log(Event.PACE_RESET) {
        "reason=$reason,ftp=$ftp,weight=${f(weightKg)},kind=$kind,w_reset=$wReset," +
            "kj=${f(fatigue?.kj, 0)},moving_s=${f(fatigue?.movingSeconds, 0)}"
    }

    fun onCarbStatus(s: CarbStatus?) { carbStatus = s }

    fun onExternalAlert(source: AlertSource) {
        val now = clock()
        arbiter.recordExternal(source, now)
        when (source) {
            AlertSource.MEDICAL -> lastMedicalMs = now
            AlertSource.FUEL_DEFICIT, AlertSource.FUEL_TIME -> lastCarbAlertMs = now
            else -> Unit
        }
    }

    fun setEmergencyActive(active: Boolean) { emergencyActive = active }

    fun setLiveAlerts(enabled: Boolean, wPrimeOverrideJ: Double?) {
        liveAlerts = enabled
        if (wPrimeOverrideJ != wOverrideJ) {
            wOverrideJ = wPrimeOverrideJ
            if (wPrime != null) wPrime = WPrimeBalance(ftp, wOverrideJ ?: WPrimeBalance.defaultWPrimeJ(kind))
        }
    }

    private fun rebuildProfileUnits() {
        wPrime = WPrimeBalance(ftp, wOverrideJ ?: WPrimeBalance.defaultWPrimeJ(kind))
        fatigue = FatigueSignals(ftp, weightKg)
    }

    /** Returns the significant-climb count. Block gaps use the learned grade pace. */
    internal fun recomputeBlocks(): Int {
        val prof = profile; val total = path?.totalM
        blocksAtMs = clock()
        if (prof == null || total == null) { blocks = emptyList(); return 0 }
        val sig = climbs.filter { RouteClimbs.isSignificant(it, kind) && it.startM.isFinite() && it.endM.isFinite() }
        blocks = RouteClimbs.blocks(sig, { from, to -> gradePace.secondsBetween(kind, prof, from, to) })
            .filter { it.startM < total }
            .map { it.copy(startM = it.startM.coerceIn(0.0, total), endM = it.endM.coerceIn(0.0, total)) }
        return sig.size
    }

    private fun secondsBetween(from: Double, to: Double): Double {
        val prof = profile
        return if (prof != null) gradePace.secondsBetween(kind, prof, from, to)
        else (to - from).coerceAtLeast(0.0) / (gradePace.speedKmh(kind, 0.0) / 3.6)
    }

    internal var tickHook: (() -> Unit)? = null  // test seam: force a tick failure

    internal fun tick() {
        tickHook?.invoke()
        val now = clock()
        tickN++
        val p = path; val proj = projector; val fx = fix
        val rp = if (p != null && proj != null && fx != null) proj.update(fx, fixAccM, now - fixAtMs, odometerM, rejoinActive)
                 else RoutePos(null, PosReason.NO_ROUTE)
        val pos = rp.distanceM?.takeIf { it.isFinite() }?.coerceIn(0.0, p?.totalM ?: 0.0)
        val trusted = pos != null && p != null

        // Learn grade pace from the stretch ridden since the previous tick.
        val dOdo = odometerM - lastTickOdoM; val dMov = movingS - lastTickMovingS
        lastTickOdoM = odometerM; lastTickMovingS = movingS
        val prof = profile
        if (started && trusted && prof != null && moving && dOdo > 0 && dMov > 0) gradePace.learn(kind, prof.gradeAt(pos!!), dOdo, dMov)
        if (prof != null && now - blocksAtMs >= BLOCKS_REFRESH_MS) recomputeBlocks()

        val fs = fatigue; val wp = wPrime
        val kj = fs?.kjPerKg ?: 0.0
        val meanW = fs?.meanMovingPowerW ?: 0.0
        val wTrusted = wp?.trusted == true
        val wPct = if (wTrusted) wp!!.pct else null
        val drift = fs?.driftPct(now)
        val stableSince = fs?.driftStableSinceMs
        val driftStableMs = if (drift != null && drift < 5.0 && stableSince != null) now - stableSince else null
        // A few seconds of power is not a ride average: no projection until MIN_PROJ_MOVING_S of it.
        val sampled = (fs?.movingSeconds ?: 0.0) >= MIN_PROJ_MOVING_S

        var ahead: List<ClimbBlock> = emptyList(); var next: ClimbBlock? = null
        var remainingS: Double? = null; var toNextS: Double? = null; var toNextM: Double? = null
        var projFinish: Double? = null; var projTop: Double? = null; var projWAtBlock: Double? = null
        if (trusted) {
            ahead = blocks.filter { it.endM > pos!! }
            next = ahead.firstOrNull { it.startM > pos!! }
            remainingS = secondsBetween(pos!!, p!!.totalM)
            next?.let { toNextM = it.startM - pos; toNextS = secondsBetween(pos, it.startM) }
            if (weightKg > 0 && sampled) {
                projFinish = kj + meanW * remainingS / 1000.0 / weightKg
                projTop = next?.let { kj + meanW * secondsBetween(pos, it.endM) / 1000.0 / weightKg }
            }
            if (wTrusted && sampled) projWAtBlock = toNextS?.let { wp!!.projectPct(it, meanW) }
            if (firstProjFinish == null) firstProjFinish = projFinish
            if (projFinish != null) lastProjFinish = projFinish
        }

        val knee = kneeKjKgOverride ?: DEFAULT_KNEE_KJKG
        val input = PacingInput(
            nowMs = now, moving = moving, hasPower = lastPowerAtMs?.let { now - it <= POWER_STALE_MS } == true, positionTrusted = trusted, climbsKnown = prof != null,
            remainingMovingS = remainingS, elapsedMovingS = movingS, blocksAhead = ahead.size,
            secondsToNextBlock = toNextS, metresToNextBlock = toNextM, nextBlockId = next?.key,
            kjPerKg = kj, projectedKjKgFinish = projFinish, projectedKjKgNextTop = projTop, kneeKjKg = knee,
            driftPct = drift, driftStableMs = driftStableMs, wTrusted = wTrusted, wPct = wPct,
            projectedWAtNextBlockPct = projWAtBlock, msSinceWellnessOrMedical = lastMedicalMs?.let { now - it },
            powerCoverage = powerCoverage(),
        )
        val d = PacingAdvisor.decide(input, prevState)
        val changed = d.state != prevState || d.recoverW != prevRecoverW
        val adv = { advFields(input, d) }

        // Candidates
        val cands = ArrayList<Pair<AlertCandidate, PacingAlert>>()
        fun alert(src: AlertSource, st: PacingState?, key: String, b: ClimbBlock?, ceiling: Int? = null, grams: Int? = null, withLogs: Boolean = false) =
            PacingAlert(src, st, key, b?.startM?.div(1000.0), b?.lengthM?.div(1000.0), b?.gradePct, ceiling, wPct, grams, withLogs)
        val worst = listOfNotNull(projFinish, projTop).maxOrNull()
        val conserveSev = maxOf((worst ?: 0.0) / knee, (drift ?: 0.0) / 10.0).takeIf { it > 0 } ?: 1.0
        val ceilingW = if (remainingS != null && remainingS > 0 && weightKg > 0)
            ((knee - kj) * weightKg * 1000.0 / remainingS).takeIf { it > 0 }?.toInt() else null

        if (d.state == PacingState.CONSERVE) conserveSinceMs = conserveSinceMs ?: now
        else { conserveSinceMs = null; condCandidateDone = false }
        // One pacing candidate per block: the first window tick in CONSERVE/HIGH_MARGIN, whether or not the state just changed.
        if (d.alertWindowPacing && next != null) {
            if ((d.state == PacingState.CONSERVE || d.state == PacingState.HIGH_MARGIN) && pacingCandBlock != next.key) {
                pacingCandBlock = next.key
                val sev = if (d.state == PacingState.CONSERVE) conserveSev else 0.5
                val key = "${d.state}#${next.key}"
                cands += AlertCandidate(AlertSource.PACING, key, sev) to alert(AlertSource.PACING, d.state, key, next, ceilingW.takeIf { d.state == PacingState.CONSERVE })
                if (d.state == PacingState.CONSERVE) condCandidateDone = true
            } else if (prevState == PacingState.CONSERVE && d.state == PacingState.KEEP && keepCandBlock != next.key) {
                keepCandBlock = next.key
                val key = "KEEP#${next.key}"
                cands += AlertCandidate(AlertSource.PACING, key, 0.0) to alert(AlertSource.PACING, d.state, key, next)
            }
        }
        val since = conserveSinceMs
        if (d.state == PacingState.CONSERVE && !d.alertWindowPacing && !condCandidateDone && since != null && now - since >= COND_SUSTAIN_MS) {
            condCandidateDone = true
            cands += AlertCandidate(AlertSource.PACING, "CONSERVE#cond", conserveSev) to alert(AlertSource.PACING, d.state, "CONSERVE#cond", null, ceilingW)
        }
        if (d.alertWindowW && next != null && wCandBlock != next.key) {
            wCandBlock = next.key
            val key = "W#${next.key}"
            cands += AlertCandidate(AlertSource.W_PRIME, key, 1.0 - (projWAtBlock ?: 0.0)) to alert(AlertSource.W_PRIME, null, key, next, ftp.takeIf { it > 0 })
        }
        val cs = carbStatus
        if (d.alertWindowFuel && cs != null && next != null && fuelFiredBlock != next.key) {
            val r = FuelHint.decide(cs.carbsEnabled && cs.masterEnabled, cs.cumLoggedG, cs.cumBurnedG, cs.deficitG,
                cs.deficitThresholdG, movingS / 60.0, true, lastCarbAlertMs?.let { now - it })
            val key = "FUEL#${next.key}"
            var fuelVerdict: ArbiterVerdict? = null
            if (r.fire) {
                fuelFiredBlock = next.key
                val c = AlertCandidate(AlertSource.FUEL_HINT, key, 0.5)
                fuelVerdict = arbitrate(c, alert(AlertSource.FUEL_HINT, null, key, next, grams = r.grams, withLogs = r.withLogs), now, adv)
            }
            if (r.fire || fuelLoggedBlock != next.key) {
                fuelLoggedBlock = next.key
                calibLogger?.log(Event.PACE_FUEL) {
                    "block=${next.key},fire=${r.fire},reason=${r.reason},grams=${r.grams},with_logs=${r.withLogs}," +
                        "would_alert=${fuelVerdict?.fire ?: false},suppressed_by=${fuelVerdict?.suppressedBy ?: "na"},live=$liveAlerts"
                }
            }
        }
        for ((c, a) in cands) arbitrate(c, a, now, adv)
        if (changed && cands.isEmpty()) calibLogger?.log(Event.PACE_ADV) { "${adv()},key=na,would_alert=na,suppressed_by=na,live=$liveAlerts" }

        if (tickN % 2 == 0) calibLogger?.log(Event.PACE_POS) {
            "pos_m=${f(pos, 0)},trusted=$trusted,reason=${rp.reason},odo_m=${f(odometerM, 0)}," +
                "pwr_age_s=${f(lastPowerAtMs?.let { (now - it) / 1000.0 }, 0)}"
        }
        if (trusted) trackClimbs(pos!!, next, toNextS, now, wPct, drift)

        prevState = d.state; prevRecoverW = d.recoverW
        _view.value = PacingView(d.state, d.recoverW, wPct, trusted, toNextM?.div(1000.0))
    }

    /** Power-sampled moving seconds over moving seconds; null under a minute moving or before a profile. */
    private fun powerCoverage(): Double? = fatigue?.takeIf { movingS >= 60.0 }?.let { it.movingSeconds / movingS }

    private fun arbitrate(c: AlertCandidate, a: PacingAlert, now: Long, adv: () -> String): ArbiterVerdict {
        evaluatedCandidateKeys += c.key
        val v = arbiter.evaluate(c, now, emergencyActive)
        if (liveAlerts && v.fire) { arbiter.recordFired(c.source, c.key, c.severity, now); alertSink?.invoke(a) }
        calibLogger?.log(Event.PACE_ADV) {
            "${adv()},key=${c.key},sev=${f(c.severity, 2)},would_alert=${v.fire},suppressed_by=${v.suppressedBy ?: "none"},live=$liveAlerts"
        }
        return v
    }

    private fun trackClimbs(pos: Double, next: ClimbBlock?, toNextS: Double?, now: Long, wPct: Double?, drift: Double?) {
        if (next != null && toNextS != null) {
            if (predBlock != next.key) { predBlock = next.key; predFirstAtMs = now; predFirstS = toNextS }
            predLastAtMs = now; predLastS = toNextS
        }
        val fs = fatigue
        val cur = inBlock
        if (cur != null) {
            if (wPct != null) blockWMin = minOf(blockWMin ?: wPct, wPct)
            if (pos < cur.startM || pos > cur.endM) {
                val dMov = (fs?.movingSeconds ?: 0.0) - blockMov0
                val meanW = if (dMov > 0) ((fs?.kj ?: 0.0) - blockKj0) * 1000.0 / dMov else null
                calibLogger?.log(Event.PACE_CLIMB) {
                    "phase=end,block=${cur.key},w_min=${f(blockWMin, 2)},mean_w=${f(meanW, 0)},drift=${f(drift)}"
                }
                inBlock = null
            }
        }
        if (inBlock == null) {
            val b = blocks.firstOrNull { pos >= it.startM && pos <= it.endM } ?: return
            inBlock = b; blockWMin = wPct; blockKj0 = fs?.kj ?: 0.0; blockMov0 = fs?.movingSeconds ?: 0.0
            val tracked = predBlock == b.key
            calibLogger?.log(Event.PACE_CLIMB) {
                "phase=start,block=${b.key},len_m=${f(b.lengthM, 0)},gain_m=${f(b.gainM, 0)}," +
                    "pred_s_first=${if (tracked) f(predFirstS, 0) else "na"}," +
                    "pred_s_at_last_eval=${if (tracked) f(predLastS, 0) else "na"}," +
                    "actual_s=${if (tracked) f((now - predFirstAtMs) / 1000.0, 0) else "na"}," +
                    "actual_s_since_last_eval=${if (tracked) f((now - predLastAtMs) / 1000.0, 0) else "na"}"
            }
        }
    }

    private fun advFields(i: PacingInput, d: PacingDecision) =
        "state=${d.state},recover_w=${d.recoverW},reason=${d.reason},moving=${i.moving},has_power=${i.hasPower}," +
            "pos_trusted=${i.positionTrusted},climbs_known=${i.climbsKnown},remaining_s=${f(i.remainingMovingS, 0)}," +
            "elapsed_s=${f(i.elapsedMovingS, 0)},blocks_ahead=${i.blocksAhead},next_block=${i.nextBlockId ?: "na"}," +
            "to_next_s=${f(i.secondsToNextBlock, 0)},to_next_m=${f(i.metresToNextBlock, 0)},kjkg=${f(i.kjPerKg, 2)}," +
            "proj_finish=${f(i.projectedKjKgFinish, 2)},proj_top=${f(i.projectedKjKgNextTop, 2)},knee=${f(i.kneeKjKg)}," +
            "knee_src=${if (kneeKjKgOverride != null) "override" else "default"},drift=${f(i.driftPct)}," +
            "drift_stable_s=${f(i.driftStableMs?.div(1000.0), 0)},w_trusted=${i.wTrusted},w_pct=${f(i.wPct, 2)}," +
            "proj_w_block=${f(i.projectedWAtNextBlockPct, 2)},power_cov=${f(i.powerCoverage, 2)},ms_since_med=${i.msSinceWellnessOrMedical ?: "na"}," +
            "ftp=$ftp,kind=$kind,gp_conf=${gradePace.isConfident(kind, 0.0)}"

    private fun f(v: Double?, decimals: Int = 1): String =
        if (v == null || !v.isFinite()) "na" else String.format(Locale.US, "%.${decimals}f", v)

    companion object {
        const val DEFAULT_KNEE_KJKG = 30.0
        const val COND_SUSTAIN_MS = 90_000L
        const val BLOCKS_REFRESH_MS = 5 * 60_000L
        const val POWER_STALE_MS = 10_000L
        const val HR_STALE_MS = 5_000L
        const val MIN_PROJ_MOVING_S = 600.0
        /** Stable across block recomputes (ids renumber when gaps merge): route metres of the block start. */
        private val ClimbBlock.key: Int get() = startM.roundToInt()
    }
}
