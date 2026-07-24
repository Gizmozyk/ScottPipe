/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.journeyapps.barcodescanner.BarcodeEncoder

/** Renders [KidModePairingQrCode]-encoded text as a [Bitmap] for the kid-side pairing dialog. */
object KidModeQrCodeGenerator {
    fun generate(text: String, sizePx: Int): Bitmap? = try {
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    } catch (e: WriterException) {
        null
    }
}
