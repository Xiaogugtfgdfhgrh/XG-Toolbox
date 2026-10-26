package com.xg.toolbox.root

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.R
import com.xg.toolbox.core.common.XgTab
import com.xg.toolbox.core.data.AppearancePreferences
import com.xg.toolbox.core.data.BackgroundImageStore
import com.xg.toolbox.core.shell.DenyReason
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.LocalXgWindowOverlayHost
import com.xg.toolbox.core.ui.component.XgBottomBar
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgGlassDialog
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgToastHost
import com.xg.toolbox.core.ui.component.XgToastState
import com.xg.toolbox.core.ui.component.XgWindowOverlayHost
import com.xg.toolbox.core.ui.component.XgWindowOverlayHostState
import com.xg.toolbox.core.ui.liquid.LocalXgBackdrop
import com.xg.toolbox.core.ui.liquid.LocalXgBackdropVisible
import com.xg.toolbox.core.ui.liquid.LocalXgLiquidGlassEnabled
import com.xg.toolbox.core.ui.liquid.isXgGlassSupported
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgMotionDuration
import com.xg.toolbox.nav.XgNavHost
import com.xg.toolbox.nav.XgNavMemory
import com.xg.toolbox.nav.XgTabBackHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

data class XgAppearanceUiState(
    val customBackgroundEnabled: Boolean = false,
    val customBitmap: ImageBitmap? = null,
    val builtInPortrait: ImageBitmap? = null,
    val builtInLandscape: ImageBitmap? = null,
)

private data class BuiltInBackgrounds(
    val portrait: ImageBitmap? = null,
    val landscape: ImageBitmap? = null,
)

private data class DecodedCustomBackground(
    val file: File? = null,
    val bitmap: ImageBitmap? = null,
)

@HiltViewModel
class XgAppearanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    preferences: AppearancePreferences,
    private val backgroundImageStore: BackgroundImageStore,
) : ViewModel() {
    private val builtIns = MutableStateFlow(BuiltInBackgrounds())
    private val decodedCustom = MutableStateFlow(DecodedCustomBackground())

    var lastActiveBackground: ImageBitmap? = null
        private set

    init {
        viewModelScope.launch {
            val portrait = async(Dispatchers.IO) {
                decodeResource(R.drawable.xg_builtin_portrait_background)
            }
            val landscape = async(Dispatchers.IO) {
                decodeResource(R.drawable.xg_builtin_landscape_background)
            }
            builtIns.value = BuiltInBackgrounds(
                portrait = portrait.await(),
                landscape = landscape.await(),
            )
        }
        viewModelScope.launch {
            combine(
                preferences.customBackgroundEnabled,
                preferences.backgroundFileName,
            ) { enabled, fileName ->
                fileName.takeIf { enabled }
            }.distinctUntilChanged().collectLatest { fileName ->
                if (fileName == null) {
                    decodedCustom.value = DecodedCustomBackground()
                    return@collectLatest
                }
                val file = withContext(Dispatchers.IO) { backgroundImageStore.fileFor(fileName) }
                if (file == null) {
                    decodedCustom.value = DecodedCustomBackground()
                    return@collectLatest
                }
                val cached = decodedCustom.value
                if (cached.file == file && cached.bitmap != null) return@collectLatest
                decodedCustom.value = DecodedCustomBackground(file = file, bitmap = cached.bitmap)
                val bitmap = withContext(Dispatchers.IO) { decodeFile(file) }
                decodedCustom.value = DecodedCustomBackground(file = file, bitmap = bitmap)
            }
        }
    }

    val uiState: StateFlow<XgAppearanceUiState> = combine(
        preferences.customBackgroundEnabled,
        builtIns,
        decodedCustom,
    ) { enabled, builtIn, custom ->
        XgAppearanceUiState(
            customBackgroundEnabled = enabled,
            customBitmap = custom.bitmap.takeIf { enabled },
            builtInPortrait = builtIn.portrait,
            builtInLandscape = builtIn.landscape,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = XgAppearanceUiState(),
    )

    fun commitActiveBackground(bitmap: ImageBitmap?) {
        lastActiveBackground = bitmap
    }

    override fun onCleared() {
        builtIns.value = BuiltInBackgrounds()
        decodedCustom.value = DecodedCustomBackground()
        lastActiveBackground = null
        super.onCleared()
    }

    private fun decodeResource(resId: Int): ImageBitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeResource(context.resources, resId, options)?.asImageBitmap()
    }.getOrNull()

    private fun decodeFile(file: File): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > CUSTOM_BACKGROUND_MAX_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull()
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun XgRootScaffold(
    viewModel: RootGateViewModel = hiltViewModel(),
    shellViewModel: XgShellViewModel = hiltViewModel(),
    appearanceViewModel: XgAppearanceViewModel = hiltViewModel(),
) {
    val colors = LocalXgColors.current
    val appearance by appearanceViewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activeBackground = appearance.customBitmap ?: if (isLandscape) {
        appearance.builtInLandscape ?: appearance.builtInPortrait
    } else {
        appearance.builtInPortrait ?: appearance.builtInLandscape
    }
    val motionScale = LocalXgMotionScale.current
    val initialPrevious = remember {
        appearanceViewModel.lastActiveBackground.takeIf {
            activeBackground != null && it !== activeBackground
        }
    }
    var currentBackground by remember {
        mutableStateOf(activeBackground ?: appearanceViewModel.lastActiveBackground)
    }
    var previousBackground by remember { mutableStateOf(initialPrevious) }
    val backgroundProgress = remember {
        Animatable(if (initialPrevious == null) 1f else 0f)
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val splashIcon by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.xg_app_icon,
                    options,
                )?.asImageBitmap()
            }.getOrNull()
        }
    }
    var splashPlayed by rememberSaveable { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(!splashPlayed) }
    val contentAlpha = remember { Animatable(if (splashPlayed) 1f else 0f) }
    val contentScale = remember { Animatable(if (splashPlayed) 1f else 1.015f) }
    val contentShift = remember {
        Animatable(if (splashPlayed) 0f else with(density) { 16.dp.toPx() })
    }
    val splashAlpha = remember { Animatable(1f) }
    val splashScale = remember { Animatable(1f) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val restoredTab by shellViewModel.restoredTab.collectAsStateWithLifecycle()
    val toastState = remember { XgToastState() }
    var restoreFallback by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        restoreFallback = true
    }
    var appliedTab by rememberSaveable { mutableStateOf<XgTab?>(null) }
    val activeTab = appliedTab ?: restoredTab ?: if (restoreFallback) XgNavMemory.lastTab else null
    val selectTab: (XgTab) -> Unit = { tab ->
        if (tab != activeTab) {
            appliedTab = tab
            shellViewModel.onTabSelected(tab)
        }
    }
    LaunchedEffect(activeTab) {
        activeTab?.let { XgNavMemory.lastTab = it }
    }
    XgTabBackHandler(currentTab = activeTab) { selectTab(XgTab.HOME) }

    LaunchedEffect(activeBackground) {
        if (activeBackground == null || activeBackground === currentBackground) {
            return@LaunchedEffect
        }
        previousBackground = currentBackground
        currentBackground = activeBackground
        backgroundProgress.snapTo(0f)
        if (motionScale <= 0f) {
            backgroundProgress.snapTo(1f)
        } else {
            backgroundProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = xgMotionDuration(BACKGROUND_CROSSFADE_MS, motionScale),
                    easing = XgEaseOutCubic,
                ),
            )
        }
        previousBackground = null
        appearanceViewModel.commitActiveBackground(currentBackground)
    }
    LaunchedEffect(Unit) {
        if (previousBackground != null) {
            if (motionScale <= 0f) {
                backgroundProgress.snapTo(1f)
            } else {
                backgroundProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = xgMotionDuration(BACKGROUND_CROSSFADE_MS, motionScale),
                        easing = XgEaseOutCubic,
                    ),
                )
            }
            previousBackground = null
        }
        appearanceViewModel.commitActiveBackground(currentBackground)
    }

    LaunchedEffect(Unit) {
        if (splashPlayed) {
            contentAlpha.snapTo(1f)
            contentScale.snapTo(1f)
            contentShift.snapTo(0f)
            splashAlpha.snapTo(0f)
            showSplash = false
            return@LaunchedEffect
        }
        splashPlayed = true
        if (motionScale <= 0f) {
            contentAlpha.snapTo(1f)
            contentScale.snapTo(1f)
            contentShift.snapTo(0f)
            splashAlpha.snapTo(0f)
            showSplash = false
            return@LaunchedEffect
        }
        repeat(SPLASH_ICON_WAIT_FRAMES) {
            if (splashIcon == null) delay(16)
        }
        delay(SPLASH_HOLD_MS)
        val exitSpec = tween<Float>(
            durationMillis = xgMotionDuration(SPLASH_EXIT_MS, motionScale),
            easing = XgEaseOutCubic,
        )
        val enterSpec = tween<Float>(
            durationMillis = xgMotionDuration(CONTENT_ENTER_MS, motionScale),
            easing = XgEaseOutCubic,
        )
        coroutineScope {
            launch { splashAlpha.animateTo(0f, exitSpec) }
            launch { splashScale.animateTo(SPLASH_AVATAR_SCALE, exitSpec) }
            launch { contentAlpha.animateTo(1f, enterSpec) }
            launch { contentScale.animateTo(1f, enterSpec) }
            launch { contentShift.animateTo(0f, enterSpec) }
        }
        showSplash = false
    }

    val glassSupported = isXgGlassSupported()
    val contentBackdrop = rememberLayerBackdrop()
    val dialogBackdrop = rememberLayerBackdrop()
    val backgroundVisible = currentBackground != null || previousBackground != null
    val overlayHost = remember { XgWindowOverlayHostState() }
    val overlayActive = overlayHost.hasOverlay || uiState.showGate

    CompositionLocalProvider(
        LocalXgToast provides toastState,
        LocalXgLiquidGlassEnabled provides true,
        LocalXgBackdropVisible provides true,
        LocalXgBackdrop provides contentBackdrop,
        LocalXgWindowOverlayHost provides overlayHost,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        scaleX = contentScale.value
                        scaleY = contentScale.value
                        translationY = contentShift.value
                    }
                    .then(
                        if (overlayActive || showSplash) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (overlayActive && glassSupported) {
                                Modifier.layerBackdrop(dialogBackdrop)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WallpaperLayer(
                            current = currentBackground,
                            previous = previousBackground,
                            progress = { backgroundProgress.value },
                            modifier = Modifier.matchParentSize(),
                        )
                        if (backgroundVisible) {
                            BackgroundScrim()
                        }
                        Scaffold(
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        ) { _ ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (activeTab != null) {
                                    XgNavHost(
                                        currentTab = activeTab,
                                        onSelectTab = selectTab,
                                        modifier = Modifier.fillMaxSize(),
                                        startTab = activeTab,
                                    )
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        XgBottomBar(
                            selected = activeTab ?: XgTab.HOME,
                            onSelect = selectTab,
                            backgroundImage = activeBackground,
                        )
                    }
                    XgToastHost(state = toastState)
                }
            }
            XgWindowOverlayHost(state = overlayHost, backdrop = dialogBackdrop)
            if (uiState.showGate) {
                RootGateDialog(
                    uiState = uiState,
                    onRetry = viewModel::retryProbe,
                    onDismiss = viewModel::dismissGate,
                    backdrop = dialogBackdrop,
                )
            }
            if (showSplash) {
                SplashOverlay(
                    icon = splashIcon,
                    alpha = { splashAlpha.value },
                    scale = { splashScale.value },
                )
            }
        }
    }
}

@Composable
private fun SplashOverlay(
    icon: ImageBitmap?,
    alpha: () -> Float,
    scale: () -> Float,
) {
    val colors = LocalXgColors.current
    val shape = remember { RoundedCornerShape(30.dp) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .graphicsLayer { this.alpha = alpha() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(128.dp)
                    .graphicsLayer {
                        scaleX = scale()
                        scaleY = scale()
                    }
                    .shadow(18.dp, shape, clip = false)
                    .clip(shape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun WallpaperLayer(
    current: ImageBitmap?,
    previous: ImageBitmap?,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (previous != null && previous !== current) {
            Image(
                bitmap = previous,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = 1f - progress() },
            )
        }
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = progress() },
            )
        }
    }
}

@Composable
private fun BackgroundScrim() {
    val colors = LocalXgColors.current
    val topAlpha = if (colors.isDark) 0.62f else 0.56f
    val middleAlpha = if (colors.isDark) 0.58f else 0.50f
    val bottomAlpha = if (colors.isDark) 0.64f else 0.56f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.bg.copy(alpha = topAlpha),
                        colors.bg.copy(alpha = middleAlpha),
                        colors.bg.copy(alpha = bottomAlpha),
                    ),
                ),
            ),
    )
}

private const val BACKGROUND_CROSSFADE_MS = 300
private const val CUSTOM_BACKGROUND_MAX_PX = 1920
private const val SPLASH_ICON_WAIT_FRAMES = 10
private const val SPLASH_HOLD_MS = 450L
private const val SPLASH_EXIT_MS = 480
private const val CONTENT_ENTER_MS = 520
private const val SPLASH_AVATAR_SCALE = 1.08f

@Composable
private fun RootGateDialog(
    uiState: RootGateUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = LocalXgColors.current
    XgGlassDialog(
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.shell_root_gate_title),
        shape = RoundedCornerShape(20.dp),
        maxWidth = 342.dp,
        contentModifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "SU · 0001",
                style = XgType.label.copy(fontSize = 8.5.sp, letterSpacing = 1.sp),
                color = colors.faint,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.shell_root_gate_title),
            style = XgType.pageTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
            color = colors.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.shell_root_gate_desc),
            style = XgType.body,
            color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = rootGateStatusText(uiState),
            style = XgType.note,
            color = colors.faint,
        )
        Spacer(Modifier.height(12.dp))
        XgHairline()
        Spacer(Modifier.height(10.dp))
        GateScopeRow(text = stringResource(R.string.shell_root_gate_scope_1))
        GateScopeRow(text = stringResource(R.string.shell_root_gate_scope_2))
        GateScopeRow(text = stringResource(R.string.shell_root_gate_scope_3))
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            XgGhostButton(
                text = stringResource(R.string.shell_root_gate_secondary),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            XgPrimaryButton(
                text = if (uiState.probing) {
                    stringResource(R.string.shell_probing)
                } else {
                    stringResource(R.string.shell_root_gate_primary)
                },
                onClick = onRetry,
                enabled = !uiState.probing,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.shell_root_gate_note),
            style = XgType.note,
            color = colors.faint,
        )
    }
}

@Composable
private fun GateScopeRow(text: String) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = XgType.body.copy(fontSize = 10.5.sp, lineHeight = 16.sp),
            color = colors.text,
        )
    }
}

@Composable
private fun rootGateStatusText(uiState: RootGateUiState): String {
    if (uiState.probing) return stringResource(R.string.shell_probing)
    val info = uiState.rootInfo
    val denied = uiState.rootState as? RootState.Denied
    if (denied != null) {
        val reason = when (denied.reason) {
            DenyReason.NO_BINARY -> stringResource(R.string.shell_gate_missing_su)
            DenyReason.USER_REJECTED -> stringResource(R.string.shell_gate_user_rejected)
            DenyReason.TIMEOUT -> stringResource(R.string.shell_gate_timeout)
            DenyReason.POLICY_BLOCKED -> stringResource(R.string.shell_gate_policy)
            DenyReason.SELINUX_BLOCKED -> stringResource(R.string.shell_gate_selinux)
        }
        if (info != null) {
            val scheme = stringResource(R.string.shell_gate_scheme_hint, info.scheme.name, info.version)
            return stringResource(R.string.shell_gate_status_combined, reason, scheme)
        }
        return reason
    }
    if (info != null) {
        return stringResource(R.string.shell_gate_scheme, info.scheme.name, info.version)
    }
    return stringResource(R.string.shell_gate_missing_su)
}
