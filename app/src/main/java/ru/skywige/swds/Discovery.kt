package ru.skywige.swds

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Автопоиск SWDS в домашней сети.
 *  • UDP: широковещательный «SWDS?» на порт 47823 — SWDS отвечает своим id и адресами;
 *  • mDNS/NSD: служба _swds._tcp (если на компьютере установлен пакет zeroconf).
 * Нужен, когда роутер выдал компьютеру новый IP: телефон находит «свой» сервер по id и сам обновляет адрес.
 */
object Discovery {
    const val PORT = 47823
    private const val NSD_TYPE = "_swds._tcp."
    @Volatile private var lastHeal = 0L

    data class Found(val id: String, val name: String, val urls: List<String>, val via: String) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("urls", org.json.JSONArray(urls)).put("via", via)
    }

    /** Широковещательные адреса текущих сетей (255.255.255.255 часто режется роутером — шлём и в подсеть). */
    private fun broadcasts(ctx: Context): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address as? Inet4Address ?: continue
                    if (a.isLoopbackAddress) continue
                    val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                    val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                    val bc = ip or mask.inv()
                    out.add(InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())))
                }
            }
        }
        out.add(InetAddress.getByName("255.255.255.255"))
        return out.distinct()
    }

    private fun udp(ctx: Context, timeoutMs: Long, out: MutableMap<String, Found>) {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val msg = "SWDS?".toByteArray()
            val targets = broadcasts(ctx)
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {            // несколько попыток — UDP может теряться
                    for (t in targets) runCatching { s.send(DatagramPacket(msg, msg.size, t, PORT)) }
                    nextSend = System.currentTimeMillis() + 800
                }
                try {
                    val pk = DatagramPacket(buf, buf.size)
                    s.receive(pk)
                    val o = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
                    if (o.optInt("swds") != 1) continue
                    val urls = ArrayList<String>()
                    o.optJSONArray("urls")?.let { a -> for (i in 0 until a.length()) urls.add(a.optString(i).trimEnd('/')) }
                    if (urls.isEmpty()) urls.add("http://${pk.address.hostAddress}:${o.optInt("port", 7979)}")
                    val id = o.optString("id")
                    synchronized(out) { out[id.ifEmpty { urls[0] }] = Found(id, o.optString("name", "SWDS"), urls.filter { it.isNotEmpty() }, "udp") }
                } catch (e: SocketTimeoutException) {
                } catch (e: Exception) { }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun nsd(ctx: Context, timeoutMs: Long, out: MutableMap<String, Found>) {
        val nm = ctx.getSystemService(NsdManager::class.java) ?: return
        val found = ArrayList<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(found) { found.add(info) } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            nm.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(timeoutMs)
        } catch (e: Exception) { return } finally { runCatching { nm.stopServiceDiscovery(listener) } }
        for (info in synchronized(found) { found.toList() }) {        // разрешаем по одному (так требует NsdManager)
            val latch = CountDownLatch(1)
            var res: NsdServiceInfo? = null
            runCatching {
                nm.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { latch.countDown() }
                    override fun onServiceResolved(i: NsdServiceInfo) { res = i; latch.countDown() }
                })
            }
            latch.await(1500, TimeUnit.MILLISECONDS)
            val r = res ?: continue
            val host = r.host?.hostAddress ?: continue
            val attrs = r.attributes ?: emptyMap()
            val id = attrs["id"]?.let { String(it) } ?: ""
            val https = attrs["https"]?.let { String(it) }?.toIntOrNull() ?: 0
            val urls = listOfNotNull(if (https > 0) "https://$host:$https" else null, "http://$host:${r.port}")
            synchronized(out) { if (!out.containsKey(id.ifEmpty { urls.last() })) out[id.ifEmpty { urls.last() }] = Found(id, r.serviceName, urls, "mdns") }
        }
    }

    /** Найти все SWDS в сети. Долго (≈ timeoutMs) — только не в главном потоке. */
    fun scan(ctx: Context, timeoutMs: Long = 2500): List<Found> {
        val out = LinkedHashMap<String, Found>()
        val t = Thread { runCatching { nsd(ctx.applicationContext, timeoutMs, out) } }
        t.start()
        runCatching { udp(ctx, timeoutMs, out) }
        t.join(timeoutMs + 3000)
        return synchronized(out) { out.values.toList() }
    }

    private fun works(ctx: Context, url: String, token: String): Boolean = runCatching {
        val c = Api.open(url, "/modules/mobile/api/hello", "GET", token, 3000, 6000)
        val code = c.responseCode
        c.disconnect()
        code == 200
    }.getOrDefault(false)

    /**
     * Все известные адреса не отвечают — ищем свой SWDS в сети. Если нашёлся по новому адресу,
     * адрес запоминается и становится основным. Не чаще раза в 45 секунд.
     */
    fun heal(ctx: Context): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val now = System.currentTimeMillis()
        if (now - lastHeal < 45_000) return false
        lastHeal = now
        val p = Prefs(ctx)
        if (!p.paired) return false
        val mine = p.serverId
        for (f in scan(ctx, 2000)) {
            if (mine.isNotEmpty() && f.id.isNotEmpty() && f.id != mine) continue
            for (u in f.urls) {
                if (!works(ctx, u, p.token)) continue          // токен подходит — значит, это действительно наш сервер
                p.servers = listOf(u) + p.servers.filter { it != u }
                p.activeServer = u
                if (mine.isEmpty() && f.id.isNotEmpty()) p.serverId = f.id
                android.util.Log.i("SWDS", "сервер найден по новому адресу: $u")
                return true
            }
        }
        return false
    }
}
