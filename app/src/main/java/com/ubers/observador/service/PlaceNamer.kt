package com.ubers.observador.service

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.ubers.observador.domain.route.E7
import com.ubers.observador.domain.route.PlaceNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Fase 8K, decision 10: a name for a GPS point («Calle 5, Los Ríos»), from Android's map service
 * (it needs internet). Null when there is no service, no internet or no answer: the screens then
 * show Uber's addresses, and the name is asked again later.
 */
class PlaceNamer(context: Context) {

    private val app = context.applicationContext

    suspend fun nameOf(latE7: Int, lngE7: Int): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(app, Locale.forLanguageTag("es-DO"))
        val lat = E7.degrees(latE7)
        val lng = E7.degrees(lngE7)
        val found: List<Address>? = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= 33) {
                    suspendCancellableCoroutine<List<Address>?> { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses)
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null // no internet or the service did not answer
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed", e)
            null
        }
        val a = found?.firstOrNull() ?: return@withContext null
        PlaceNames.of(a.thoroughfare, a.subThoroughfare, a.subLocality, a.locality, a.featureName)
    }

    private companion object {
        const val TAG = "PlaceNamer"
        const val TIMEOUT_MS = 15_000L
    }
}
