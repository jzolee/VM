package com.jzolee.vibrationmonitor

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

class CustomMarker(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {

    private val tvContent: TextView = findViewById(R.id.tvContent)

    override fun refreshContent(entry: Entry, highlight: Highlight?) {
        // Az értékek formázása és megjelenítése
        tvContent.text = "Freq: ${entry.x}, Amp: ${entry.y}"
        super.refreshContent(entry, highlight)
    }

    override fun getOffset(): MPPointF {
        // A Marker pozíciójának beállítása (középre igazítás)
        return MPPointF(-(width / 2f), -height.toFloat())
    }
}