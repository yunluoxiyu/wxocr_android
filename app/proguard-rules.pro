# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.yunluo.wxocr.knowledge.Question { *; }
-keep class com.yunluo.wxocr.server.OcrRequest { *; }

# PaddleLite
-keep class com.baidu.paddle.lite.** { *; }
-dontwarn com.baidu.paddle.lite.**

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
