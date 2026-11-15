package ch.widmer.airview

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.widmer.airview.data.HealthRepo
import ch.widmer.airview.data.PERMISSIONS
import ch.widmer.airview.data.Snapshot
import ch.widmer.airview.ui.*
import ch.widmer.airview.update.Release
import ch.widmer.airview.update.Updater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

sealed interface UiState {
    data object Loading : UiState
    data object NoHealthConnect : UiState
    data object NeedsPermission : UiState
    data class Ready(val data: Snapshot) : UiState
    data class Error(val msg: String) : UiState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val r: Release) : UpdateState
    data class Downloading(val r: Release, val progress: Float) : UpdateState
    data class Failed(val msg: String) : UpdateState
}

class MainVm(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf<UiState>(UiState.Loading); private set
    var update by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    var updateDismissed by mutableStateOf(false)

    fun checkUpdate(manual: Boolean = false) {
        if (update is UpdateState.Downloading) return
        viewModelScope.launch {
            update = UpdateState.Checking
            update = try {
                Updater.check()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                if (manual) UpdateState.Failed("Update-Server nicht erreichbar.") else UpdateState.Idle
            }
            if (manual) updateDismissed = false
        }
    }

    fun installUpdate(r: Release) {
        val ctx = getApplication<Application>()
        if (Updater.needsInstallPermission(ctx)) { Updater.openInstallPermission(ctx); return }
        viewModelScope.launch {
            update = UpdateState.Downloading(r, 0f)
            try {
                val apk = Updater.download(ctx, r) { p -> update = UpdateState.Downloading(r, p) }
                Updater.install(ctx, apk)
                update = UpdateState.Available(r)
            } catch (e: Exception) {
                update = UpdateState.Failed(e.message ?: "Download fehlgeschlagen.")
            }
        }
    }
    var refreshing by mutableStateOf(false); private set
    private var repo: HealthRepo? = null

    /** Alle Rechte inkl. Verlauf und Hintergrund, soweit das Gerät sie kennt. */
    fun allPermissions(): Set<String> = PERMISSIONS + runCatching { (repo ?: HealthRepo(getApplication())).extraPermissions() }.getOrDefault(emptySet())

    fun refresh() {
        val ctx = getApplication<Application>()
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
            state = UiState.NoHealthConnect; return
        }
        val r = repo ?: HealthRepo(ctx).also { repo = it }
        viewModelScope.launch {
            refreshing = true
            state = try {
                if (r.granted().isEmpty()) UiState.NeedsPermission else UiState.Ready(r.load().also { snap ->
                    // Widget, Hinweise und Erinnerung im Hintergrund nachziehen
                    viewModelScope.launch { runCatching { ch.widmer.airview.work.AfterLoad.run(ctx, snap, fromBackground = false) } }
                    ch.widmer.airview.work.Scheduler.periodic(ctx)
                })
            } catch (e: Exception) {
                UiState.Error(e.message ?: e.javaClass.simpleName)
            }
            refreshing = false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AirTheme { App() } }
    }
}

enum class Tab(val label: String) { Heute("Heute"), Fitness("Fitness"), Schlaf("Schlaf"), Gesundheit("Gesundheit") }

@Composable
fun App(vm: MainVm = viewModel()) {
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refresh() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.refresh(); vm.checkUpdate() }
    val notif = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(vm.state is UiState.Ready) {
        if (vm.state is UiState.Ready && android.os.Build.VERSION.SDK_INT >= 33) {
            val prefs = ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifAsked", false)) {
                prefs.edit().putBoolean("notifAsked", true).apply()
                notif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    when (val st = vm.state) {
        is UiState.Ready -> Pages(
            st.data, vm.refreshing, onRefresh = { vm.refresh(); vm.checkUpdate() }, onPermissions = { launcher.launch(vm.allPermissions()) },
            update = vm.update, updateVisible = !vm.updateDismissed,
            onInstall = vm::installUpdate, onDismissUpdate = { vm.updateDismissed = true }, onCheckUpdate = { vm.checkUpdate(manual = true) },
        )
        UiState.Loading -> Box(Modifier.fillMaxSize().background(LocalPalette.current.bg).statusBarsPadding()) { Skeleton() }
        else -> {
            val p = LocalPalette.current
            Box(Modifier.fillMaxSize().background(p.bg).systemBarsPadding()) {
                when (st) {
                    UiState.NoHealthConnect -> Gate(
                        "Health Connect fehlt",
                        "Schalte in Google Health das Teilen mit Health Connect ein.",
                        "Health Connect installieren",
                    ) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    }
                    UiState.NeedsPermission -> Gate(
                        "Zugriff erlauben",
                        "Die Daten bleiben auf dem Telefon.",
                        "Zugriff erlauben",
                    ) { launcher.launch(vm.allPermissions()) }
                    is UiState.Error -> Gate("Daten konnten nicht geladen werden", st.msg, "Erneut versuchen") { vm.refresh() }
                    else -> {}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pages(
    data: Snapshot, refreshing: Boolean, onRefresh: () -> Unit, onPermissions: () -> Unit, startPage: Int = 0,
    update: UpdateState = UpdateState.Idle, updateVisible: Boolean = true,
    onInstall: (Release) -> Unit = {}, onDismissUpdate: () -> Unit = {}, onCheckUpdate: () -> Unit = {},
) {
    val tabs = Tab.entries
    val pager = rememberPagerState(initialPage = startPage) { tabs.size }
    val scope = rememberCoroutineScope()
    val base = basePalette()
    val night = nightPalette()
    fun pal(i: Int) = if (tabs[i] == Tab.Schlaf) night else base

    // Farbe folgt dem Finger: zwischen zwei Seiten wird gemischt
    val pos = pager.currentPage + pager.currentPageOffsetFraction
    val i0 = pos.toInt().coerceIn(0, tabs.lastIndex)
    val i1 = (i0 + 1).coerceAtMost(tabs.lastIndex)
    val live = lerpPalette(pal(i0), pal(i1), (pos - i0).coerceIn(0f, 1f))

    // Statusleiste hell/dunkel passend zum Hintergrund
    val view = LocalView.current
    if (!view.isInEditMode) LaunchedEffect(live.isDark) {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !live.isDark
            WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = !live.isDark
        }
    }

    // Einblenden pro Seite, jeweils beim ersten Ankommen
    val reveals = remember { tabs.map { Animatable(0f) } }
    // Auftritt bei jedem Besuch: startet sofort beim Ansteuern der Seite (nicht erst nach dem
    // Einrasten) und dauert lange genug, um gesehen zu werden.
    LaunchedEffect(pager.targetPage) {
        val a = reveals[pager.targetPage]
        if (a.value < 1f) a.animateTo(1f, tween(1100, easing = LinearEasing))
    }
    // verlassene Seiten zurücksetzen, damit sie beim nächsten Besuch wieder aufgebaut werden
    LaunchedEffect(pager.settledPage) {
        reveals.forEachIndexed { i, a -> if (i != pager.settledPage && i != pager.targetPage) a.snapTo(0f) }
    }

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    CompositionLocalProvider(LocalPalette provides live, LocalOpenSheet provides { sheet = it }, LocalRefresh provides onRefresh,
        LocalRequestAccess provides onPermissions) {
        Box(Modifier.fillMaxSize().background(live.bg)) {
            PullToRefreshBox(refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
                    CompositionLocalProvider(
                        LocalPalette provides lerpPalette(pal(page), live, offset.coerceIn(0f, 1f)),
                        LocalReveal provides reveals[page].value,
                        LocalRefresh provides onRefresh,
                    ) {
                        Column(
                            Modifier.fillMaxSize().graphicsLayer {
                                // leichte Tiefe beim Wischen
                                val s = 1f - offset.coerceIn(0f, 1f) * 0.06f
                                scaleX = s; scaleY = s
                                alpha = 1f - offset.coerceIn(0f, 1f) * 0.5f
                            },
                        ) {
                            if (data.missing.isNotEmpty() && page == 0) {
                                Text(
                                    "${data.missing.size} Datentypen gesperrt. Freigeben",
                                    style = Type.small, color = LocalPalette.current.heart,
                                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPermissions)
                                        .padding(horizontal = 24.dp, vertical = 10.dp),
                                )
                            }
                            when (tabs[page]) {
                                Tab.Heute -> TodayScreen(data)
                                Tab.Fitness -> FitnessScreen(data)
                                Tab.Schlaf -> SleepScreen(data)
                                Tab.Gesundheit -> HealthScreen(data) { VersionLine(update, onCheckUpdate) }
                            }
                        }
                    }
                }
            }
            val showBanner = updateVisible && (update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Failed)
            AnimatedVisibility(
                showBanner, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
                enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            ) { UpdateBanner(update, onInstall, onDismissUpdate) }
            TaskBar(
                pos = pos, tabs = tabs,
                accent = { t -> when (t) { Tab.Heute -> live.ink; Tab.Fitness -> live.move; Tab.Schlaf -> live.sleep; Tab.Gesundheit -> live.heart } },
                onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = spring(dampingRatio = .85f, stiffness = 300f)) } },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            // Detailansichten in der Farbe der aktuellen Seite
            SheetHost(sheet, data) { sheet = null }
        }
    }
}

/**
 * Schwebende Leiste mit vier Knöpfen. Die Markierung gleitet mit dem Finger mit
 * und nimmt die Farbe des Datentyps der Seite an.
 */
@Composable
fun TaskBar(pos: Float, tabs: List<Tab>, accent: (Tab) -> Color, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val slotW = with(density) { (width / tabs.size).toDp() }
    val i0 = pos.toInt().coerceIn(0, tabs.lastIndex)
    val i1 = (i0 + 1).coerceAtMost(tabs.lastIndex)
    val indicatorColor = androidx.compose.ui.graphics.lerp(accent(tabs[i0]), accent(tabs[i1]), pos - i0)

    Box(
        modifier.fillMaxWidth().height(68.dp)
            .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(.25f), spotColor = Color.Black.copy(.25f))
            .clip(RoundedCornerShape(26.dp)).background(p.bar).onSizeChanged { width = it.width },
    ) {
        // gleitende Markierung
        Box(
            Modifier.padding(6.dp).width(slotW - 12.dp).fillMaxHeight()
                .offset(x = slotW * pos)
                .clip(RoundedCornerShape(20.dp))
                .background(indicatorColor.copy(alpha = if (p.isDark) .22f else .13f)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, t ->
                val near = (1f - (pos - i).absoluteValue).coerceIn(0f, 1f)   // 1 = aktiv
                val tint = androidx.compose.ui.graphics.lerp(p.muted, accent(t), near)
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSelect(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Canvas(Modifier.size(24.dp).graphicsLayer { val s = 1f + near * .12f; scaleX = s; scaleY = s }) {
                        tabIcon(t, tint, near)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.label,
                        style = (if (near > .5f) Type.title else Type.body).copy(fontSize = 12.sp, lineHeight = 14.sp),
                        color = if (near > .5f) p.ink else p.muted,
                    )
                }
            }
        }
    }
}

/** Eigene Symbole, gezeichnet statt aus einem Icon-Set. `on` 0..1 = wie aktiv. */
private fun DrawScope.tabIcon(t: Tab, c: Color, on: Float) {
    val w = size.width
    val stroke = Stroke(w * .09f, cap = StrokeCap.Round)
    when (t) {
        Tab.Heute -> { // Tagesring, füllt sich wenn aktiv
            drawCircle(c.copy(alpha = .35f), w * .38f, style = stroke)
            drawArc(c, -90f, 200f + 140f * on, false, Offset(w * .12f, w * .12f),
                androidx.compose.ui.geometry.Size(w * .76f, w * .76f), style = stroke)
        }
        Tab.Fitness -> { // drei steigende Balken
            listOf(.45f, .65f, .9f).forEachIndexed { i, h ->
                val x = w * (.2f + i * .3f)
                val hh = h * (0.75f + .25f * on)
                drawLine(c, Offset(x, w * .9f), Offset(x, w * (.9f - hh * .8f)), w * .13f, StrokeCap.Round)
            }
        }
        Tab.Schlaf -> { // Mondsichel
            val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w / 2, w / 2), w * .4f)) }
            val cut = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w * (.72f - .08f * on), w * .3f), w * .34f))
            }
            drawPath(Path().apply { op(moon, cut, PathOperation.Difference) }, c)
        }
        Tab.Gesundheit -> { // Pulsschlag
            val path = Path().apply {
                moveTo(w * .05f, w * .55f); lineTo(w * .3f, w * .55f); lineTo(w * .42f, w * (.25f - .1f * on))
                lineTo(w * .58f, w * (.85f + .05f * on)); lineTo(w * .7f, w * .55f); lineTo(w * .95f, w * .55f)
            }
            drawPath(path, c, style = Stroke(w * .09f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun Gate(title: String, text: String, action: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = Type.numeral.copy(fontSize = 48.sp), color = p.ink)
        Spacer(Modifier.height(14.dp))
        Text(text, style = Type.body, color = p.muted)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick, colors = ButtonDefaults.buttonColors(containerColor = p.ink, contentColor = p.bg),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        ) { Text(action, style = Type.title.copy(fontSize = 15.sp)) }
    }
}


/** Schwebender Hinweis oben, wenn eine neue Version bereitliegt. */
@Composable
private fun UpdateBanner(u: UpdateState, onInstall: (Release) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(p.ink).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        when (u) {
            is UpdateState.Available -> {
                Text("Version ${u.r.versionName} ist bereit", style = Type.title.copy(fontSize = 16.sp), color = p.bg)
                if (u.r.notes.isNotBlank()) Text(u.r.notes, style = Type.small, color = p.bg.copy(alpha = .75f), modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Später", style = Type.body, color = p.bg.copy(alpha = .75f)) }
                    Spacer(Modifier.width(6.dp))
                    Button({ onInstall(u.r) }, colors = ButtonDefaults.buttonColors(containerColor = p.bg, contentColor = p.ink)) {
                        Text("Installieren", style = Type.title.copy(fontSize = 15.sp))
                    }
                }
            }
            is UpdateState.Downloading -> {
                Text("Version ${u.r.versionName} wird geladen", style = Type.title.copy(fontSize = 16.sp), color = p.bg)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator({ u.progress }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = p.bg, trackColor = p.bg.copy(alpha = .2f))
            }
            is UpdateState.Failed -> {
                Text(u.msg, style = Type.body, color = p.bg)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Schliessen", style = Type.body, color = p.bg.copy(alpha = .75f)) }
                }
            }
            else -> {}
        }
    }
}

/** Version unten auf der Gesundheitsseite; antippen sucht nach Updates. */
@Composable
private fun VersionLine(u: UpdateState, onCheck: () -> Unit) {
    val p = LocalPalette.current
    val v = BuildConfig.VERSION_NAME
    Text(
        when (u) {
            UpdateState.Checking -> "Version $v, prüfe …"
            UpdateState.UpToDate -> "Version $v, aktuell"
            is UpdateState.Available -> "Version ${u.r.versionName} bereit"
            else -> "Version $v"
        },
        style = Type.small.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        color = p.muted, modifier = Modifier.clickable(onClick = onCheck).padding(vertical = 6.dp),
    )
}


/**
 * Platzhalter in exakt der Form der Heute-Seite (gleiche Seite, gleiche Masse wie
 * TodayScreen), damit beim Laden nichts springt.
 */
@Composable
internal fun Skeleton() {
    val p = LocalPalette.current
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val a by pulse.animateFloat(.45f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val c = p.track.copy(alpha = a)
    @Composable fun bar(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) =
        Box(Modifier.width(w).height(h).clip(RoundedCornerShape(h / 2)).background(c))
    Box(Modifier.fillMaxSize()) {
        Page { avail ->
            val ring = (avail - 502.dp).coerceIn(150.dp, 280.dp)
            Column { bar(230.dp, 24.dp); Spacer(Modifier.height(10.dp)); bar(160.dp, 14.dp) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(ring)) {
                    val sw = ring.toPx() * 0.085f; val gap = sw * 0.28f
                    for (i in 0..2) drawCircle(c, size.width / 2 - sw / 2 - i * (sw + gap), style = Stroke(sw))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    repeat(3) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            bar(56.dp, 12.dp); Spacer(Modifier.height(8.dp)); bar(72.dp, 24.dp); Spacer(Modifier.height(8.dp)); bar(64.dp, 12.dp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(7) {
                        Column(Modifier.padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.size(32.dp)) { drawCircle(c, size.width / 2 - 2.dp.toPx(), style = Stroke(4.dp.toPx())) }
                            Spacer(Modifier.height(6.dp)); bar(18.dp, 10.dp)
                        }
                    }
                }
            }
            Column {
                bar(96.dp, 20.dp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).background(c))
            }
            Row(Modifier.fillMaxWidth()) {
                repeat(3) { Column(Modifier.weight(1f)) { bar(54.dp, 28.dp); Spacer(Modifier.height(6.dp)); bar(64.dp, 12.dp) } }
            }
        }
        // Leiste unten an derselben Stelle wie die echte
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth().height(68.dp).clip(RoundedCornerShape(26.dp)).background(p.bar),
        )
    }
}
