package com.lanshare.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val kind: String,
    val sizeH: String
)

data class ServerHit(val name: String, val ip: String, val port: Int)

class Api(var base: String = "", var token: String = "") {
    fun login(pin: String): String {
        val conn = post("/api/login", """{"pin":"$pin"}""")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("PIN غير صحيح")
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

fun discoverServers(): List<ServerHit> {
    val found = linkedMapOf<String, ServerHit>()
    val sock = DatagramSocket()
    sock.broadcast = true
    sock.soTimeout = 900
    val payload = "LANSHARE_DISCOVER".toByteArray()
    val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 45454)
    try {
        sock.send(packet)
        val buf = ByteArray(2048)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2500) {
            try {
                val rec = DatagramPacket(buf, buf.size)
                sock.receive(rec)
                val msg = String(rec.data, 0, rec.length)
                if (msg.startsWith("LANSHARE_OK|")) {
                    val obj = JSONObject(msg.substringAfter("|"))
                    val hit = ServerHit(obj.optString("name"), obj.getString("ip"), obj.getInt("port"))
                    found[hit.ip] = hit
                }
            } catch (_: Exception) {
            }
        }
    } finally {
        sock.close()
    }
    return found.values.toList()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(
                    background = Color(0xFF0B1220),
                    surface = Color(0xFF121A2B),
                    primary = Color(0xFF5B8CFF)
                )) {
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
        "connect" -> ConnectScreen { host, pin, setStatus ->
            scope.launch {
                try {
                    val (p, list) = withContext(Dispatchers.IO) {
                        api.base = host.trim().trimEnd('/')
                        if (!api.base.startsWith("http")) api.base = "http://${api.base}"
                        val home = api.login(pin)
                        api.list(home)
                    }
                    path = p
                    items = list
                    screen = "browse"
                } catch (e: Exception) {
                    setStatus(e.message ?: "فشل الاتصال")
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
fun ConnectScreen(onConnect: (String, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ابحث عن الكمبيوتر على شبكة المحل") }
    var servers by remember { mutableStateOf(listOf<ServerHit>()) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Text("LAN Share", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("الاتصال بالكمبيوتر عبر LAN", color = Color(0xFF8EA0C4), modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            status = "جاري البحث…"
            scope.launch {
                val hits = withContext(Dispatchers.IO) { discoverServers() }
                servers = hits
                status = if (hits.isEmpty()) "لم يُعثر على جهاز. تأكد أن برنامج ويندوز يعمل." else "اختر الكمبيوتر"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("بحث تلقائي في الشبكة") }

        servers.forEach { s ->
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
                    host = "http://${s.ip}:${s.port}"
                },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121A2B))
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("http://${s.ip}:${s.port}", color = Color(0xFF8EA0C4), fontSize = 13.sp)
                }
            }
        }

        OutlinedTextField(
            host, { host = it },
            label = { Text("عنوان الكمبيوتر") },
            placeholder = { Text("http://192.168.1.10:8080") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        OutlinedTextField(
            pin, { pin = it },
            label = { Text("رمز PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            onClick = { onConnect(host, pin) { status = it } },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) { Text("اتصال") }
        Text(status, color = Color(0xFF8EA0C4), modifier = Modifier.padding(top = 16.dp), fontSize = 13.sp)
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
