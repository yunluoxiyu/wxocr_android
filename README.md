# WxOCR Android

忘仙 OCR 答题 Android 版本。应用在手机端启动一个本地 HTTP 服务，接收截图 Base64，完成 OCR 识别、题库匹配、反作弊弹窗识别和 DeepSeek 答题。

## 功能

- 固定区域 OCR 识别题号、题目和 A/B/C/D 选项
- 题库模糊匹配并返回最佳选项点击坐标
- 反作弊弹窗识别接口 `/wx_anti_cheat_popup`
- 反作弊按钮模板匹配定位
- 反作弊题目/选项白字 OCR 识别
- DeepSeek API 自动答题
- 图片 OCR 测试页面
- 题库导入/导出管理
- 调试截图保存
- 卡密启动校验
- DeepSeek API Key 设置

## 接口

服务默认端口：`20010`

### 健康检查

```http
GET /health
```

返回：

```json
{"status":"ok"}
```

### 普通答题 OCR

```http
POST /wx_ocr
Content-Type: application/json
```

请求：

```json
{
  "img": "base64 image"
}
```

返回示例：

```json
{
  "letter": "A",
  "x": 248,
  "y": 261,
  "question": "题目文本",
  "options": ["A: 选项A", "B: 选项B", "C: 选项C", "D: 选项D"],
  "answer": "题库答案",
  "index": 1
}
```

### 反作弊弹窗 OCR

```http
POST /wx_anti_cheat_popup
Content-Type: application/json
```

请求：

```json
{
  "img": "base64 image"
}
```

返回示例：

```json
{
  "question": "请选择下列不是水果的选项一",
  "options": {
    "A": "苹果",
    "B": "香蕉",
    "C": "汽车",
    "D": "橘子"
  },
  "answer": "C",
  "click_x": 500,
  "click_y": 420,
  "btn_x": 627,
  "btn_y": 397
}
```

## 设置

应用内菜单进入「设置」可配置：

- 卡密：启动服务前会调用卡密接口校验，失败则不启动服务
- DeepSeek API Key：反作弊弹窗答题使用
- 保存目录：调试截图、日志输出目录

## 调试文件

开启「保存调试图片」后，会在保存目录下生成调试图，例如：

- `wx_ocr_req_*`：请求原图
- `raw_question_*`：题目原始裁剪
- `proc_question_*`：题目预处理图
- `proc_question_line_*`：多行题目拆行图
- `anti_roi_*`：反作弊搜索窗预处理图
- `anti_option_A/B/C/D_*`：反作弊选项网格兜底识别图
- `annotated_*`：裁剪框标注图

## 构建

环境要求：

- Android Studio / Android Gradle Plugin 8.2.2
- JDK 17
- Android SDK 34

命令：

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 主要技术

- Kotlin
- Jetpack Compose
- NanoHTTPD
- OpenCV Android
- PaddleLite
- PP-OCRv4/PP-OCRv5 模型
- DeepSeek Chat Completions API

## 注意

- `local.properties`、构建产物、IDE 配置不会提交到仓库。
- 模型文件和 PaddleLite 原生库已包含在项目内。
- 如果反作弊选项识别为空，优先查看 `anti_option_A/B/C/D` 调试图，调整搜索窗偏移或网格 padding。
