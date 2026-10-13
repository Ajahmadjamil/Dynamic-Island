package com.codewithaj.pixelisland.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.codewithaj.pixelisland.ui.components.AppLogo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Animated how-to clips for the setup wizard: a small mock phone that "plays" the taps the user
 * has to make in Android's Settings, with a finger that moves and taps. Drawn with Compose rather
 * than shipped as videos, so it follows the app theme, adds nothing to the APK and loops forever.
 *
 * A [Tutorial] is a list of [TutorialFrame]s. Each frame shows a mock screen (and maybe a dialog on
 * top) and names the element the finger taps. Frames that share a [MockScreen.key] update in place
 * (a switch flips, a menu opens) instead of sliding in as a new screen.
 */

@Immutable
data class Tutorial(
    /** Instructions shown next to the phone; `**text**` is drawn bold. */
    val steps: List<String>,
    val frames: List<TutorialFrame>,
)

@Immutable
data class TutorialFrame(
    val screen: MockScreen,
    val overlay: MockOverlay? = null,
    /** Element the finger taps in this frame ([Tap]), or null to just hold the frame. */
    val tap: String? = null,
    /** Index into [Tutorial.steps] that this frame illustrates. */
    val step: Int,
)

/** Ids of tappable mock elements. */
object Tap {
    const val SWITCH = "switch"
    const val CONFIRM = "confirm"
    const val MENU = "menu"
    const val MENU_ITEM = "menuItem"
    const val FINGERPRINT = "fingerprint"
    fun row(index: Int) = "row$index"
}

sealed interface MockScreen { val key: String }

/** A Settings list page. Rows are tapped with [Tap.row] using their index in [rows]. */
data class ListScreen(val title: String, val rows: List<MockRow>) : MockScreen {
    override val key: String get() = "list:$title"
}

data class MockRow(
    val label: String,
    val summary: String? = null,
    val isApp: Boolean = false,
    val isHeader: Boolean = false,
)

/** A Settings detail page built around one main switch ("Use Pixel Island overlay"). */
data class SwitchScreen(
    val title: String,
    val switchLabel: String,
    val on: Boolean = false,
    val enabled: Boolean = true,
) : MockScreen {
    override val key: String get() = "switch:$title"
}

/** App info, with the ⋮ menu that holds "Allow restricted settings". */
data class AppInfoScreen(val menuOpen: Boolean = false, val menuItem: String) : MockScreen {
    override val key: String get() = "appinfo"
}

/** Pixel Island itself, seen behind a system dialog. */
data object OwnAppScreen : MockScreen {
    override val key: String get() = "app"
}

sealed interface MockOverlay { val key: String }

data class MockDialog(
    val title: String,
    val text: String? = null,
    val confirm: String,
    val dismiss: String? = null,
) : MockOverlay {
    override val key: String get() = "dialog:$title"
}

/** Android 12+ runtime permission dialog. The "Allow" button is [Tap.CONFIRM]. */
data class MockPermissionPrompt(val icon: ImageVector, val text: String) : MockOverlay {
    override val key: String get() = "prompt:$text"
}

/** "Verify it's you" sheet. The fingerprint is [Tap.FINGERPRINT]. */
data class MockBiometric(val title: String, val text: String) : MockOverlay {
    override val key: String get() = "bio:$title"
}

/** Which frame is playing; the instruction list reads [step] to highlight the current line. */
@Stable
class TutorialState(val tutorial: Tutorial) {
    var frameIndex by mutableIntStateOf(0)
        internal set
    val step: Int get() = tutorial.frames[frameIndex].step
}

@Composable
fun rememberTutorialState(tutorial: Tutorial): TutorialState = remember(tutorial) { TutorialState(tutorial) }

private val PhoneWidth = 172.dp
private val PhoneHeight = 316.dp
private val Bezel = 5.dp
private val MockStatusBar = 20.dp

/** Remembers where each tappable mock element sits, so the finger knows where to go. */
private class TargetRegistry {
    var root: LayoutCoordinates? = null
    val centers = mutableStateMapOf<String, Offset>()
}

/** Marks a mock element as tap target [id] of the screen or overlay [scope]. */
private fun Modifier.target(registry: TargetRegistry, scope: String, id: String): Modifier =
    onGloballyPositioned { c ->
        val r = registry.root
        if (r != null && r.isAttached && c.isAttached) {
            registry.centers["$scope/$id"] = r.localPositionOf(c, c.size.toSize().center)
        }
    }

/** Text sizes in dp, so the mock keeps its proportions whatever the user's font size. */
@Immutable
private class MockType(val title: TextStyle, val body: TextStyle, val small: TextStyle, val tiny: TextStyle)

@Composable
private fun rememberMockType(): MockType {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    return remember(density, color) {
        with(density) {
            MockType(
                title = TextStyle(fontSize = 11.dp.toSp(), fontWeight = FontWeight.SemiBold, color = color, lineHeight = 13.dp.toSp()),
                body = TextStyle(fontSize = 9.dp.toSp(), color = color, lineHeight = 11.dp.toSp()),
                small = TextStyle(fontSize = 7.5.dp.toSp(), color = color, lineHeight = 9.5.dp.toSp()),
                tiny = TextStyle(fontSize = 6.5.dp.toSp(), color = color, lineHeight = 8.dp.toSp()),
            )
        }
    }
}

/**
 * The looping mock phone. Plays every frame of [state]'s tutorial: waits for the screen to slide
 * in, moves the finger to the frame's target, taps (ripple), then goes on to the next frame.
 */
@Composable
fun TutorialPhone(state: TutorialState, modifier: Modifier = Modifier) {
    val frames = state.tutorial.frames
    val registry = remember(state) { TargetRegistry() }
    val type = rememberMockType()
    val density = LocalDensity.current

    val finger = remember(state) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val ripple = remember(state) { Animatable(1f) }
    var fingerShown by remember(state) { mutableStateOf(false) }
    var pressed by remember(state) { mutableStateOf(false) }
    val fingerAlpha by animateFloatAsState(if (fingerShown) 1f else 0f, tween(250), label = "fingerAlpha")
    val fingerScale by animateFloatAsState(
        if (pressed) 0.8f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "fingerScale",
    )

    LaunchedEffect(state) {
        val restY = with(density) { (PhoneHeight - Bezel * 2).toPx() }
        val restDx = with(density) { 36.dp.toPx() }
        while (true) {
            frames.forEachIndexed { i, frame ->
                state.frameIndex = i
                delay(if (i == 0) 700L else 600L) // let the screen slide in first
                val tap = frame.tap
                if (tap == null) {
                    delay(1_300L)
                    return@forEachIndexed
                }
                val scope = frame.overlay?.key ?: frame.screen.key
                val target = withTimeoutOrNull(1_500L) {
                    snapshotFlow { registry.centers["$scope/$tap"] }.filterNotNull().first()
                } ?: return@forEachIndexed
                if (!fingerShown) {
                    finger.snapTo(Offset(target.x + restDx, restY))
                    fingerShown = true
                }
                finger.animateTo(target, tween(650, easing = FastOutSlowInEasing))
                pressed = true
                ripple.snapTo(0f)
                launch { ripple.animateTo(1f, tween(520, easing = LinearOutSlowInEasing)) }
                delay(170L)
                pressed = false
                delay(450L)
            }
            delay(1_500L)
            fingerShown = false
            delay(500L)
        }
    }

    val frame = frames[state.frameIndex]
    Box(
        modifier
            .size(PhoneWidth, PhoneHeight)
            .clearAndSetSemantics { contentDescription = "Animated example of the steps" }
            .background(Color(0xFF1C1C1E), RoundedCornerShape(28.dp))
            .padding(Bezel),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(23.dp))
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { registry.root = it },
        ) {
            AnimatedContent(
                targetState = state.frameIndex,
                contentKey = { frames[it].screen.key },
                transitionSpec = {
                    (slideInHorizontally(tween(360)) { it / 3 } + fadeIn(tween(360))) togetherWith
                        (slideOutHorizontally(tween(360)) { -it / 3 } + fadeOut(tween(240)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "tutorialScreen",
            ) { index ->
                MockScreenContent(frames[index].screen, registry, type)
            }

            MockStatusBarRow(type)

            AnimatedContent(
                targetState = frame.overlay,
                contentKey = { it?.key },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.fillMaxSize(),
                label = "tutorialOverlay",
            ) { overlay ->
                if (overlay == null) Spacer(Modifier.fillMaxSize()) else MockOverlayContent(overlay, registry, type)
            }

            Canvas(Modifier.fillMaxSize()) {
                if (fingerAlpha <= 0f) return@Canvas
                val c = finger.value
                val r = 12.dp.toPx()
                val p = ripple.value
                if (p < 1f) {
                    drawCircle(Color.Gray.copy(alpha = 0.35f * (1f - p) * fingerAlpha), radius = r * (1f + 1.3f * p), center = c)
                }
                drawCircle(Color.White.copy(alpha = 0.8f * fingerAlpha), radius = r * fingerScale, center = c)
                drawCircle(
                    Color.Black.copy(alpha = 0.35f * fingerAlpha),
                    radius = r * fingerScale,
                    center = c,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

/** Time, a tiny idle island over the camera, and two status icons. */
@Composable
private fun MockStatusBarRow(type: MockType) {
    Box(Modifier.fillMaxWidth().height(MockStatusBar)) {
        Text("12:30", style = type.tiny.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 5.dp)
                .size(30.dp, 9.dp)
                .background(Color.Black, RoundedCornerShape(5.dp)),
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = MaterialTheme.colorScheme.onSurface
            Box(Modifier.size(7.dp, 5.dp).background(c, RoundedCornerShape(1.dp)))
            Box(Modifier.size(10.dp, 5.dp).background(c, RoundedCornerShape(1.5.dp)))
        }
    }
}

@Composable
private fun MockTopBar(title: String, type: MockType, menuTarget: Pair<TargetRegistry, String>? = null) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (menuTarget != null) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = null,
                modifier = Modifier.target(menuTarget.first, menuTarget.second, Tap.MENU).padding(2.dp).size(12.dp),
            )
        }
    }
}

@Composable
private fun AppHeader(type: MockType, logo: Int = 32) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AppLogo(Modifier.size(logo.dp))
        Spacer(Modifier.height(4.dp))
        Text("Pixel Island", style = type.body.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun PlaceholderLines(count: Int) {
    val c = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            Box(Modifier.fillMaxWidth(if (i == count - 1) 0.6f else 0.95f).height(5.dp).background(c, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun MockScreenContent(screen: MockScreen, registry: TargetRegistry, type: MockType) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.surface).padding(top = MockStatusBar)) {
        when (screen) {
            is ListScreen -> Column {
                MockTopBar(screen.title, type)
                screen.rows.forEachIndexed { i, row ->
                    if (row.isHeader) {
                        Text(
                            row.label,
                            style = type.small.copy(color = scheme.primary, fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .then(Modifier.target(registry, screen.key, Tap.row(i)))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (row.isApp) AppLogo(Modifier.size(18.dp))
                            else Box(Modifier.size(18.dp).background(scheme.surfaceVariant, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(row.label, style = type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.summary != null) {
                                    Text(row.summary, style = type.tiny.copy(color = scheme.onSurfaceVariant), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            is SwitchScreen -> Column {
                MockTopBar(screen.title, type)
                Spacer(Modifier.height(6.dp))
                AppHeader(type)
                Spacer(Modifier.height(10.dp))
                val card by animateColorAsState(
                    if (screen.on) scheme.primaryContainer else scheme.surfaceContainerHighest,
                    label = "mockSwitchCard",
                )
                Row(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(card)
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        screen.switchLabel,
                        style = type.body.copy(
                            fontWeight = FontWeight.Medium,
                            color = if (screen.enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                        ),
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = screen.on,
                        onCheckedChange = null,
                        enabled = screen.enabled,
                        modifier = Modifier.target(registry, screen.key, Tap.SWITCH).scale(0.62f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                PlaceholderLines(3)
            }

            is AppInfoScreen -> {
                Column {
                    MockTopBar("App info", type, menuTarget = registry to screen.key)
                    Spacer(Modifier.height(4.dp))
                    AppHeader(type, logo = 34)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Open", "Uninstall", "Force stop").forEach { label ->
                            Box(
                                Modifier.weight(1f).height(18.dp).background(scheme.surfaceContainerHighest, RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = type.tiny, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    listOf("Notifications", "Permissions", "Storage & cache", "Battery").forEach { label ->
                        Text(label, style = type.body, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                AnimatedVisibility(
                    visible = screen.menuOpen,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 6.dp),
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceContainer, shadowElevation = 6.dp) {
                        Text(
                            screen.menuItem,
                            style = type.body,
                            modifier = Modifier.target(registry, screen.key, Tap.MENU_ITEM).padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            }

            OwnAppScreen -> Column {
                Spacer(Modifier.height(28.dp))
                AppHeader(type, logo = 40)
                Spacer(Modifier.height(14.dp))
                PlaceholderLines(4)
            }
        }
    }
}

@Composable
private fun MockOverlayContent(overlay: MockOverlay, registry: TargetRegistry, type: MockType) {
    val scheme = MaterialTheme.colorScheme
    val scrim = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f))
    when (overlay) {
        is MockDialog -> Box(scrim, contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(12.dp),
            ) {
                Text(overlay.title, style = type.title)
                if (overlay.text != null) {
                    Spacer(Modifier.height(5.dp))
                    Text(overlay.text, style = type.small.copy(color = scheme.onSurfaceVariant))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val button = type.body.copy(color = scheme.primary, fontWeight = FontWeight.SemiBold)
                    if (overlay.dismiss != null) Text(overlay.dismiss, style = button, modifier = Modifier.padding(4.dp))
                    Text(overlay.confirm, style = button, modifier = Modifier.target(registry, overlay.key, Tap.CONFIRM).padding(4.dp))
                }
            }
        }

        is MockPermissionPrompt -> Box(scrim, contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(overlay.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(6.dp))
                Text(overlay.text, style = type.small, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                PromptButton("Allow", type, Modifier.target(registry, overlay.key, Tap.CONFIRM))
                Spacer(Modifier.height(4.dp))
                PromptButton("Don't allow", type)
            }
        }

        is MockBiometric -> Box(scrim, contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(scheme.surfaceContainerHigh)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(overlay.title, style = type.title)
                Spacer(Modifier.height(3.dp))
                Text(overlay.text, style = type.small.copy(color = scheme.onSurfaceVariant), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Icon(
                    Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.target(registry, overlay.key, Tap.FINGERPRINT).size(30.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("Use PIN", style = type.small.copy(color = scheme.primary))
            }
        }
    }
}

@Composable
private fun PromptButton(label: String, type: MockType, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = type.body.copy(fontWeight = FontWeight.Medium)) }
}

/** The numbered instructions next to the phone; the line the animation is on is highlighted. */
@Composable
fun TutorialSteps(state: TutorialState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.tutorial.steps.forEachIndexed { i, text ->
            val active = state.step == i
            val badge by animateColorAsState(if (active) scheme.primary else scheme.surfaceContainerHighest, label = "stepBadge")
            val badgeText by animateColorAsState(if (active) scheme.onPrimary else scheme.onSurfaceVariant, label = "stepBadgeText")
            val body by animateColorAsState(if (active) scheme.onSurface else scheme.onSurfaceVariant, label = "stepText")
            Row {
                Box(Modifier.size(24.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = badgeText)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    boldMarkup(text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = body,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** `**bold**` → bold spans. */
fun boldMarkup(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}
