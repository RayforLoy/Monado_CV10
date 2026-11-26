// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import org.json.JSONObject

internal object Cv10BundledRuntime {
    @Synchronized
    fun manifestPath(context: Context, filename: String): String? {
        val runtime = File(context.applicationInfo.nativeLibraryDir, "libopenxr_monado.so")
        if (!runtime.isFile) return null
        val manifest = AtomicFile(File(context.cacheDir, filename))
        val contents = JSONObject()
            .put("file_format_version", "1.0.0")
            .put("runtime", JSONObject().put("library_path", runtime.absolutePath))
        return try {
            val stream = manifest.startWrite()
            try {
                stream.write(contents.toString().toByteArray(Charsets.UTF_8))
                manifest.finishWrite(stream)
            } catch (error: IOException) {
                manifest.failWrite(stream)
                throw error
            }
            manifest.baseFile.absolutePath
        } catch (error: IOException) {
            Log.w("Cv10BundledRuntime", "Unable to prepare the bundled OpenXR runtime", error)
            null
        }
    }
}
