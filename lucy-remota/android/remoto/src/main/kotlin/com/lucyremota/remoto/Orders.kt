package com.lucyremota.remoto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Una orden enviada a la PC (para la pantalla): qué se dijo, qué se mandó y cómo terminó. */
data class SentOrder(
    val id: String,
    val at: Long,
    val heard: String,
    val intent: String,
    val status: String,
    val message: String,
)

class OrderLog {
    private val _orders = MutableStateFlow<List<SentOrder>>(emptyList())
    val orders: StateFlow<List<SentOrder>> = _orders.asStateFlow()

    @Synchronized
    fun upsert(o: SentOrder) {
        _orders.value = (listOf(o) + _orders.value.filter { it.id != o.id }).take(100)
    }
}
