package com.xhsmonitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 只读监控服务：搜索关键词 -> 打开命中的笔记 -> 读取评论 -> 汇总。
 * 不发帖、不评论、不私信、不绕过任何验证。
 */
class MonitorService : AccessibilityService() {

    companion object {
        const val XHS_PACKAGE = "com.xingin.xhs"
        const val ACTION_START = "com.xhsmonitor.action.START"
        const val ACTION_STOP = "com.xhsmonitor.action.STOP"
        const val ACTION_DUMP = "com.xhsmonitor.action.DUMP"
        const val ACTION_GENERATE = "com.xhsmonitor.action.GENERATE"
        const val ACTION_RELOAD = "com.xhsmonitor.action.RELOAD"

        const val BROADCAST_STATUS = "com.xhsmonitor.status"
        const val BROADCAST_NEW_COMMENT = "com.xhsmonitor.new_comment"
        const val BROADCAST_DUMP = "com.xhsmonitor.dump"
        const val BROADCAST_REPORT = "com.xhsmonitor.report"
    }

    private enum class State {
        LAUNCH, FIND_SEARCH, INPUT_KEYWORD, TYPING_KEYWORD, SUBMIT_SEARCH,
        COLLECT_NOTES, READ_COMMENTS
    }

    private data class Card(val node: AccessibilityNodeInfo, val key: String, val title: String)

    private val handler = Handler(Looper.getMainLooper())
    private val db by lazy { DbHelper(this) }

    private var running = false
    private var keywords = mutableListOf<String>()
    private var keywordIndex = 0
    private var notesThisRound = 0
    private var scrollEmptyCount = 0
    private var commentScrollCount = 0
    private var state = State.LAUNCH
    private var visited = mutableSetOf<String>()
    private var currentNoteTitle = ""

    private var selectors = Selectors()
    private var intervalMs = 5000L
    private var maxNotes = 12
    private var reportIntervalMs = 3_600_000L
    @Volatile private var lastReportTs = System.currentTimeMillis()

    // 类人化节奏相关状态
    private var humanMode = true
    private var typingSteps: List<HumanBehavior.TypingStep> = emptyList()
    private var typingIndex = 0
    private var typingRetryCount = 0
    private var nextBreakAt = 0L
    private var restUntil = 0L
    private var resting = false
    private var targetCommentScrolls = 2
    private var justReturnedFromNote = false
    private var findSearchRetryCount = 0
    private var inputRetryCount = 0

    private val stepRunnable = Runnable { if (running) step() }
    private val reportRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            Thread { generateReport() }.start()
            handler.postDelayed(this, reportIntervalMs)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START -> startInternal()
                ACTION_STOP -> stopInternal()
                ACTION_DUMP -> dumpCurrentScreen()
                ACTION_GENERATE -> generateReport()
                ACTION_RELOAD -> reloadSettings()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter()
        filter.addAction(ACTION_START)
        filter.addAction(ACTION_STOP)
        filter.addAction(ACTION_DUMP)
        filter.addAction(ACTION_GENERATE)
        filter.addAction(ACTION_RELOAD)
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
        reloadSettings()
        startReportTimer()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(reportRunnable)
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        reloadSettings()
        if (Config.isRunning(this)) startInternal()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 流程由定时器驱动，事件仅用于系统保持服务连接
    }

    override fun onInterrupt() {
        // 系统打断时不做操作
    }

    // ---------------- 配置与生命周期 ----------------

    private fun reloadSettings() {
        val previousHumanMode = humanMode
        selectors = Config.getSelectors(this)
        intervalMs = Config.getInt(this, Config.KEY_INTERVAL, 5)
            .coerceIn(3, 60).toLong() * 1000L
        maxNotes = Config.getInt(this, Config.KEY_MAX_NOTES, 12).coerceIn(1, 60)
        reportIntervalMs = Config.getInt(this, Config.KEY_REPORT_MIN, 60)
            .coerceIn(5, 1440).toLong() * 60_000L
        humanMode = Config.isHumanMode(this)
        // 中途开启类人化时重置休息计时，避免立刻进入休息
        if (!previousHumanMode && humanMode) {
            nextBreakAt = System.currentTimeMillis() + HumanBehavior.breakAfterMs()
        }
        if (running) startReportTimer()
    }

    private fun startReportTimer() {
        handler.removeCallbacks(reportRunnable)
        if (running) handler.postDelayed(reportRunnable, reportIntervalMs)
    }

    private fun startInternal() {
        reloadSettings()
        keywords = Config.getKeywords(this)
        if (keywords.isEmpty()) {
            postStatus("请先在「关键词」页添加关键词")
            Config.setRunning(this, false)
            return
        }
        running = true
        Config.setRunning(this, true)
        keywordIndex = 0
        notesThisRound = 0
        scrollEmptyCount = 0
        commentScrollCount = 0
        typingRetryCount = 0
        justReturnedFromNote = false
        findSearchRetryCount = 0
        inputRetryCount = 0
        visited.clear()
        state = State.LAUNCH
        lastReportTs = System.currentTimeMillis()
        resting = false
        restUntil = 0L
        nextBreakAt = System.currentTimeMillis() + HumanBehavior.breakAfterMs()
        startReportTimer()
        db.addLog("监控启动：${keywords.joinToString(" / ")}")
        postStatus("监控已启动")
        schedule(400)
    }

    private fun stopInternal() {
        running = false
        Config.setRunning(this, false)
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(reportRunnable)
        db.addLog("监控停止")
        postStatus("监控已停止")
    }

    /** 带类人化抖动的调度；关闭拟真模式时退回简单抖动 */
    private fun schedule(delay: Number) {
        handler.removeCallbacks(stepRunnable)
        val base = delay.toLong()
        val actual = if (humanMode) HumanBehavior.actionDelay(base) else simpleJitter(base)
        handler.postDelayed(stepRunnable, actual)
    }

    /** 不带抖动的调度，仅用于休息期的分段轮询 */
    private fun scheduleRaw(delayMs: Long) {
        handler.removeCallbacks(stepRunnable)
        handler.postDelayed(stepRunnable, delayMs.coerceAtLeast(200L))
    }

    private fun simpleJitter(base: Long): Long =
        (base * (0.85 + Random.nextDouble() * 0.3)).toLong().coerceAtLeast(300)

    private fun currentKeyword(): String? = keywords.getOrNull(keywordIndex)

    private fun nextKeyword(needBack: Boolean = true) {
        if (needBack) performGlobalAction(GLOBAL_ACTION_BACK)
        findSearchRetryCount = 0
        inputRetryCount = 0
        keywordIndex++
        notesThisRound = 0
        scrollEmptyCount = 0
        visited.clear()
        if (keywordIndex >= keywords.size) {
            keywordIndex = 0
            postStatus("一轮完成，回到第一个关键词")
        }
        state = if (rootInActiveWindow?.packageName?.toString() == XHS_PACKAGE) {
            State.FIND_SEARCH
        } else {
            State.LAUNCH
        }
        schedule(1600)
    }

    // ---------------- 状态机 ----------------

    private fun step() {
        // 会话节奏：连续运行一段时间后休息 1~3 分钟，避免长时间不间断的高频操作
        if (humanMode) {
            val now = System.currentTimeMillis()
            if (resting) {
                if (now < restUntil) {
                    scheduleRaw((restUntil - now).coerceAtMost(60_000L))
                    return
                }
                resting = false
                restUntil = 0L
                nextBreakAt = now + HumanBehavior.breakAfterMs()
                postStatus("休息结束，继续监控")
            } else if (now >= nextBreakAt) {
                resting = true
                restUntil = now + HumanBehavior.sessionBreakMs()
                db.addLog("进入类人休息，约 ${(restUntil - now) / 60_000L} 分钟")
                postStatus("休息中，稍后自动继续")
                scheduleRaw((restUntil - now).coerceAtMost(60_000L))
                return
            }
        }

        val root = rootInActiveWindow
        if (root == null) {
            schedule(1200)
            return
        }
        when (state) {
            State.LAUNCH -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(XHS_PACKAGE)
                if (launchIntent == null) {
                    postStatus("未安装小红书，无法启动")
                    stopInternal()
                    return
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                state = State.FIND_SEARCH
                schedule(3200)
            }

            State.FIND_SEARCH -> {
                if (root.packageName?.toString() != XHS_PACKAGE) {
                    findSearchRetryCount++
                    if (findSearchRetryCount >= 6) {
                        findSearchRetryCount = 0
                        postStatus("小红书未在前台，尝试重新启动")
                        state = State.LAUNCH
                        schedule(600)
                    } else {
                        schedule(1500)
                    }
                    return
                }
                val byId = findFirst(root, emptyList(), selectors.searchEntryIds, clickableOnly = true)
                val entry = byId ?: findFirst(root, selectors.searchEntryTexts, emptyList(), clickableOnly = true)
                val clicked = when {
                    entry == null -> false
                    humanMode -> tapNodeHuman(entry) || entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    else -> entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (clicked) {
                    findSearchRetryCount = 0
                    state = State.INPUT_KEYWORD
                    schedule(if (humanMode) 900 else 1200)
                } else {
                    findSearchRetryCount++
                    if (findSearchRetryCount >= 8) {
                        findSearchRetryCount = 0
                        postStatus("搜索入口识别失败，请导出界面诊断")
                        state = State.LAUNCH
                        schedule(600)
                    } else {
                        schedule(2500)
                    }
                }
            }

            State.INPUT_KEYWORD -> {
                val keyword = currentKeyword()
                if (keyword.isNullOrBlank()) {
                    nextKeyword()
                    return
                }
                val input = findEditable(root)
                if (input != null) {
                    if (humanMode) {
                        // 按词组分段输入，模拟中文输入法的选词上屏节奏
                        typingSteps = HumanBehavior.typingSteps(keyword)
                        typingIndex = 0
                        typingRetryCount = 0
                        if (typingSteps.isEmpty()) {
                            setInputText(input, keyword)
                            state = State.SUBMIT_SEARCH
                            schedule(500)
                        } else {
                            state = State.TYPING_KEYWORD
                            schedule(typingSteps.first().waitBeforeMs)
                        }
                    } else {
                        if (!setInputText(input, keyword)) {
                            inputRetryCount++
                            if (inputRetryCount >= 6) {
                                inputRetryCount = 0
                                nextKeyword()
                            } else {
                                schedule(800)
                            }
                            return
                        }
                        inputRetryCount = 0
                        postStatus("正在搜索：$keyword")
                        state = State.SUBMIT_SEARCH
                        schedule(900)
                    }
                } else {
                    inputRetryCount++
                    if (inputRetryCount >= 10) {
                        inputRetryCount = 0
                        postStatus("搜索框长时间未出现，跳过该关键词")
                        nextKeyword()
                    } else {
                        schedule(1200)
                    }
                }
            }

            State.TYPING_KEYWORD -> {
                val input = findEditable(root)
                if (input == null) {
                    typingRetryCount++
                    if (typingRetryCount >= 10) {
                        typingRetryCount = 0
                        postStatus("输入框长时间不可用，跳过该关键词")
                        nextKeyword()
                    } else {
                        schedule(800)
                    }
                    return
                }
                if (typingIndex >= typingSteps.size) {
                    state = State.SUBMIT_SEARCH
                    schedule(500)
                    return
                }
                val step = typingSteps[typingIndex]
                if (!setInputText(input, step.text)) {
                    typingRetryCount++
                    if (typingRetryCount >= 6) {
                        typingRetryCount = 0
                        postStatus("文字输入失败，跳过该关键词")
                        nextKeyword()
                    } else {
                        schedule(700)
                    }
                    return
                }
                typingRetryCount = 0
                typingIndex++
                if (typingIndex < typingSteps.size) {
                    schedule(typingSteps[typingIndex].waitBeforeMs)
                } else {
                    postStatus("正在搜索：${currentKeyword() ?: ""}")
                    state = State.SUBMIT_SEARCH
                    schedule(500)
                }
            }

            State.SUBMIT_SEARCH -> {
                val button = findFirst(root, selectors.searchButtonTexts, emptyList(), clickableOnly = true)
                if (button != null) {
                    val clicked = if (humanMode) tapNodeHuman(button) else false
                    if (!clicked) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    if (!tapAt(0.82f, 0.10f)) tapAt(0.50f, 0.08f)
                }
                notesThisRound = 0
                scrollEmptyCount = 0
                visited.clear()
                state = State.COLLECT_NOTES
                schedule(2300)
            }

            State.COLLECT_NOTES -> {
                val cameFromNote = justReturnedFromNote
                justReturnedFromNote = false
                val keyword = currentKeyword()
                if (keyword.isNullOrBlank() || notesThisRound >= maxNotes) {
                    nextKeyword(!cameFromNote)
                    return
                }
                val cards = collectNoteCards(root, keyword)
                val fresh = cards.firstOrNull { !visited.contains(it.key) }
                if (fresh != null) {
                    visited.add(fresh.key)
                    // 真人不会点开每一条搜索结果
                    if (humanMode && HumanBehavior.shouldSkipNote()) {
                        schedule(700)
                        return
                    }
                    val clicked = if (humanMode) {
                        tapNodeHuman(fresh.node) ||
                                fresh.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        fresh.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                tapNode(fresh.node)
                    }
                    if (!clicked) {
                        schedule(900)
                        return
                    }
                    notesThisRound++
                    currentNoteTitle = fresh.title
                    commentScrollCount = 0
                    targetCommentScrolls = if (humanMode) HumanBehavior.commentScrollRounds() else 2
                    state = State.READ_COMMENTS
                    // 先停留「看正文」，再去读评论
                    schedule(if (humanMode) HumanBehavior.readingPauseMs() else 1800)
                } else {
                    val scrolled = scrollForward(root)
                    scrollEmptyCount = if (scrolled) 0 else scrollEmptyCount + 1
                    if (scrollEmptyCount >= 3) {
                        nextKeyword(!cameFromNote)
                    } else {
                        schedule(1300)
                    }
                }
            }

            State.READ_COMMENTS -> {
                val keyword = currentKeyword() ?: ""
                val area = findCommentArea(root)
                val records = extractComments(area, keyword, currentNoteTitle)
                var added = 0
                for (record in records) {
                    if (db.insertComment(record)) added++
                }
                if (added > 0) {
                    postStatus("发现 $added 条新评论")
                    db.addLog("发现 $added 条新评论：$keyword / $currentNoteTitle")
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(BROADCAST_NEW_COMMENT).putExtra("count", added))
                }
                if (humanMode && HumanBehavior.shouldBackScroll()) {
                    swipeDownSmallGesture()
                    schedule(HumanBehavior.actionDelay(900))
                    return
                }
                if (commentScrollCount < targetCommentScrolls && scrollForward(area)) {
                    commentScrollCount++
                    schedule(if (humanMode) HumanBehavior.actionDelay(1200) else 1200)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    justReturnedFromNote = true
                    state = State.COLLECT_NOTES
                    schedule(if (humanMode) HumanBehavior.actionDelay(1100) else 1300)
                }
            }
        }
    }

    // ---------------- 节点查找 ----------------

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        action: (AccessibilityNodeInfo) -> Unit
    ) {
        if (depth > 25) return
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, depth + 1, action)
        }
    }

    private fun containsIgnoreCase(value: CharSequence?, part: String): Boolean {
        if (value == null || part.isBlank()) return false
        return value.toString().contains(part, ignoreCase = true)
    }

    private fun matchesId(node: AccessibilityNodeInfo, ids: List<String>): Boolean {
        val id = node.viewIdResourceName ?: return false
        return ids.any { id.contains(it, ignoreCase = true) }
    }

    private fun findFirst(
        root: AccessibilityNodeInfo,
        texts: List<String>,
        ids: List<String>,
        clickableOnly: Boolean = false,
        minChildren: Int = 0
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (found == null) {
                val textHit = texts.any {
                    containsIgnoreCase(node.text, it) || containsIgnoreCase(node.contentDescription, it)
                }
                val idHit = matchesId(node, ids)
                if ((textHit || idHit) &&
                    (!clickableOnly || node.isClickable) &&
                    node.childCount >= minChildren
                ) {
                    found = node
                }
            }
        }
        return found
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (found == null) {
                val cls = node.className?.toString() ?: ""
                val isEdit = cls.contains("EditText", ignoreCase = true) || node.isEditable
                if (isEdit && (cls.contains("EditText", ignoreCase = true) ||
                            matchesId(node, selectors.searchInputIds))
                ) {
                    found = node
                }
            }
        }
        return found
    }

    private fun setInputText(input: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 焦点不在输入框时先聚焦再重试一次
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun collectNoteCards(root: AccessibilityNodeInfo, keyword: String): List<Card> {
        val result = mutableListOf<Card>()
        traverse(root) { node ->
            if (!node.isClickable) return@traverse
            val rect: Rect = node.boundsInScreen ?: return@traverse
            if (rect.width() < 200 || rect.height() < 60) return@traverse

            val visible = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val idHit = matchesId(node, selectors.noteCardIds)
            val textHit = visible.length >= keyword.length + 2 &&
                    visible.contains(keyword, ignoreCase = true)
            if (idHit || textHit) {
                val title = if (visible.isNotBlank()) visible.take(60) else findTitleIn(node)
                result.add(Card(node, "${visible.take(40)}|${rect.left},${rect.top}", title))
            }
        }
        return result.distinctBy { it.key }
    }

    private fun findTitleIn(node: AccessibilityNodeInfo): String {
        var title = ""
        traverse(node, 0) { n ->
            if (title.isEmpty() && matchesId(n, selectors.titleIds)) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.length > 2) title = t.take(60)
            }
        }
        return title.ifEmpty { "未命名笔记" }
    }

    private fun findCommentArea(root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val byId = findFirst(root, emptyList(), selectors.commentAreaIds, minChildren = 2)
        if (byId != null) return byId
        var found: AccessibilityNodeInfo? = null
        traverse(root) { node ->
            if (found == null && node.isScrollable && node.childCount >= 2) {
                val t = node.text?.toString().orEmpty()
                if (t.contains("评论") || t.contains("条评论")) found = node
            }
        }
        return found ?: root
    }

    // ---------------- 评论解析 ----------------

    private val timeHintRegex = Regex(
        "刚刚|分钟前|小时前|天前|昨天|前天|星期[一二三四五六日]|周[一二三四五六日]|" +
                "\\d{1,2}-\\d{1,2}|\\d{4}-\\d{1,2}-\\d{1,2}"
    )

    private val noiseWords = listOf(
        "关注", "点赞", "收藏", "分享", "评论", "回复", "发送", "说点什么", "展开更多",
        "全部", "热度", "最新", "搜索", "返回", "小红书", "登录", "举报", "更多",
        "拿铁", "赞", "收藏了", "已关注"
    )

    private fun looksLikeTime(s: String): Boolean = timeHintRegex.containsMatchIn(s)

    private fun isNoise(s: String): Boolean {
        if (s.isBlank()) return true
        if (noiseWords.any { s == it }) return true
        return s.length <= 2 && s.all { it.isDigit() }
    }

    private fun leafTexts(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12) return
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(node, 0)
        return out
    }

    private fun extractComments(
        area: AccessibilityNodeInfo,
        keyword: String,
        noteTitle: String
    ): List<CommentRecord> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<CommentRecord>()
        val itemNodes = mutableListOf<AccessibilityNodeInfo>()

        traverse(area) { node ->
            if (node.childCount > 0 && matchesId(node, selectors.commentItemIds)) {
                itemNodes.add(node)
            }
        }

        if (itemNodes.isNotEmpty()) {
            for (item in itemNodes.take(30)) {
                val texts = leafTexts(item).filter { !isNoise(it) }
                if (texts.isEmpty()) continue
                val author = texts[0].take(40)
                val time = texts.firstOrNull { looksLikeTime(it) } ?: ""
                val content = texts.drop(1)
                    .filter { it != time && !looksLikeTime(it) }
                    .joinToString(" ")
                    .take(500)
                if (content.isBlank()) continue
                result.add(
                    CommentRecord(
                        CommentRecord.makeId(keyword, noteTitle, author, content),
                        keyword, noteTitle, author, content, time, now
                    )
                )
            }
            return result
        }

        // 兜底：没有 comment_item 标识时，要求区域内至少出现时间文案，避免把正文当评论
        val allTexts = leafTexts(area)
        if (!allTexts.any { looksLikeTime(it) }) return emptyList()

        val texts = allTexts.filter { it.length >= 2 && !isNoise(it) }.takeLast(40)
        var i = 0
        while (i + 1 < texts.size) {
            val author = texts[i].take(40)
            val content = texts[i + 1].take(500)
            val time = if (i + 2 < texts.size && looksLikeTime(texts[i + 2])) texts[i + 2] else ""
            i += if (time.isNotEmpty()) 3 else 2
            if (isNoise(author) || isNoise(content)) continue
            result.add(
                CommentRecord(
                    CommentRecord.makeId(keyword, noteTitle, author, content),
                    keyword, noteTitle, author, content, time, now
                )
            )
        }
        return result
    }

    // ---------------- 滚动与手势 ----------------

    private fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        // 拟真模式下优先用真实注入手势，轨迹和速度每次都不同
        if (humanMode && Random.nextDouble() < 0.55 && swipeUpGesture()) return true

        var done = false
        var current: AccessibilityNodeInfo? = node
        while (current != null && !done) {
            if (current.isScrollable &&
                current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                done = true
            }
            current = current.parent
        }
        if (!done) {
            traverse(node) { n ->
                if (!done && n.isScrollable &&
                    n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ) done = true
            }
        }
        if (!done) done = swipeUpGesture()
        return done
    }

    private fun swipeUpGesture(): Boolean {
        val metrics = resources.displayMetrics
        val swipe = HumanBehavior.swipeUp(metrics.widthPixels, metrics.heightPixels)
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipe.path, 0L, swipe.durationMs))
                .build(),
            null, null
        )
    }

    private fun swipeDownSmallGesture(): Boolean {
        val metrics = resources.displayMetrics
        val swipe = HumanBehavior.swipeDownSmall(metrics.widthPixels, metrics.heightPixels)
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipe.path, 0L, swipe.durationMs))
                .build(),
            null, null
        )
    }

    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = node.boundsInScreen ?: return false
        return tapPoint((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }

    /** 拟真点击：触点在控件范围内随机偏移，按压时长也有波动 */
    private fun tapNodeHuman(node: AccessibilityNodeInfo): Boolean {
        val rect = node.boundsInScreen ?: return false
        val tap = HumanBehavior.tap(rect)
        return tapPoint(tap.x, tap.y, tap.durationMs)
    }

    private fun tapAt(xRatio: Float, yRatio: Float): Boolean {
        val metrics = resources.displayMetrics
        return tapPoint(metrics.widthPixels * xRatio, metrics.heightPixels * yRatio)
    }

    private fun tapPoint(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------------- 诊断与报告 ----------------

    private fun dumpCurrentScreen() {
        val root = rootInActiveWindow
        val sb = StringBuilder()
        val counter = intArrayOf(0)
        if (root == null) {
            sb.append("当前拿不到窗口节点。请先切到小红书界面，再回到本 App 点诊断。\n")
        } else {
            dumpNode(root, 0, sb, counter)
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val path = ReportGenerator.saveText(this, "界面诊断_$stamp.txt", sb.toString())
        val intent = Intent(BROADCAST_DUMP).apply {
            putExtra("dump", sb.toString())
            putExtra("path", path ?: "")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        sb: StringBuilder,
        counter: IntArray
    ) {
        if (counter[0] > 400 || depth > 18) return
        counter[0]++
        val text = node.text?.toString()?.replace("\n", " ")?.take(70) ?: ""
        val desc = node.contentDescription?.toString()?.take(50) ?: ""
        sb.append("  ".repeat(depth))
            .append(node.className ?: "?")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" click=").append(node.isClickable)
            .append(" scroll=").append(node.isScrollable)
            .append(" bounds=").append(node.boundsInScreen?.toShortString() ?: "-")
            .append(" text=").append(text)
            .append(" desc=").append(desc)
            .append("\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, sb, counter)
        }
    }

    private fun generateReport() {
        val path = ReportGenerator.generate(this, lastReportTs)
        if (path == null) {
            postStatus("汇总生成失败，请检查存储权限")
            return
        }
        lastReportTs = System.currentTimeMillis()
        postStatus("汇总已生成")
        showNotification("新的评论汇总已保存到 Downloads/XhsMonitor")
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(BROADCAST_REPORT).putExtra("path", path))
    }

    private fun showNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("xhs_report", "监控汇总", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, "xhs_report")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小红书评论监控")
            .setContentText(message.take(80))
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
    }

    private fun postStatus(message: String) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(BROADCAST_STATUS).putExtra("msg", message))
    }
}
