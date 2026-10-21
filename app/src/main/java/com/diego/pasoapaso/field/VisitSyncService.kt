package com.diego.pasoapaso.field

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

/** Android schedules retries with network availability; execution time is OS controlled. */
class VisitSyncService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.IO).launch {
            val again = try {
                val store = FieldStore(applicationContext)
                if (store.loggedIn) VisitOutbox(applicationContext, store).sync() else false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { true }
            withContext(Dispatchers.Main) { jobFinished(params, again) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
    companion object {
        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(1701, ComponentName(context, VisitSyncService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
