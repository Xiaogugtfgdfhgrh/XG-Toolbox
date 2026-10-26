package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable

fun <T> LazyListScope.xgItems(
    data: List<T>,
    key: (T) -> Any,
    contentType: (T) -> Any? = { item -> item?.let { it::class } },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    items(
        count = data.size,
        key = { index -> key(data[index]) },
        contentType = { index -> contentType(data[index]) },
    ) { index ->
        itemContent(data[index])
    }
}
