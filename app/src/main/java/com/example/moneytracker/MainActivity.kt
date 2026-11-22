package com.example.moneytracker

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.systemDefault()
private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val green = Color(0xFF2E7D32)
private val red = Color(0xFFC62828)

private fun money(v: Double) = "₹%,.2f".format(v)
private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(dateFmt)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showAddTxn by remember { mutableStateOf(false) }
    var showAddEmi by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Money Tracker") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("₹") }, label = { Text("Transactions") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("📅") }, label = { Text("EMI") })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (tab == 0) showAddTxn = true else showAddEmi = true }) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab == 0) TransactionsScreen(vm) else EmiScreen(vm)
        }
    }

    if (showAddTxn) AddTxnDialog(onDismiss = { showAddTxn = false }) { vm.add(it); showAddTxn = false }
    if (showAddEmi) AddEmiDialog(onDismiss = { showAddEmi = false }) { vm.addEmi(it); showAddEmi = false }
}

@Composable
fun TransactionsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val month by vm.month.collectAsState()
    val txns by vm.txns.collectAsState()
    val income = txns.filter { it.isCredit }.sumOf { it.amount }
    val expense = txns.filter { !it.isCredit }.sumOf { it.amount }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.READ_SMS] == true) {
            vm.importSms { n -> Toast.makeText(ctx, "Imported $n transactions from SMS", Toast.LENGTH_LONG).show() }
        } else Toast.makeText(ctx, "SMS permission denied", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.shiftMonth(-1) }) { Text("◀") }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.shiftMonth(1) }) { Text("▶") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Income"); Text(money(income), color = green, fontWeight = FontWeight.Bold) }
                    Column { Text("Expense"); Text(money(expense), color = red, fontWeight = FontWeight.Bold) }
                    Column { Text("Balance"); Text(money(income - expense), fontWeight = FontWeight.Bold) }
                }
            }
        }
        OutlinedButton(
            onClick = { launcher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) { Text("Read SMS & import credits/debits") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(txns, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.category, fontWeight = FontWeight.SemiBold)
                            if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text(fmt(t.date), style = MaterialTheme.typography.labelSmall)
                        }
                        Text((if (t.isCredit) "+ " else "- ") + money(t.amount),
                            color = if (t.isCredit) green else red, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { vm.delete(t) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

@Composable
fun EmiScreen(vm: MainViewModel) {
    val emis by vm.emis.collectAsState()
    val totalPending = emis.sumOf { (it.tenureMonths - it.paidMonths) * it.monthlyAmount }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Total pending EMI: ${money(totalPending)}", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp))
        if (emis.isEmpty()) Text("No EMIs yet. Tap + to add one.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emis, key = { it.id }) { e -> EmiCard(e, vm) }
        }
    }
}

@Composable
fun EmiCard(e: Emi, vm: MainViewModel) {
    val start = Instant.ofEpochMilli(e.startDate).atZone(zone).toLocalDate()
    val endDate = start.plusMonths((e.tenureMonths - 1).toLong())
    val nextDue = start.plusMonths(e.paidMonths.toLong())
    val paidAmt = e.paidMonths * e.monthlyAmount
    val pendingAmt = (e.tenureMonths - e.paidMonths) * e.monthlyAmount
    val done = e.paidMonths >= e.tenureMonths

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Monthly EMI: ${money(e.monthlyAmount)}")
            LinearProgressIndicator(progress = { e.paidMonths.toFloat() / e.tenureMonths },
                modifier = Modifier.fillMaxWidth())
            Text("Paid: ${e.paidMonths} of ${e.tenureMonths} months  •  ${e.tenureMonths - e.paidMonths} left")
            Text("Paid amount: ${money(paidAmt)}", color = green)
            Text("Pending amount: ${money(pendingAmt)}", color = red)
            Text("Ends on: ${endDate.format(dateFmt)}")
            Text(if (done) "Completed ✓" else "Next due: ${nextDue.format(dateFmt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.markPaid(e) }, enabled = !done) { Text("Mark month paid") }
                TextButton(onClick = { vm.deleteEmi(e) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun AddTxnDialog(onDismiss: () -> Unit, onSave: (Txn) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var credit by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !credit, onClick = { credit = false }); Text("Expense")
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = credit, onClick = { credit = true }); Text("Income")
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("Note") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.toDoubleOrNull() ?: return@TextButton
                onSave(Txn(amount = a, isCredit = credit,
                    category = category.ifBlank { if (credit) "Income" else "Expense" },
                    note = note, date = System.currentTimeMillis()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddEmiDialog(onDismiss: () -> Unit, onSave: (Emi) -> Unit) {
    var name by remember { mutableStateOf("") }
    var monthly by remember { mutableStateOf("") }
    var tenure by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("0") }
    var start by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add EMI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Car loan)") }, singleLine = true)
                OutlinedTextField(monthly, { monthly = it }, label = { Text("Monthly EMI amount") }, singleLine = true)
                OutlinedTextField(tenure, { tenure = it }, label = { Text("Total months") }, singleLine = true)
                OutlinedTextField(paid, { paid = it }, label = { Text("Months already paid") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("First EMI date (yyyy-MM-dd)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = monthly.toDoubleOrNull() ?: return@TextButton
                val t = tenure.toIntOrNull()?.takeIf { it > 0 } ?: return@TextButton
                val p = (paid.toIntOrNull() ?: 0).coerceIn(0, t)
                val d = try { LocalDate.parse(start) } catch (e: Exception) { return@TextButton }
                onSave(Emi(name = name.ifBlank { "EMI" }, monthlyAmount = m, tenureMonths = t,
                    startDate = d.atStartOfDay(zone).toInstant().toEpochMilli(), paidMonths = p))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
