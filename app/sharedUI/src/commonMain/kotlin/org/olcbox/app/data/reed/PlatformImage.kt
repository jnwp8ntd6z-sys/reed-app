package org.olcbox.app.data.reed

import androidx.compose.ui.graphics.ImageBitmap

// Декодирование JPEG/PNG-байтов в ImageBitmap. Реализация платформенная:
// Android — BitmapFactory, iOS/Desktop/macOS — Skia.
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
