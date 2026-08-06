package com.lightfastread.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lightfastread.ui.light.LightBarItem
import com.lightfastread.ui.light.LightBottomBar
import com.lightfastread.ui.light.LightRule
import com.lightfastread.ui.light.LightText
import com.lightfastread.ui.light.LightTextVariant
import com.lightfastread.ui.light.LightThemeTokens
import com.lightfastread.ui.light.LightTopBar
import com.lightfastread.ui.light.designVerticalPxToDp
import com.lightfastread.ui.light.lightInset

/**
 * Point the camera at a QR code.
 *
 * Exists for one job — filling in the four Calibre fields without typing them — but knows nothing
 * about what the code contains: it hands back the raw string and lets [com.lightfastread.calibre.CalibreQr]
 * decide whether it means anything. That keeps the part worth testing free of the camera.
 *
 * ML Kit's *bundled* barcode model, not the Play-services one: LightOS has no Play Services (see
 * LightNews and its OAuth), so the on-demand variant would sit there waiting for a download that
 * never comes. It costs a couple of megabytes in the APK and works on a phone with nothing else
 * installed, which is the whole point.
 *
 * The preview is a plain `PreviewView` through `AndroidView`. Compose has no camera surface, and the
 * two seconds this is on screen do not justify one.
 */
@Composable
fun QrScanner(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LightThemeTokens.colors
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var failure by remember { mutableStateOf<String?>(null) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        if (!allowed) failure = "The camera is needed to read a code."
    }

    LaunchedEffect(Unit) {
        if (!granted) askForCamera.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding(),
        ) {
            LightTopBar(title = "Scan")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (granted) {
                    CameraPreview(
                        onBarcode = onScanned,
                        onFailure = { failure = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LightText(
                        text = failure ?: "Waiting for the camera…",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = lightInset()),
                    )
                }
            }
            failure?.takeIf { granted }?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(
                        horizontal = lightInset(),
                        vertical = 6f.designVerticalPxToDp(),
                    ),
                )
            }
            LightText(
                text = "Hold the phone over the code on your computer.",
                variant = LightTextVariant.Superfine,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = lightInset(), vertical = 8f.designVerticalPxToDp()),
            )
            Spacer(Modifier.height(4f.designVerticalPxToDp()))
            LightRule()
            LightBottomBar(
                items = listOf(LightBarItem.Text(text = "CANCEL", lighten = true, onClick = onDismiss)),
            )
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    onBarcode: (String) -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    // One decode ends the screen. Without this the analyser keeps firing while the dialog closes and
    // the settings page gets the same code three or four times.
    var handled by remember { mutableStateOf(false) }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || handled) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val value = codes.firstOrNull {
                                it.format == Barcode.FORMAT_QR_CODE || it.rawValue != null
                            }?.rawValue
                            if (value != null && !handled) {
                                handled = true
                                onBarcode(value)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                onFailure(e.message ?: "The camera would not start.")
            }
        }, executor)

        onDispose {
            runCatching { provider?.unbindAll() }
            scanner.close()
        }
    }
}
