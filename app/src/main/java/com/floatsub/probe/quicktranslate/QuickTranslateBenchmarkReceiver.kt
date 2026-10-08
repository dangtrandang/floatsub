package com.floatsub.probe.quicktranslate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickTranslateBenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "com.floatsub.probe.RUN_QUICK_BENCHMARK") {
            val runs = intent.getIntExtra("runs", 10)
            Log.i("QuickBench", "Nhận được broadcast RUN_QUICK_BENCHMARK! Runs: $runs")

            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val runner = QuickTranslateBenchmarkRunner(appContext)
                    runner.runFullExperiment(runsPerConfig = runs)
                } catch (e: Exception) {
                    Log.e("QuickBench", "Lỗi thực thi benchmark: ${e.message}", e)
                }
            }
        }
    }
}
