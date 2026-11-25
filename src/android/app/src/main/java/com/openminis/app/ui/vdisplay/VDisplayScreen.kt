package com.openminis.app.ui.vdisplay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitch
import kotlin.math.roundToInt

/**
 * [T-vdisplay-ui] 后台屏 — the background virtual display page.
 *
 * Scope is deliberately small: start/stop, geometry, a 2 fps lifecycle-aware
 * preview with tap/swipe/BACK, app launch and text injection. The page never
 * creates a display itself; [VDisplayBackend] (the core) owns every display
 * handle, and all IPC/screenshot work runs on Dispatchers.IO inside the
 * ViewModel.
 *
 * Leaving the page stops the PREVIEW only. The display, and every app running
 * on it, keeps going — that is the point of the feature.
 */
@Composable
fun VDisplayScreen(
    onBack: () -> Unit,
    onOpenShizukuSettings: () -> Unit,
    backend: VDisplayBackend,
) {
    val vm: VDisplayViewModel = viewModel(factory = VDisplayViewModel.factory(backend))
    val state by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle-aware preview: refresh only while the page is started. ON_STOP
    // cancels the loop and drops the last frame; it never touches the display.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    vm.refreshFromCore()
                    if (vm.state.value.isRunning) vm.startPreview()
                }
                Lifecycle.Event.ON_STOP -> vm.stopPreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopPreview()
        }
    }

    // A display started from here should show its preview immediately.
    LaunchedEffect(state.status) {
        if (state.status == VDisplayStatus.RUNNING) vm.startPreview() else vm.stopPreview()
    }

    // Notices are confirmations, not state: clear them after a moment.
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(1800)
            vm.consumeNotice()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.vdisplay_title),
        onBack = onBack,
        actions = {
            MinisTextButton(onClick = { ShizukuManager.refresh(); vm.refreshFromCore() }) {
                Text(stringResource(R.string.vdisplay_refresh))
            }
        },
    ) {
        if (!state.privilegedReady) {
            PrivilegedMissingSection(onOpenShizukuSettings = onOpenShizukuSettings)
        }

        StatusSection(
            state = state,
            onStart = { vm.start() },
            onStop = { vm.stop() },
        )

        GeometrySection(state = state, vm = vm)

        if (state.isRunning) {
            PreviewSection(state = state, vm = vm)
            LaunchSection(state = state, vm = vm)
            TextSection(state = state, vm = vm)
        }

        state.error?.let { err -> ErrorSection(error = err, onDismiss = { vm.clearError() }) }

        state.notice?.let { notice ->
            Text(
                text = stringResource(noticeTextRes(notice)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Sections ──────────────────────────────────────────────────────────────────

@Composable
private fun PrivilegedMissingSection(onOpenShizukuSettings: () -> Unit) {
    SettingsSection(
        header = stringResource(R.string.vdisplay_privileged_header),
        footer = stringResource(R.string.vdisplay_privileged_footer),
    ) {
        SettingsRow(
            title = stringResource(R.string.vdisplay_privileged_row),
            subtitle = stringResource(R.string.vdisplay_privileged_row_subtitle),
            onClick = onOpenShizukuSettings,
            showDivider = false,
        )
    }
}

@Composable
private fun StatusSection(
    state: VDisplayUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.vdisplay_status_header),
        footer = stringResource(R.string.vdisplay_status_footer),
    ) {
        SettingsRow(
            title = stringResource(statusTitleRes(state.status)),
            subtitle = when {
                state.displayId != null ->
                    stringResource(R.string.vdisplay_status_display_id, state.displayId)
                state.appliedConfig != null ->
                    stringResource(
                        R.string.vdisplay_status_geometry,
                        state.appliedConfig.widthPx,
                        state.appliedConfig.heightPx,
                        state.appliedConfig.densityDpi,
                    )
                else -> stringResource(R.string.vdisplay_status_stopped_hint)
            },
            trailing = {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            showDivider = state.trusted == false,
        )

        // Focus is reported, never assumed: the preview surface (ImageReader)
        // says nothing about focus ownership, so the only honest source is what
        // the core says the display was created with.
        if (state.trusted == false) {
            SettingsRow(
                title = stringResource(R.string.vdisplay_focus_shared_title),
                subtitle = stringResource(R.string.vdisplay_focus_shared_subtitle),
                titleColor = MaterialTheme.colorScheme.tertiary,
                showDivider = false,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isRunning) {
                MinisButton(
                    onClick = onStop,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vdisplay_stop))
                }
            } else {
                MinisButton(
                    onClick = onStart,
                    enabled = !state.busy && state.privilegedReady && state.config.isValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vdisplay_start))
                }
            }
        }
    }
}

@Composable
private fun GeometrySection(
    state: VDisplayUiState,
    vm: VDisplayViewModel,
) {
    val cfg = state.config
    SettingsSection(
        header = stringResource(R.string.vdisplay_geometry_header),
        footer = stringResource(R.string.vdisplay_geometry_footer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DimensionField(
                label = stringResource(R.string.vdisplay_width),
                value = cfg.widthPx.toString(),
                onValueChange = vm::setWidth,
                modifier = Modifier.weight(1f),
            )
            DimensionField(
                label = stringResource(R.string.vdisplay_height),
                value = cfg.heightPx.toString(),
                onValueChange = vm::setHeight,
                modifier = Modifier.weight(1f),
            )
            DimensionField(
                label = stringResource(R.string.vdisplay_density),
                value = cfg.densityDpi.toString(),
                onValueChange = vm::setDensity,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(
                R.string.vdisplay_geometry_summary,
                cfg.widthPx,
                cfg.heightPx,
                String.format("%.2f", cfg.densityScale),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (!cfg.isValid) {
            Text(
                text = stringResource(
                    R.string.vdisplay_geometry_invalid,
                    VDisplayConfig.MIN_SIZE,
                    VDisplayConfig.MAX_SIZE,
                    VDisplayConfig.MIN_DPI,
                    VDisplayConfig.MAX_DPI,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisSmallButton(onClick = { vm.applyPreset(1080, 1920) }) { Text("1080×1920") }
            MinisSmallButton(onClick = { vm.applyPreset(720, 1280) }) { Text("720×1280") }
            MinisSmallButton(onClick = { vm.applyPreset(1600, 2560) }) { Text("1600×2560") }
        }

        // Geometry is baked into the surface at creation time, so applying a
        // change while running is always a restart — named as such.
        if (state.isRunning) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MinisOutlinedButton(
                    onClick = { vm.applyAndRestart() },
                    enabled = !state.busy && cfg.isValid && state.needsRestart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.vdisplay_apply_restart))
                }
            }
        }
    }
}

@Composable
private fun DimensionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun PreviewSection(
    state: VDisplayUiState,
    vm: VDisplayViewModel,
) {
    val applied = state.appliedConfig ?: state.config
    val aspect = applied.widthPx.toFloat() / applied.heightPx.toFloat().coerceAtLeast(1f)

    SettingsSection(
        header = stringResource(R.string.vdisplay_preview_header),
        footer = stringResource(R.string.vdisplay_preview_footer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .aspectRatio(aspect.coerceIn(0.4f, 2.4f)),
        ) {
            val frame = state.frame
            if (frame != null) {
                PreviewSurface(
                    frame = frame,
                    displayWidth = applied.widthPx,
                    displayHeight = applied.heightPx,
                    onTap = vm::tap,
                    onSwipe = { x1, y1, x2, y2 -> vm.swipe(x1, y1, x2, y2) },
                )
            } else {
                Text(
                    text = stringResource(
                        if (state.captureFailing) R.string.vdisplay_preview_waiting
                        else R.string.vdisplay_preview_idle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
        }

        // Keys act on the owned display only; the core rejects device-level
        // keys (power / volume / HOME / recents), so only navigation keys that
        // are genuinely per-display are offered here.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisOutlinedButton(onClick = { vm.back() }, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.vdisplay_key_back))
            }
            MinisOutlinedButton(onClick = { vm.enter() }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vdisplay_key_enter))
            }
        }

        // Preview rate, 1..8 fps. 2 fps is the default: a monitor, not a stream.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.vdisplay_preview_fps, state.previewFps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.previewFps.toFloat(),
                onValueChange = { vm.onPreviewFpsChanged(it.roundToInt()) },
                valueRange = VDisplayUiState.MIN_PREVIEW_FPS.toFloat()..VDisplayUiState.MAX_PREVIEW_FPS.toFloat(),
                steps = VDisplayUiState.MAX_PREVIEW_FPS - VDisplayUiState.MIN_PREVIEW_FPS - 1,
            )
        }
    }
}

/**
 * The preview bitmap plus gesture mapping.
 *
 * Bitmap pixels are displayed with [ContentScale.Fit] inside the box, so a
 * touch point has to be mapped back through the letterbox: local → bitmap pixel
 * → display pixel. Without that, taps near a letterbox edge would land
 * somewhere else on the virtual screen.
 *
 * The core returns the frame at the display BUFFER's physical size, which can
 * differ from the requested geometry after rotation, so the mapping is driven by
 * the frame's own width/height rather than by the configured size.
 */
@Composable
private fun PreviewSurface(
    frame: VDisplayFrame,
    displayWidth: Int,
    displayHeight: Int,
    onTap: (Int, Int) -> Unit,
    onSwipe: (Int, Int, Int, Int) -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val frameWidth = frame.width.coerceAtLeast(1)
    val frameHeight = frame.height.coerceAtLeast(1)

    fun map(offset: Offset): Pair<Int, Int>? = PreviewTouchMapper.map(
        localX = offset.x,
        localY = offset.y,
        boxWidth = boxSize.width,
        boxHeight = boxSize.height,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(frameWidth, frameHeight) {
                // One gesture pass decides tap vs swipe on release: the preview
                // is 2 fps, so streaming drag moves would only queue
                // meaningless intermediate swipes on the core.
                val swipeThresholdPx = 12.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = map(down.position)
                    var last = down.position
                    var moved = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val end = map(change.position) ?: map(last)
                            if (start != null && end != null) {
                                if (moved > swipeThresholdPx) {
                                    onSwipe(start.first, start.second, end.first, end.second)
                                } else {
                                    onTap(start.first, start.second)
                                }
                            }
                            change.consume()
                            break
                        }
                        moved += (change.position - last).getDistance()
                        last = change.position
                        change.consume()
                    }
                }
            },
    ) {
        Image(
            bitmap = frame.image,
            contentDescription = stringResource(R.string.vdisplay_preview_cd),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize(),
        )
        // The configured size is shown so the operator can see that the buffer
        // dimensions (used for input mapping) may differ after rotation.
        if (displayWidth != frameWidth || displayHeight != frameHeight) {
            Text(
                text = stringResource(R.string.vdisplay_preview_buffer, frameWidth, frameHeight),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun LaunchSection(
    state: VDisplayUiState,
    vm: VDisplayViewModel,
) {
    SettingsSection(
        header = stringResource(R.string.vdisplay_launch_header),
        footer = stringResource(R.string.vdisplay_launch_footer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = state.packageInput,
                onValueChange = vm::setPackageInput,
                label = { Text(stringResource(R.string.vdisplay_launch_field)) },
                placeholder = { Text("com.android.settings") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            MinisButton(
                onClick = { vm.launchPackage() },
                enabled = state.packageInput.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vdisplay_launch_button))
            }
        }
    }
}

@Composable
private fun TextSection(
    state: VDisplayUiState,
    vm: VDisplayViewModel,
) {
    SettingsSection(
        header = stringResource(R.string.vdisplay_text_header),
        footer = stringResource(R.string.vdisplay_text_footer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = state.textInput,
                onValueChange = vm::setTextInput,
                label = { Text(stringResource(R.string.vdisplay_text_field)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingsRow(
                title = stringResource(R.string.vdisplay_text_enter),
                showDivider = false,
                trailing = {
                    SettingsSwitch(
                        checked = state.pressEnterAfterText,
                        onCheckedChange = { vm.setPressEnterAfterText(it) },
                    )
                },
            )
            MinisButton(
                onClick = { vm.sendText() },
                enabled = state.textInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vdisplay_text_send))
            }
        }
    }
}

@Composable
private fun ErrorSection(error: VDisplayError, onDismiss: () -> Unit) {
    SettingsSection(header = stringResource(R.string.vdisplay_error_header)) {
        SettingsRow(
            title = stringResource(errorTitleRes(error.code)),
            subtitle = error.detail,
            titleColor = MaterialTheme.colorScheme.error,
            showDivider = false,
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vdisplay_error_dismiss))
            }
        }
    }
}

// ── Mappings ─────────────────────────────────────────────────────────────────

private fun statusTitleRes(status: VDisplayStatus): Int = when (status) {
    VDisplayStatus.STOPPED -> R.string.vdisplay_status_stopped
    VDisplayStatus.STARTING -> R.string.vdisplay_status_starting
    VDisplayStatus.RUNNING -> R.string.vdisplay_status_running
    VDisplayStatus.STOPPING -> R.string.vdisplay_status_stopping
}

private fun noticeTextRes(notice: VDisplayNotice): Int = when (notice) {
    VDisplayNotice.LAUNCH_SENT -> R.string.vdisplay_notice_launch
    VDisplayNotice.TEXT_SENT -> R.string.vdisplay_notice_text
    VDisplayNotice.CONFIG_APPLIED -> R.string.vdisplay_notice_config
}

private fun errorTitleRes(code: VDisplayErrorCode): Int = when (code) {
    VDisplayErrorCode.SHIZUKU_UNAVAILABLE -> R.string.vdisplay_err_shizuku
    VDisplayErrorCode.PERMISSION_DENIED -> R.string.vdisplay_err_permission
    VDisplayErrorCode.CREATE_FAILED -> R.string.vdisplay_err_create
    VDisplayErrorCode.NOT_RUNNING -> R.string.vdisplay_err_not_running
    VDisplayErrorCode.INVALID_CONFIG -> R.string.vdisplay_err_config
    VDisplayErrorCode.UNSUPPORTED -> R.string.vdisplay_err_unsupported
    VDisplayErrorCode.PACKAGE_NOT_FOUND -> R.string.vdisplay_err_package
    VDisplayErrorCode.MAIN_SCREEN_IN_USE -> R.string.vdisplay_err_main_in_use
    VDisplayErrorCode.LAUNCH_FAILED -> R.string.vdisplay_err_launch
    VDisplayErrorCode.INPUT_FAILED -> R.string.vdisplay_err_input
    VDisplayErrorCode.TEXT_REJECTED -> R.string.vdisplay_err_text
    VDisplayErrorCode.CAPTURE_FAILED -> R.string.vdisplay_err_capture
    VDisplayErrorCode.SERVICE_DIED -> R.string.vdisplay_err_service_died
    VDisplayErrorCode.UNKNOWN -> R.string.vdisplay_err_unknown
}
