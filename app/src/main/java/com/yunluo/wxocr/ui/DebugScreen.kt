package com.yunluo.wxocr.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunluo.wxocr.config.AppConfig
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(listOf<File>()) }
    var selectedImage by remember { mutableStateOf<File?>(null) }

    fun saveToGallery(file: File) {
        try {
            val fileName = file.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WxOCR")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(context, "已保存到 相册/WxOCR", Toast.LENGTH_SHORT).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES + "/WxOCR"
                )
                dir.mkdirs()
                val dest = File(dir, fileName)
                file.inputStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                Toast.makeText(context, "已保存到 $dest", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun refreshFiles() {
        files = AppConfig.debugDir.listFiles()
            ?.filter { it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    LaunchedEffect(Unit) { refreshFiles() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调试图片") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    var showClearConfirm by remember { mutableStateOf(false) }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空全部")
                    }
                    if (showClearConfirm) {
                        AlertDialog(
                            onDismissRequest = { showClearConfirm = false },
                            title = { Text("清空调试图片") },
                            text = { Text("确定删除所有调试截图吗？") },
                            confirmButton = {
                                TextButton(onClick = {
                                    files.forEach { it.delete() }
                                    refreshFiles()
                                    showClearConfirm = false
                                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                                }) { Text("确定") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "暂无调试图片",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                    Text(
                        "启动 OCR 服务后自动生成",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.absolutePath }) { file ->
                    DebugImageCard(
                        file = file,
                        onClick = { selectedImage = file },
                        onDelete = {
                            file.delete()
                            refreshFiles()
                        },
                        onSave = { saveToGallery(file) }
                    )
                }
            }
        }
    }

    // 全屏预览
    if (selectedImage != null) {
        val file = selectedImage!!
        Dialog(
            onDismissRequest = { selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedImage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${"%.1f".format(file.length() / 1024.0)} KB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { saveToGallery(file) }) {
                            Icon(Icons.Default.SaveAlt, contentDescription = "保存到本地")
                        }
                    }
                    Divider()
                    // 图片
                    val bitmap = remember(file) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    }
                    if (bitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 8.dp)
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = file.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无法加载图片", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugImageCard(
    file: File,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit = {}
) {
    val bitmap = remember(file) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 4
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = file.name.removeSuffix(".png"),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${"%.1f".format(file.length() / 1024.0)} KB",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSave, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.SaveAlt,
                        contentDescription = "保存",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
