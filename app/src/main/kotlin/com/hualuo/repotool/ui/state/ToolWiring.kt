package com.hualuo.repotool.ui.state

import com.hualuo.engine.sandbox.ProotSession
import com.hualuo.engine.sandbox.SandboxManager
import com.hualuo.engine.toolcalls.GitHubToolFamily
import com.hualuo.engine.toolcalls.GitHubWriteTool
import com.hualuo.engine.toolcalls.SandboxConfirmer
import com.hualuo.engine.toolcalls.SandboxToolFamily
import com.hualuo.engine.toolcalls.ToolRegistry
import com.hualuo.engine.toolcalls.WriteConfirmer
import java.io.File

/** 对话模型可调用工具的组装口。 */
fun buildGithubToolRegistry(
    persist: UiPersistence,
    writeConfirmer: WriteConfirmer? = null,
    sandboxConfirmer: SandboxConfirmer? = null,
    sandboxRootDir: File? = null,
    prConfirmer: com.hualuo.engine.toolcalls.PrConfirmer? = null,
    memoryStore: com.hualuo.engine.memory.MemoryStore? = null,
    sessionStore: com.hualuo.engine.store.SessionStore? = null,
    webSearchEnabled: (() -> Boolean)? = null,
    skillStore: com.hualuo.engine.memory.MemoryStore? = null,
    imageGenConfig: (() -> com.hualuo.engine.toolcalls.ImageGenConfig?)? = null,
    imageGenPersist: ((ByteArray, String) -> String)? = null,
    videoUrlSession: (() -> com.hualuo.engine.api.ProviderSession?)? = null,
    watchInboxDir: File? = null,
    watchFramesDir: File? = null,
    visionSession: (() -> com.hualuo.engine.api.ProviderSession?)? = null,
): ToolRegistry = GitHubToolFamily.build(
    loadToken = { persist.load(UiKeys.GITHUB_TOKEN) },
    defaultRepo = { persist.load(UiKeys.GITHUB_REPO) },
).also { registry ->
    if (writeConfirmer != null) {
        GitHubWriteTool.register(registry, { persist.load(UiKeys.GITHUB_TOKEN) }, { persist.load(UiKeys.GITHUB_REPO) }, com.hualuo.engine.github.GitHubRepoClient(), writeConfirmer)
    }
    if (prConfirmer != null) {
        com.hualuo.engine.toolcalls.GitHubPrTool.register(registry, { persist.load(UiKeys.GITHUB_TOKEN) }, { persist.load(UiKeys.GITHUB_REPO) }, com.hualuo.engine.github.GitHubPrClient(), prConfirmer)
    }
    com.hualuo.engine.toolcalls.MemoryTool.register(registry, memoryStore)
    com.hualuo.engine.toolcalls.RagTool.register(registry, sessionStore)
    com.hualuo.engine.toolcalls.WebTool.register(registry, com.hualuo.engine.search.WebSearchClient(), { com.hualuo.engine.search.WebSearchClient.defaultFetcher(it) }, webSearchEnabled)
    com.hualuo.engine.toolcalls.SkillTool.register(registry, skillStore)
    if (videoUrlSession != null) com.hualuo.engine.toolcalls.VideoUrlTool.register(registry, videoUrlSession)
    if (imageGenConfig != null && imageGenPersist != null) {
        com.hualuo.engine.toolcalls.ImageGenTool.register(registry, imageGenConfig, { url, body, bearer -> com.hualuo.engine.toolcalls.ImageGenTool.defaultPoster(url, body, bearer) }, { com.hualuo.engine.toolcalls.ImageGenTool.defaultDownloader(it) }, imageGenPersist)
    }
    if (watchInboxDir != null && watchFramesDir != null && visionSession != null) {
        com.hualuo.engine.toolcalls.VideoTool.register(registry, watchInboxDir, watchFramesDir, visionSession)
    }
    if (sandboxConfirmer != null && sandboxRootDir != null) {
        val root = sandboxRootDir
        SandboxToolFamily.register(registry, SandboxManager(File(root, "rootfs"), File(root, "work"), File(root, "shared"), { rootfs, binds -> ProotSession(rootfs, File(root, "shared"), binds) }), sandboxConfirmer)
    }
}
