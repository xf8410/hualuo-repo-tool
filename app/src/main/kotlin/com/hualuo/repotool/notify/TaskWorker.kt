package com.hualuo.repotool.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hualuo.repotool.HualuoApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 定时任务 Worker（tasks 页实装刀）：与 [CiNotifyWorker] 同一节拍（15 分钟 tick），
 * 每 tick 读任务表，到点的任务开一个新会话把提示词发出去——现场留在会话库
 * （列表里点开就是完整对话），这里只往任务表记一行执行账。
 *
 * 纪律：
 *  - 任务没配模型密钥/没到点/没任务一律安静返回（不装样子）；
 *  - 执行失败把人话写进 lastResult（下回列表里看得见），不吞错；
 *  - 不在前台时也能跑（Worker 本来就后台）；会话态是进程级单例，
 *    前台正在聊就把任务排到下个 tick（chat.busy 时跳过本次，不抢线）。
 */
class TaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? HualuoApplication ?: return@withContext Result.success()
        val store = app.taskStore
        val now = System.currentTimeMillis()
        store.due(now).forEach { task ->
            runCatching {
                val ui = app.uiState
                if (ui.chat.busy) {
                    store.recordRun(task.id, now, "跳过：前台正有会话在生成，下个节拍再试")
                    return@runCatching
                }
                ui.newConversation()
                val sessionId = ui.chat.sessionId
                if (sessionId == null) {
                    store.recordRun(task.id, now, "失败：新会话没建成（会话库降级）")
                    return@runCatching
                }
                ui.chat.send(task.prompt, ui.currentModel)
                store.recordRun(task.id, now, "已发：新会话 ${sessionId.takeLast(6)}（模型 ${ui.currentModel}）")
            }.onFailure { e ->
                store.recordRun(task.id, now, "失败：${e.message ?: "原因不明"}")
            }
        }
        Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "scheduled-tasks"
    }
}
