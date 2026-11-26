// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import android.view.Display
import android.widget.Toast
import java.text.Collator

data class Cv10AppEntry(
    val component: ComponentName,
    val label: String,
    val isXr: Boolean,
    val category: String,
)

object Cv10AppCatalog {
    private const val IMMERSIVE_HMD = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"

    fun load(context: Context): List<Cv10AppEntry> {
        val pm = context.packageManager
        val phoneApps = findActivities(context, Intent.CATEGORY_LAUNCHER)
        val immersiveApps = findActivities(context, IMMERSIVE_HMD)
        val immersivePackages = immersiveApps.keys.mapTo(hashSetOf()) { it.packageName }
        val entries = immersiveApps.map { (component, info) ->
            Cv10AppEntry(component, info.loadLabel(pm).toString(), true, IMMERSIVE_HMD)
        } + phoneApps.filterKeys { it.packageName !in immersivePackages }.map { (component, info) ->
            Cv10AppEntry(component, info.loadLabel(pm).toString(), false, Intent.CATEGORY_LAUNCHER)
        }
        val collator = Collator.getInstance()
        return entries.sortedWith(compareBy(collator) { it.label })
    }

    private fun findActivities(context: Context, category: String): Map<ComponentName, ResolveInfo> {
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .filter { info ->
                val activity = info.activityInfo
                activity.exported && activity.packageName != context.packageName &&
                    (activity.permission == null ||
                        context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
            }
            .associateBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    fun launch(activity: Activity, entry: Cv10AppEntry): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(entry.category)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        try {
            activity.startActivity(intent, options.toBundle())
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w("Cv10Launcher", "Activity disappeared: ${entry.component}", e)
        } catch (e: SecurityException) {
            Log.w("Cv10Launcher", "Cannot open activity: ${entry.component}", e)
        }
        Toast.makeText(activity, activity.getString(R.string.cv10_app_launch_failed, entry.label), Toast.LENGTH_SHORT).show()
        return false
    }
}
