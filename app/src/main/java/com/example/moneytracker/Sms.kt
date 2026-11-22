package com.example.moneytracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsParser {
    private val amountRx = Regex("""(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val debitRx = Regex("""\b(debited|spent|withdrawn|purchase|paid|sent)\b""", RegexOption.IGNORE_CASE)
    private val creditRx = Regex("""\b(credited|received|deposited|refund(?:ed)?)\b""", RegexOption.IGNORE_CASE)

    /** Returns a transaction if the SMS looks like a bank debit/credit alert, else null. */
    fun parse(sender: String, body: String, date: Long): Txn? {
        if (body.contains("otp", ignoreCase = true)) return null
        val amount = amountRx.find(body)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull() ?: return null
        val d = debitRx.find(body)?.range?.first
        val c = creditRx.find(body)?.range?.first
        if (d == null && c == null) return null
        // whichever keyword appears first decides (e.g. "debited ... credited to XYZ" = debit)
        val isCredit = c != null && (d == null || c < d)
        return Txn(
            amount = amount,
            isCredit = isCredit,
            category = "SMS",
            note = "$sender: ${body.take(80)}",
            date = date,
            smsKey = "$sender|$date|${body.hashCode()}"
        )
    }
}

object SmsImporter {
    /** Scans the inbox for the last [days] days. Returns number of new transactions added. */
    suspend fun importInbox(ctx: Context, dao: AppDao, days: Int = 90): Int {
        val since = System.currentTimeMillis() - days * 86_400_000L
        var added = 0
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val t = SmsParser.parse(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2))
                if (t != null && dao.insert(t) != -1L) added++
            }
        }
        return added
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = msgs.firstOrNull()?.originatingAddress ?: ""
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val t = SmsParser.parse(sender, body, System.currentTimeMillis()) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { AppDb.get(context).dao().insert(t) } finally { pending.finish() }
        }
    }
}
