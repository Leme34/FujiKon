package com.fujikon.app.render

import android.net.Uri
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 进程级（Application scope）导出协调器。
 *
 * 之前导出任务挂在 [androidx.lifecycle.ViewModel] 的 `viewModelScope` 上，而
 * 编辑器 ViewModel 绑定导航目的地；一旦页面因内存压力 / 配置变更 / 用户离开被
 * 回收，`onCleared()` 会取消导出，弹窗随之消失，进度与结果全部丢失——这正是
 * 用户反馈「正在导出的弹窗自己消失、重启也导不出来」的主因之一。
 *
 * 这里把导出迁到 Application 级作用域，生命周期不再依赖任何页面；页面重建后
 * 仍可从 [status] 读取真实进度并恢复弹窗。进程被杀则状态自然回到 [Status.Idle]
 * （不会卡在「正在导出」），下次进入会重新开始，绝不残留死状态。
 */
class ExportCoordinator(
    private val manager: ExportManager,
    private val scope: CoroutineScope
) {

    sealed interface Status {
        data object Idle : Status
        data class Running(
            val done: Int,
            val total: Int,
            val currentName: String,
            /** 单张内阶段："" / "解码" / "渲染" / "写入相册"。 */
            val stage: String = ""
        ) : Status
        data class Done(
            val saved: Int,
            val failures: List<String>,
            val firstUri: Uri?,
            val album: String
        ) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null

    fun export(
        photos: List<PhotoItem>,
        preset: Preset,
        options: ExportManager.Options,
        finalImageUris: Map<Long, Uri> = emptyMap(),
        presetsByPhoto: Map<Long, Preset> = emptyMap()
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = Status.Running(0, photos.size, "", "")
            runCatching {
                manager.export(
                    photos = photos,
                    preset = preset,
                    options = options,
                    finalImageUris = finalImageUris,
                    presetsByPhoto = presetsByPhoto
                ) { p ->
                    _status.value = Status.Running(p.done, p.total, p.currentName, p.stage)
                }
            }.onSuccess { result ->
                _status.value = Status.Done(
                    saved = result.saved.size,
                    failures = result.failures,
                    firstUri = result.saved.firstOrNull(),
                    album = options.album
                )
            }.onFailure { e ->
                if (e is CancellationException) {
                    _status.value = Status.Idle
                } else {
                    _status.value = Status.Failed(e.message ?: "导出失败")
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _status.value = Status.Idle
    }

    /** 结果弹窗被用户「知道了 / 完成」后调用：仅在未运行时复位，避免误清进行中的任务。 */
    fun reset() {
        if (job?.isActive != true) {
            job = null
            _status.value = Status.Idle
        }
    }
}
