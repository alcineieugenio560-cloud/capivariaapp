@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package br.capivaria.shop

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil.compose.AsyncImage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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
object Th {
    var mode by mutableIntStateOf(0); var idx by mutableIntStateOf(0); var store by mutableStateOf<Color?>(null)
    val presets = listOf(Color(0xFF3B82F6), Color(0xFFA855F7), Color(0xFFEC4899), Color(0xFFF97316), Color(0xFF22C55E))
    val accent: Color get() = if (idx == 0) (store ?: Color(0xFF22C55E)) else presets[idx - 1]
    fun load() { mode = Net.sp.getInt("th_mode", 0); idx = Net.sp.getInt("th_idx", 0) }
    fun save() { Net.sp.edit().putInt("th_mode", mode).putInt("th_idx", idx).apply() }
}
val GREEN: Color get() = Th.accent
val ON: Color get() = if (GREEN.luminance() > 0.5f) Color.Black else Color.White
val BG: Color get() = when (Th.mode) { 1 -> Color(0xFFF3F5F4); 2 -> Color.Black; else -> Color(0xFF0D1311) }
val CARD: Color get() = when (Th.mode) { 1 -> Color.White; 2 -> Color(0xFF121212); else -> Color(0xFF151D19) }
val TX: Color get() = if (Th.mode == 1) Color(0xFF111827) else Color.White
val GRAY: Color get() = if (Th.mode == 1) Color(0xFF6B7280) else Color(0xFF9CA3AF)
val SOFT: Color get() = if (Th.mode == 1) Color(0xFF374151) else Color(0xFFD1D5DB)
val FIELD: Color get() = if (Th.mode == 1) Color(0xFFE5EAE8) else Color(0x14FFFFFF)
fun storeColor(c: JSONObject): Color? {
    val st = c.opt("settings"); var v = ""
    val keys = listOf("primary_color", "primaryColor", "accent_color", "accentColor", "theme_color", "themeColor", "brand_color", "brandColor", "color")
    if (st is JSONObject) v = st.s(*keys.toTypedArray())
    else if (st is JSONArray) st.list().forEach { if (v.isEmpty() && it.s("key", "name") in keys) v = it.s("value") }
    return runCatching { Color(android.graphics.Color.parseColor(v.trim())) }.getOrNull()
}

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
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    fun req(m: String, u: String, body: String? = null, form: Boolean = false, auth: Boolean = false): Pair<Int, String> {
        val rb = Request.Builder().url(u).header("Accept", "application/json")
        if (auth) sp.getString("at", null)?.let { rb.header("Authorization", "Bearer $it") }
        val type = (if (form) "application/x-www-form-urlencoded" else "application/json").toMediaType()
        val rbody = if (body != null) body.toRequestBody(type) else if (m == "GET" || m == "DELETE") null else "".toRequestBody(type)
        rb.method(m, rbody)
        client.newCall(rb.build()).execute().use { r -> return r.code to (r.body?.string() ?: "") }
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
    var open by mutableStateOf<String?>(null); var tab by mutableIntStateOf(0); var sub by mutableStateOf<String?>(null); var checkout by mutableStateOf<JSONObject?>(null); var msg by mutableStateOf("")
    suspend fun loadCat() { val r = Net.api("GET", "/catalog", auth = false); if (r.first == 200) { cat = JSONObject(r.second); Th.store = storeColor(cat) } }
    suspend fun loadOrders() { val r = Net.api("GET", "/orders?offset=0&limit=50"); if (r.first == 200) { orders = JSONObject(r.second).optJSONArray("orders").list() } else if (r.first == 401) { logout() } }
    suspend fun loadMe() { val r = Net.api("GET", "/me"); if (r.first == 200) { me = JSONObject(r.second) } }
    fun logout() { Net.sp.edit().remove("at").remove("rt").apply(); logged = false; orders = emptyList(); me = JSONObject() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Net.init(this)
        Th.load(); St.logged = Net.sp.getString("at", null) != null
        intent?.data?.let { cb(it) }
        setContent { AppTheme { Root() } }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); i.data?.let { cb(it) } }
    private fun cb(u: Uri) { if (u.scheme == "capivaria") thread { if (Net.finish(u)) St.logged = true else St.msg = "Falha no login" } }
}

@Composable fun Root() {
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2400); splash = false }
    Surface(Modifier.fillMaxSize(), color = BG, contentColor = TX) {
      Box(Modifier.fillMaxSize()) { Watermark()
        Crossfade(splash, animationSpec = tween(600), label = "s") { s -> if (s) Splash() else if (St.logged) Main() else Login() } }
    }
}

@Composable fun Splash() {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val sc by animateFloatAsState(if (go) 1f else 0.3f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessVeryLow), label = "sc")
    val al = ((sc - 0.3f) / 0.7f).coerceIn(0f, 1f)
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pp")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(GREEN.copy(alpha = 0.3f), BG))), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_capivaria), null, Modifier.size(180.dp).scale(sc * pulse).alpha(al))
            Spacer(Modifier.height(16.dp)); Text("CAPIVARIA SHOP BR", fontWeight = FontWeight.Black, fontSize = 22.sp, color = GREEN, modifier = Modifier.alpha(al))
            Spacer(Modifier.height(24.dp)); CircularProgressIndicator(color = GREEN, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable fun Login() {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo_capivaria), null, Modifier.size(140.dp)); Spacer(Modifier.height(24.dp))
        Text("Bem-vindo à Capivaria Shop", fontSize = 22.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
        Text("Entre ou crie sua conta no navegador seguro. Sua senha nunca passa pelo app.", color = Color.Gray)
        Spacer(Modifier.height(24.dp)); Button({ Net.start(c) }, Modifier.fillMaxWidth()) { Text("Entrar / Criar conta") }
        if (St.msg.isNotEmpty()) Text(St.msg, color = Color.Red)
    }
}


val AMBER = Color(0xFFF59E0B); val RED = Color(0xFFEF4444)
fun statusColor(s: String) = when (s.lowercase()) { "entregue" -> Color(0xFF38BDF8); "pago" -> Color(0xFF22C55E); else -> AMBER }
fun statusLabel(s: String) = when (s.lowercase()) { "entregue" -> "ENTREGUE"; "pago" -> "PAGO"; "" -> "—"; else -> "AGUARDANDO PIX" }
fun isPaid(s: String) = s.lowercase() in listOf("pago", "entregue")
fun isMine(m: JSONObject, me: JSONObject): Boolean {
    val uid = me.s("id", "user_id", "userId"); val sid = m.s("user_id", "userId", "sender_id", "senderId")
    if (uid.isNotEmpty() && sid.isNotEmpty()) return uid == sid
    val r = m.s("sender", "role", "author", "sender_role").lowercase()
    return !(r.contains("admin") || r.contains("loja") || r.contains("store") || r.contains("seller"))
}
fun prizeName(j: JSONObject): String { val pr = j.opt("prize"); return if (pr is JSONObject) pr.s("name", "title", "label") else j.s("prize_name", "prizeName", "name", "title", "label", "prize", "result") }
fun prizeCode(j: JSONObject): String { val pr = j.opt("prize"); val a = if (pr is JSONObject) pr.s("code", "coupon", "coupon_code") else ""; return a.ifEmpty { j.s("code", "coupon", "coupon_code", "couponCode") } }

@Composable fun AppTheme(content: @Composable () -> Unit) {
    val cs = if (Th.mode == 1) lightColorScheme(primary = GREEN, onPrimary = ON, background = BG, surface = CARD, onSurface = TX, onBackground = TX, onSurfaceVariant = GRAY, outline = GRAY, surfaceContainerHigh = CARD)
    else darkColorScheme(primary = GREEN, onPrimary = ON, background = BG, surface = CARD, onSurface = TX, onBackground = TX, onSurfaceVariant = GRAY, outline = GRAY, surfaceContainerHigh = CARD)
    MaterialTheme(colorScheme = cs, content = content)
}

@Composable fun Chip(t: String, c: Color) = Text(t, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))

@Composable fun Appear(delayMs: Int = 0, content: @Composable () -> Unit) {
    var v by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); v = true }
    AnimatedVisibility(v, enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 5 }) { content() }
}

@Composable fun Header(t: String) = Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(GREEN.copy(alpha = 0.25f), Color.Transparent))).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Image(painterResource(R.drawable.logo_capivaria), null, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp)); Text(t, fontSize = 20.sp, fontWeight = FontWeight.Black)
}

@Composable fun TopBack(t: String, sub: String = "", right: @Composable () -> Unit = {}, onBack: () -> Unit) =
    Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { onBack() }, Alignment.Center) { Icon(Icons.Filled.ArrowBack, "Voltar", tint = TX) }
        Column(Modifier.weight(1f)) { Text(t, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub.isNotEmpty()) Text(sub, color = GRAY, fontSize = 12.sp) }
        right(); Spacer(Modifier.width(10.dp))
    }

@Composable fun Tabs(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(CARD)) { items.forEachIndexed { i, t ->
        val c by animateColorAsState(if (sel == i) GREEN else GRAY, label = "tab")
        Column(Modifier.weight(1f).clickable { onSel(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t, Modifier.padding(vertical = 12.dp), color = c, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth(if (sel == i) 0.6f else 0f).height(3.dp).background(GREEN, RoundedCornerShape(3.dp))) } } }
}

@Composable fun BottomBar() {
    Row(Modifier.fillMaxWidth().background(CARD).padding(vertical = 8.dp), Arrangement.SpaceEvenly) {
        listOf(R.drawable.ic_home to "Loja", R.drawable.ic_orders to "Pedidos", R.drawable.ic_profile to "Perfil").forEachIndexed { i, (ic, t) ->
            val sel = St.tab == i && St.open == null && St.sub == null
            val sc by animateFloatAsState(if (sel) 1.2f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "n")
            val col by animateColorAsState(if (sel) GREEN else GRAY, label = "c")
            val bg by animateColorAsState(if (sel) GREEN.copy(alpha = 0.14f) else Color.Transparent, label = "b")
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable {
                St.tab = i; St.open = null; St.sub = null; St.sel = null
                if (i == 1) thread { runBlocking { St.loadOrders() } }
            }.padding(horizontal = 22.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(ic), t, Modifier.size(24.dp).scale(sc), colorFilter = ColorFilter.tint(col))
                Text(t, color = col, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable fun Main() {
    BackHandler(St.pix != null || St.checkout != null || St.open != null || St.sel != null || St.sub != null) { when { St.pix != null -> { St.pix = null; St.tab = 1 }; St.checkout != null -> St.checkout = null; St.sub != null -> St.sub = null; St.open != null -> St.open = null; else -> St.sel = null } }
    LaunchedEffect(Unit) { St.loadMe(); St.loadOrders(); St.loadCat() }
    Scaffold(containerColor = Color.Transparent, bottomBar = { if (St.sel == null && St.checkout == null && St.pix == null) BottomBar() }) { p ->
        Box(Modifier.padding(p)) {
            val s = St.sel; val o = St.open
            when {
                St.pix != null -> PixScreen(St.pix!!)
                St.checkout != null -> Checkout(St.checkout!!)
                s != null -> ProductScreen(s)
                o != null -> OrderDetail(o)
                St.sub == "support" -> Support()
                St.sub == "coupons" -> CouponsAdmin()
                else -> when (St.tab) { 0 -> Home(); 1 -> Orders(); else -> Profile() }
            }
        }
    }
}

@Composable fun Ticker(t: String) {
    val txt = List(6) { t }.joinToString("        /        ")
    Box(Modifier.fillMaxWidth().background(GREEN.copy(alpha = 0.07f)).padding(vertical = 5.dp)) {
        Text(txt, color = GRAY, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, velocity = 45.dp))
    }
}

@Composable fun Banners(l: List<JSONObject>) {
    if (l.isEmpty()) return
    val st = rememberPagerState { l.size }
    LaunchedEffect(l.size) { while (true) { delay(4000); st.animateScrollToPage((st.currentPage + 1) % l.size) } }
    Box {
        HorizontalPager(st, Modifier.fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(16.dp))) {
            AsyncImage(img(l[it].s("image", "image_url", "imageUrl", "src", "photo")), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            l.indices.forEach { i -> Box(Modifier.size(if (i == st.currentPage) 18.dp else 7.dp, 7.dp).background(if (i == st.currentPage) GREEN else Color(0x88FFFFFF), CircleShape)) }
        }
    }
}

@Composable fun Home() {
    LaunchedEffect(Unit) { while (true) { St.loadCat(); delay(30000) } }
    val c = St.cat
    var sec by remember { mutableStateOf("") }; var gm by remember { mutableStateOf("") }
    val sections = c.optJSONArray("sections").list(); val games = c.optJSONArray("games").list()
    val prods = c.optJSONArray("products").list().filter { p ->
        (sec.isEmpty() || matches(p, sec, sections, "section_id", "sectionId", "section", "category_id", "categoryId", "category")) &&
        (gm.isEmpty() || matches(p, gm, games, "game_id", "gameId", "game")) }
    val ann = c.optJSONArray("announcements").list().map { it.s("text", "message", "title", "content") }.filter { it.isNotBlank() }
    Column {
        Header("Capivaria Shop BR")
        if (ann.isNotEmpty()) Ticker(ann.joinToString("        /        "))
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(2) }) { Banners(c.optJSONArray("banners").list()) }
            item(span = { GridItemSpan(2) }) { Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterRow(sections, sec, "Todas as categorias") { sec = it }; FilterRow(games, gm, "Todos os jogos") { gm = it }
                Text("Produtos", fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (prods.isEmpty()) Text("Nenhum produto nesta categoria.", color = GRAY) } }
            items(prods) { p -> Appear { ProductCard(p) } }
        }
    }
}

@Composable fun ProductCard(p: JSONObject) {
    val out = p.b("sold_out", "soldOut", "esgotado", "out_of_stock"); val new = p.b("is_new", "isNew", "new", "badge_new")
    val price = p.optDouble("price", 0.0); val d = p.optDouble("discount", 0.0); val fin = if (d > 0 && d < 100) price * (1 - d / 100) else price
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(CARD).clickable { St.sel = p }) {
        Box {
            AsyncImage(img(p.s("image", "image_url", "imageUrl", "photo", "banner", "cover")), null, Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (out) 0.4f else 1f), contentScale = ContentScale.Crop)
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { if (new) Chip("NOVO", AMBER); if (d > 0) Chip("-${d.toInt()}%", RED); if (out) Chip("ESGOTADO", GRAY) }
        }
        Column(Modifier.padding(10.dp)) {
            Text(p.s("title", "name"), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            if (d > 0) Text(brl(price), color = GRAY, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough)
            Text(brl(fin), color = GREEN, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
    }
}

@Composable fun Watermark() {
    Box(Modifier.fillMaxSize().clipToBounds(), Alignment.Center) {
        Column(Modifier.requiredSize(1000.dp, 1500.dp).rotate(-20f).alpha(if (Th.mode == 1) 0.06f else 0.045f)) {
            repeat(26) { r -> Row(Modifier.offset(x = if (r % 2 == 0) 0.dp else (-70).dp)) {
                repeat(6) { Text("CAPIVARIA", color = TX, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) } } }
        }
    }
}

@Composable fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(16.dp)).padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.height(12.dp)); content() }
}

@Composable fun PriceRow(l: String, v: String, big: Boolean = false, c: Color = TX) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(l, Modifier.weight(1f), color = if (big) TX else GRAY, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, fontSize = if (big) 17.sp else 14.sp)
        Text(v, color = c, fontWeight = if (big) FontWeight.Black else FontWeight.Medium, fontSize = if (big) 20.sp else 14.sp) }
}

@Composable fun Field(label: String, v: String, onChange: (String) -> Unit, hint: String, icon: ImageVector) {
    var foc by remember { mutableStateOf(false) }
    val line by animateColorAsState(if (foc) GREEN else GRAY.copy(alpha = 0.25f), label = "fl")
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GRAY, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth().background(FIELD, RoundedCornerShape(14.dp)).border(if (foc) 2.dp else 1.dp, line, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (foc) GREEN else GRAY, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (v.isEmpty()) Text(hint, color = GRAY.copy(alpha = 0.7f))
                BasicTextField(v, onChange, Modifier.fillMaxWidth().onFocusChanged { foc = it.isFocused }, textStyle = TextStyle(color = TX, fontSize = 15.sp), cursorBrush = SolidColor(GREEN), singleLine = true)
            }
        }
    }
}

@Composable fun Steps(cur: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Dados", "Pagamento", "Pronto").forEachIndexed { i, t ->
            val on = cur >= i; val c by animateColorAsState(if (on) GREEN else GRAY.copy(alpha = 0.4f), label = "st")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(28.dp).background(c, CircleShape), Alignment.Center) { if (cur > i) Icon(Icons.Filled.Check, null, tint = ON, modifier = Modifier.size(17.dp)) else Text("${i + 1}", color = if (on) ON else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                Text(t, fontSize = 11.sp, color = if (on) TX else GRAY) }
            if (i < 2) Box(Modifier.weight(1f).padding(bottom = 16.dp).padding(horizontal = 6.dp).height(2.dp).background(if (cur > i) GREEN else GRAY.copy(alpha = 0.3f)))
        }
    }
}

@Composable fun ProductScreen(p: JSONObject) {
    val out = p.b("sold_out", "soldOut", "esgotado", "out_of_stock"); val new = p.b("is_new", "isNew", "new", "badge_new")
    val price = p.optDouble("price", 0.0); val d = p.optDouble("discount", 0.0); val fin = if (d > 0 && d < 100) price * (1 - d / 100) else price
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            Box {
                AsyncImage(img(p.s("image", "image_url", "imageUrl", "photo", "banner", "cover")), null, Modifier.fillMaxWidth().aspectRatio(1.25f), contentScale = ContentScale.Crop)
                Box(Modifier.padding(12.dp).size(42.dp).background(Color(0x99000000), CircleShape).clickable { St.sel = null }, Alignment.Center) { Icon(Icons.Filled.ArrowBack, "Voltar", tint = Color.White) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { if (new) Chip("NOVO", AMBER); if (out) Chip("ESGOTADO", GRAY) }
                Text(p.s("title", "name"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                if (d > 0) Text(brl(price), color = GRAY, textDecoration = TextDecoration.LineThrough, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(brl(fin), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    if (d > 0) Text("${d.toInt()}% OFF", color = Color(0xFF22C55E), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp)) }
                Text("à vista no Pix", color = GRAY)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Pagamento via Pix", "Entrega combinada pelo chat do pedido", "Avalie o produto depois de receber").forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Check, null, tint = GREEN, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(t, fontSize = 14.sp) } } }
                Spacer(Modifier.height(12.dp)); Text("Descrição", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(p.s("description"), color = SOFT, lineHeight = 22.sp)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CARD).padding(14.dp)) {
            Button({ St.checkout = p }, Modifier.fillMaxWidth().height(52.dp), enabled = !out, shape = RoundedCornerShape(14.dp)) { Text(if (out) "Produto esgotado" else "Comprar agora", fontWeight = FontWeight.Bold, fontSize = 16.sp) } }
    }
}

@Composable fun Checkout(p: JSONObject) {
    var n by remember { mutableStateOf(St.me.optJSONObject("profile")?.s("name", "display_name", "full_name", "username") ?: "") }
    var g by remember { mutableStateOf("") }; var cp by remember { mutableStateOf("") }; var cpOk by remember { mutableStateOf(false) }; var cpPct by remember { mutableStateOf(0.0) }; var cpMsg by remember { mutableStateOf("") }; var cpBusy by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var e by remember { mutableStateOf("") }; val sc = rememberCoroutineScope()
    val price = p.optDouble("price", 0.0); val d = p.optDouble("discount", 0.0); val fin = if (d > 0 && d < 100) price * (1 - d / 100) else price
    val valid = n.trim().length >= 2 && g.isNotBlank()
    val cDisc = if (cpOk) fin * cpPct / 100 else 0.0; val total = (fin - cDisc).coerceAtLeast(0.0)
    Column(Modifier.fillMaxSize()) {
        TopBack("Finalizar compra", "1 unidade") { St.checkout = null }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Steps(0)
            SectionCard("Resumo da compra") { Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(img(p.s("image", "image_url", "imageUrl", "photo", "banner", "cover")), null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(p.s("title", "name"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text("1 unidade", color = GRAY, fontSize = 12.sp) }
                Text(brl(fin), fontWeight = FontWeight.Bold) } }
            SectionCard("Seus dados") {
                Field("Seu nome", n, { n = it }, "Como você se chama", Icons.Filled.Person); Spacer(Modifier.height(14.dp))
                Field("Nome no jogo", g, { g = it }, "Seu nickname dentro do jogo", Icons.Filled.PlayArrow)
                Text("Usamos esses dados para entregar o produto na conta certa.", color = GRAY, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            SectionCard("Cupom de desconto") {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { Field("Código do cupom", cp, { cp = it.uppercase().trim(); cpOk = false; cpPct = 0.0; cpMsg = "" }, "Digite o cupom da loja", Icons.Filled.Star) }
                    Spacer(Modifier.width(8.dp)); Button({ cpBusy = true; sc.launch {
                        val r = Net.api("POST", "/coupons/validate", JSONObject().put("code", cp.trim()).toString()); cpBusy = false
                        val c = if (r.first == 200) JSONObject(r.second).optJSONObject("coupon") else null
                        if (c != null) { cpPct = c.optDouble("discount_percent", 0.0); cpOk = true; cpMsg = "Cupom ${c.s("code")} aplicado: ${cpPct.toInt()}% de desconto" + (if (c.b("single_use")) " (uso único)" else "") }
                        else { cpOk = false; cpPct = 0.0; cpMsg = "Cupom inválido ou indisponível" } } },
                        Modifier.height(50.dp), enabled = cp.isNotBlank() && !cpBusy, shape = RoundedCornerShape(14.dp)) { Text(if (cpBusy) "..." else if (cpOk) "Aplicado" else "Aplicar") } }
                Text(cpMsg.ifEmpty { "Tem um cupom da loja? Informe aqui antes de pagar." }, color = if (cpOk) GREEN else if (cpMsg.isNotEmpty()) RED else GRAY, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            SectionCard("Valor total") {
                PriceRow("Produto", brl(price)); if (d > 0) PriceRow("Desconto da loja", "- ${brl(price - fin)}", c = GREEN)
                PriceRow("Cupom", if (cpOk) "- ${brl(cDisc)}" else "—", c = if (cpOk) GREEN else TX)
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(GRAY.copy(alpha = 0.25f)))
                PriceRow("Total", brl(total), big = true, c = GREEN) }
            if (e.isNotEmpty()) Text(e, color = RED)
        }
        Column(Modifier.fillMaxWidth().background(CARD).padding(14.dp)) {
            Button({ busy = true; e = ""; sc.launch {
                val r = Net.api("POST", "/orders", JSONObject().put("productId", p.s("id")).put("buyerName", n.trim()).put("gameName", g.trim()).apply { if (cpOk) put("couponCode", cp.trim()) }.toString()); busy = false
                if (r.first in 200..299) { val res = JSONObject(r.second); val amt = res.optJSONObject("payment")?.optDouble("amount", -1.0) ?: -1.0
                    if (cpOk && cpPct > 0 && kotlin.math.abs(amt - fin) < 0.01) res.put("couponIgnored", true)
                    St.pix = res; St.checkout = null; St.sel = null; St.loadOrders() } else { e = err(r.second) } } },
                Modifier.fillMaxWidth().height(52.dp), enabled = valid && !busy && (cp.isBlank() || cpOk), shape = RoundedCornerShape(14.dp)) { Text(if (busy) "Gerando Pix..." else "Confirmar e gerar Pix", fontWeight = FontWeight.Bold, fontSize = 16.sp) } }
    }
}

@Composable fun PixScreen(r: JSONObject) {
    val pay = r.optJSONObject("payment") ?: JSONObject(); val o = r.optJSONObject("order") ?: JSONObject(); val payload = pay.s("payload")
    val clip = LocalClipboardManager.current; val sc = rememberCoroutineScope(); val ctx = LocalContext.current
    var done by remember { mutableStateOf(false) }; var copied by remember { mutableStateOf(false) }
    val pop by animateFloatAsState(if (done) 1f else 0.2f, spring(Spring.DampingRatioMediumBouncy), label = "pop")
    LaunchedEffect(o.s("id")) { while (!done) {
        val rr = Net.api("GET", "/orders/${o.s("id")}")
        if (rr.first == 200 && isPaid(runCatching { JSONObject(rr.second).optJSONObject("order")?.s("status") ?: "" }.getOrDefault(""))) { done = true; St.loadOrders() } else delay(4000) } }
    Column(Modifier.fillMaxSize()) {
        TopBack(if (done) "Pagamento confirmado" else "Pagamento via Pix", "Pedido #${o.s("id").take(8)}") { St.pix = null; St.tab = 1 }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Steps(if (done) 2 else 1)
            if (!done) {
                SectionCard("Valor a pagar") { Text(brl(pay.optDouble("amount", 0.0)), fontSize = 32.sp, fontWeight = FontWeight.Black, color = GREEN)
                    if (r.optBoolean("couponIgnored")) { Text("O cupom não foi aplicado a este pedido pelo aplicativo. Para usar cupom, finalize a compra pelo site.", color = AMBER, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE))) }) { Text("Abrir o site para usar cupom") } } }
                SectionCard("QR Code ou Pix copia e cola") {
                    if (payload.isNotEmpty()) Box(Modifier.align(Alignment.CenterHorizontally).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp)) { Image(remember(payload) { qr(payload) }, "QR Pix", Modifier.size(220.dp)) }
                    OutlinedButton({ clip.setText(AnnotatedString(payload)); copied = true }, Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(if (copied) "Código copiado" else "Copiar Pix copia e cola", fontWeight = FontWeight.Bold) } }
                SectionCard("Como pagar") { Text("1. Abra o aplicativo do seu banco.\n2. Escolha pagar com Pix e leia o QR Code ou cole o código.\n3. Volte aqui: a tela verifica o pagamento sozinha.", color = SOFT, lineHeight = 22.sp) }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(96.dp).scale(pop).background(GREEN, CircleShape), Alignment.Center) { Icon(Icons.Filled.Check, null, tint = ON, modifier = Modifier.size(54.dp)) }
                    Spacer(Modifier.height(18.dp)); Text("Pagamento confirmado", fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("A loja confirmou o seu Pix. O chat com o administrador e a roleta já estão liberados no seu pedido.", color = GRAY, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) }
            }
        }
        Column(Modifier.fillMaxWidth().background(CARD).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!done) { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = GREEN, strokeWidth = 3.dp); Spacer(Modifier.width(12.dp))
                Column { Text("Verificando pagamento...", fontWeight = FontWeight.Bold); Text("Esta tela atualiza sozinha quando o Pix for confirmado.", color = GRAY, fontSize = 12.sp) } }
                TextButton({ St.pix = null; St.tab = 1 }, Modifier.fillMaxWidth()) { Text("Ver meus pedidos") } }
            else { Button({ val id = o.s("id"); St.pix = null; St.tab = 1; St.open = id }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Acompanhar pedido", fontWeight = FontWeight.Bold) }
                TextButton({ St.pix = null; St.tab = 0 }, Modifier.fillMaxWidth()) { Text("Voltar à loja") } }
        }
    }
}

@Composable fun Orders() {
    Column {
        Header("Meus pedidos")
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (St.orders.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_orders), null, Modifier.size(80.dp), colorFilter = ColorFilter.tint(GRAY)); Text("Nenhum pedido ainda", color = GRAY) } }
            items(St.orders) { o -> Appear {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).clickable { St.open = o.s("id") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.s("product_title", "productTitle", "title", "product_name", "id"), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("#${o.s("id").take(8)}  ${o.s("created_at", "createdAt").take(10)}", color = GRAY, fontSize = 12.sp)
                    }
                    Chip(statusLabel(o.s("status")), statusColor(o.s("status")))
                } } }
        }
    }
}

@Composable fun Wheel(rot: Float, mod: Modifier) {
    Canvas(mod) {
        val n = 8; val sw = 360f / n; val c = center; val r = size.minDimension / 2
        for (i in 0 until n) drawArc(if (i % 2 == 0) GREEN else GREEN.copy(alpha = 0.4f), -90f + i * sw + rot, sw, true)
        drawCircle(BG, r * 0.62f); drawCircle(GREEN, r * 0.62f, style = Stroke(r * 0.04f))
        drawCircle(GREEN, r * 0.2f); drawCircle(BG, r * 0.1f)
        drawCircle(CARD, r, style = Stroke(r * 0.06f))
        val tri = Path().apply { moveTo(c.x - r * 0.1f, 0f); lineTo(c.x + r * 0.1f, 0f); lineTo(c.x, r * 0.2f); close() }
        drawPath(tri, TX)
    }
}

@Composable fun Slot(n: Int, j: JSONObject?, next: Boolean, mod: Modifier) {
    val clip = LocalClipboardManager.current
    Column(mod.background(CARD, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text("Giro $n", color = GRAY, fontSize = 12.sp)
        if (j == null) Text(if (next) "Disponível" else "Bloqueado", color = if (next) GREEN else GRAY, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        else {
            Text(prizeName(j).ifEmpty { "Prêmio" }, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            val code = prizeCode(j)
            if (code.isNotEmpty()) Column(Modifier.padding(top = 8.dp).fillMaxWidth().drawBehind {
                drawRoundRect(GREEN, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))), cornerRadius = CornerRadius(24f, 24f))
            }.clickable { clip.setText(AnnotatedString(code)) }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CUPOM", fontSize = 10.sp, color = GREEN, fontWeight = FontWeight.Bold)
                Text(code, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Toque para copiar", fontSize = 10.sp, color = GRAY)
            }
        }
    }
}

@Composable fun InputBar(v: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val ok = v.isNotBlank(); val sc by animateFloatAsState(if (ok) 1f else 0.88f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    val line by animateColorAsState(if (ok) GREEN.copy(alpha = 0.7f) else GRAY.copy(alpha = 0.25f), label = "l")
    Row(Modifier.fillMaxWidth().background(BG).padding(10.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f).heightIn(min = 50.dp).background(CARD, RoundedCornerShape(26.dp)).border(1.dp, line, RoundedCornerShape(26.dp)).padding(horizontal = 18.dp, vertical = 13.dp), Alignment.CenterStart) {
            if (v.isEmpty()) Text("Escreva uma mensagem...", color = GRAY)
            BasicTextField(v, onChange, Modifier.fillMaxWidth(), textStyle = TextStyle(color = TX, fontSize = 15.sp), cursorBrush = SolidColor(GREEN), maxLines = 4)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(50.dp).scale(sc).background(if (ok) GREEN else GRAY.copy(alpha = 0.3f), CircleShape).clickable(enabled = ok) { onSend() }, Alignment.Center) {
            Icon(Icons.Filled.Send, "Enviar", tint = if (ok) ON else GRAY, modifier = Modifier.size(22.dp)) }
    }
}

@Composable fun ChatPanel(id: String, enabled: Boolean, pollMs: Long = 4000) {
    var msgs by remember(id) { mutableStateOf(listOf<JSONObject>()) }; var txt by remember { mutableStateOf("") }
    val sc = rememberCoroutineScope(); val sv = rememberScrollState()
    val me = St.me.optJSONObject("profile") ?: JSONObject(); val blocked = me.b("banned") || me.s("removed_at").isNotEmpty()
    suspend fun load() { Net.api("GET", "/orders/$id/messages").let { if (it.first == 200) { msgs = JSONObject(it.second).optJSONArray("messages").list() } } }
    LaunchedEffect(id) { while (true) { load(); delay(pollMs) } }
    LaunchedEffect(msgs.size) { delay(80); sv.animateScrollTo(sv.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(sv).padding(14.dp)) {
            if (msgs.isEmpty()) Text(if (enabled) "Nenhuma mensagem ainda. Escreva para o administrador." else "O chat é liberado após a confirmação do Pix.", color = GRAY)
            msgs.forEach { m -> val mine = isMine(m, me)
                Appear { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), if (mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 280.dp).background(if (mine) GREEN else CARD, RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (!mine) Text("Administrador", color = GREEN, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(m.s("content"), color = if (mine) ON else TX)
                        Text(m.s("created_at", "createdAt").take(16).replace("T", " "), fontSize = 10.sp, color = if (mine) ON.copy(alpha = 0.7f) else GRAY, modifier = Modifier.align(Alignment.End)) } } } }
        }
        if (enabled && !blocked) InputBar(txt, { txt = it }) { val t = txt.trim(); txt = ""; sc.launch { Net.api("POST", "/orders/$id/messages", JSONObject().put("content", t).toString()); load() } }
        else Text(if (blocked) "Conta restrita: mensagens bloqueadas." else "Chat liberado após o Pix confirmado.", Modifier.fillMaxWidth().background(CARD).padding(14.dp), color = GRAY, fontSize = 12.sp)
    }
}

@Composable fun OrderDetail(id: String) {
    var d by remember { mutableStateOf(JSONObject()) }; var spinning by remember { mutableStateOf(false) }; var note by remember { mutableStateOf("") }
    var rev by remember { mutableStateOf("") }; var stars by remember { mutableIntStateOf(0) }; var tab by remember { mutableIntStateOf(0) }; var target by remember { mutableFloatStateOf(0f) }
    val sc = rememberCoroutineScope()
    suspend fun load() { Net.api("GET", "/orders/$id").let { if (it.first == 200) { d = JSONObject(it.second) } } }
    LaunchedEffect(id) { while (true) { load(); delay(8000) } }
    val o = d.optJSONObject("order") ?: JSONObject(); val draws = d.optJSONArray("draws").list()
    val status = o.s("status").lowercase(); val paid = isPaid(status); val stage = if (status == "entregue") 3 else if (status == "pago") 2 else 1
    val rot by animateFloatAsState(target, tween(2000, easing = FastOutSlowInEasing), label = "r")
    Column(Modifier.fillMaxSize()) {
        TopBack(o.s("product_title", "productTitle", "title", "id"), "Pedido #${id.take(8)}", { Chip(statusLabel(status), statusColor(status)) }) { St.open = null }
        Tabs(listOf("Detalhes", "Chat"), tab) { tab = it }
        if (tab == 1) Box(Modifier.weight(1f)) { ChatPanel(id, paid) }
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { listOf("Pedido", "Pago", "Entregue").forEachIndexed { i, t ->
                val c by animateColorAsState(if (stage > i) GREEN else GRAY.copy(alpha = 0.4f), tween(600), label = "t")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(30.dp).background(c, CircleShape), Alignment.Center) { if (stage > i) Icon(Icons.Filled.Check, null, tint = ON, modifier = Modifier.size(18.dp)) else Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold) }
                    Text(t, fontSize = 11.sp, color = c) }
                if (i < 2) Box(Modifier.weight(1f).height(3.dp).background(c)) } }
            if (!paid) Text("Aguardando confirmação do Pix pela loja.", color = GRAY)
            else {
                Text("Roleta de prêmios", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Wheel(rot, Modifier.padding(vertical = 14.dp).size(190.dp).align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Slot(1, draws.getOrNull(0), draws.isEmpty(), Modifier.weight(1f)); Slot(2, draws.getOrNull(1), draws.size == 1, Modifier.weight(1f)) }
                Button({ sc.launch { spinning = true; target += 1800f + (0..360).random(); val r = Net.api("POST", "/orders/$id/spin", JSONObject().put("spinNumber", draws.size + 1).toString()); delay(2100); spinning = false; note = if (r.first in 200..299) "" else err(r.second); load() } },
                    Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp), enabled = !spinning && draws.size < 2) { Text(if (spinning) "Girando..." else if (draws.size >= 2) "Giros concluídos" else "Girar roleta", fontWeight = FontWeight.Bold) }
                if (note.isNotEmpty()) Text(note, color = RED)
            }
            if (status == "entregue" && !o.b("buyer_confirmed", "buyerConfirmed")) {
                Spacer(Modifier.height(18.dp)); Text("Confirmar recebimento e avaliar", fontWeight = FontWeight.Bold)
                Row(Modifier.padding(vertical = 6.dp)) { (1..5).forEach { s -> Icon(Icons.Filled.Star, null, tint = if (s <= stars) AMBER else GRAY.copy(alpha = 0.4f), modifier = Modifier.size(38.dp).clip(CircleShape).clickable { stars = s }.padding(3.dp)) } }
                OutlinedTextField(rev, { rev = it }, Modifier.fillMaxWidth(), label = { Text("Comentário") })
                Button({ sc.launch { Net.api("PATCH", "/orders/$id", JSONObject().put("buyerConfirmed", true).put("rating", stars).put("review", rev).toString()); load() } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = stars > 0) { Text("Enviar avaliação") }
            }
        }
    }
}

@Composable fun StatBox(label: String, value: Int, mod: Modifier) {
    val v by animateIntAsState(value, tween(1200), label = "n")
    Column(mod.background(CARD, RoundedCornerShape(14.dp)).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$v", fontSize = 24.sp, fontWeight = FontWeight.Black, color = GREEN); Text(label, color = GRAY, fontSize = 12.sp) }
}

@Composable fun MenuItem(t: String, sub: String, c: Color = TX, leading: @Composable () -> Unit, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(c.copy(alpha = 0.14f), RoundedCornerShape(12.dp)), Alignment.Center) { leading() }; Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(t, color = c, fontWeight = FontWeight.Bold); Text(sub, color = GRAY, fontSize = 12.sp) }
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = GRAY)
    }

@Composable fun ThemeCard() {
    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("Aparência", fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().background(FIELD, RoundedCornerShape(50)).padding(4.dp)) { listOf("Escuro", "Claro", "Preto").forEachIndexed { i, t ->
            val sel = Th.mode == i; val bg by animateColorAsState(if (sel) GREEN else Color.Transparent, label = "m")
            Text(t, Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg).clickable { Th.mode = i; Th.save() }.padding(vertical = 9.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (sel) ON else GRAY) } }
        Text("Cor do app", Modifier.padding(top = 14.dp, bottom = 8.dp), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { (0..5).forEach { i ->
            val c = if (i == 0) (Th.store ?: Color(0xFF22C55E)) else Th.presets[i - 1]; val sel = Th.idx == i
            val sc by animateFloatAsState(if (sel) 1.15f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
            Box(Modifier.size(36.dp).scale(sc).background(c, CircleShape).then(if (sel) Modifier.border(3.dp, TX, CircleShape) else Modifier).clickable { Th.idx = i; Th.save() }, Alignment.Center) {
                if (i == 0) Text("Loja", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold) } } }
        Text("A opção Loja usa a cor definida pelo administrador.", color = GRAY, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable fun Profile() {
    val ctx = LocalContext.current; val p = St.me.optJSONObject("profile") ?: JSONObject()
    val ps by rememberInfiniteTransition(label = "av").animateFloat(1f, 1.35f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "a")
    val nm = p.s("name", "display_name", "full_name", "username")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(GREEN.copy(alpha = 0.28f), Color.Transparent))).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp), Alignment.Center) {
                Box(Modifier.size(90.dp).scale(ps).background(GREEN.copy(alpha = (0.05f + 0.3f * (1.35f - ps) / 0.35f).coerceIn(0f, 1f)), CircleShape))
                Box(Modifier.size(88.dp).background(CARD, CircleShape).border(2.dp, GREEN, CircleShape), Alignment.Center) {
                    Image(painterResource(R.drawable.ic_profile), null, Modifier.size(50.dp), colorFilter = ColorFilter.tint(GREEN)) }
            }
            Spacer(Modifier.height(10.dp))
            Text(nm.ifEmpty { p.s("email") }, fontSize = 20.sp, fontWeight = FontWeight.Black)
            if (nm.isNotEmpty() && p.s("email").isNotEmpty()) Text(p.s("email"), color = GRAY, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (St.me.b("isAdmin")) Chip("ADMIN", AMBER)
                if (p.s("created_at", "createdAt").isNotEmpty()) Chip("Cliente desde ${p.s("created_at", "createdAt").take(10)}", GREEN) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (p.b("banned") || p.s("removed_at").isNotEmpty())
                Text("Conta restrita. ${p.s("removal_reason")} ${p.s("removal_details")}", color = RED, modifier = Modifier.fillMaxWidth().background(RED.copy(alpha = 0.13f), RoundedCornerShape(12.dp)).padding(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Pedidos", St.orders.size, Modifier.weight(1f)); StatBox("Pagos", St.orders.count { isPaid(it.s("status")) }, Modifier.weight(1f))
                StatBox("Entregues", St.orders.count { it.s("status").lowercase() == "entregue" }, Modifier.weight(1f)) }
            Appear(100) { MenuItem("Meus pedidos", "Status, roleta e chat", leading = { Image(painterResource(R.drawable.ic_orders), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(GREEN)) }) { St.tab = 1 } }
            Appear(200) { MenuItem("Chat com o administrador", "Suporte em tempo real", leading = { Icon(Icons.Filled.Call, null, tint = GREEN) }) { St.sub = "support" } }
            if (St.me.b("isAdmin")) Appear(250) { MenuItem("Gerenciar cupons", "Criar, editar e excluir cupons", leading = { Icon(Icons.Filled.Star, null, tint = GREEN) }) { St.sub = "coupons" } }
            Appear(300) { ThemeCard() }
            Appear(400) { MenuItem("Site oficial", "Cupons, carrinho e gestão avançada", leading = { Icon(Icons.Filled.Info, null, tint = GREEN) }) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE))) } }
            Appear(500) { MenuItem("Sair da conta", "Encerrar sessão neste aparelho", RED, leading = { Icon(Icons.Filled.ExitToApp, null, tint = RED) }) { St.logout() } }
        }
    }
}

@Composable fun Support() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }; var sel by remember { mutableStateOf<String?>(null) }
    val paid = St.orders.filter { isPaid(it.s("status")) }; val cur = sel ?: paid.firstOrNull()?.s("id")
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val faqs = listOf(
        "Como pago meu pedido?" to "Escolha o produto, informe seu nome e o nome no jogo e toque em Comprar. Pague o QR Pix: o app verifica o pagamento sozinho.",
        "Quando meu pedido é confirmado?" to "A confirmação do Pix é feita pela loja. Depois dela, o status vira PAGO e o chat e a roleta são liberados.",
        "Como funciona a roleta?" to "Depois do pagamento você gira a roleta no detalhe do pedido. O prêmio é sorteado pela loja e fica salvo no pedido. Se for um cupom, toque nele para copiar.",
        "Como recebo meu produto?" to "Combine a entrega pelo chat do pedido. Quando a loja marcar como ENTREGUE, confirme o recebimento e avalie.",
        "Tive um problema, e agora?" to "Abra o chat com o administrador e conte o que aconteceu. Para outros assuntos, use o site oficial.")
    Column(Modifier.fillMaxSize()) {
        TopBack("Suporte", "Converse com o administrador", { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).alpha(pulse).background(Color(0xFF22C55E), CircleShape)); Spacer(Modifier.width(6.dp)); Text("Ao vivo", fontSize = 12.sp, color = GRAY) } }) { St.sub = null }
        Tabs(listOf("Chat com o admin", "Ajuda"), tab) { tab = it }
        if (tab == 0) {
            if (cur == null) Column(Modifier.padding(24.dp)) {
                Text("Chat disponível depois do primeiro Pix confirmado", fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
                Text("O atendimento acontece pelo chat do seu pedido, atualizado a cada poucos segundos. Enquanto isso, veja a aba Ajuda.", color = GRAY)
                Spacer(Modifier.height(12.dp)); Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE))) }) { Text("Abrir suporte no site") } }
            else Column(Modifier.weight(1f)) {
                if (paid.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paid.forEach { o -> val on = o.s("id") == cur
                        Text("#${o.s("id").take(6)}", Modifier.clip(RoundedCornerShape(50)).background(if (on) GREEN else CARD).clickable { sel = o.s("id") }.padding(horizontal = 14.dp, vertical = 7.dp), color = if (on) ON else GRAY, fontWeight = FontWeight.Bold, fontSize = 13.sp) } }
                ChatPanel(cur, true, 3000)
            }
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            faqs.forEach { (q, a) -> var ex by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).clickable { ex = !ex }.padding(14.dp).animateContentSize()) {
                    Row { Text(q, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(if (ex) "−" else "+", color = GREEN, fontSize = 20.sp) }
                    if (ex) Text(a, color = SOFT, modifier = Modifier.padding(top = 8.dp)) } }
        }
    }
}

fun matches(p: JSONObject, id: String, list: List<JSONObject>, vararg keys: String): Boolean {
    val v = p.s(*keys); val lbl = list.firstOrNull { it.s("id", "slug", "key") == id }?.s("title", "name", "label") ?: ""
    return v == id || (lbl.isNotEmpty() && v.equals(lbl, true))
}

@Composable fun FilterRow(items: List<JSONObject>, sel: String, all: String, onSel: (String) -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("" to all) + items.map { it.s("id", "slug", "key") to it.s("title", "name", "label") }).forEach { (id, t) ->
            val on = sel == id; val bg by animateColorAsState(if (on) GREEN else CARD, label = "f")
            Text(t, Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSel(id) }.padding(horizontal = 16.dp, vertical = 8.dp), color = if (on) ON else GRAY, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable fun CouponsAdmin() {
    var list by remember { mutableStateOf(listOf<JSONObject>()) }; var edit by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }; var del by remember { mutableStateOf<JSONObject?>(null) }; var msg by remember { mutableStateOf("") }
    val sc = rememberCoroutineScope()
    suspend fun load() { val r = Net.api("GET", "/admin/coupons"); if (r.first == 200) { list = JSONObject(r.second).optJSONArray("coupons").list(); msg = "" } else { msg = err(r.second) } }
    LaunchedEffect(Unit) { load() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBack("Cupons", "Somente administradores", { Text("Novo", color = GREEN, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { creating = true }.padding(horizontal = 12.dp, vertical = 8.dp)) }) { St.sub = null }
            if (msg.isNotEmpty()) Text(msg, color = RED, modifier = Modifier.padding(14.dp))
            LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty() && msg.isEmpty()) item { Text("Nenhum cupom criado.", color = GRAY) }
                items(list) { c -> Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.s("code"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text("${c.s("discount_percent")}% de desconto  /  ${if (c.b("single_use")) "uso único" else "uso livre"}  /  usos: ${c.s("uses").ifEmpty { "0" }}", color = GRAY, fontSize = 12.sp) }
                        val on = c.opt("active") != false && c.opt("active") != 0; Chip(if (on) "ATIVO" else "INATIVO", if (on) Color(0xFF22C55E) else GRAY) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ edit = c }) { Text("Editar") }; OutlinedButton({ del = c }) { Text("Excluir", color = RED) } } } }
            }
        }
        if (creating || edit != null) CouponDialog(edit, { creating = false; edit = null }) { creating = false; edit = null; sc.launch { load() } }
        del?.let { d -> AlertDialog({ del = null }, title = { Text("Excluir cupom") }, text = { Text("Excluir o cupom ${d.s("code")}? Esta ação não pode ser desfeita.") },
            confirmButton = { Button({ val id = d.s("id"); del = null; sc.launch { val r = Net.api("DELETE", "/admin/coupons/$id"); if (r.first in 200..299) { load() } else { msg = err(r.second) } } },
                colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White)) { Text("Excluir") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancelar") } }) }
    }
}

@Composable fun CouponDialog(c: JSONObject?, onDismiss: () -> Unit, onDone: () -> Unit) {
    var code by remember { mutableStateOf(c?.s("code") ?: "") }; var pct by remember { mutableStateOf(c?.s("discount_percent") ?: "") }
    var single by remember { mutableStateOf(c?.b("single_use") ?: false) }; var active by remember { mutableStateOf(c?.let { it.opt("active") != false && it.opt("active") != 0 } ?: true) }
    var e by remember { mutableStateOf("") }; val sc = rememberCoroutineScope()
    AlertDialog(onDismiss, title = { Text(if (c == null) "Novo cupom" else "Editar cupom") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Código", code, { code = it.uppercase().trim() }, "Ex: BEMVINDO10", Icons.Filled.Star)
        Field("Desconto (%)", pct, { pct = it.filter { ch -> ch.isDigit() || ch == '.' } }, "De 1 a 100", Icons.Filled.Create)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Uso único", Modifier.weight(1f)); Switch(single, { single = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Ativo", Modifier.weight(1f)); Switch(active, { active = it }) }
        if (e.isNotEmpty()) Text(e, color = RED, fontSize = 12.sp) } },
        confirmButton = { Button({ val v = pct.toDoubleOrNull()
            if (code.length < 2 || v == null || v <= 0 || v > 100) { e = "Informe um código e um desconto entre 1 e 100." }
            else { sc.launch { val body = JSONObject().put("code", code).put("discount_percent", v).put("single_use", single).put("active", active).toString()
                val r = if (c == null) Net.api("POST", "/admin/coupons", body) else Net.api("PATCH", "/admin/coupons/${c.s("id")}", body)
                if (r.first in 200..299) { onDone() } else { e = err(r.second) } } } }) { Text("Salvar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}
