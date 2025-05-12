package com.jzolee.vibrationmonitor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.toColorInt

@SuppressLint("MissingPermission", "DefaultLocale")
class MainActivity : AppCompatActivity() {
    private lateinit var bleManager: BLEManager
    private lateinit var sensor: Sensor
    private lateinit var logFile: LogFile

    private lateinit var textViewState: TextView
    private lateinit var textViewRms: TextView
    private lateinit var textViewRpm: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewRssi: TextView
    private lateinit var textViewLog: TextView
    private lateinit var scrollViewLog: ScrollView
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var fftLayout: ConstraintLayout
    private lateinit var logLayout: ConstraintLayout
    private lateinit var barChart: BarChart
    private lateinit var switchXEN: SwitchCompat
    private lateinit var switchYEN: SwitchCompat
    private lateinit var switchZEN: SwitchCompat
    private lateinit var seekBarFilter: SeekBar
    private lateinit var buttonRecord: Button
    private lateinit var textViewFilter: TextView
    private lateinit var textViewRpm0: TextView
    private lateinit var textViewRpm1: TextView
    private lateinit var textViewRpm2: TextView
    private lateinit var textViewRpm3: TextView
    private lateinit var textViewRpm4: TextView
    private lateinit var textViewRpm5: TextView
    private lateinit var textViewRpm6: TextView
    private lateinit var textViewRms0: TextView
    private lateinit var textViewRms1: TextView
    private lateinit var textViewRms2: TextView
    private lateinit var textViewRms3: TextView
    private lateinit var textViewRms4: TextView
    private lateinit var textViewRms5: TextView
    private lateinit var textViewRms6: TextView
    private lateinit var textViewName: TextView
    private lateinit var textViewName0: TextView
    private lateinit var textViewName1: TextView
    private lateinit var textViewName2: TextView
    private lateinit var textViewName3: TextView
    private lateinit var textViewName4: TextView
    private lateinit var textViewName5: TextView
    private lateinit var textViewName6: TextView

    private val fftData = mutableMapOf<Float, Float>()  // Frekvencia -> Amplitúdó
    private var bleInitialized: Boolean = false
    private var actRpmIdx: Int = 0

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        window.decorView.apply {
            // Hide both the navigation bar and the status bar.
            // SYSTEM_UI_FLAG_FULLSCREEN is only available on Android 4.1 and higher, but as
            // a general rule, you should design your app to hide the status bar whenever you
            // hide the navigation bar.
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        textViewState = findViewById(R.id.textViewState)
        textViewRms = findViewById(R.id.textViewRms)
        textViewRpm = findViewById(R.id.textViewRpm)
        textViewBattery = findViewById(R.id.textViewBattery)
        textViewRssi = findViewById(R.id.textViewRssi)
        textViewLog = findViewById(R.id.textViewLog)
        scrollViewLog = findViewById(R.id.scrollViewLog)
        mainLayout = findViewById(R.id.mainLayout)
        fftLayout = findViewById(R.id.fftLayout)
        logLayout = findViewById(R.id.logLayout)
        barChart = findViewById(R.id.barChart)
        switchXEN = findViewById(R.id.switchXEN)
        switchYEN = findViewById(R.id.switchYEN)
        switchZEN = findViewById(R.id.switchZEN)
        seekBarFilter = findViewById(R.id.seekBarFilter)
        buttonRecord = findViewById(R.id.buttonRecord)
        textViewFilter = findViewById(R.id.textViewFilter)

        textViewRpm0 = findViewById(R.id.textViewRpm0)
        textViewRpm1 = findViewById(R.id.textViewRpm1)
        textViewRpm2 = findViewById(R.id.textViewRpm2)
        textViewRpm3 = findViewById(R.id.textViewRpm3)
        textViewRpm4 = findViewById(R.id.textViewRpm4)
        textViewRpm5 = findViewById(R.id.textViewRpm5)
        textViewRpm6 = findViewById(R.id.textViewRpm6)

        textViewRms0 = findViewById(R.id.textViewRms0)
        textViewRms1 = findViewById(R.id.textViewRms1)
        textViewRms2 = findViewById(R.id.textViewRms2)
        textViewRms3 = findViewById(R.id.textViewRms3)
        textViewRms4 = findViewById(R.id.textViewRms4)
        textViewRms5 = findViewById(R.id.textViewRms5)
        textViewRms6 = findViewById(R.id.textViewRms6)

        textViewName = findViewById(R.id.textViewName)
        textViewName0 = findViewById(R.id.textViewName0)
        textViewName1 = findViewById(R.id.textViewName1)
        textViewName2 = findViewById(R.id.textViewName2)
        textViewName3 = findViewById(R.id.textViewName3)
        textViewName4 = findViewById(R.id.textViewName4)
        textViewName5 = findViewById(R.id.textViewName5)
        textViewName6 = findViewById(R.id.textViewName6)

        sensor = Sensor(
            logCallback = { runOnUiThread { appendLog(it) } },
            /*
            updateFFT = { newFftData ->
                updateFFTData(newFftData)
                logFile.onDataReceived(newFftData)
            },*/
            updateControls = { runOnUiThread { updateControls() } },
            updateData = { runOnUiThread { updateData() } }
        )

        setupChart() // Diagram konfigurálása
        defaultDisplay()

        // Action when SeekBar is used
        seekBarFilter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            // Member Implementation (Required)
            // Keeps the track if touch was lifted off the SeekBar
            override fun onStopTrackingTouch(seekBar: SeekBar) {

                val filter = seekBarFilter.progress / 100.0f
                sensor.filter = filter
                //appendLog(sensor.filter.toString())
                seekBarFilter.isEnabled = false
                textViewFilter.text = "---"
                sendControl()

                // If touch was lifted before the SeekBar progress was 100
                // Make a Toast message "Try Again" and set the progress to 0
                //if (seekBar.progress < 100) {
                //}
            }

            // Member Implementation (Required)
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do anything or Nothing
            }

            // Member Implementation (Required)
            // Keeps the track of progress of the seekbar
            override fun onProgressChanged(
                seekBar: SeekBar, progress: Int,
                fromUser: Boolean
            ) {
                textViewFilter.text = "$progress"
                // Show the progress when progress was less than 100
                //if (progress < 100) {
                //tv.text = "Progress : $progress"
                //}

                // If the progress is 100, take the user to another activity
                // Via Intent
                //if (progress == 100) {
                //}
            }
        })
    }

    override fun onStart() {
        super.onStart()

        if (!bleInitialized) {
            bleInitialized = true
            //Handler(Looper.getMainLooper()).postDelayed({ // késleltetett indítás
            bleManager = BLEManager.create(
                activity = this,
                logCallback = { runOnUiThread { appendLog(it) } },
                statusCallback = { runOnUiThread { textViewState.text = it } },
                onDataReceived = { uuid, data -> runOnUiThread { sensor.processData(uuid, data) } },
                onRssiUpdated = { rssi -> runOnUiThread { updateRssi(rssi) } },
                onDisconnect = { runOnUiThread { defaultDisplay() } }
            )

            logFile = LogFile.create(
                activity = this,
                logCallback = { runOnUiThread { appendLog(it) } },
                permissionOK = { runOnUiThread { buttonRecord.isEnabled = true } },
                onStart = { buttonRecord.text = "STOP" },
                onStop = { buttonRecord.text = "REC" },
                onError = { buttonRecord.isEnabled = false }
            )
            //}, 300)
        }
    }

    override fun onDestroy() {
        logFile.stop()
        bleManager.release()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_fft -> {
                /*if (logLayout.isVisible) */showFFTView()
                //sensor.control = sensor.control and ControlMode.RMS.inv() or ControlMode.FFT
                //bluetoothManager.sendControl(sensor.filter, sensor.control.toUShort())
                true
            }

            R.id.menu_main -> {
                /*if (logLayout.isVisible) */showMainView()
                //sensor.control = sensor.control and ControlMode.FFT.inv() or ControlMode.RMS
                //bluetoothManager.sendControl(sensor.filter, sensor.control.toUShort())
                true
            }

            R.id.menu_log -> {
                showLogView()
                true
            }

            R.id.menu_exit -> {
                bleManager.stopBLEService()
                //finishAndRemoveTask()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun defaultDisplay() {
        textViewState.text = "- - -"
        textViewRssi.text = "- - -"
        textViewBattery.text = "- - -"
        textViewRpm.text = "- - -"
        textViewRms.text = "- - -"
        switchXEN.isChecked = false
        switchYEN.isChecked = false
        switchZEN.isChecked = false
        switchXEN.isEnabled = false
        switchYEN.isEnabled = false
        switchZEN.isEnabled = false
        seekBarFilter.setProgress(0, true)
        seekBarFilter.isEnabled = false
        textViewFilter.text = "---"
        textViewRms0.text = "- - -"
        textViewRms1.text = "- - -"
        textViewRms2.text = "- - -"
        textViewRms3.text = "- - -"
        textViewRms4.text = "- - -"
        textViewRms5.text = "- - -"
        textViewRms6.text = "- - -"
        textViewRpm0.text = "- - -"
        textViewRpm1.text = "- - -"
        textViewRpm2.text = "- - -"
        textViewRpm3.text = "- - -"
        textViewRpm4.text = "- - -"
        textViewRpm5.text = "- - -"
        textViewRpm6.text = "- - -"

        //buttonRecord.isEnabled = false
        barChart.clear()
    }

    private fun updateRssi(rssi: Int) {
        val signalStrengthPercentage = rssiToPercentage(rssi)
        runOnUiThread { textViewRssi.text = "Signal: $signalStrengthPercentage %" }
    }

    private fun rssiToPercentage(rssi: Int): Int {
        val rssiMax = -30 // Maximális RSSI érték (nagyon erős jel)
        val rssiMin = -90 // Minimális RSSI érték (gyenge jel)

        // Korlátozzuk az RSSI értéket a minimális és maximális értékek közé
        val clampedRssi = rssi.coerceIn(rssiMin, rssiMax)

        // Százalékos érték kiszámítása
        return ((clampedRssi - rssiMin) / (rssiMax - rssiMin).toFloat() * 100).toInt()
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val strTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textViewLog.text = textViewLog.text.toString() + "$strTime $message\n"
            Handler().postDelayed({ scrollViewLog.fullScroll(View.FOCUS_DOWN) }, 16)
        }
    }

    fun onTapClearLog(view: View) {
        textViewLog.text = null
    }

    fun onTapRecord(view: View) {
        /*if(buttonRecord.text=="REC"){
            logFile.start()
        } else {
            logFile.stop()
        }*/
    }

    fun onTapXEN(view: View) {
        if (switchXEN.isChecked) {
            sensor.control = sensor.control or ControlMode.XEN
        } else {
            sensor.control = sensor.control and ControlMode.XEN.inv()
        }
        switchXEN.isEnabled = false
        switchXEN.setTextColor("#AAAAAA".toColorInt())
        sendControl()
    }

    fun onTapYEN(view: View) {
        if (switchYEN.isChecked) {
            sensor.control = sensor.control or ControlMode.YEN
        } else {
            sensor.control = sensor.control and ControlMode.YEN.inv()
        }
        switchYEN.isEnabled = false
        switchYEN.setTextColor("#AAAAAA".toColorInt())
        sendControl()
    }

    fun onTapZEN(view: View) {
        if (switchZEN.isChecked) {
            sensor.control = sensor.control or ControlMode.ZEN
        } else {
            sensor.control = sensor.control and ControlMode.ZEN.inv()
        }
        switchZEN.isEnabled = false
        switchZEN.setTextColor("#AAAAAA".toColorInt())
        sendControl()
    }

    fun onTapRms0(view: View) {
        actRpmIdx = 0
    }

    fun onTapRms1(view: View) {
        actRpmIdx = 1
    }

    fun onTapRms2(view: View) {
        actRpmIdx = 2
    }

    fun onTapRms3(view: View) {
        actRpmIdx = 3
    }

    fun onTapRms4(view: View) {
        actRpmIdx = 4
    }

    fun onTapRms5(view: View) {
        actRpmIdx = 5
    }

    fun onTapRms6(view: View) {
        actRpmIdx = 6
    }

    private fun sendControl() {
        bleManager.sendControl(sensor.filter, sensor.control.toUShort(), sensor.rpm)
    }

    private fun updateControls() {
        switchXEN.isEnabled = true
        switchXEN.isChecked = sensor.control and ControlMode.XEN == ControlMode.XEN
        if (switchXEN.isChecked) switchXEN.setTextColor("#FFFFFF".toColorInt())
        switchYEN.isEnabled = true
        switchYEN.isChecked = sensor.control and ControlMode.YEN == ControlMode.YEN
        if (switchYEN.isChecked) switchYEN.setTextColor("#FFFFFF".toColorInt())
        switchZEN.isEnabled = true
        switchZEN.isChecked = sensor.control and ControlMode.ZEN == ControlMode.ZEN
        if (switchZEN.isChecked) switchZEN.setTextColor("#FFFFFF".toColorInt())
        seekBarFilter.isEnabled = true
        val filter = sensor.filter * 100.0f
        seekBarFilter.setProgress(filter.toInt(), true)
        textViewFilter.text = filter.toInt().toString()//seekBarFilter.progress.toString()
        textViewRpm1.text = String.format("%.0f", sensor.rpm[1])
        textViewRpm2.text = String.format("%.0f", sensor.rpm[2])
        textViewRpm3.text = String.format("%.0f", sensor.rpm[3])
        textViewRpm4.text = String.format("%.0f", sensor.rpm[4])
        textViewRpm5.text = String.format("%.0f", sensor.rpm[5])
        textViewRpm6.text = String.format("%.0f", sensor.rpm[6])
    }

    private fun showFFTView() {
        if (fftLayout.visibility != View.VISIBLE) {
            mainLayout.visibility = View.GONE
            logLayout.visibility = View.GONE
            fftLayout.visibility = View.VISIBLE
        }
    }

    private fun showMainView() {
        if (mainLayout.visibility != View.VISIBLE) {
            fftLayout.visibility = View.GONE
            logLayout.visibility = View.GONE
            mainLayout.visibility = View.VISIBLE
        }
    }

    private fun showLogView() {
        if (logLayout.visibility != View.VISIBLE) {
            mainLayout.visibility = View.GONE
            fftLayout.visibility = View.GONE
            logLayout.visibility = View.VISIBLE
        }
    }

    private fun setupChart() {
        // Diagram konfigurálása
        barChart.description.isEnabled = false
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        //barChart.setPinchZoom(false)
        barChart.setDrawValueAboveBar(true)
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = false

        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        xAxis.textColor = Color.WHITE
        xAxis.textSize = 12.0f
        xAxis.valueFormatter = RPMValueFormatter()

        val leftAxis = barChart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f
        leftAxis.textColor = Color.WHITE
        leftAxis.textSize = 16.0f
        leftAxis.axisLineColor = Color.DKGRAY
        leftAxis.gridColor = Color.DKGRAY

        // Egyéni Marker hozzáadása
        //val marker = CustomMarker(this, R.layout.custom_marker)
        //barChart.marker = marker
    }

    private fun updateData() {
        textViewBattery.text = String.format("battery: %d %% ", sensor.battery)

        textViewRpm0.text = String.format("%.0f", sensor.rpm[0])

        textViewRms0.text = String.format("%.2f", sensor.rms[0])
        textViewRms1.text = String.format("%.2f", sensor.rms[1])
        textViewRms2.text = String.format("%.2f", sensor.rms[2])
        textViewRms3.text = String.format("%.2f", sensor.rms[3])
        textViewRms4.text = String.format("%.2f", sensor.rms[4])
        textViewRms5.text = String.format("%.2f", sensor.rms[5])
        textViewRms6.text = String.format("%.2f", sensor.rms[6])

        when (actRpmIdx) {
            0 -> {
                textViewName.text = textViewName0.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[0])
                textViewRms.text = String.format("%.2f", sensor.rms[0])
            }

            1 -> {
                textViewName.text = textViewName1.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[1])
                textViewRms.text = String.format("%.2f", sensor.rms[1])
            }

            2 -> {
                textViewName.text = textViewName2.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[2])
                textViewRms.text = String.format("%.2f", sensor.rms[2])
            }

            3 -> {
                textViewName.text = textViewName3.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[3])
                textViewRms.text = String.format("%.2f", sensor.rms[3])
            }

            4 -> {
                textViewName.text = textViewName4.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[4])
                textViewRms.text = String.format("%.2f", sensor.rms[4])
            }

            5 -> {
                textViewName.text = textViewName5.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[5])
                textViewRms.text = String.format("%.2f", sensor.rms[5])
            }

            6 -> {
                textViewName.text = textViewName6.text
                textViewRpm.text = String.format("%.0f", sensor.rpm[6])
                textViewRms.text = String.format("%.2f", sensor.rms[6])
            }
        }
    }

    private fun updateFFTData(newData: Map<Float, Float>) {
        // Frissítsd a meglévő adatokat az új adatokkal
        fftData.putAll(newData)

        // Konvertáld a Map-et BarEntry listává
        val entries = fftData.map { (freq, amp) -> BarEntry(freq, amp) }

        // Frissítsd a diagramot
        updateChart(entries)
    }

    private fun updateChart(entries: List<BarEntry>) {
        val dataSet = BarDataSet(entries, "FFT Amplitúdók").apply {
            color = Color.WHITE
            //color =Color.rgb(255, 102, 0)  // Narancs
            //valueTextColor = Color.WHITE
            //valueTextSize=10f
            setDrawValues(false) // Oszlopok értékeinek elrejtése
        }

        //val barData = BarData(dataSet)
        //barData.barWidth = 0.85f // Oszlopok szélességének beállítása (0.5f = 50%)

        barChart.data = BarData(dataSet)
        barChart.invalidate() // Diagram frissítése
    }
}

class RPMValueFormatter : ValueFormatter() {
    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        // Az érték 60-szorosának kiszámítása (RPM-ben)
        val rpm = value * 60
        return rpm.toInt().toString() // Egész számként jeleníti meg
    }
}



