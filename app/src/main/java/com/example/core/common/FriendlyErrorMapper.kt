package com.example.core.common

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object FriendlyErrorMapper {

    fun map(error: Throwable?, fallback: String): String {
        if (error == null) return fallback
        val msg = error.localizedMessage?.trim().orEmpty()
        return when {
            error is UnknownHostException ||
                error is ConnectException ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("No address associated with hostname", ignoreCase = true) ||
                msg.contains("Offline:", ignoreCase = true) ->
                "Нет подключения к интернету. Проверьте сеть и повторите попытку."

            error is SocketTimeoutException ||
                error is InterruptedIOException ||
                msg.contains("timeout", ignoreCase = true) ->
                "Сервер долго не отвечает. Проверьте интернет-соединение и попробуйте ещё раз."

            error is SSLException ->
                "Ошибка защищённого соединения (SSL). Проверьте дату/время на устройстве и сеть."

            msg.contains("HTTP 403", ignoreCase = true) ||
                msg.contains("403", ignoreCase = true) && msg.contains("HTTP", ignoreCase = true) ->
                "Доступ к потоку временно ограничен. Попробуйте повторить через пару секунд."

            msg.contains("HTTP 429", ignoreCase = true) ->
                "Слишком много запросов. Подождите немного и повторите попытку."

            msg.contains("[а-яА-ЯёЁ]".toRegex()) -> msg
            msg.isNotBlank() && !msg.startsWith("java.") && !msg.startsWith("okhttp") -> msg
            else -> fallback
        }
    }
}
