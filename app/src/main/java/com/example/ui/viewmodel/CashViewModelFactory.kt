package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.SecurityManager
import com.example.data.repository.CashRepository

class CashViewModelFactory(
    private val repository: CashRepository,
    private val securityManager: SecurityManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CashViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CashViewModel(repository, securityManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
