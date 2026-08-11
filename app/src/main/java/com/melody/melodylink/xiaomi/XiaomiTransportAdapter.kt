package com.melody.melodylink.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.xiaomi.config.XiaomiDeviceCatalog
import com.melody.melodylink.xiaomi.config.XiaomiDeviceConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class XiaomiTransportAdapter(
    private val context: Context,
    private val listener: Listener,
    private val clientFactory: (String) -> XiaomiSppClient = { XiaomiSppClient(listener::onLog) },
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onStateChanged(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var job: Job? = null
    private var client: XiaomiSppClient? = null
    private var route: XiaomiDeviceConfig? = null
    private var decoder = XiaomiRcspStreamDecoder()
    private var policy: XiaomiAncPolicy? = null
    private var ancWriteVerified = false
    private var o77TargetInfoVerified = false
    private var currentState: EarbudsState? = null
    private var pending: CompletableDeferred<XiaomiRcspFrame>? = null
    private var pendingOpcode: Int? = null
    private var pendingSequence: Int? = null
    private var nextSequence = 0

    @Volatile var isConnected = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized fun connect(device: BluetoothDevice) {
        val selected = XiaomiDeviceCatalog.find(com.melody.melodylink.domain.DeviceIdentity(bluetoothName = device.name))?.route
            ?: run { listener.onFailed("Xiaomi device is not registered"); return }
        val request = generation.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            release(false)
            route = selected; policy = null; ancWriteVerified = false; o77TargetInfoVerified = false; currentState = null; decoder = XiaomiRcspStreamDecoder(); nextSequence = 0
            listener.onConnecting()
            val active = clientFactory(device.address); client = active
            try {
                active.connect(device).getOrThrow()
                if (request != generation.get()) return@launch
                coroutineScope {
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                        active.notifications.collect { chunk -> onIncoming(chunk, request) }
                    }
                    // mibudstest sends RCSP directly after the RFCOMM socket opens.  Xiaomi's
                    // AF00 authentication exchange belongs to its BLE characteristic channel
                    // and must not be injected into the SPP byte stream.
                    isConnected = true
                    listener.onLog("Xiaomi SPP RCSP channel ready")
                    readInitialState(request)
                    reader.join()
                }
            } catch (error: Throwable) {
                if (request == generation.get()) listener.onFailed("Xiaomi SPP session failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (request == generation.get()) release(isConnected)
            }
        }
    }

    fun disconnect() { generation.incrementAndGet(); job?.cancel(); scope.launch { release(isConnected) } }
    fun refreshBattery() = Unit // Xiaomi sends battery through +XIAOMI vendor events.

    fun acceptVendorBatteryEvent(value: String) {
        acceptVendorBatteryEvent(listOf(value))
    }

    fun acceptVendorBatteryEvent(values: Collection<String>) {
        val selected = route ?: return
        val battery = XiaomiBatteryParser.parseVendorEvent(values)
        if (battery.isEmpty()) return
        val state = (currentState ?: EarbudsState(capabilities(selected))).copy(battery = battery)
        currentState = state
        listener.onBatteryState(state)
    }

    fun setAncMode(mode: AncMode) {
        val selected = route
        val active = client
        if (selected?.isO77() == true) {
            if (!isConnected || !o77TargetInfoVerified || active == null) {
                listener.onAncWriteResult(false, null, "Redmi Buds 6 ANC session is not ready")
                return
            }
            scope.launch {
                val response = request(active, 0x08, XiaomiRcspCodec.setO77Anc(nextSequence(), mode))
                if (response?.status == 0 && response.payload.isEmpty()) {
                    val state = (currentState ?: EarbudsState(capabilities(selected))).copy(ancMode = mode)
                    currentState = state
                    listener.onAncWriteResult(true, state, "")
                } else listener.onAncWriteResult(false, null, "Redmi Buds 6 ANC command was rejected or timed out")
            }
            return
        }
        val rawMode = policy?.codeFor(mode)
        if (!isConnected || !ancWriteVerified || selected == null || active == null || rawMode == null) {
            listener.onAncWriteResult(false, null, "Xiaomi ANC mode is not verified for this session")
            return
        }
        scope.launch {
            val response = request(active, 0xF2, XiaomiRcspCodec.setAnc(
                nextSequence(), rawMode, XiaomiRcspCodec.SPP_TARGET_APP
            ))
            if (response?.status == 0) {
                val state = (currentState ?: EarbudsState(capabilities(selected))).copy(ancMode = mode)
                currentState = state
                listener.onAncWriteResult(true, state, "")
            } else listener.onAncWriteResult(false, null, "Xiaomi ANC command did not succeed")
        }
    }

    private suspend fun onIncoming(chunk: ByteArray, request: Long) {
        decoder.accept(chunk).forEach { frame ->
            listener.onLog("Xiaomi SPP RX frame opcode=0x${frame.opcode.toString(16).padStart(2, '0')}"
                + " control=0x${frame.control.toString(16).padStart(2, '0')}"
                + " parameterBytes=${frame.parameter.size}")
            if (!frame.isCommand && frame.opcode == 0x02) {
                updateAncCapability(frame.payload, request)
            }
            acceptO77Status(frame)
            if (!frame.isCommand && frame.opcode == pendingOpcode && frame.sequence == pendingSequence) pending?.complete(frame)
        }
    }

    private fun updateAncCapability(payload: ByteArray, request: Long) {
        val capability = extractCapabilityString(payload) ?: return
        val candidate = XiaomiAncPolicy.fromCapabilityString(capability)
        if (candidate == null) {
            listener.onLog("Xiaomi SPP target-info capability table does not define an unambiguous three-mode ANC mapping")
            return
        }
        if (policy == candidate) return
        policy = candidate
        listener.onLog("Xiaomi SPP target-info confirmed a three-mode ANC mapping")
        if (isConnected) scope.launch { readInitialState(request) }
    }

    private suspend fun readInitialState(request: Long) {
        val active = client ?: return
        val targetInfo = request(active, 0x02, XiaomiRcspCodec.getTargetInfo(
            nextSequence(), XiaomiRcspCodec.SPP_TARGET_APP
        ))
        if (route?.isO77() == true) {
            o77TargetInfoVerified = targetInfo?.status == 0
            targetInfo?.let(::acceptO77Status)
            if (!o77TargetInfoVerified) listener.onLog("Redmi Buds 6 target-info read was not confirmed")
        }
        if (request != generation.get()) return
        val response = request(active, 0xF3, XiaomiRcspCodec.getConfigs(
            nextSequence(), targetApp = XiaomiRcspCodec.SPP_TARGET_APP
        ))
        if (request != generation.get()) return
        val selected = route ?: return
        val config = response?.takeIf { it.status == 0 }?.let { XiaomiConfigParser.parse(it.payload) }
        if (response == null) {
            listener.onLog("Xiaomi SPP F3 returned no response")
        } else if (response.status != 0) {
            listener.onLog("Xiaomi SPP F3 returned status=${response.status}")
        } else if (config == null) {
            listener.onLog("Xiaomi SPP F3 configuration payload is malformed")
        } else {
            listener.onLog("Xiaomi SPP F3 configuration ids=" + config.joinToString(",") {
                "0x${it.id.toString(16).padStart(4, '0')}:${it.data.size}"
            })
        }
        val rawAnc = config?.firstOrNull { it.id == 0x000B }?.data
        rawAnc?.let {
            listener.onLog("Xiaomi SPP F3 ANC 000B=${it.toHex()}")
        }
        val ancMode = if (selected.isO77()) currentState?.ancMode else rawAnc?.let { policy?.modeFor(it) }
        if (rawAnc != null && policy == null) {
            listener.onLog("Xiaomi SPP ANC remains read-only: target capability mode table is unavailable")
        }
        ancWriteVerified = ancMode != null
        val state = EarbudsState(capabilities(selected), ancMode = ancMode,
            battery = currentState?.battery.orEmpty())
        currentState = state
        listener.onConnected(state)
    }

    private suspend fun request(active: XiaomiSppClient, opcode: Int, bytes: ByteArray): XiaomiRcspFrame? {
        val sequence = bytes[7].toInt() and 0xFF
        val completion = CompletableDeferred<XiaomiRcspFrame>()
        pending = completion; pendingOpcode = opcode; pendingSequence = sequence
        try {
            listener.onLog("Xiaomi SPP TX frame opcode=0x${opcode.toString(16).padStart(2, '0')}"
                + " sequence=$sequence bytes=${bytes.size}")
            active.write(bytes).getOrThrow()
            return withTimeoutOrNull(500L) { completion.await() }.also {
                if (it == null) listener.onLog("Xiaomi SPP response timeout opcode=0x${opcode.toString(16).padStart(2, '0')} sequence=$sequence")
            }
        } finally {
            pending = null; pendingOpcode = null; pendingSequence = null
        }
    }

    private fun nextSequence(): Int = nextSequence++ and 0xFF
    private fun capabilities(config: XiaomiDeviceConfig) = EarbudsCapabilities(
        ancModes = if (policy == null) emptySet() else setOf(AncMode.OFF, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY),
        batteryParts = config.batteryParts,
    )

    private fun acceptO77Status(frame: XiaomiRcspFrame) {
        if (route?.isO77() != true) return
        val update = XiaomiO77StatusParser.parse(frame) ?: return
        val selected = route ?: return
        val state = (currentState ?: EarbudsState(capabilities(selected))).copy(
            ancMode = update.ancMode ?: currentState?.ancMode,
            battery = update.battery ?: currentState?.battery.orEmpty(),
        )
        currentState = state
        listener.onStateChanged(state)
        if (update.battery != null) {
            listener.onLog("Redmi Buds 6 SPP battery state received")
            listener.onBatteryState(state)
        }
    }

    private fun XiaomiDeviceConfig.isO77() = id == "xiaomi.redmi_buds_6"

    private suspend fun release(notify: Boolean) {
        client?.close(); client = null; route = null; policy = null; ancWriteVerified = false; o77TargetInfoVerified = false; currentState = null; isConnected = false
        if (notify) listener.onDisconnected()
    }

    fun releaseResources() { disconnect(); scope.cancel() }

    private fun extractCapabilityString(value: ByteArray): String? {
        val text = buildString {
            value.forEach { byte -> append(if (byte.toInt().and(0xFF) in 32..126) byte.toInt().and(0xFF).toChar() else '\n') }
        }
        return text.lineSequence().firstOrNull { candidate ->
            candidate.count { character -> character == ',' } == 3
                && candidate.length <= 512
                && candidate.split(',').getOrNull(1)?.contains(';') == true
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
