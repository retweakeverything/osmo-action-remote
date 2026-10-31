package com.retweakeverything.osmoremote.data

import android.content.Context
import com.retweakeverything.osmoremote.core.CameraDevice
import com.retweakeverything.osmoremote.core.PairingStore
import com.retweakeverything.osmoremote.core.workflow.Workflow
import com.retweakeverything.osmoremote.core.workflow.WorkflowJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    override fun isPaired(address: String): Boolean = address in paired()

    override fun markPaired(device: CameraDevice) {
        prefs.edit()
            .putStringSet(KEY_PAIRED, paired() + device.address)
            .putString(KEY_LAST_ADDR, device.address)
            .putString(KEY_LAST_NAME, device.name)
            .apply()
    }

    override fun forget(address: String) {
        val editor = prefs.edit().putStringSet(KEY_PAIRED, paired() - address)
        if (prefs.getString(KEY_LAST_ADDR, null) == address) editor.remove(KEY_LAST_ADDR).remove(KEY_LAST_NAME)
        editor.apply()
    }

    override fun lastDevice(): CameraDevice? {
        val addr = prefs.getString(KEY_LAST_ADDR, null) ?: return null
        return CameraDevice(addr, prefs.getString(KEY_LAST_NAME, null) ?: addr)
    }

    private fun paired(): Set<String> = prefs.getStringSet(KEY_PAIRED, emptySet()).orEmpty()

    private companion object {
        const val KEY_PAIRED = "paired"
        const val KEY_LAST_ADDR = "last_addr"
        const val KEY_LAST_NAME = "last_name"
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(v) = prefs.edit().putBoolean("auto_reconnect", v).apply()

    /** Id of the selected [com.retweakeverything.osmoremote.core.CameraRoute]. */
    var routeId: String
        get() = prefs.getString("route", "phone") ?: "phone"
        set(v) = prefs.edit().putString("route", v).apply()

    var keepAliveInBackground: Boolean
        get() = prefs.getBoolean("keep_alive", true)
        set(v) = prefs.edit().putBoolean("keep_alive", v).apply()
}

/** User workflows persisted as one JSON file. Templates live in code, not here. */
class WorkflowRepository(context: Context, private val io: CoroutineDispatcher) {
    private val file = File(context.filesDir, "workflows.json")
    private val _workflows = MutableStateFlow<List<Workflow>>(emptyList())
    val workflows: StateFlow<List<Workflow>> = _workflows

    suspend fun load() = withContext(io) {
        _workflows.value = if (file.exists()) runCatching { WorkflowJson.decodeAll(file.readText()) }.getOrDefault(emptyList())
        else emptyList()
    }

    suspend fun save(workflow: Workflow) = update { list ->
        val i = list.indexOfFirst { it.id == workflow.id }
        if (i >= 0) list.toMutableList().also { it[i] = workflow } else list + workflow
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(change: (List<Workflow>) -> List<Workflow>) = withContext(io) {
        val next = change(_workflows.value)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(WorkflowJson.encodeAll(next))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _workflows.value = next
    }
}
