package com.example.samsung_remote_test.samsung

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class SamsungSsdpDiscoverer(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient()
) {
    fun scan(durationMs: Long = 2500L): Flow<SamsungDiscoveredTv> = channelFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = runCatching {
            wifi.createMulticastLock("samsung_ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        try {
            val seen = HashSet<String>()
            val group = InetAddress.getByName("239.255.255.250")
            val port = 1900

            val sts = listOf(
                "urn:samsung.com:device:RemoteControlReceiver:1",
                "urn:dial-multiscreen-org:service:dial:1"
            )

            val start = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                DatagramSocket().use { sock ->
                    sock.soTimeout = 250

                    sts.forEach { st ->
                        val msg = buildMSearch(st)
                        val data = msg.toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(data, data.size, group, port))
                    }

                    while (System.currentTimeMillis() - start < durationMs) {
                        val buf = ByteArray(4096)
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }

                        val ip = pkt.address.hostAddress ?: continue
                        val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val headers = parseHeaders(raw)

                        val st = headers["st"] ?: headers["nt"]
                        val usn = headers["usn"]
                        val server = headers["server"]
                        val location = headers["location"]

                        if (!looksSamsungByHeaders(st, usn, server) && location.isNullOrBlank()) continue

                        val meta = if (!location.isNullOrBlank()) fetchDeviceMeta(location) else null
                        if (!looksSamsungByHeaders(st, usn, server) && !looksSamsungByMeta(meta)) continue

                        val key = "${ip}|${usn.orEmpty()}"
                        if (!seen.add(key)) continue

                        trySend(
                            SamsungDiscoveredTv(
                                ip = ip,
                                location = location,
                                st = st,
                                usn = usn,
                                server = server,
                                friendlyName = meta?.friendlyName,
                                modelName = meta?.modelName
                            )
                        )
                    }
                }
            }
        } finally {
            runCatching { lock?.release() }
            awaitClose { }
        }
    }

    private fun buildMSearch(st: String): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: $st\r\n" +
                "\r\n"
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        raw.split("\r\n").forEach { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@forEach
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            out[k] = v
        }
        return out
    }

    private fun looksSamsungByHeaders(st: String?, usn: String?, server: String?): Boolean {
        val s = (st ?: "").lowercase()
        val u = (usn ?: "").lowercase()
        val sv = (server ?: "").lowercase()
        return s.contains("samsung.com") || u.contains("samsung.com") || sv.contains("samsung")
    }

    private fun looksSamsungByMeta(meta: DeviceMeta?): Boolean {
        val m = (meta?.manufacturer ?: "").lowercase()
        val dt = (meta?.deviceType ?: "").lowercase()
        return m.contains("samsung") || dt.contains("samsung")
    }

    private data class DeviceMeta(
        val friendlyName: String?,
        val modelName: String?,
        val manufacturer: String?,
        val deviceType: String?
    )

    private fun fetchDeviceMeta(location: String): DeviceMeta? {
        val xml = runCatching {
            val req = Request.Builder().url(location).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return null

        var friendly: String? = null
        var model: String? = null
        var manufacturer: String? = null
        var deviceType: String? = null

        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "friendlyName" -> {
                            parser.next()
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) friendly = parser.text
                        }
                        "modelName" -> {
                            parser.next()
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) model = parser.text
                        }
                        "manufacturer" -> {
                            parser.next()
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) manufacturer = parser.text
                        }
                        "deviceType" -> {
                            parser.next()
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) deviceType = parser.text
                        }
                    }
                }
                event = parser.next()
            }
        }

        return DeviceMeta(
            friendlyName = friendly,
            modelName = model,
            manufacturer = manufacturer,
            deviceType = deviceType
        )
    }
}