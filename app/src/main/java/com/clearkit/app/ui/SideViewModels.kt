package com.clearkit.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clearkit.app.ClearKitApp
import com.clearkit.app.data.MedItem
import com.clearkit.app.data.ProofItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MedsViewModel(private val app: ClearKitApp) : ViewModel() {
    private val _items = MutableStateFlow<List<MedItem>>(emptyList())
    val items: StateFlow<List<MedItem>> = _items
    init { viewModelScope.launch { _items.value = app.medStore.load() } }
    fun add(name: String, detail: String) {
        viewModelScope.launch {
            _items.value = _items.value + MedItem(name = name, detail = detail)
            app.medStore.save(_items.value)
        }
    }
    fun toggle(id: String) {
        viewModelScope.launch {
            _items.value = _items.value.map { if (it.id == id) it.copy(done = !it.done) else it }
            app.medStore.save(_items.value)
        }
    }
    fun remove(id: String) {
        viewModelScope.launch {
            _items.value = _items.value.filterNot { it.id == id }
            app.medStore.save(_items.value)
        }
    }
}

class ProofsViewModel(private val app: ClearKitApp) : ViewModel() {
    private val _items = MutableStateFlow<List<ProofItem>>(emptyList())
    val items: StateFlow<List<ProofItem>> = _items
    init { viewModelScope.launch { _items.value = app.proofStore.load() } }
    fun add(uri: String, note: String) {
        viewModelScope.launch {
            _items.value = listOf(ProofItem(uri = uri, note = note)) + _items.value
            app.proofStore.save(_items.value)
        }
    }
    fun remove(id: String) {
        viewModelScope.launch {
            _items.value = _items.value.filterNot { it.id == id }
            app.proofStore.save(_items.value)
        }
    }
}
