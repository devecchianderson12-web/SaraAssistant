package com.lucyremota.nucleo.state

import com.lucyremota.nucleo.model.AssistantState
import com.lucyremota.nucleo.model.StateKind
import com.lucyremota.nucleo.model.kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tabla de transiciones válidas. Cualquier estado puede volver a INACTIVE (detener). */
object AssistantTransitions {
    private val allowed: Map<StateKind, Set<StateKind>> = mapOf(
        StateKind.INACTIVE to setOf(StateKind.WAITING, StateKind.LISTENING, StateKind.SPEAKING, StateKind.ERROR),
        StateKind.WAITING to setOf(StateKind.LISTENING, StateKind.SPEAKING, StateKind.ERROR),
        StateKind.LISTENING to setOf(StateKind.PROCESSING, StateKind.SPEAKING, StateKind.ERROR, StateKind.WAITING),
        StateKind.PROCESSING to setOf(StateKind.EXECUTING, StateKind.SPEAKING, StateKind.ERROR, StateKind.WAITING),
        StateKind.EXECUTING to setOf(StateKind.SPEAKING, StateKind.ERROR, StateKind.WAITING),
        StateKind.SPEAKING to setOf(StateKind.LISTENING, StateKind.WAITING, StateKind.ERROR),
        StateKind.ERROR to setOf(StateKind.WAITING, StateKind.LISTENING, StateKind.SPEAKING),
    )

    fun isAllowed(from: StateKind, to: StateKind): Boolean =
        from == to || to == StateKind.INACTIVE || allowed[from].orEmpty().contains(to)
}

class AssistantStateMachine(initial: AssistantState = AssistantState.Inactive) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Aplica la transición si es válida. Devuelve `false` (y no cambia nada) si no lo es. */
    @Synchronized
    fun moveTo(next: AssistantState): Boolean {
        val current = _state.value
        if (!AssistantTransitions.isAllowed(current.kind, next.kind)) return false
        _state.value = next
        return true
    }

    /** Fuerza un estado de reposo (usado al detener servicios o recuperar errores). */
    @Synchronized
    fun reset(to: AssistantState = AssistantState.Inactive) {
        _state.value = to
    }
}
