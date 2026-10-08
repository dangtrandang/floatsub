package com.floatsub.probe.cloud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeminiBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "com.floatsub.probe.RUN_GEMINI_BENCHMARK") {
            val model = intent.getStringExtra("model") ?: "gemini-3.8-flash"
            val apiKey = intent.getStringExtra("api_key") ?: ""
            val runs = intent.getIntExtra("runs", 10)

            Log.i("GeminiBench", "Nhận được broadcast RUN_GEMINI_BENCHMARK! Model: $model | Runs: $runs")

            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runner = GeminiDirectBenchmarkRunner(appContext)
                    runner.runFullBenchmark(
                        apiKey = apiKey,
                        model = model,
                        runsPerDuration = runs
                    )
                } catch (e: Exception) {
                    Log.e("GeminiBench", "Lỗi thực thi benchmark: ${e.message}", e)
                }
            }
        }
    }
}
