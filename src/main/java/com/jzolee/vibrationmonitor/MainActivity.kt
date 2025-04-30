package com.jzolee.vibrationmonitor

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.work.Configuration
import androidx.work.WorkManager
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

@SuppressLint("MissingPermission", "DefaultLocale")
class MainActivity : AppCompatActivity() {
    private lateinit var bleManager: BLEManager
    private lateinit var sensor: Sensor
    private lateinit var logFile : LogFile

    private lateinit var textViewState: TextView
    private lateinit var textViewRms: TextView
    private lateinit var textViewMajorPeak: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewRssi: TextView
    private lateinit var textViewLog: TextView
    private lateinit var scrollViewLog: ScrollView
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var fftLayout: ConstraintLayout
    private lateinit var logLayout: ConstraintLayout
    private lateinit var barChart: BarChart
    private lateinit var switchXEN: Switch
    private lateinit var switchYEN: Switch
    private lateinit var switchZEN: Switch
    private lateinit var seekBarFilter: SeekBar
    private lateinit var buttonRecord: Button
    private lateinit var textViewFilter: TextView

    private val fftData = mutableMapOf<Float, Float>()  // Frekvencia -> Amplitúdó

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*WorkManager.initialize(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        )*/
        setContentView(R.layout.activity_main)

        textViewState = findViewById(R.id.textViewState)
        textViewRms = findViewById(R.id.textViewRms)
        textViewMajorPeak = findViewById(R.id.textViewMajorPeak)
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
        buttonRecord  = findViewById(R.id.buttonRecord)
        textViewFilter = findViewById(R.id.textViewFilter)

        logFile = LogFile.create(
            activity = this,
            logCallback = { runOnUiThread {  appendLog(it) } },
            permissionOK = {  runOnUiThread { buttonRecord.isEnabled = false } },
            permissionNOK = { runOnUiThread { buttonRecord.isEnabled = false } }
        )

        sensor = Sensor(
            logCallback = { runOnUiThread { appendLog(it) } },
            updateRms = { runOnUiThread { textViewRms.text = it } },
            updateBattery = { runOnUiThread { textViewBattery.text = it } },
            updateMajorPeak = { runOnUiThread { textViewMajorPeak.text = it } },
            updateFFT = { newFftData ->
                updateFFTData(newFftData)
                logFile.onDataReceived(newFftData)
                        },
            updateControls = {runOnUiThread { updateControls() }}
        )

        bleManager = BLEManager.create(
            activity = this,
            logCallback = { runOnUiThread { appendLog(it) }},
            statusCallback = { runOnUiThread { textViewState.text = it} },
            onDataReceived = { uuid, data -> runOnUiThread {sensor.processData(uuid, data)} },
            onRssiUpdated = { rssi -> runOnUiThread { updateRssi(rssi)}},
            onDisconnect = {runOnUiThread {
                defaultDisplay()
                BLEManager.instance?.reconnect()
            }}
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
                bleManager.sendControl(sensor.filter, sensor.control.toUShort())

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
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        logFile.stop()
        bleManager.release()
        super.onDestroy()
    }

    private fun defaultDisplay() {
        textViewState.text = "- - -"
        textViewRssi.text = "- - -"
        textViewBattery.text = "- - -"
        textViewMajorPeak.text = "- - -"
        textViewRms.text = "- - -"
        switchXEN.isChecked = false
        switchYEN.isChecked = false
        switchZEN.isChecked = false
        switchXEN.isEnabled = false
        switchYEN.isEnabled = false
        switchZEN.isEnabled = false
        seekBarFilter.setProgress(0,true)
        seekBarFilter.isEnabled = false
        textViewFilter.text = "---"
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
        if(buttonRecord.text=="REC"){
            logFile.start()
            buttonRecord.text="STOP"
            Toast.makeText(this,
                "Files will be saved to: ${logFile.getFileLocation()}",
                Toast.LENGTH_LONG).show()
        } else {
            logFile.stop()
            buttonRecord.text="REC"
            //sensorWrapper.initializeSensor()
        }
    }

    fun onTapXEN(view: View) {
        if (switchXEN.isChecked){
            sensor.control = sensor.control  or ControlMode.XEN
        } else{
            sensor.control = sensor.control  and ControlMode.XEN.inv()
        }
        switchXEN.isEnabled = false
        bleManager.sendControl(sensor.filter, sensor.control.toUShort())
    }

    fun onTapYEN(view: View) {
        if (switchYEN.isChecked){
            sensor.control = sensor.control  or ControlMode.YEN
        } else{
            sensor.control = sensor.control  and ControlMode.YEN.inv()
        }
        switchYEN.isEnabled = false
        bleManager.sendControl(sensor.filter, sensor.control.toUShort())
    }

    fun onTapZEN(view: View) {
        if (switchZEN.isChecked){
            sensor.control = sensor.control  or ControlMode.ZEN
        } else{
            sensor.control = sensor.control  and ControlMode.ZEN.inv()
        }
        switchZEN.isEnabled = false
        bleManager.sendControl(sensor.filter, sensor.control.toUShort())
    }

    private fun updateControls(){
        switchXEN.isEnabled = true
        switchXEN.isChecked = sensor.control and ControlMode.XEN == ControlMode.XEN
        switchYEN.isEnabled = true
        switchYEN.isChecked = sensor.control and ControlMode.YEN == ControlMode.YEN
        switchZEN.isEnabled = true
        switchZEN.isChecked = sensor.control and ControlMode.ZEN == ControlMode.ZEN
        seekBarFilter.isEnabled = true
        val filter = sensor.filter * 100.0f
        seekBarFilter.setProgress(filter.toInt(),true)
        textViewFilter.text = filter.toInt().toString()//seekBarFilter.progress.toString()

        /*if (logLayout.visibility != View.VISIBLE) {
            if (sensor.control and ControlMode.FFT == ControlMode.FFT) {
                showFFTView()
            }
            if (sensor.control and ControlMode.RMS == ControlMode.RMS) {
                showMainView()
            }
        }*/
        //appendLog("Control: ${sensor.controlWord.toString(2)}")
    }

    private fun showFFTView() {
        if (fftLayout.visibility != View.VISIBLE) {
            mainLayout.visibility = View.GONE
            logLayout.visibility = View.GONE
            fftLayout.visibility = View.VISIBLE
        }
        //sensor.controlWord = sensor.controlWord and ControlMode.RMS.inv() or ControlMode.FFT
        //bluetoothManager.sendControlWord(sensor.controlWord)
    }

    private fun showMainView() {
        if (mainLayout.visibility != View.VISIBLE) {
            fftLayout.visibility = View.GONE
            logLayout.visibility = View.GONE
            mainLayout.visibility = View.VISIBLE
        }
        //sensor.controlWord = sensor.controlWord and ControlMode.FFT.inv() or ControlMode.RMS
        //bluetoothManager.sendControlWord(sensor.controlWord)
    }

    private fun showLogView() {
        if (logLayout.visibility != View.VISIBLE) {
            mainLayout.visibility = View.GONE
            fftLayout.visibility = View.GONE
            logLayout.visibility = View.VISIBLE
        }
        //sensor.controlWord = sensor.controlWord and ControlMode.FFT.inv() or ControlMode.RMS
        //bluetoothManager.sendControlWord(sensor.controlWord)
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
        xAxis.textSize= 12.0f
        xAxis.valueFormatter = RPMValueFormatter()

        val leftAxis = barChart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f
        leftAxis.textColor = Color.WHITE
        leftAxis.textSize= 16.0f
        leftAxis.axisLineColor = Color.DKGRAY
        leftAxis.gridColor= Color.DKGRAY

        // Egyéni Marker hozzáadása
        //val marker = CustomMarker(this, R.layout.custom_marker)
        //barChart.marker = marker
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



