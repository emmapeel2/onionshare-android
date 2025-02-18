package org.onionshare.android.tor

import android.app.Application
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat.startForegroundService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.briarproject.moat.MoatApi
import org.briarproject.onionwrapper.CircumventionProvider
import org.briarproject.onionwrapper.LocationUtils
import org.briarproject.onionwrapper.TorWrapper
import org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTED
import org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTING
import org.briarproject.onionwrapper.TorWrapper.TorState.DISABLED
import org.briarproject.onionwrapper.TorWrapper.TorState.NOT_STARTED
import org.briarproject.onionwrapper.TorWrapper.TorState.STARTED
import org.briarproject.onionwrapper.TorWrapper.TorState.STARTING
import org.briarproject.onionwrapper.TorWrapper.TorState.STOPPED
import org.briarproject.onionwrapper.TorWrapper.TorState.STOPPING
import org.onionshare.android.server.PORT
import org.onionshare.android.ui.settings.SettingsManager
import org.slf4j.LoggerFactory.getLogger
import java.io.IOException
import java.util.concurrent.TimeUnit.MINUTES
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private val LOG = getLogger(TorManager::class.java)
private val TOR_START_TIMEOUT_SINCE_START = MINUTES.toMillis(5)
private val TOR_START_TIMEOUT_SINCE_LAST_PROGRESS = MINUTES.toMillis(2)
private const val MOAT_URL = "https://onion.azureedge.net/"
private const val MOAT_FRONT = "ajax.aspnetcdn.com"

@Singleton
class TorManager @Inject constructor(
    private val app: Application,
    private val tor: TorWrapper,
    private val settingsManager: SettingsManager,
    private val circumventionProvider: CircumventionProvider,
    private val locationUtils: LocationUtils,
) : TorWrapper.Observer {

    private val _state = MutableStateFlow<TorState>(TorState.Stopped(false))
    internal val state = _state.asStateFlow()

    private var startCheckJob: Job? = null

    init {
        tor.setObserver(this@TorManager)
    }

    /**
     * Starts [ShareService] and creates a new onion service.
     * Suspends until the address of the onion service is available.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (state.value !is TorState.Stopped) stop()

        LOG.info("Starting...")
        val now = System.currentTimeMillis()
        _state.value = TorState.Starting(progress = 0, startTime = now, lastProgressTime = now)

        Intent(app, ShareService::class.java).also { intent ->
            startForegroundService(app, intent)
        }

        tor.start()
    }

    fun stop(failedToConnect: Boolean = false) {
        LOG.info("Stopping...")
        startCheckJob?.cancel()
        startCheckJob = null
        _state.value = TorState.Stopping(failedToConnect)
        tor.stop()
        Intent(app, ShareService::class.java).also { intent ->
            app.stopService(intent)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onState(s: TorWrapper.TorState) {
        when (s) {
            NOT_STARTED -> LOG.info("new state: not started")
            STARTING -> LOG.info("new state: starting")
            STARTED -> GlobalScope.launch {
                LOG.info("new state: started")
                try {
                    onTorServiceStarted()
                } catch (e: Exception) {
                    LOG.warn("Error while starting Tor: ", e)
                    stop()
                }
            }

            CONNECTING -> LOG.info("new state: connecting")
            CONNECTED -> LOG.info("new state: connected")
            DISABLED -> LOG.info("new state: network disabled")
            STOPPING -> LOG.info("new state: stopping")
            STOPPED -> onStopped()
        }
    }

    @UiThread
    fun onStopped() {
        LOG.info("Stopped")
        val failedToConnect = (state.value as? TorState.Stopping)?.failedToConnect == true
        _state.value = TorState.Stopped(failedToConnect)
    }

    override fun onBootstrapPercentage(percentage: Int) {
        changeStartingState((percentage * 0.7).roundToInt())
    }

    override fun onHsDescriptorUpload(onion: String) {
        if (state.value !is TorState.Started) changeStartingState(90, onion)
        onDescriptorUploaded(onion)
    }

    override fun onClockSkewDetected(skewSeconds: Long) {
        // TODO
    }

    @Throws(IOException::class, IllegalArgumentException::class)
    private suspend fun onTorServiceStarted() = withContext(Dispatchers.IO) {
        changeStartingState(5)
        val autoMode = settingsManager.automaticBridges.value
        if (!autoMode) {
            val customBridges = settingsManager.customBridges.value
            if (customBridges.isNotEmpty()) {
                LOG.info("Using ${customBridges.size} custom bridges...")
                tor.enableBridges(customBridges.map { "Bridge $it" })
            }
        }
        LOG.info("Starting hidden service...")
        val hsResponse = tor.publishHiddenService(PORT, 80, null)
        changeStartingState(10, hsResponse.onion)
        if (autoMode) {
            startCheckJob = launch {
                LOG.info("Starting check job")
                checkStartupProgress()
                LOG.info("Check job finished")
            }
        }
        tor.enableNetwork(true)
    }

    private fun changeStartingState(progress: Int, onion: String? = null) {
        val oldStartingState = state.value as? TorState.Starting
        if (oldStartingState == null) LOG.warn("Old state was not Starting, but ${state.value}")
        val now = System.currentTimeMillis()
        val newState = if (onion == null) oldStartingState?.copy(progress = progress, lastProgressTime = now)
        else oldStartingState?.copy(progress = progress, onion = onion, lastProgressTime = now)
        _state.value = newState ?: TorState.Starting(
            progress = progress,
            startTime = now,
            lastProgressTime = now,
            onion = onion,
        )
    }

    private fun onDescriptorUploaded(onion: String) {
        _state.value = TorState.Started(onion)
        startCheckJob?.cancel()
        startCheckJob = null
    }

    private suspend fun checkStartupProgress() {
        // first we try to start Tor without bridges
        if (waitForTorToStart()) return
        LOG.info("Getting bridges from Moat...")
        val bridges: List<String>? = try {
            getBridgesFromMoat()
        } catch (e: IOException) {
            LOG.warn("Error getting bridges from moat: ", e)
            null
        }
        // if Tor finished starting while we were getting bridges from Moat then we don't need them
        if (state.value !is TorState.Starting) return
        // try bridges we got from Moat, if any
        if (!bridges.isNullOrEmpty()) {
            LOG.info("Using bridges from Moat...")
            useBridges(bridges)
            if (waitForTorToStart()) return
        }
        // use built-in bridges
        LOG.info("Using built-in bridges...")
        val countryCode = locationUtils.currentCountry
        val builtInBridges = circumventionProvider.getSuitableBridgeTypes(countryCode).flatMap { type ->
            circumventionProvider.getBridges(type, countryCode, SDK_INT >= 25)
        }
        useBridges(builtInBridges)
        if (waitForTorToStart()) return
        LOG.info("Could not connect to Tor, stopping...")
        stop(failedToConnect = true)
    }

    /**
     * Waits for [state] to become something other than [TorState.Starting].
     *
     * @return True if [state] became something other than [TorState.Starting], or false if startup took more than
     * [TOR_START_TIMEOUT_SINCE_START] ms or failed to make progress for more than
     * [TOR_START_TIMEOUT_SINCE_LAST_PROGRESS] ms.
     */
    private suspend fun waitForTorToStart(): Boolean {
        LOG.info("Waiting for Tor to start...")
        // Measure TOR_START_TIMEOUT_SINCE_START from the time when this method was called, rather than the time
        // when Tor was started, otherwise if one connection method times out then all subsequent methods will be
        // considered to have timed out too
        val start = System.currentTimeMillis()
        while (true) {
            val s = state.value
            if (s !is TorState.Starting) return true
            val now = System.currentTimeMillis()
            if (now - start > TOR_START_TIMEOUT_SINCE_START) {
                LOG.info("Tor is taking too long to start")
                return false
            }
            if (now - s.lastProgressTime > TOR_START_TIMEOUT_SINCE_LAST_PROGRESS) {
                LOG.info("Tor startup is not making progress")
                return false
            }
            delay(1_000)
        }
    }

    private fun getBridgesFromMoat(): List<String> {
        val obfs4Executable = tor.obfs4ExecutableFile
        val stateDir = app.getDir("state", MODE_PRIVATE)
        val moat = MoatApi(obfs4Executable, stateDir, MOAT_URL, MOAT_FRONT)
        val bridges = moat.get().let {
            // if response was empty, try it again with what we think the country should be
            if (it.isEmpty()) moat.getWithCountry(locationUtils.currentCountry)
            else it
        }
        return bridges.flatMap { bridge ->
            bridge.bridgeStrings.map { line ->
                "Bridge $line"
            }
        }
    }

    private fun useBridges(bridges: List<String>) {
        if (LOG.isInfoEnabled) {
            LOG.info("Using bridges:")
            bridges.forEach { LOG.info("  $it") }
        }
        tor.enableBridges(bridges)
    }

}
