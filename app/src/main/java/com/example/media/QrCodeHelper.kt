package com.example.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeHelper {

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()

    fun decodeQrFromImageProxy(image: ImageProxy): String? {
        if (image.format != ImageFormat.YUV_420_888 &&
            image.format != ImageFormat.YUV_422_888 &&
            image.format != ImageFormat.YUV_444_888
        ) {
            return null
        }
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        return runCatching {
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(bitmap).text
        }.getOrNull()
    }
}
