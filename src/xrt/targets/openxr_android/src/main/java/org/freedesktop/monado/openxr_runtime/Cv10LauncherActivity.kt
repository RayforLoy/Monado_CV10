// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast

class Cv10LauncherActivity : Activity() {
    private val directLaunch = Cv10DirectLaunch(this)
    private lateinit var search: SearchView
    private lateinit var xrOnly: CheckBox
    private lateinit var adapter: ArrayAdapter<Cv10AppEntry>
    private var apps = emptyList<Cv10AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv10_launcher)
        findViewById<View>(R.id.cv10_launch_spatial).setOnClickListener {
            startActivity(Intent(this, Cv10SpatialLauncherActivity::class.java))
        }
        findViewById<View>(R.id.cv10_background_restricted).setOnClickListener {
            openAppInfo(packageName)
        }
        search = findViewById(R.id.cv10_app_search)
        xrOnly = findViewById(R.id.cv10_xr_only)
        adapter = object : ArrayAdapter<Cv10AppEntry>(this, android.R.layout.simple_list_item_2) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val app = requireNotNull(getItem(position))
                row.findViewById<TextView>(android.R.id.text1).text = app.label
                row.findViewById<TextView>(android.R.id.text2).text = app.component.packageName
                return row
            }
        }
        findViewById<ListView>(R.id.cv10_apps).apply {
            adapter = this@Cv10LauncherActivity.adapter
            emptyView = this@Cv10LauncherActivity.findViewById<TextView>(R.id.cv10_apps_empty)
            setOnItemClickListener { _, _, position, _ ->
                val activity = this@Cv10LauncherActivity
                val app = activity.adapter.getItem(position) ?: return@setOnItemClickListener
                activity.directLaunch.select(app) { Cv10AppCatalog.launch(activity, app) }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val activity = this@Cv10LauncherActivity
                val app = activity.adapter.getItem(position) ?: return@setOnItemLongClickListener false
                activity.openAppInfo(app.component.packageName)
                true
            }
        }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps()
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                search.clearFocus()
                return true
            }
        })
        xrOnly.setOnCheckedChangeListener { _, _ -> filterApps() }
    }

    override fun onResume() {
        super.onResume()
        val backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        findViewById<View>(R.id.cv10_background_restricted).visibility =
            if (backgroundRestricted) View.VISIBLE else View.GONE
        apps = Cv10AppCatalog.load(this)
        filterApps()
    }

    override fun onPause() {
        directLaunch.onPause()
        super.onPause()
    }

    override fun onStop() {
        directLaunch.cancel()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing) directLaunch.onWindowFocused()
    }

    private fun openAppInfo(appPackage: String) {
        val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appPackage, null))
        try {
            startActivity(info)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cv10_app_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterApps() {
        val query = search.query.toString().trim()
        val visible = apps.filter {
            (!xrOnly.isChecked || it.isXr) &&
                (it.label.contains(query, ignoreCase = true) || it.component.packageName.contains(query, ignoreCase = true))
        }
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(visible)
        adapter.notifyDataSetChanged()
    }
}
