package com.anatej.c2s

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads QR codes and barcodes from a frozen frame, fully on-device. */
object BarcodeEngine {

    enum class Kind { WIFI, CONTACT, CALENDAR, SMS, GEO, URL, EMAIL, PHONE, TEXT }

    data class Result(
        val raw: String,
        val kind: Kind,
        val title: String,
        val details: String,
        val primary: String? = null,
        val secondary: String? = null,
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val location: String? = null,
    )

    private val scanner by lazy { BarcodeScanning.getClient() }

    suspend fun scan(bitmap: Bitmap): List<Result> = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { codes -> cont.resume(codes.mapNotNull(::map)) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private fun map(code: Barcode): Result? {
        val raw = code.rawValue?.takeIf { it.isNotBlank() } ?: return null
        return when (code.valueType) {
            Barcode.TYPE_WIFI -> {
                val wifi = code.wifi
                Result(
                    raw = raw, kind = Kind.WIFI,
                    title = wifi?.ssid ?: raw,
                    details = buildString {
                        append("SSID: ${wifi?.ssid.orEmpty()}")
                        wifi?.password?.takeIf { it.isNotBlank() }?.let { append("\nPassword: $it") }
                    },
                    primary = wifi?.ssid, secondary = wifi?.password,
                )
            }
            Barcode.TYPE_CONTACT_INFO -> {
                val contact = code.contactInfo
                val name = contact?.name?.formattedName ?: contact?.organization ?: raw
                val phone = contact?.phones?.firstOrNull()?.number
                val email = contact?.emails?.firstOrNull()?.address
                Result(
                    raw = raw, kind = Kind.CONTACT, title = name,
                    details = listOfNotNull(phone?.let { "Phone: $it" }, email?.let { "Email: $it" }).joinToString("\n"),
                    primary = phone, secondary = email,
                )
            }
            Barcode.TYPE_CALENDAR_EVENT -> {
                val event = code.calendarEvent
                Result(
                    raw = raw, kind = Kind.CALENDAR,
                    title = event?.summary ?: raw,
                    details = listOfNotNull(event?.location, event?.description).joinToString("\n"),
                    startMillis = event?.start?.let(::millis),
                    endMillis = event?.end?.let(::millis),
                    location = event?.location,
                )
            }
            Barcode.TYPE_SMS -> Result(
                raw = raw, kind = Kind.SMS,
                title = code.sms?.phoneNumber ?: raw,
                details = code.sms?.message.orEmpty(),
                primary = code.sms?.phoneNumber, secondary = code.sms?.message,
            )
            Barcode.TYPE_GEO -> Result(
                raw = raw, kind = Kind.GEO,
                title = "${code.geoPoint?.lat}, ${code.geoPoint?.lng}",
                details = raw,
                primary = code.geoPoint?.lat.toString(), secondary = code.geoPoint?.lng.toString(),
            )
            Barcode.TYPE_URL -> Result(
                raw = raw, kind = Kind.URL,
                title = code.url?.title?.takeIf { it.isNotBlank() } ?: (code.url?.url ?: raw),
                details = code.url?.url ?: raw,
                primary = code.url?.url ?: raw,
            )
            Barcode.TYPE_EMAIL -> Result(
                raw = raw, kind = Kind.EMAIL,
                title = code.email?.address ?: raw,
                details = listOfNotNull(code.email?.subject, code.email?.body).joinToString("\n"),
                primary = code.email?.address, secondary = code.email?.subject,
            )
            Barcode.TYPE_PHONE -> Result(
                raw = raw, kind = Kind.PHONE,
                title = code.phone?.number ?: raw,
                details = raw, primary = code.phone?.number,
            )
            else -> Result(raw = raw, kind = Kind.TEXT, title = code.displayValue ?: raw, details = raw)
        }
    }

    private fun millis(value: com.google.mlkit.vision.barcode.common.Barcode.CalendarDateTime): Long? {
        val raw = value.rawValue ?: return null
        return runCatching {
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(raw)?.time
        }.getOrNull()
    }
}
