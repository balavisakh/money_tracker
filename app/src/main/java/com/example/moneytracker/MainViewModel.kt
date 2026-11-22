package com.example.moneytracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val zone = ZoneId.systemDefault()

    val month = MutableStateFlow(YearMonth.now())

    val txns = month.flatMapLatest { m ->
        val from = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        dao.txns(from, to)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val emis = dao.emis().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun shiftMonth(d: Long) { month.value = month.value.plusMonths(d) }

    fun add(t: Txn) = viewModelScope.launch { dao.insert(t) }
    fun delete(t: Txn) = viewModelScope.launch { dao.delete(t) }

    fun addEmi(e: Emi) = viewModelScope.launch { dao.insertEmi(e) }
    fun deleteEmi(e: Emi) = viewModelScope.launch { dao.deleteEmi(e) }

    /** Marks next EMI instalment as paid and records it as an expense. */
    fun markPaid(e: Emi) = viewModelScope.launch {
        if (e.paidMonths >= e.tenureMonths) return@launch
        dao.updateEmi(e.copy(paidMonths = e.paidMonths + 1))
        dao.insert(
            Txn(
                amount = e.monthlyAmount, isCredit = false, category = "EMI",
                note = "${e.name} EMI ${e.paidMonths + 1}/${e.tenureMonths}",
                date = System.currentTimeMillis()
            )
        )
    }

    fun importSms(onDone: (Int) -> Unit) = viewModelScope.launch {
        val n = withContext(Dispatchers.IO) { SmsImporter.importInbox(getApplication(), dao) }
        onDone(n)
    }
}
