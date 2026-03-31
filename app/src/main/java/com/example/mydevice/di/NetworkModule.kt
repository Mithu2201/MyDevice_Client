package com.example.mydevice.di

import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.util.Constants
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Koin module for networking.
 *
 * WHAT IT PROVIDES:
 * - Ktor HttpClient configured with OkHttp engine
 * - JSON serialization (lenient, ignores unknown keys)
 * - Bearer auth (reads token from EncryptedSharedPreferences)
 * - Logging in debug builds
 * - Timeouts matching the original app (2min call, 20s connect, 30s read/write)
 * - MyDevicesApi instance wrapping all 13 endpoints
 */
val networkModule = module {

    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    single {
        val securePrefs: SecurePreferences = get()

        HttpClient(OkHttp) {
            engine {
                config {
                    callTimeout(Constants.HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    connectTimeout(Constants.HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    readTimeout(Constants.HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    writeTimeout(Constants.HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }

            defaultRequest {
                url(Constants.BASE_URL)
            }

            install(ContentNegotiation) {
                json(get())
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        val token = securePrefs.accessToken
                        if (token != null) BearerTokens(token, "") else null
                    }
                }
            }

            install(Logging) {
                logger = Logger.SIMPLE
                level = LogLevel.HEADERS
            }

            install(HttpTimeout) {
                requestTimeoutMillis = Constants.HTTP_CALL_TIMEOUT_MS
                connectTimeoutMillis = Constants.HTTP_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = Constants.HTTP_READ_TIMEOUT_MS
            }

            HttpResponseValidator {
                validateResponse { response ->
                    val statusCode = response.status.value
                    if (statusCode >= 400) {
                        throw when (statusCode) {
                            401 -> ClientRequestException(response, "Unauthorized")
                            in 400..499 -> ClientRequestException(response, "Client error: $statusCode")
                            in 500..599 -> ServerResponseException(response, "Server error: $statusCode")
                            else -> ResponseException(response, "HTTP error: $statusCode")
                        }
                    }
                }
            }
        }
    }

    single { MyDevicesApi(get()) }
}
