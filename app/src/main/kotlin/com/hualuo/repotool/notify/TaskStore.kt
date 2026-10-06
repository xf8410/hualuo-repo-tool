package com.hualuo.repotool.notify

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务存取件（tasks 页实装刀）：
 *  - 单文件 JSON：files/tasks.json，全表 @Synchronized（Worker 线程与设置页同时动不打架）；
 *  - 一条任务 = 名称 + 间隔分钟 + 提示词 + 启用位 + 上次执行时间戳 + 最近执行记录；
 *  - 零脱敏纪律：提示词原文进出，不做任何过滤、截断与替换。
 *
 * 执行语义（与 CiNotifyWorker 同一节拍）：TaskWorker 每 15 分钟 tick 一次，
 * 到点判定 = now - lastRunAt >= intervalMin 分钟；执行 = 开新会话 + 发提示词，
 * 现场留在会话库本身（列表里点开就是完整对话），这里只记一行执行账。
 */
class TaskStore(private val file: File) {

    /** 一条定时任务。 */
    data class Task(
        val id: String,
        val name: String,
        val intervalMin: Long,
        val prompt: String,
        val enabled: Boolean,
        val lastRunAt: Long,
        val lastResult: String,
    )

    /** 一行执行账（最近若干条，环形截断在 [save] 里做）。 */
    data class RunLog(val at: Long, val name: String, val result: String)

    private val lock = Any()

    fun list(): List<Task> = synchronized(lock) { readAll() }

    fun upsert(task: Task) = synchronized(lock) {
        val all = readAll().filterNot { it.id == task.id } + task
        writeAll(all.sortedBy { it.name })
    }

    fun delete(id: String) = synchronized(lock) { writeAll(readAll().filterNot { it.id == id }) }

    /** 到点未跑的任务（Worker tick 时取走）。 */
    fun due(now: Long): List<Task> = synchronized(lock) {
        readAll().filter { it.enabled && now - it.lastRunAt >= it.intervalMin * 60_000L }
    }

    /** 执行完记账：更新 lastRunAt/lastResult，并往 runLog 追一行（保留最近 50 条）。 */
    fun recordRun(id: String, now: Long, result: String) = synchronized(lock) {
        val all = readAll()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val t = all[idx]
        val updated = t.copy(lastRunAt = now, lastResult = result)
        val newAll = all.toMutableList().also { it[idx] = updated }
        val logs = readLogs().toMutableList()
        logs.add(0, RunLog(now, t.name, result))
        writeAll(newAll)
        writeLogs(logs.take(50))
    }

    fun logs(): List<RunLog> = synchronized(lock) { readLogs() }

    // ---------- 内部件 ----------

    private fun readAll(): List<Task> =
        runCatching {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Task(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    intervalMin = o.getLong("intervalMin"),
                    prompt = o.getString("prompt"),
                    enabled = o.getBoolean("enabled"),
                    lastRunAt = o.optLong("lastRunAt", 0L),
                    lastResult = o.optString("lastResult", ""),
                )
            }
        }.getOrElse { emptyList() }

    private fun writeAll(tasks: List<Task>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("intervalMin", t.intervalMin)
                    .put("prompt", t.prompt)
                    .put("enabled", t.enabled)
                    .put("lastRunAt", t.lastRunAt)
                    .put("lastResult", t.lastResult),
            )
        }
        file.writeText(arr.toString())
    }

    private fun logsFile() = File(file.parentFile, "tasks-run-log.json")

    private fun readLogs(): List<RunLog> =
        runCatching {
            val f = logsFile()
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RunLog(at = o.getLong("at"), name = o.getString("name"), result = o.getString("result"))
            }
        }.getOrElse { emptyList() }

    private fun writeLogs(logs: List<RunLog>) {
        val f = logsFile()
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        logs.forEach { l -> arr.put(JSONObject().put("at", l.at).put("name", l.name).put("result", l.result)) }
        f.writeText(arr.toString())
    }
}
