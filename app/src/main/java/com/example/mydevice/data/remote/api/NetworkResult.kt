package com.example.mydevice.data.remote.api

/**
 * Sealed class wrapping every network call result.
 * ViewModels observe this to drive UI state (loading spinner, error banner, data display).
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
    data object NoInternet : NetworkResult<Nothing>()
}

/**
 * Safe wrapper that catches exceptions from any suspend API call
 * and converts them into [NetworkResult].
 */
suspend fun <T> safeApiCall(block: suspend () -> T): NetworkResult<T> {
    return try {
        NetworkResult.Success(block())
    } catch (e: io.ktor.client.plugins.ClientRequestException) {
        val code = e.response.status.value
        val msg = when (code) {
            401 -> "Unauthorized – please login again"
            403 -> "Access denied"
            404 -> "Resource not found"
            else -> e.message
        }
        NetworkResult.Error(msg, code)
    } catch (e: io.ktor.client.plugins.ServerResponseException) {
        NetworkResult.Error("Server error: ${e.response.status.value}", e.response.status.value)
    } catch (e: java.net.UnknownHostException) {
        NetworkResult.NoInternet
    } catch (e: java.net.ConnectException) {
        NetworkResult.NoInternet
    } catch (e: Exception) {
        NetworkResult.Error(e.message ?: "Unknown error")
    }
}
