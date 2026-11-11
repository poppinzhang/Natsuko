package com.poppinzhang.natsuko.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.FeatureOrder

class FeatureOrderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("home_features", Context.MODE_PRIVATE)

    fun read(): List<Feature> {
        val saved = try {
            preferences.getString("order", null)
        } catch (_: ClassCastException) {
            null
        }
        return FeatureOrder.restore(saved?.split(',').orEmpty())
    }

    fun write(order: List<Feature>): List<Feature> {
        val normalized = FeatureOrder.restore(order.map(Feature::name))
        // Enum names are stable IDs, with no dependency on locale or enum ordinal.
        preferences.edit { putString("order", normalized.joinToString(",") { it.name }) }
        return normalized
    }

    /** Retain the subscription, then invoke it when the observer is no longer needed. */
    fun observe(onChanged: (List<Feature>) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "order" || key == null) onChanged(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
