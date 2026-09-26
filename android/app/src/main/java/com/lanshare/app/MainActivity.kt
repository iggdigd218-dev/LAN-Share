package com.lanshare.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val kind: String,
    val sizeH: String
)

data class ServerHit(val name: String, val ip: String, val port: Int)

fun normalizeHost(raw: String): String {
    var h = raw.trim().trimEnd('/')
    if (h.isBlank()) return ""
    if (!h.startsWith("http://") && !h.startsWith("https://")) {
        h = "http://$h"
    }
    try {
        val uri = Uri.parse(h)
        val host = uri.host
        val port = uri.port
        if (host != null && port == -1) {
            val scheme = uri.scheme ?: "http"
            val path = uri.path ?: ""
            h = "$scheme://$host:8080$path"
        }
    } catch (_: Exception) {}
    return h
}

fun parseQr(content: String): Pair<String, String> {
    val raw = content.trim()
    var pin = ""
    var host = ""

    // 1. JSON format: {"host":"...","pin":"..."}
    if (raw.startsWith("{") && raw.endsWith("}")) {
        try {
            val obj = JSONObject(raw)
            host = obj.optString("host", obj.optString("url", ""))
            pin = obj.optString("pin", "")
            return normalizeHost(host) to pin
        } catch (_: Exception) {}
    }

    // 2. Custom URI scheme: lanshare://...
    var cleaned = raw
    if (cleaned.startsWith("lanshare://", ignoreCase = true)) {
        cleaned = "http://" + cleaned.substring("lanshare://".length)
    }

    // 3. HTTP / HTTPS URL: http://ip:8080/?pin=...
    try {
        val uri = Uri.parse(if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) cleaned else "http://$cleaned")
        val pinParam = uri.getQueryParameter("pin")
        if (!pinParam.isNullOrBlank()) {
            pin = pinParam
        }
        val p = if (uri.port != -1) uri.port else 8080
        val h = uri.host
        if (!h.isNullOrBlank()) {
            host = "${uri.scheme ?: "http"}://$h:$p"
            return host to pin
        }
    } catch (_: Exception) {}

    return normalizeHost(cleaned) to pin
}

class Api(var base: String = "", var token: String = "") {
    fun login(pin: String): String {
        val conn = try {
            post("/api/login", """{"pin":"$pin"}""")
        } catch (e: Exception) {
            val emsg = e.message ?: ""
            if (emsg.contains("refused", ignoreCase = true)) {
                throw RuntimeException("تم رفض الاتصال: تأكد من تشغيل البرنامج على الكمبيوتر وأن جدار حماية ويندوز يسمح به.")
            } else if (emsg.contains("timeout", ignoreCase = true) || emsg.contains("timed out", ignoreCase = true)) {
                throw RuntimeException("انتهت مهلة الاتصال: تأكد أن الهاتف والكمبيوتر متصلان بنفس شبكة Wi-Fi.")
            } else if (emsg.contains("unreachable", ignoreCase = true)) {
                throw RuntimeException("تعذر الوصول لعنوان الكمبيوتر: تحقق من عنوان IP المدخل.")
            }
            throw RuntimeException("فشل الاتصال: ${e.localizedMessage}")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code == 403) throw RuntimeException("رمز PIN غير صحيح. تحقق من الرمز المعروض على شاشة الكمبيوتر.")
        if (code !in 200..299) throw RuntimeException("خطأ من الخادم ($code): $body")
        val tok = JSONObject(body).getString("token")
        token = tok
        return JSONObject(body).optString("home")
    }

    fun list(path: String): Pair<String, List<FileItem>> {
        val q = URLEncoder.encode(path, "UTF-8")
        val obj = JSONObject(get("/api/list?path=$q"))
        val arr = obj.getJSONArray("items")
        val items = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    FileItem(
                        o.getString("name"),
                        o.getString("path"),
                        o.getBoolean("is_dir"),
                        o.getString("kind"),
                        o.optString("size_h")
                    )
                )
            }
        }
        return obj.getString("path") to items
    }

    fun streamUrl(path: String) =
        "$base/api/stream?path=${URLEncoder.encode(path, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"

    fun downloadUrl(path: String) =
        "$base/api/download?path=${URLEncoder.encode(path, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"

    private fun get(path: String): String {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException(body)
        return body
    }

    private fun post(path: String, json: String): HttpURLConnection {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.write(json.toByteArray())
        return conn
    }
}

fun getLocalWifiIp(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            if (intf.isLoopback || !intf.isUp) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress ?: ""
                    if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        return ip
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return null
}

fun discoverServersUdp(): List<ServerHit> {
    val found = linkedMapOf<String, ServerHit>()
    val sock = DatagramSocket()
    try {
        sock.broadcast = true
        sock.soTimeout = 900
        val payload = "LANSHARE_DISCOVER".toByteArray()
        val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 45454)
        sock.send(packet)
        val buf = ByteArray(2048)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1500) {
            try {
                val rec = DatagramPacket(buf, buf.size)
                sock.receive(rec)
                val msg = String(rec.data, 0, rec.length)
                if (msg.startsWith("LANSHARE_OK|")) {
                    val obj = JSONObject(msg.substringAfter("|"))
                    val hit = ServerHit(obj.optString("name", "LAN Share"), obj.getString("ip"), obj.getInt("port"))
                    found[hit.ip] = hit
                }
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {
    } finally {
        try { sock.close() } catch (_: Exception) {}
    }
    return found.values.toList()
}

suspend fun scanSubnetForServers(): List<ServerHit> = withContext(Dispatchers.IO) {
    val localIp = getLocalWifiIp() ?: return@withContext emptyList()
    val prefix = localIp.substringBeforeLast('.') + "."
    val hits = CopyOnWriteArrayList<ServerHit>()
    val jobs = (1..254).map { i ->
        async {
            val targetIp = "$prefix$i"
            try {
                val conn = URL("http://$targetIp:8080/api/hello").openConnection() as HttpURLConnection
                conn.connectTimeout = 400
                conn.readTimeout = 400
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val obj = JSONObject(body)
                    if (obj.optString("name").contains("LAN Share", ignoreCase = true)) {
                        val comp = obj.optString("computer", "")
                        val label = if (comp.isNotBlank()) "كمبيوتر: $comp" else "LAN Share"
                        hits.add(ServerHit(label, targetIp, 8080))
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
    jobs.awaitAll()
    hits.toList()
}

suspend fun discoverServersCombined(): List<ServerHit> = withContext(Dispatchers.IO) {
    val map = linkedMapOf<String, ServerHit>()
    try {
        val udpHits = discoverServersUdp()
        for (h in udpHits) {
            map[h.ip] = h
        }
    } catch (_: Exception) {}

    if (map.isEmpty()) {
        try {
            val subnetHits = scanSubnetForServers()
            for (h in subnetHits) {
                map[h.ip] = h
            }
        } catch (_: Exception) {}
    }
    map.values.toList()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0B1220),
                        surface = Color(0xFF121A2B),
                        primary = Color(0xFF5B8CFF)
                    )
                ) {
                    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1220)) {
                        App()
                    }
                }
            }
        }
    }
}

@Composable
fun App() {
    val api = remember { Api() }
    var screen by remember { mutableStateOf("connect") }
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<FileItem>()) }
    var playing by remember { mutableStateOf<FileItem?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    when (screen) {
        "connect" -> ConnectScreen { targetHost, targetPin, setStatus ->
            scope.launch {
                try {
                    val normalized = normalizeHost(targetHost)
                    val (p, list) = withContext(Dispatchers.IO) {
                        api.base = normalized
                        val home = api.login(targetPin)
                        api.list(home)
                    }
                    path = p
                    items = list
                    screen = "browse"
                } catch (e: Exception) {
                    val msg = e.message ?: "فشل الاتصال"
                    setStatus(msg)
                }
            }
        }
        "browse" -> BrowseScreen(
            path = path,
            items = items,
            onBack = {
                val parent = path.replace('\\', '/').substringBeforeLast('/', path)
                scope.launch {
                    val (p, list) = withContext(Dispatchers.IO) { api.list(parent) }
                    path = p
                    items = list
                }
            },
            onOpen = { it ->
                if (it.isDir) {
                    scope.launch {
                        val (p, list) = withContext(Dispatchers.IO) { api.list(it.path) }
                        path = p
                        items = list
                    }
                } else if (it.kind == "audio" || it.kind == "video") {
                    playing = it
                } else {
                    download(ctx, api.downloadUrl(it.path), it.name)
                }
            },
            onDownload = { it -> download(ctx, api.downloadUrl(it.path), it.name) },
            onDisconnect = { screen = "connect" }
        )
    }

    playing?.let { f ->
        PlayerSheet(url = api.streamUrl(f.path), title = f.name, isVideo = f.kind == "video") {
            playing = null
        }
    }
}

fun download(ctx: Context, url: String, name: String) {
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setDescription("نسخ من الكمبيوتر")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    dm.enqueue(req)
}

@Composable
fun ConnectScreen(onConnect: (String, String, (String) -> Unit) -> Unit) {
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("وجّه كاميرا الهاتف نحو كود QR على شاشة الكمبيوتر، أو استخدم البحث التلقائي.") }
    var isSearching by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf(listOf<ServerHit>()) }
    val scope = rememberCoroutineScope()

    val scanLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        val content = result.contents
        if (!content.isNullOrBlank()) {
            val (detectedHost, detectedPin) = parseQr(content)
            if (detectedHost.isNotBlank()) {
                host = detectedHost
            }
            if (detectedPin.isNotBlank()) {
                pin = detectedPin
                status = "تم التقاط العنوان ورمز PIN بنجاح! جاري الاتصال…"
                onConnect(detectedHost, detectedPin) { status = it }
            } else {
                status = "تم التقاط عنوان الكمبيوتر! أدخل رمز PIN واضغط اتصال."
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("LAN Share", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "مشاركة الملفات عبر شبكة المحل",
            color = Color(0xFF8EA0C4),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Primary action: QR Code Scanner Button
        Button(
            onClick = {
                val options = ScanOptions().apply {
                    setPrompt("وجّه الكاميرا نحو كود QR على شاشة الكمبيوتر")
                    setBeepEnabled(true)
                    setOrientationLocked(false)
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                }
                scanLauncher.launch(options)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("📷  مسح كود QR من شاشة الكمبيوتر", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(
            "طريقة الربط الفورية: اضغط لمسح الكود الظاهر على شاشة الكمبيوتر",
            color = Color(0xFF8EA0C4),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )

        // Secondary action: Automatic LAN discovery
        OutlinedButton(
            onClick = {
                isSearching = true
                status = "جاري البحث عن الكمبيوتر في الشبكة المحلية…"
                scope.launch {
                    val hits = discoverServersCombined()
                    servers = hits
                    isSearching = false
                    if (hits.isEmpty()) {
                        status = "لم يُعثر على كمبيوتر تلقائياً. تأكد أن برنامج LAN Share يعمل على الكمبيوتر، أو امسح كود QR."
                    } else {
                        status = "تم العثور على ${hits.size} كمبيوتر. اختر جهازك:"
                        if (hits.size == 1) {
                            host = "http://${hits[0].ip}:${hits[0].port}"
                        }
                    }
                }
            },
            enabled = !isSearching,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("جاري البحث في الشبكة…")
            } else {
                Text("🔍  بحث تلقائي في الشبكة")
            }
        }

        if (servers.isNotEmpty()) {
            Text(
                "الأجهزة المتاحة:",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).align(Alignment.Start)
            )
            servers.forEach { s ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            host = "http://${s.ip}:${s.port}"
                            status = "تم اختيار: ${s.name} (${s.ip})"
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF162035)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💻", fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("http://${s.ip}:${s.port}", color = Color(0xFF8EA0C4), fontSize = 12.sp)
                        }
                        Text("اختيار", color = Color(0xFF5B8CFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color(0xFF1E2A44), thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        Text("أو الاتصال اليدوي بالبيانات", color = Color(0xFF8EA0C4), fontSize = 13.sp, modifier = Modifier.align(Alignment.Start))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("عنوان الكمبيوتر (IP)") },
            placeholder = { Text("مثال: 192.168.1.15:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.trim() },
            label = { Text("رمز PIN (المعروض على شاشة الكمبيوتر)") },
            placeholder = { Text("مثال: 123456") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        Button(
            onClick = {
                if (pin.isBlank()) {
                    status = "يرجى كتابة رمز PIN المعروض على شاشة الكمبيوتر"
                    return@Button
                }
                var target = host.trim()
                if (target.isBlank()) {
                    if (servers.size == 1) {
                        target = "http://${servers[0].ip}:${servers[0].port}"
                        host = target
                    } else {
                        status = "يرجى إدخال عنوان الكمبيوتر أو مسح كود QR"
                        return@Button
                    }
                }
                status = "جاري الاتصال بالكمبيوتر…"
                onConnect(normalizeHost(target), pin) { status = it }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B8CFF)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(50.dp)
        ) {
            Text("🔗  اتصال بالكمبيوتر", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        Card(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10192A)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = status,
                color = if (status.contains("غير صحيح") || status.contains("رفض") || status.contains("تعذر") || status.contains("خطأ") || status.contains("لم يُعثر"))
                    Color(0xFFFF6B6B)
                else if (status.contains("تم") || status.contains("بنجاح"))
                    Color(0xFF4ADE80)
                else
                    Color(0xFF8EA0C4),
                modifier = Modifier.padding(12.dp),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun BrowseScreen(
    path: String,
    items: List<FileItem>,
    onBack: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text(
                path,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp
            )
            TextButton(onClick = onDisconnect) { Text("خروج") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(items, key = { it.path }) { it ->
                val icon = when {
                    it.isDir -> "📁"
                    it.kind == "audio" -> "🎵"
                    it.kind == "video" -> "🎬"
                    it.kind == "image" -> "🖼️"
                    else -> "📄"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(Color(0xFF121A2B), RoundedCornerShape(16.dp))
                        .clickable { onOpen(it) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(icon, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(if (it.isDir) "مجلد" else it.sizeH, color = Color(0xFF8EA0C4), fontSize = 12.sp)
                    }
                    if (!it.isDir) {
                        TextButton(onClick = { onDownload(it) }) { Text("نسخ") }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSheet(url: String, title: String, isVideo: Boolean, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE60B1220)).clickable(enabled = false) {}
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF0E1628))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("إغلاق") }
            }
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(url))
                        setOnPreparedListener { it.isLooping = false; start() }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isVideo) 240.dp else 80.dp)
                    .padding(top = 8.dp)
            )
        }
    }
}
