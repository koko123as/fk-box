package io.nekohasekai.sfa.compose.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import io.nekohasekai.sfa.compose.theme.AppShape

/**
 * qr_code.dart's QrCodeView: dark modules on white whatever the theme, with
 * the four-module quiet zone, on a 16 dp superellipse; text too long for a
 * code is shown as selectable text instead.
 */
private const val QUIET_ZONE = 4

private fun encode(data: String): ByteMatrix? = try {
    Encoder.encode(data, ErrorCorrectionLevel.L).matrix
} catch (_: WriterException) {
    null
}

fun qrFits(data: String): Boolean = encode(data) != null

@Composable
fun QrCodeView(data: String, size: Dp = 240.dp) {
    val matrix = remember(data) { encode(data) }
    if (matrix == null) {
        SelectionContainer { Text(data, textAlign = TextAlign.Center) }
        return
    }
    Canvas(
        Modifier
            .widthIn(max = size)
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White, AppShape.md)
            .semantics { contentDescription = data },
    ) {
        val count = matrix.width
        val module = this.size.minDimension / (count + QUIET_ZONE * 2)
        val origin = module * QUIET_ZONE
        val path = Path()
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (matrix.get(x, y).toInt() == 1) {
                    path.addRect(androidx.compose.ui.geometry.Rect(Offset(origin + x * module, origin + y * module), Size(module, module)))
                }
            }
        }
        drawPath(path, Color.Black)
    }
}
