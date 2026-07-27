package com.yunluo.wxocr.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object AppConfig {

    lateinit var appContext: Context
        private set

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("wxocr_config", Context.MODE_PRIVATE)
    }

    var serverPort: Int
        get() = prefs.getInt("server_port", 20010)
        set(v) = prefs.edit().putInt("server_port", v).apply()

    val questionsDir: File
        get() = File(appContext.filesDir, "knowledge").also { it.mkdirs() }

    val questionsFile: File
        get() = File(questionsDir, "questions.json")

    val dataDir: File
        get() = File(appContext.filesDir, "data").also { it.mkdirs() }

    // 保存目录（调试图片、日志等），可在菜单中修改
    var saveDirPath: String
        get() = prefs.getString("save_dir", "/storage/emulated/0/wx_ocr") ?: "/storage/emulated/0/wx_ocr"
        set(v) = prefs.edit().putString("save_dir", v).apply()

    val saveDir: File
        get() = File(saveDirPath).also { it.mkdirs() }

    val debugDir: File
        get() = File(saveDir, "debug_crops").also { it.mkdirs() }

    // PP-OCRv4 模型配置
    val paddleRecModelDir: File
        get() = File(appContext.filesDir, "models").also { it.mkdirs() }

    // 模板匹配图片（反作弊按钮小图）
    val templateFile: File
        get() = File(appContext.filesDir, "template.bmp")

    // 缓存 TTL
    const val CACHE_TTL_SECONDS = 30.0f
    const val INDEX_CACHE_TTL_SECONDS = 2.0f

    // 工作线程数
    const val PADDLE_OCR_WORKERS = 2
    const val INDEX_OCR_WORKERS = 2
    const val ANTI_CHEAT_WORKERS = 2

    // 超时
    const val INDEX_OCR_TIMEOUT_SECONDS = 3.0f

    // 模板匹配
    const val TEMPLATE_MATCH_THRESHOLD = 0.79
    const val TEMPLATE_MATCH_METHOD = org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED

    // 调试截图
    var saveDebug: Boolean
        get() = prefs.getBoolean("save_debug", true)
        set(v) = prefs.edit().putBoolean("save_debug", v).apply()



    // 反作弊搜索窗（相对于小图左上角偏移）
    val QUESTION_SEARCH = SearchRect(433, 262, 640, 80)
    val OPTION_SEARCH = SearchRect(375, 180, 400, 140)
    const val BTN_CLICK_OFFSET_X = -50

    // 反作弊 OCR 预处理
    val WHITE_HSV_LOWER = intArrayOf(0, 0, 200)
    val WHITE_HSV_UPPER = intArrayOf(180, 20, 255)
    const val WHITE_BINARY_THRESHOLD = 128
    const val OCR_RESIZE_TARGET_WIDTH = 280
    const val OCR_MIN_CONFIDENCE = 0.4f

    // 选项网格分类
    const val GRID_Y_GAP_MIN = 15
    const val GRID_TOP_N_BY_CONF = 2

    // 固定区域裁剪坐标 (x1, y1, x2, y2) — 与 Python 版 config.py 一致
    val CROP_AREA = mapOf(
        "question" to intArrayOf(128, 122, 875, 230),
        "option_a" to intArrayOf(243, 251, 575, 284),
        "option_b" to intArrayOf(614, 251, 894, 288),
        "option_c" to intArrayOf(242, 353, 544, 391),
        "option_d" to intArrayOf(611, 350, 886, 386),
    )
    val CROP_AREA_ORDER = listOf("question", "option_a", "option_b", "option_c", "option_d")

    // 选项点击坐标
    val OPTION_COORDS = mapOf(
        "A" to intArrayOf(248, 261),
        "B" to intArrayOf(619, 261),
        "C" to intArrayOf(247, 363),
        "D" to intArrayOf(616, 360),
    )

    // 题目标号裁剪区域 (x, y, w, h) — 与 Python 版 config.py 一致
    val INDEX_CROP_AREA = intArrayOf(128, 122, 80, 50)

    // HSV 颜色范围 — 与 Python config.py 一致
    val YELLOW_HSV_LOWER = intArrayOf(15, 80, 150)
    val YELLOW_HSV_UPPER = intArrayOf(45, 255, 255)
    val WHITE_OPTION_HSV_LOWER = intArrayOf(0, 0, 200)
    val WHITE_OPTION_HSV_UPPER = intArrayOf(180, 40, 255)

    // DBNet 检测参数 — 与 Python config.py 一致
    const val DET_DB_THRESH = 0.22f
    const val DET_DB_BOX_THRESH = 0.38f
    const val DET_DB_UNCLIP_RATIO = 1.8f
    const val DET_USE_DILATION = true
    const val DET_MAX_SIDE_LEN = 480

    // 检测模型预处理均值/标准差
    val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    // 模糊匹配
    const val FUZZ_MATCH_THRESHOLD = 0.3

    // 卡密认证
    var cardKey: String?
        get() = prefs.getString("card_key", null)
        set(v) = prefs.edit().putString("card_key", v).apply()

    // DeepSeek API
    var deepSeekApiKey: String?
        get() = prefs.getString("deepseek_api_key", null)
        set(v) = prefs.edit().putString("deepseek_api_key", v).apply()
    const val DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
    const val DEEPSEEK_MODEL = "deepseek-v4-flash"
    const val DEEPSEEK_TIMEOUT_MS = 30000

    data class SearchRect(val offsetX: Int, val offsetY: Int, val w: Int, val h: Int)
}
