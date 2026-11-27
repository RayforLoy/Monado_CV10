// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.freedesktop.monado.auxiliary.Cv10RenderSettings

internal fun showCv10RenderSettings(activity: Activity) {
    val preferences = Cv10RenderSettings.preferences(activity)
    val savedSize = Cv10RenderSettings.readSupersamplingEyeSize(preferences)
    val presetSize = Cv10RenderSettings.HUAWEI_CENTER_EYE_SIZE
    val padding = (24 * activity.resources.displayMetrics.density).toInt()
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, 0, padding, 0)
    }
    val off = RadioButton(activity).apply {
        id = View.generateViewId()
        setText(R.string.cv10_render_off)
    }
    val oem = RadioButton(activity).apply {
        id = View.generateViewId()
        text = activity.getString(R.string.cv10_render_oem, presetSize)
    }
    val custom = RadioButton(activity).apply {
        id = View.generateViewId()
        setText(R.string.cv10_render_custom)
    }
    val size = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        setHint(R.string.cv10_render_size_hint)
        setText((if (savedSize > 0) savedSize else presetSize).toString())
        visibility = View.GONE
    }
    val options = RadioGroup(activity).apply {
        addView(off)
        addView(oem)
        addView(custom)
        setOnCheckedChangeListener { _, id ->
            size.visibility = if (id == custom.id) View.VISIBLE else View.GONE
        }
        check(when (savedSize) {
            0 -> off.id
            presetSize -> oem.id
            else -> custom.id
        })
    }
    val enhanced = CheckBox(activity).apply {
        setText(R.string.cv10_render_enhanced)
        isChecked = Cv10RenderSettings.readEnhancedRenderMode(preferences)
    }
    content.addView(TextView(activity).apply { setText(R.string.cv10_render_description) })
    content.addView(options)
    content.addView(size)
    content.addView(enhanced)
    val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.cv10_render_title)
        .setView(ScrollView(activity).apply { addView(content) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val selectedSize = when (options.checkedRadioButtonId) {
            oem.id -> presetSize
            custom.id -> size.text.toString().toIntOrNull() ?: -1
            else -> 0
        }
        if (options.checkedRadioButtonId == custom.id && !Cv10RenderSettings.isSupportedEyeSize(selectedSize)) {
            size.error = activity.getString(
                R.string.cv10_render_size_error,
                Cv10RenderSettings.BASELINE_EYE_SIZE,
                presetSize,
            )
            return@setOnClickListener
        }
        preferences.edit()
            .putInt(Cv10RenderSettings.SUPERSAMPLING_EYE_SIZE, selectedSize)
            .putBoolean(Cv10RenderSettings.ENABLE_ENHANCED_RENDER_MODE, enhanced.isChecked)
            .apply()
        dialog.dismiss()
    }
}
