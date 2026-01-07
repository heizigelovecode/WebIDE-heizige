// 文件: java/com/example/sorarunrun/lsp/ProotStreamConnectionProvider.kt
// 把这个类替换进去，它会把通信内容打印到 Logcat

package com.web.webide.lsp

import android.content.Context
import android.util.Log
import com.web.webide.ui.terminal.AlpineManager
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

class ProotStreamConnectionProvider(
    private val context: Context,
    private val command: List<String>
) : StreamConnectionProvider {

    private var process: Process? = null

    override fun start() {
        val fullCommand = AlpineManager.buildProotCommand(context, command.toTypedArray())
        val hostEnv = AlpineManager.getProotEnv(context)

        val pb = ProcessBuilder(fullCommand)
        pb.environment().putAll(hostEnv)
        pb.directory(context.filesDir)

        Log.d("LSP-Process", "启动命令: $fullCommand")

        try {
            process = pb.start()
            Log.d("LSP-Process", "进程启动成功 PID: ${process.toString()}")

            // 监控错误流
            thread {
                process?.errorStream?.bufferedReader()?.forEachLine {
                    Log.e("LSP-Stderr", it)
                }
            }

        } catch (e: Exception) {
            Log.e("LSP-Process", "启动失败", e)
            throw e
        }
    }

    // 包装输入流，打印 LSP 回复给 Editor 的内容
    override val inputStream: InputStream
        get() = object : FilterInputStream(process?.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val read = super.read(b, off, len)
                if (read > 0) {
                    val content = String(b, off, read)
                     Log.v("LSP-RX", content) // 内容太多可以注释掉，只看是否有数据
                    Log.v("LSP-RX", "收到 $read 字节数据")
                }
                return read
            }
        }

    // 包装输出流，打印 Editor 发送给 LSP 的内容
    override val outputStream: OutputStream
        get() = object : FilterOutputStream(process?.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                val content = String(b, off, len)
                Log.d("LSP-TX", "发送: $content")
                super.write(b, off, len)
            }
        }

    override fun close() {
        process?.destroy()
    }
}