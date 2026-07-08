package com.example.aemet_tiempo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.aemet_tiempo.data.AemetClient
import com.example.aemet_tiempo.data.MunicipiosRepository
import com.example.aemet_tiempo.ui.App
import com.example.aemet_tiempo.ui.Prefs
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        // AEMET runs on an old Tomcat behind a BIG-IP load balancer that
        // misbehaves with modern TLS handshakes. The web version pins TLS 1.2
        // and disables HTTP/2 (no ALPN). OkHttp on Android is more lenient,
        // but we apply the same belt-and-braces config to stay defensive.
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectionSpecs(listOf(tlsSpec, ConnectionSpec.COMPATIBLE_TLS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                val aemet = remember { AemetClient(okHttp) }
                val prefs = remember { Prefs(applicationContext) }
                val municipiosRepo = remember { MunicipiosRepository(applicationContext, aemet) }
                App(aemet = aemet, prefs = prefs, municipiosRepo = municipiosRepo)
            }
        }
    }
}

// AEMET brand-aligned light scheme — matches the yellow accent + blue
// secondary used throughout the UI.
private val AppColorScheme = lightColorScheme(
    primary = Color(0xFFF2B400),
    onPrimary = Color(0xFF111111),
    secondary = Color(0xFF1E6CFF),
    onSecondary = Color.White,
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    error = Color(0xFFB00020),
)

