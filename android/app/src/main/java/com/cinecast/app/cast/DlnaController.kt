package com.cinecast.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import com.cinecast.app.data.network.NetworkClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.*
import java.util.Locale

data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val location: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null
)

data class CastSessionState(
    val connectedDevice: DlnaDevice? = null,
    val mediaTitle: String = "",
    val mediaUrl: String = "",
    val transportState: String = "STOPPED", // PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 50,
    val errorMessage: String? = null,
    val isBusy: Boolean = false
)

class DlnaController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _session = MutableStateFlow(CastSessionState())
    val session: StateFlow<CastSessionState> = _session.asStateFlow()

    private var scanJob: Job? = null
    private var pollJob: Job? = null

    fun startDiscovery(timeoutMs: Long = 5000L) {
        scanJob?.cancel()
        scanJob = scope.launch {
            _isScanning.value = true
            val foundLocations = LinkedHashSet<String>()
            val discovered = LinkedHashMap<String, DlnaDevice>()

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifiManager?.createMulticastLock("CineCastDlnaLock")?.apply {
                setReferenceCounted(true)
                try { acquire() } catch (_: Exception) {}
            }

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    soTimeout = 700
                    reuseAddress = true
                }
                val groupAddr = InetAddress.getByName("239.255.255.250")
                val searchTargets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1"
                )
                searchTargets.forEach { st ->
                    val payload = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 3\r\n")
                        append("ST: ").append(st).append("\r\n\r\n")
                    }.toByteArray()
                    val packet = DatagramPacket(payload, payload.size, groupAddr, 1900)
                    repeat(2) {
                        try { socket.send(packet) } catch (_: Exception) {}
                    }
                }

                val buf = ByteArray(4096)
                val endAt = System.currentTimeMillis() + timeoutMs
                while (isActive && System.currentTimeMillis() < endAt) {
                    val respPacket = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(respPacket)
                        val respText = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
                        val loc = extractHeader(respText, "LOCATION")
                        if (!loc.isNullOrBlank() && foundLocations.add(loc)) {
                            launch {
                                val dev = fetchDeviceDescription(loc)
                                if (dev != null) {
                                    synchronized(discovered) {
                                        discovered[dev.udn] = dev
                                        _devices.value = discovered.values.toList()
                                    }
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // continue until timeoutMs
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {}
                delay(400)
                _isScanning.value = false
            }
        }
    }

    fun castMedia(device: DlnaDevice, mediaUrl: String, title: String) {
        scope.launch {
            _session.value = _session.value.copy(
                connectedDevice = device,
                mediaTitle = title,
                mediaUrl = mediaUrl,
                isBusy = true,
                errorMessage = null,
                transportState = "TRANSITIONING"
            )
            try {
                // Stop previous playback if any
                runCatching { sendAvTransportAction(device, "Stop", "<InstanceID>0</InstanceID>") }

                val metadata = buildDidlLite(title, mediaUrl)
                val setUriArgs = """
                    <InstanceID>0</InstanceID>
                    <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                    <CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>
                """.trimIndent()
                sendAvTransportAction(device, "SetAVTransportURI", setUriArgs)

                val playArgs = """
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                """.trimIndent()
                sendAvTransportAction(device, "Play", playArgs)

                _session.value = _session.value.copy(
                    isBusy = false,
                    transportState = "PLAYING",
                    errorMessage = null
                )
                startPolling(device)
            } catch (e: Exception) {
                _session.value = _session.value.copy(
                    isBusy = false,
                    transportState = "STOPPED",
                    errorMessage = "投屏失败：${e.message ?: "请检查电视与手机是否在同一Wi-Fi"}"
                )
            }
        }
    }

    fun play() {
        val device = _session.value.connectedDevice ?: return
        scope.launch {
            runCatching {
                sendAvTransportAction(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                _session.value = _session.value.copy(transportState = "PLAYING", errorMessage = null)
            }.onFailure {
                _session.value = _session.value.copy(errorMessage = "继续播放失败：${it.message}")
            }
        }
    }

    fun pause() {
        val device = _session.value.connectedDevice ?: return
        scope.launch {
            runCatching {
                sendAvTransportAction(device, "Pause", "<InstanceID>0</InstanceID>")
                _session.value = _session.value.copy(transportState = "PAUSED_PLAYBACK", errorMessage = null)
            }.onFailure {
                _session.value = _session.value.copy(errorMessage = "暂停失败：${it.message}")
            }
        }
    }

    fun stopAndDisconnect() {
        val device = _session.value.connectedDevice
        pollJob?.cancel()
        scope.launch {
            if (device != null) {
                runCatching { sendAvTransportAction(device, "Stop", "<InstanceID>0</InstanceID>") }
            }
            _session.value = CastSessionState()
        }
    }

    fun seekTo(positionMs: Long) {
        val device = _session.value.connectedDevice ?: return
        scope.launch {
            val formatted = formatUpnpTime(positionMs)
            runCatching {
                sendAvTransportAction(
                    device,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$formatted</Target>"
                )
                _session.value = _session.value.copy(positionMs = positionMs, errorMessage = null)
            }
        }
    }

    fun adjustVolume(delta: Int) {
        val device = _session.value.connectedDevice ?: return
        if (device.renderingControlUrl.isNullOrBlank()) return
        val newVol = (_session.value.volume + delta).coerceIn(0, 100)
        scope.launch {
            runCatching {
                sendRenderingAction(
                    device,
                    "SetVolume",
                    "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$newVol</DesiredVolume>"
                )
                _session.value = _session.value.copy(volume = newVol)
            }
        }
    }

    private fun startPolling(device: DlnaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _session.value.connectedDevice?.udn == device.udn) {
                runCatching {
                    val posXml = sendAvTransportAction(device, "GetPositionInfo", "<InstanceID>0</InstanceID>")
                    val relTime = extractXmlTag(posXml, "RelTime")
                    val duration = extractXmlTag(posXml, "TrackDuration")
                    val infoXml = sendAvTransportAction(device, "GetTransportInfo", "<InstanceID>0</InstanceID>")
                    val state = extractXmlTag(infoXml, "CurrentTransportState")

                    _session.value = _session.value.copy(
                        positionMs = parseUpnpTime(relTime).takeIf { it >= 0 } ?: _session.value.positionMs,
                        durationMs = parseUpnpTime(duration).takeIf { it > 0 } ?: _session.value.durationMs,
                        transportState = state.ifBlank { _session.value.transportState }
                    )
                }
                delay(2000L)
            }
        }
    }

    private fun sendAvTransportAction(device: DlnaDevice, action: String, innerArgs: String): String {
        val serviceType = "urn:schemas-upnp-org:service:AVTransport:1"
        return sendSoapRequest(device.avTransportControlUrl, serviceType, action, innerArgs)
    }

    private fun sendRenderingAction(device: DlnaDevice, action: String, innerArgs: String): String {
        val controlUrl = device.renderingControlUrl ?: throw IllegalStateException("设备不支持音量控制")
        val serviceType = "urn:schemas-upnp-org:service:RenderingControl:1"
        return sendSoapRequest(controlUrl, serviceType, action, innerArgs)
    }

    private fun sendSoapRequest(
        controlUrl: String,
        serviceType: String,
        action: String,
        innerArgs: String
    ): String {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="$serviceType">
      $innerArgs
    </u:$action>
  </s:Body>
</s:Envelope>"""

        val request = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", "\"$serviceType#$action\"")
            .post(soapBody.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        NetworkClient.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("UPnP HTTP ${resp.code}")
            }
            return body
        }
    }

    private fun fetchDeviceDescription(location: String): DlnaDevice? {
        return try {
            val xml = NetworkClient.getString(location)
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var tag = ""
            var friendlyName = ""
            var manufacturer = ""
            var udn = location
            var inService = false
            var serviceType = ""
            var controlUrl = ""
            var avTransportUrl: String? = null
            var renderingUrl: String? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag.equals("service", ignoreCase = true)) {
                            inService = true
                            serviceType = ""
                            controlUrl = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.orEmpty().trim()
                        if (text.isNotEmpty()) {
                            when {
                                !inService && tag.equals("friendlyName", ignoreCase = true) && friendlyName.isBlank() -> friendlyName = text
                                !inService && tag.equals("manufacturer", ignoreCase = true) && manufacturer.isBlank() -> manufacturer = text
                                !inService && tag.equals("UDN", ignoreCase = true) -> udn = text
                                inService && tag.equals("serviceType", ignoreCase = true) -> serviceType = text
                                inService && tag.equals("controlURL", ignoreCase = true) -> controlUrl = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("service", ignoreCase = true) && inService) {
                            if (serviceType.contains("AVTransport", ignoreCase = true) && controlUrl.isNotBlank()) {
                                avTransportUrl = resolveUrl(location, controlUrl)
                            } else if (serviceType.contains("RenderingControl", ignoreCase = true) && controlUrl.isNotBlank()) {
                                renderingUrl = resolveUrl(location, controlUrl)
                            }
                            inService = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }

            if (avTransportUrl.isNullOrBlank()) return null
            DlnaDevice(
                udn = udn,
                friendlyName = friendlyName.ifBlank { URI(location).host ?: "DLNA 电视设备" },
                manufacturer = manufacturer.ifBlank { "Smart TV" },
                location = location,
                avTransportControlUrl = avTransportUrl,
                renderingControlUrl = renderingUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(baseLocation: String, relative: String): String {
        return try {
            URI(baseLocation).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun extractHeader(response: String, headerName: String): String? {
        return response.lines()
            .firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    private fun extractXmlTag(xml: String, tagName: String): String {
        val regex = Regex("<$tagName[^>]*>(.*?)</$tagName>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun buildDidlLite(title: String, url: String): String {
        val mime = when {
            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            url.contains(".mp4", ignoreCase = true) -> "video/mp4"
            url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            else -> "video/*"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mime:*">${escapeXml(url)}</res></item></DIDL-Lite>"""
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatUpnpTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun parseUpnpTime(timeStr: String): Long {
        if (timeStr.isBlank() || timeStr.contains("NOT_IMPLEMENTED", ignoreCase = true)) return -1L
        val parts = timeStr.split(":")
        if (parts.size != 3) return -1L
        val h = parts[0].toLongOrNull() ?: return -1L
        val m = parts[1].toLongOrNull() ?: return -1L
        val s = parts[2].substringBefore(".").toLongOrNull() ?: return -1L
        return ((h * 3600) + (m * 60) + s) * 1000L
    }
}
