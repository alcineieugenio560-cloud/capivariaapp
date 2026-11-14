package br.capivaria.shop

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

const val SITE = "https://capivariashopbr.store"
const val API = "$SITE/api/v1"
const val LOGO = "$SITE/__l5e/assets-v1/9488f642-2358-468f-93e1-fe1ab185d52e/logo-capivaria.png"
const val CB = "capivaria://auth/callback"
val GREEN = Color(0xFF22C55E); val BG = Color(0xFF0B1410); val CARD = Color(0xFF14231B)

fun JSONObject.s(vararg k: String): String { for (x in k) { val v = opt(x); if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) return v.toString() }; return "" }
fun JSONObject.b(vararg k: String) = k.any { val v = opt(it); v == true || v == 1 || v == "true" }
fun JSONArray?.list(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun img(u: String) = if (u.isEmpty() || u.startsWith("http")) u else SITE + (if (u.startsWith("/")) "" else "/") + u
fun brl(v: Double): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(v)
fun err(t: String) = runCatching { JSONObject(t).optString("error", "Erro") }.getOrDefault("Erro")
fun b64(x: ByteArray) = Base64.encodeToString(x, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
fun rnd(n: Int) = b64(ByteArray(n).also { SecureRandom().nextBytes(it) })
fun qr(t: String): ImageBitmap {
    val m = QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
    for (x in 0 until 512) for (y in 0 until 512) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else -1)
    return bmp.asImageBitmap()
}

object Net {
    lateinit var sp: SharedPreferences
    fun init(c: Context) {
        val k = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        sp = EncryptedSharedPreferences.create(c, "sec", k, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun req(m: String, u: String, body: String? = null, form: Boolean = false, auth: Boolean = false): Pair<Int, String> {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = m; c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        if (auth) sp.getString("at", null)?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    }
    suspend fun api(m: String, p: String, b: String? = null, auth: Boolean = true) = withContext(Dispatchers.IO) {
        try { var r = req(m, API + p, b, false, auth); if (r.first == 401 && auth && refresh()) r = req(m, API + p, b, false, auth); r }
        catch (e: Exception) { 0 to "{\"error\":\"Sem conexão\"}" }
    }
    fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    fun save(t: String): Boolean { val j = JSONObject(t); if (!j.has("access_token")) return false
        sp.edit().putString("at", j.getString("access_token")).apply { if (j.has("refresh_token")) putString("rt", j.getString("refresh_token")) }.apply(); return true }
    fun refresh(): Boolean = try {
        val rt = sp.getString("rt", null); val te = sp.getString("te", null); val cid = sp.getString("cid", null)
        if (rt == null || te == null || cid == null) false else { val r = req("POST", te, form("grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to cid), true); r.first == 200 && save(r.second) }
    } catch (e: Exception) { false }
    fun disc(): JSONObject {
        var d = JSONObject(req("GET", "$API/oauth").second)
        if (!d.has("authorization_endpoint")) { val i = d.optString("issuer").trimEnd('/'); if (i.isNotEmpty()) d = JSONObject(req("GET", "$i/.well-known/openid-configuration").second) }
        return d
    }
    fun start(c: Context) = thread {
        try {
            val d = disc(); var cid = sp.getString("cid", null)
            if (cid == null) {
                val r = req("POST", d.getString("registration_endpoint"), JSONObject().put("client_name", "Capivaria Shop BR")
                    .put("redirect_uris", JSONArray().put(CB)).put("token_endpoint_auth_method", "none")
                    .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    .put("response_types", JSONArray().put("code")).put("scope", "openid email profile").toString())
                cid = JSONObject(r.second).getString("client_id"); sp.edit().putString("cid", cid).apply()
            }
            val v = rnd(64); val st = rnd(16)
            sp.edit().putString("v", v).putString("st", st).putString("te", d.getString("token_endpoint")).apply()
            val u = Uri.parse(d.getString("authorization_endpoint")).buildUpon().appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", cid).appendQueryParameter("redirect_uri", CB).appendQueryParameter("scope", "openid email profile")
                .appendQueryParameter("state", st).appendQueryParameter("code_challenge", b64(MessageDigest.getInstance("SHA-256").digest(v.toByteArray())))
                .appendQueryParameter("code_challenge_method", "S256").build()
            Handler(Looper.getMainLooper()).post { c.startActivity(Intent(Intent.ACTION_VIEW, u)) }
        } catch (e: Exception) { St.msg = "Falha ao iniciar o login" }
    }
    fun finish(u: Uri): Boolean = try {
        if (u.getQueryParameter("state") != sp.getString("st", null)) false else {
            val r = req("POST", sp.getString("te", "")!!, form("grant_type" to "authorization_code", "code" to (u.getQueryParameter("code") ?: ""),
                "redirect_uri" to CB, "client_id" to sp.getString("cid", "")!!, "code_verifier" to sp.getString("v", "")!!), true)
            r.first == 200 && save(r.second)
        }
    } catch (e: Exception) { false }
}

object St {
    var logged by mutableStateOf(false); var cat by mutableStateOf(JSONObject()); var orders by mutableStateOf(listOf<JSONObject>())
    var me by mutableStateOf(JSONObject()); var sel by mutableStateOf<JSONObject?>(null); var pix by mutableStateOf<JSONObject?>(null)
    var open by mutableStateOf<String?>(null); var tab by mutableIntStateOf(0); var msg by mutableStateOf("")
    suspend fun loadCat() { val r = Net.api("GET", "/catalog", auth = false); if (r.first == 200) cat = JSONObject(r.second) }
    suspend fun loadOrders() { val r = Net.api("GET", "/orders?offset=0&limit=50"); if (r.first == 200) orders = JSONObject(r.second).optJSONArray("orders").list() else if (r.first == 401) logout() }
    suspend fun loadMe() { val r = Net.api("GET", "/me"); if (r.first == 200) me = JSONObject(r.second) }
    fun logout() { Net.sp.edit().remove("at").remove("rt").apply(); logged = false; orders = emptyList(); me = JSONObject() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Net.init(this)
        St.logged = Net.sp.getString("at", null) != null
        intent?.data?.let { cb(it) }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = GREEN, background = BG, surface = CARD)) { Root() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.data?.let { cb(it) } }
    private fun cb(u: Uri) { if (u.scheme == "capivaria") thread { if (Net.finish(u)) St.logged = true else St.msg = "Falha no login" } }
}

@Composable fun Root() {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2400); splash = false }
    Surface(Modifier.fillMaxSize(), color = BG) {
        Crossfade(splash, animationSpec = tween(600), label = "s") { s -> if (s) Splash() else if (St.logged) Main() else Login() }
    }
}

@Composable fun Splash() {
    val sc by animateFloatAsState(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessVeryLow), label = "sc")
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pp")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF052E16), BG))), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(LOGO, null, Modifier.size(180.dp).scale(sc * pulse).alpha(sc.coerceIn(0f, 1f)))
            Spacer(Modifier.height(16.dp)); Text("CAPIVARIA SHOP BR", fontWeight = FontWeight.Black, fontSize = 22.sp, color = GREEN, modifier = Modifier.alpha(sc.coerceIn(0f, 1f)))
            Spacer(Modifier.height(24.dp)); CircularProgressIndicator(color = GREEN, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable fun Login() {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        AsyncImage(LOGO, null, Modifier.size(140.dp)); Spacer(Modifier.height(24.dp))
        Text("Bem-vindo à Capivaria Shop", fontSize = 22.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
        Text("Entre ou crie sua conta no navegador seguro. Sua senha nunca passa pelo app.", color = Color.Gray)
        Spacer(Modifier.height(24.dp)); Button({ Net.start(c) }, Modifier.fillMaxWidth()) { Text("Entrar / Criar conta") }
        if (St.msg.isNotEmpty()) Text(St.msg, color = Color.Red)
    }
}

@Composable fun Main() {
    BackHandler(St.open != null) { St.open = null }
    LaunchedEffect(Unit) { St.loadMe(); St.loadOrders() }
    Scaffold(containerColor = BG, bottomBar = {
        NavigationBar(containerColor = CARD) { listOf("Loja", "Pedidos", "Conta").forEachIndexed { i, t ->
            NavigationBarItem(St.tab == i && St.open == null, { St.tab = i; St.open = null; if (i == 1) thread { runBlocking { St.loadOrders() } } }, { Text(listOf("🛍", "📦", "👤")[i]) }, label = { Text(t) }) } }
    }) { p -> Box(Modifier.padding(p)) {
        val o = St.open
        if (o != null) OrderDetail(o) else when (St.tab) { 0 -> Home(); 1 -> Orders(); else -> Account() }
        St.sel?.let { BuyDialog(it) }; St.pix?.let { PixDialog(it) }
    } }
}

@Composable fun Ticker(t: String) {
    val txt = List(6) { t }.joinToString("      •      ")
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    Box(Modifier.fillMaxWidth().background(Color(0xFF15803D)).padding(vertical = 8.dp)) {
        Text("📢 $txt", color = Color.White, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 60.dp))
    }
}

@Composable fun Banners(l: List<JSONObject>) {
    if (l.isEmpty()) return
    val st = rememberPagerState { l.size }
    LaunchedEffect(l.size) { while (true) { delay(4000); st.animateScrollToPage((st.currentPage + 1) % l.size) } }
    HorizontalPager(st, Modifier.fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(16.dp))) {
        AsyncImage(img(l[it].s("image", "image_url", "imageUrl", "src", "photo")), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable fun Home() {
    LaunchedEffect(Unit) { while (true) { St.loadCat(); delay(30000) } }
    val c = St.cat
    val ann = c.optJSONArray("announcements").list().map { it.s("text", "message", "title", "content") }.filter { it.isNotBlank() }
    Column {
        if (ann.isNotEmpty()) Ticker(ann.joinToString("      •      "))
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(2) }) { Banners(c.optJSONArray("banners").list()) }
            items(c.optJSONArray("products").list()) { Card(it) }
        }
    }
}

@Composable fun Tag(t: String, c: Color) = Text(t, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp).background(c, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))

@Composable fun Card(p: JSONObject) {
    val out = p.b("sold_out", "soldOut", "esgotado", "out_of_stock"); val new = p.b("is_new", "isNew", "new", "badge_new")
    val price = p.optDouble("price", 0.0); val d = p.optDouble("discount", 0.0); val fin = if (d > 0 && d < 100) price * (1 - d / 100) else price
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(CARD).clickable { St.sel = p }) {
        Box {
            AsyncImage(img(p.s("image", "image_url", "imageUrl", "photo", "banner", "cover")), null, Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (out) 0.4f else 1f), contentScale = ContentScale.Crop)
            Column(Modifier.padding(6.dp)) { if (new) Tag("NOVO", Color(0xFFF59E0B)); if (d > 0) Tag("-${d.toInt()}%", Color(0xFFDC2626)); if (out) Tag("ESGOTADO", Color.Gray) }
        }
        Column(Modifier.padding(10.dp)) {
            Text(p.s("title", "name"), fontWeight = FontWeight.Bold, maxLines = 2)
            if (d > 0) Text(brl(price), color = Color.Gray, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
            Text(brl(fin), color = GREEN, fontWeight = FontWeight.Black)
        }
    }
}

@Composable fun BuyDialog(p: JSONObject) {
    var n by remember { mutableStateOf("") }; var g by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val sc = rememberCoroutineScope(); val out = p.b("sold_out", "soldOut", "esgotado", "out_of_stock")
    AlertDialog({ St.sel = null }, title = { Text(p.s("title", "name")) }, text = { Column {
        Text(p.s("description")); Spacer(Modifier.height(8.dp))
        OutlinedTextField(n, { n = it }, label = { Text("Seu nome") }, singleLine = true)
        OutlinedTextField(g, { g = it }, label = { Text("Nome no jogo") }, singleLine = true)
        if (St.msg.isNotEmpty()) Text(St.msg, color = Color.Red)
    } }, confirmButton = { Button(enabled = !out && n.isNotBlank() && g.isNotBlank() && !busy, onClick = {
        busy = true; sc.launch {
            val r = Net.api("POST", "/orders", JSONObject().put("productId", p.s("id")).put("buyerName", n).put("gameName", g).toString())
            busy = false
            if (r.first in 200..299) { St.msg = ""; St.pix = JSONObject(r.second); St.sel = null; St.loadOrders() } else St.msg = err(r.second)
        } }) { Text(if (out) "Esgotado" else "Comprar") } },
        dismissButton = { TextButton({ St.sel = null }) { Text("Fechar") } })
}

@Composable fun PixDialog(r: JSONObject) {
    val pay = r.optJSONObject("payment") ?: JSONObject(); val o = r.optJSONObject("order") ?: JSONObject()
    val payload = pay.s("payload"); val clip = LocalClipboardManager.current; val sc = rememberCoroutineScope()
    AlertDialog({ St.pix = null }, title = { Text("Pague com Pix • ${brl(pay.optDouble("amount", 0.0))}") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (payload.isNotEmpty()) Image(remember(payload) { qr(payload) }, "QR Pix", Modifier.size(220.dp).background(Color.White).padding(6.dp))
        TextButton({ clip.setText(AnnotatedString(payload)) }) { Text("Copiar Pix copia e cola") }
        Text("Após pagar, toque em “Já enviei”. A confirmação é feita pela loja.", fontSize = 12.sp, color = Color.Gray)
    } }, confirmButton = { Button({ sc.launch { Net.api("POST", "/orders/${o.s("id")}/pix"); St.pix = null; St.tab = 1; St.loadOrders() } }) { Text("Já enviei o Pix") } },
        dismissButton = { TextButton({ St.pix = null }) { Text("Depois") } })
}

@Composable fun Orders() {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (St.orders.isEmpty()) item { Text("Nenhum pedido ainda.", color = Color.Gray) }
        items(St.orders) { o -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).clickable { St.open = o.s("id") }.padding(14.dp), Arrangement.SpaceBetween) {
            Text(o.s("product_title", "productTitle", "title", "product_name", "id"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(o.s("status").uppercase(), color = GREEN, fontWeight = FontWeight.Bold) } }
    }
}

@Composable fun OrderDetail(id: String) {
    var d by remember { mutableStateOf(JSONObject()) }; var msgs by remember { mutableStateOf(listOf<JSONObject>()) }
    var txt by remember { mutableStateOf("") }; var spinning by remember { mutableStateOf(false) }; var note by remember { mutableStateOf("") }; var rev by remember { mutableStateOf("") }
    val sc = rememberCoroutineScope()
    suspend fun load() {
        Net.api("GET", "/orders/$id").let { if (it.first == 200) d = JSONObject(it.second) }
        Net.api("GET", "/orders/$id/messages").let { if (it.first == 200) msgs = JSONObject(it.second).optJSONArray("messages").list() }
    }
    LaunchedEffect(id) { while (true) { load(); delay(10000) } }
    val o = d.optJSONObject("order") ?: JSONObject(); val draws = d.optJSONArray("draws").list()
    val paid = o.s("status") in listOf("pago", "entregue"); val rot by animateFloatAsState(if (spinning) 1440f else 0f, tween(1800, easing = FastOutSlowInEasing), label = "r")
    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        TextButton({ St.open = null }) { Text("← Voltar") }
        Text(o.s("product_title", "productTitle", "title", "id"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Status: ${o.s("status")}", color = GREEN)
        if (paid) {
            Spacer(Modifier.height(12.dp)); Text("Sorteio", fontWeight = FontWeight.Bold)
            Box(Modifier.size(90.dp).rotate(rot).align(Alignment.CenterHorizontally).background(Brush.sweepGradient(listOf(GREEN, Color(0xFFF59E0B), Color(0xFFDC2626), GREEN)), CircleShape))
            draws.forEach { Text("🎁 Giro ${it.s("spin_number", "spinNumber")}: ${it.s("prize", "name", "title", "label", "result")}") }
            if (draws.size < 2) Button({ sc.launch { spinning = true; val r = Net.api("POST", "/orders/$id/spin", JSONObject().put("spinNumber", draws.size + 1).toString()); delay(1900); spinning = false; note = if (r.first in 200..299) "" else err(r.second); load() } }, enabled = !spinning) { Text("Girar (${draws.size + 1})") }
            if (note.isNotEmpty()) Text(note, color = Color.Red)
        }
        Spacer(Modifier.height(12.dp)); Text("Chat do pedido", fontWeight = FontWeight.Bold)
        msgs.forEach { Text("${it.s("sender_name", "author", "role")}: ${it.s("content")}", fontSize = 14.sp) }
        if (paid && !St.me.optJSONObject("profile").let { it?.b("banned") == true || it?.s("removed_at")?.isNotEmpty() == true }) Row {
            OutlinedTextField(txt, { txt = it }, Modifier.weight(1f), singleLine = true)
            Button({ sc.launch { Net.api("POST", "/orders/$id/messages", JSONObject().put("content", txt).toString()); txt = ""; load() } }, enabled = txt.isNotBlank()) { Text("Enviar") }
        } else Text("Chat liberado após a confirmação do Pix.", color = Color.Gray, fontSize = 12.sp)
        if (o.s("status") == "entregue" && !o.b("buyer_confirmed", "buyerConfirmed")) {
            Spacer(Modifier.height(12.dp)); Text("Confirmar recebimento e avaliar", fontWeight = FontWeight.Bold)
            OutlinedTextField(rev, { rev = it }, label = { Text("Comentário") })
            Row { (1..5).forEach { s -> TextButton({ sc.launch { Net.api("PATCH", "/orders/$id", JSONObject().put("buyerConfirmed", true).put("rating", s).put("review", rev).toString()); load() } }) { Text("$s⭐") } } }
        }
    }
}

@Composable fun Account() {
    val p = St.me.optJSONObject("profile") ?: JSONObject()
    Column(Modifier.padding(20.dp)) {
        AsyncImage(LOGO, null, Modifier.size(90.dp)); Spacer(Modifier.height(12.dp))
        Text(p.s("name", "display_name", "email"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (p.b("banned") || p.s("removed_at").isNotEmpty()) Text("Conta restrita: ${p.s("removal_reason")} ${p.s("removal_details")}", color = Color.Red)
        Spacer(Modifier.height(16.dp)); Button({ St.logout() }) { Text("Sair") }
    }
}
