package ix.xdocui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object DexFingerprint {
    private val dexName = Regex("classes(?:[0-9]+)?\\.dex")

    fun hasDex(path: String): Boolean = ZipFile(path).use { zip ->
        zip.entries().asSequence().any { dexName.matches(it.name) }
    }

    @Suppress("DEPRECATION")
    fun key(context: Context, info: ApplicationInfo): String {
        val pkg = context.packageManager.getPackageInfo(info.packageName, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        add("xdocui-sort-fingerprint-v1-dexkit-2.3.0")
        add(info.packageName)
        add(pkg.longVersionCode.toString())
        add(pkg.lastUpdateTime.toString())
        add(Build.FINGERPRINT)
        val paths = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct().sorted()
        val buffer = ByteArray(64 * 1024)
        for (path in paths) {
            add(path)
            ZipFile(path).use { zip ->
                val entries = zip.entries().asSequence().filter { dexName.matches(it.name) }.sortedBy { it.name }.toList()
                for (entry in entries) {
                    add(entry.name)
                    add(entry.size.toString())
                    zip.getInputStream(entry).use { stream ->
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
