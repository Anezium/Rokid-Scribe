package com.rokidscribe.glasses

import android.content.Context

object GlassesRuntime {
    @Volatile
    private var instance: Session? = null

    fun get(context: Context): Session {
        return instance ?: synchronized(this) {
            instance ?: Session(context.applicationContext).also { created ->
                created.repository.recoverInterruptedRecordings()
                instance = created
            }
        }
    }

    class Session(
        appContext: Context,
    ) {
        val repository = RecordingRepository(appContext)
        val recorder = GlassesRecorder(repository)
        val bluetoothServer = GlassesBluetoothServer(appContext, repository)
    }
}
