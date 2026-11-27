package com.xhsmonitor

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var statusText: TextView
    private val dumpHandler = Handler(Looper.getMainLooper())
    private lateinit var keywordAdapter: KeywordAdapter
    private lateinit var commentAdapter: CommentAdapter
    private val keywordItems = mutableListOf<String>()
    private val commentItems = mutableListOf<CommentRecord>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitorService.BROADCAST_STATUS -> {
                    intent.getStringExtra("msg")?.let { statusText.text = "状态：$it" }
                }
                MonitorService.BROADCAST_NEW_COMMENT -> {
                    loadComments()
                    refreshStatus()
                }
                MonitorService.BROADCAST_REPORT -> refreshReports()
                MonitorService.BROADCAST_DUMP -> {
                    showDumpDialog(
                        intent.getStringExtra("dump") ?: "",
                        intent.getStringExtra("path") ?: ""
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = DbHelper(this)
        statusText = findViewById(R.id.tv_status)

        requestPermissionsIfNeeded()
        setupTabs()
        setupKeywords()
        setupComments()
        setupReports()

        val filter = IntentFilter()
        filter.addAction(MonitorService.BROADCAST_STATUS)
        filter.addAction(MonitorService.BROADCAST_NEW_COMMENT)
        filter.addAction(MonitorService.BROADCAST_REPORT)
        filter.addAction(MonitorService.BROADCAST_DUMP)
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        loadComments()
        refreshReports()
    }

    override fun onDestroy() {
        dumpHandler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
        if (denied) {
            toast("部分权限被拒绝：通知或旧版存储可能不可用，报告仍可在 App 内查看")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 权限 ----------------

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
            )
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 102
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            if (Settings.Secure.getInt(
                    contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
                ) == 0
            ) return false
            val setting = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            setting.split(':').any {
                it.contains(packageName, ignoreCase = true) &&
                        it.contains("MonitorService", ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        toast("在列表中找到「小红书评论监控」并打开")
    }

    // ---------------- 界面 ----------------

    private fun setupTabs() {
        findViewById<Button>(R.id.btn_tab_keywords).setOnClickListener { showTab(0) }
        findViewById<Button>(R.id.btn_tab_comments).setOnClickListener { showTab(1) }
        findViewById<Button>(R.id.btn_tab_reports).setOnClickListener { showTab(2) }
    }

    private fun showTab(index: Int) {
        findViewById<View>(R.id.view_keywords).visibility =
            if (index == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.view_comments).visibility =
            if (index == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.view_reports).visibility =
            if (index == 2) View.VISIBLE else View.GONE
    }

    private fun setupKeywords() {
        val input = findViewById<EditText>(R.id.et_new_keyword)
        val recycler = findViewById<RecyclerView>(R.id.rv_keywords)
        recycler.layoutManager = LinearLayoutManager(this)

        keywordAdapter = KeywordAdapter(keywordItems) { position ->
            if (position in keywordItems.indices) {
                keywordItems.removeAt(position)
                Config.setKeywords(this, keywordItems)
                keywordAdapter.notifyDataSetChanged()
            }
        }
        recycler.adapter = keywordAdapter
        keywordItems.addAll(Config.getKeywords(this))
        keywordAdapter.notifyDataSetChanged()

        findViewById<Button>(R.id.btn_add_keyword).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                toast("请输入关键词")
                return@setOnClickListener
            }
            if (keywordItems.any { it.equals(value, ignoreCase = true) }) {
                toast("关键词已存在")
                return@setOnClickListener
            }
            keywordItems.add(value)
            Config.setKeywords(this, keywordItems)
            keywordAdapter.notifyDataSetChanged()
            input.text.clear()
            toast("已添加：$value")
        }

        findViewById<EditText>(R.id.et_interval).setText(
            Config.getInt(this, Config.KEY_INTERVAL, 5).toString()
        )
        findViewById<EditText>(R.id.et_max_notes).setText(
            Config.getInt(this, Config.KEY_MAX_NOTES, 12).toString()
        )
        findViewById<EditText>(R.id.et_report_minutes).setText(
            Config.getInt(this, Config.KEY_REPORT_MIN, 60).toString()
        )

        val humanCheck = findViewById<CheckBox>(R.id.cb_human_mode)
        humanCheck.isChecked = Config.isHumanMode(this)
        humanCheck.setOnCheckedChangeListener { _, checked ->
            Config.setHumanMode(this, checked)
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(MonitorService.ACTION_RELOAD))
            toast(if (checked) "已开启类人化节奏" else "已关闭类人化节奏，操作会变快")
        }

        findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            val interval = findViewById<EditText>(R.id.et_interval)
                .text.toString().toIntOrNull() ?: 5
            val maxNotes = findViewById<EditText>(R.id.et_max_notes)
                .text.toString().toIntOrNull() ?: 12
            val reportMinutes = findViewById<EditText>(R.id.et_report_minutes)
                .text.toString().toIntOrNull() ?: 60
            Config.setInt(this, Config.KEY_INTERVAL, interval.coerceIn(3, 60))
            Config.setInt(this, Config.KEY_MAX_NOTES, maxNotes.coerceIn(1, 60))
            Config.setInt(this, Config.KEY_REPORT_MIN, reportMinutes.coerceIn(5, 1440))
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(MonitorService.ACTION_RELOAD))
            toast("设置已保存")
        }

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            if (isAccessibilityEnabled()) {
                toast("无障碍权限已开启")
            } else {
                openAccessibilitySettings()
            }
        }

        findViewById<Button>(R.id.btn_start_stop).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                openAccessibilitySettings()
                return@setOnClickListener
            }
            val wasRunning = Config.isRunning(this)
            if (!wasRunning && keywordItems.isEmpty()) {
                toast("请先添加至少一个关键词")
                return@setOnClickListener
            }
            Config.setRunning(this, !wasRunning)
            val action = if (wasRunning) MonitorService.ACTION_STOP else MonitorService.ACTION_START
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
            refreshStatus()
        }

        findViewById<Button>(R.id.btn_dump).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                openAccessibilitySettings()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("界面诊断")
                .setMessage(
                    "点「开始」后，请立刻切到小红书，停在搜索结果页或笔记详情页。" +
                            "5 秒后会自动抓取当前界面的文字和控件标识，用来排查识别不准的问题。"
                )
                .setPositiveButton("开始") { _, _ ->
                    dumpHandler.postDelayed({
                        LocalBroadcastManager.getInstance(this)
                            .sendBroadcast(Intent(MonitorService.ACTION_DUMP))
                        toast("诊断完成，结果已弹出")
                    }, 5000L)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun setupComments() {
        val recycler = findViewById<RecyclerView>(R.id.rv_comments)
        recycler.layoutManager = LinearLayoutManager(this)
        commentAdapter = CommentAdapter(commentItems)
        recycler.adapter = commentAdapter

        findViewById<Button>(R.id.btn_clear_comments).setOnClickListener {
            db.clearComments()
            loadComments()
            toast("本地记录已清空")
        }
    }

    private fun setupReports() {
        findViewById<Button>(R.id.btn_generate_report).setOnClickListener {
            val path = ReportGenerator.generate(this, 0L)
            if (path == null) toast("报告生成失败，请检查存储权限") else {
                refreshReports()
                toast("报告已保存到 Downloads/XhsMonitor")
            }
        }

        findViewById<Button>(R.id.btn_share_report).setOnClickListener {
            val latest = ReportGenerator.listReports(this).firstOrNull()
            if (latest == null) {
                toast("暂无可分享的报告")
                return@setOnClickListener
            }
            val uri = ReportGenerator.uriFor(this, latest)
            if (uri == null) {
                toast("分享失败")
                return@setOnClickListener
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享监控报告"))
        }
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        val running = Config.isRunning(this)
        statusText.text = "状态：" + when {
            !enabled -> "无障碍权限未开启"
            running -> "监控运行中"
            else -> "已就绪，未启动"
        }
        findViewById<Button>(R.id.btn_start_stop).text =
            if (running) "停止监控" else "启动监控"
    }

    private fun loadComments() {
        val list = db.getAllComments(300)
        commentAdapter.submit(list)
        findViewById<TextView>(R.id.tv_comments_count).text =
            "已记录 ${list.size} 条评论"
    }

    private fun refreshReports() {
        val reports = ReportGenerator.listReports(this)
        val tv = findViewById<TextView>(R.id.tv_reports)
        if (reports.isEmpty()) {
            tv.text = "暂无汇总报告\n\n报告会按你设置的间隔自动生成到 Downloads/XhsMonitor 目录。"
        } else {
            tv.text = buildString {
                append("共 ${reports.size} 份报告：\n\n")
                reports.take(30).forEachIndexed { index, file ->
                    append(index + 1).append(". ").append(file.name).append("\n")
                }
            }
        }
    }

    private fun showDumpDialog(dump: String, path: String) {
        val scroll = ScrollView(this)
        val textView = TextView(this)
        textView.text = if (dump.isBlank()) "没有抓到内容。" else dump
        textView.textSize = 11f
        scroll.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("界面结构诊断")
            .setView(scroll)
            .setNeutralButton("复制内容") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("xhs_dump", dump))
                toast("已复制到剪贴板")
            }
            .setPositiveButton("关闭", null)
            .show()

        if (path.isNotBlank()) {
            db.addLog("导出界面诊断：$path")
        }
    }
}
