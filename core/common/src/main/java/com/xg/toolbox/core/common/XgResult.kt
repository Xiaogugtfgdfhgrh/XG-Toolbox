package com.xg.toolbox.core.common

sealed class XgResult<out T> {
    data class Ok<T>(val data: T) : XgResult<T>()

    data class Err(
        val code: Int,
        val message: String,
        val detail: String? = null,
    ) : XgResult<Nothing>()

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.data

    inline fun <R> map(transform: (T) -> R): XgResult<R> = when (this) {
        is Ok -> Ok(transform(data))
        is Err -> this
    }

    inline fun onOk(block: (T) -> Unit): XgResult<T> {
        if (this is Ok) block(data)
        return this
    }

    inline fun onErr(block: (Err) -> Unit): XgResult<T> {
        if (this is Err) block(this)
        return this
    }
}

inline fun <T> xgRunCatching(block: () -> T): XgResult<T> = try {
    XgResult.Ok(block())
} catch (t: Throwable) {
    XgResult.Err(ErrorCode.ERR_CMD_FAILED, ErrorCatalog.message(ErrorCode.ERR_CMD_FAILED), t.message)
}

fun <T> XgResult<T>.orElse(fallback: T): T = getOrNull() ?: fallback
