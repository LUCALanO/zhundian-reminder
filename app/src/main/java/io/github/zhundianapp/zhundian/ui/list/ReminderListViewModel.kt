package io.github.zhundianapp.zhundian.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhundianapp.zhundian.data.Reminder
import io.github.zhundianapp.zhundian.repository.ReminderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReminderListViewModel(
    private val repository: ReminderRepository
) : ViewModel() {

    val reminders: StateFlow<List<Reminder>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleEnabled(reminder: Reminder) {
        viewModelScope.launch { repository.setEnabled(reminder, !reminder.isEnabled) }
    }

    fun delete(reminder: Reminder) {
        viewModelScope.launch { repository.delete(reminder) }
    }
}
