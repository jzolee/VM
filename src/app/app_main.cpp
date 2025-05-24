//
// XIAO nRF52840 Sense
// LSM6DS3TR-C

// https://enzolombardi.net/adding-ota-firmware-update-support-to-xiao-ble-boards-3db6f034c1fa

//https://amcvibro.com/publications/1-acceleration-velocity-and-displacement-of-vibrations/

//https://blog.prosig.com/2015/01/06/rms-of-time-history-and-fft-spectrum/
//https://amcvibro.com/publications/1-acceleration-velocity-and-displacement-of-vibrations/
//https://www.researchgate.net/publication/228579292_Acceleration_Velocity_and_Displacement_Measurements_in_Vibration_and_Shock_Testing

#include "Arduino.h"

#ifdef TAIL
#include "Adafruit_TinyUSB.h"
#endif

#if !defined(TAIL) && !defined(LOG_ON) && defined(GAGE)
#define DISABLE_SERIAL_HW
#endif

#include "shared/log.h"

#include <vector>

#include "arduinoFFT.h"

#include "globals.h"

#include "IMU.h"
#include "FIR.h"
#include "BLE_.h"
//#include "Flash.h"

//#include "settings.h"

//#include <nrf_nvic.h>
//#include <nrf_soc.h>

//#include "Adafruit_SPIFlash.h"

ArduinoFFT<float> myFFT = ArduinoFFT<float>(vReal, vImag, samples, sampling_frequency);

void disableUnusedPeripherals() {

#ifdef DISABLE_SERIAL_HW
    // UART letiltása
    NRF_UART0->TASKS_STOPRX = 1;
    NRF_UART0->TASKS_STOPTX = 1;
    NRF_UART0->ENABLE = 0;

    // USB letiltása (ha nem használod)
    NRF_USBD->ENABLE = 0;
#endif

    // I2C (TWI) letiltása
    NRF_TWIM0->ENABLE = 0;
    //NRF_TWIM1->ENABLE = 0;

    // SPI letiltása
    NRF_SPIM0->ENABLE = 0;
    NRF_SPIM1->ENABLE = 0;
    NRF_SPIM2->ENABLE = 0;

#ifndef ANALOG_OUT_ON
    // PWM letiltása
    NRF_PWM0->ENABLE = 0;
    NRF_PWM1->ENABLE = 0;
    NRF_PWM2->ENABLE = 0;
    NRF_PWM3->ENABLE = 0;
#endif
    // ADC letiltása
    //NRF_SAADC->ENABLE = 0;

    // GPIO-k letiltása () ///////////////analogread nem működik
    /*for (int i = 0; i < 32; i++) {
      pinMode(i, INPUT);
      nrf_gpio_cfg_default(i);  // Alapértelmezett állapotba állítás
    }*/

    // Timer-ek letiltása (ha nem használod őket)
    //NRF_TIMER0->TASKS_STOP = 1;
    //NRF_TIMER1->TASKS_STOP = 1;
    //NRF_TIMER2->TASKS_STOP = 1;
    //NRF_TIMER3->TASKS_STOP = 1;
    //NRF_TIMER4->TASKS_STOP = 1;
}

void enterSleepMode() {
    __WFI();  // Várakozás megszakításra (Wait For Interrupt)
    //__SEV();
    //__WFE();
    //__WFE();
}

void prepareTimeDomainAcceleration() {
    //int errz = 0;
    for (size_t i = 0; i < samples; ++i) {
        uint16_t ptr = imu::rawIdx + i;
        if (ptr >= samples) ptr -= samples;
        (settings.data.control & Control::XEN) ? accelXYZ[X][i] = (float)imu::rawXYZ[ptr][X] * accelMultiplier : accelXYZ[X][i] = 0.0f;
        (settings.data.control & Control::YEN) ? accelXYZ[Y][i] = (float)imu::rawXYZ[ptr][Y] * accelMultiplier : accelXYZ[Y][i] = 0.0f;
        (settings.data.control & Control::ZEN) ? accelXYZ[Z][i] = (float)imu::rawXYZ[ptr][Z] * accelMultiplier : accelXYZ[Z][i] = 0.0f;
        //if (accelXYZ[Z][i] < 5 && accelXYZ[X][i] != 0.0f) ++errz;
    }
    //if (errz) PRINTf("\t\tHIBÁS: %d db\n", errz);
}

void removeAccelerationDcOffset() {

    float dc[3] = { 0 };

    for (size_t i = 0; i < samples; ++i) {
        dc[X] += accelXYZ[X][i];
        dc[Y] += accelXYZ[Y][i];
        dc[Z] += accelXYZ[Z][i];
    }

    dc[X] *= samples_inv;
    dc[Y] *= samples_inv;
    dc[Z] *= samples_inv;

    for (size_t i = 0; i < samples; ++i) {
        accelXYZ[X][i] -= dc[X];
        accelXYZ[Y][i] -= dc[Y];
        accelXYZ[Z][i] -= dc[Z];
    }
}

size_t selectDominantAxisByEnergy() {

    float sum[3] = { 0 };

    for (size_t i = 0; i < samples; ++i) {
        sum[X] += abs(accelXYZ[X][i]);
        sum[Y] += abs(accelXYZ[Y][i]);
        sum[Z] += abs(accelXYZ[Z][i]);
    }

    size_t largest = X;
    if (sum[Y] >= sum[X] && sum[Y] >= sum[Z]) largest = Y;
    else if (sum[Z] >= sum[X] && sum[Z] >= sum[Y]) largest = Z;

    return largest;
}

void buildFftInputMagnitudeVector(size_t dominantAxis) {
    for (size_t i = 0; i < samples; ++i) {
        vReal[i] = sqrt(sq(accelXYZ[X][i]) + sq(accelXYZ[Y][i]) + sq(accelXYZ[Z][i]));
        // Change the sign of the real part of the signal if the significant axis of DC is negative
        if (accelXYZ[dominantAxis][i] < 0.0) vReal[i] = -vReal[i];
        // Set imaginary part to zero
        vImag[i] = 0.0;
    }
}

void filterFftSpectrumAndExtractPeaks(float beta) {

    float peakThreshold = bins * 0.01f;

    if (beta < 0.0f) beta = 0.0f;
    if (beta > 0.9f) beta = 0.9f;

    for (size_t i = 0; i < bins; ++i)
        fft_filtered[i] = (fft_filtered[i] - vReal[i]) * beta + vReal[i];

    std::vector<size_t> peaks;

    for (size_t i = 1; i < bins; ++i) {  // find peaks
        if (fft_filtered[i] > peakThreshold) {
            float prev_val, next_val;
            prev_val = fft_filtered[i - 1];
            next_val = i < bins - 1 ? fft_filtered[i + 1] : 0.0f;
            if (fft_filtered[i] > prev_val && fft_filtered[i] > next_val)
                peaks.push_back(i);
        }
    }

    float totalEnergy = 0.0f;
    float discardedEnergy = 0.0f;

    size_t peakIndex = 0;

    for (size_t i = 0; i < bins; ++i) {
        if (peaks.size() > 0) {
            if (i == peaks[peakIndex]) {
                accelSpectrumPeak[i] = fft_filtered[i];
                if (peakIndex < peaks.size() - 1) ++peakIndex;
            } else {
                accelSpectrumPeak[i] = 0.0f;
                discardedEnergy += fft_filtered[i];
            }
        } else
            accelSpectrumPeak[i] = fft_filtered[i];
        totalEnergy += fft_filtered[i];
    }

    float energyCompensationFactor = totalEnergy - discardedEnergy > 0.0f ? 1.0f + discardedEnergy / (totalEnergy - discardedEnergy) : 1.0f;

    for (size_t i = 0; i < bins; ++i) {
        if (accelSpectrumPeak[i] > 0.0f) {
            accelSpectrumPeak[i] *= energyCompensationFactor;
            //accelSpectrumPeak[i] *= 0.887;
            accelSpectrumPeak[i] /= bins;
        }
    }
}

void calculateVelocitySpectrumRms() {

    velocitySpectrumRms[0] = 0.0f;
    float max = 0.0f;
    dominantFrequencyHz = 0.0f;
    bzero(velocityBandRms, sizeof(velocityBandRms));

    for (size_t i = 1; i < bins; ++i) {
        if (accelSpectrumPeak[i] > 0.0) {
            velocitySpectrumRms[i] = accelSpectrumPeak[i] * invAngularFreq[i] * rmsScaleFactor;
            velocityBandRms[0] += sq(velocitySpectrumRms[i]);
            if (velocitySpectrumRms[i] > max) {
                max = velocitySpectrumRms[i];
                dominantFrequencyHz = frequency[i];
            }
            for (size_t j = 0; j < (sizeof(settings.data.freq) / sizeof(float)); ++j) {
                float loFreq = 0.95f * settings.data.freq[j];
                float hiFreq = 1.05f * settings.data.freq[j];
                if (frequency[i] >= loFreq && frequency[i] <= hiFreq)
                    velocityBandRms[j + 1] += sq(velocitySpectrumRms[i]);
            }
        } else {
            velocitySpectrumRms[i] = 0.0f;
        }
    }

    for (size_t i = 0; i < (sizeof(velocityBandRms) / sizeof(float)); ++i)
        velocityBandRms[i] = sqrt(velocityBandRms[i]);
}

void downsampleVelocitySpectrumRms() {
    bzero(velocityRmsDownsampled, sizeof(velocityRmsDownsampled));
    const float multiplier = (float)binsReduced / (float)bins;
    for (size_t i = 1; i < bins; ++i) {
        size_t j = i * multiplier + 0.5f;
        if (j > 0)
            velocityRmsDownsampled[j - 1] += sq(velocitySpectrumRms[i]);
    }
    for (size_t i = 0; i < binsReduced; ++i)
        velocityRmsDownsampled[i] = sqrt(velocityRmsDownsampled[i]);
}

void processAccelerationToVelocityRms() {
    prepareTimeDomainAcceleration();
    removeAccelerationDcOffset();
    buildFftInputMagnitudeVector(selectDominantAxisByEnergy());
    highPassFirFilter(vReal, vReal, samples);
    myFFT.dcRemoval();                               // Remove DC offset
    myFFT.windowing(FFT_WIN_TYP_HANN, FFT_FORWARD);  // Apply Hanning window
    myFFT.compute(FFT_FORWARD);                      // Compute FFT
    myFFT.complexToMagnitude();                      // Compute magnitudes
    filterFftSpectrumAndExtractPeaks(settings.data.filter);
    calculateVelocitySpectrumRms();
    downsampleVelocitySpectrumRms();
    if (velocityBandRms[0] < 0.0005f) dominantFrequencyHz = 0.0f;
}

void blink(uint32_t now) {
    static uint32_t last = now;
    static bool on = false;
    LED_BLUE_OFF();
    if (on) {
        if (now - last > 50) {
            on = false;
            last = now;
            LED_GREEN_OFF();
        }
    } else {
        if (now - last > 2000) {
            on = true;
            last = now;
            LED_GREEN_ON();
        }
    }
}

void update_output() {
#ifdef ANALOG_OUT_ON
    const float maxVal = 0.6f; // rms value for max output voltage

    const uint32_t outMax = (2.5f / 3.3f * 255.0f); // AnalogWrite output for 2.5V

    const float mul = 1.0f / maxVal * outMax;

    float val = velocityBandRms[3] * 39.3700787f; // in/s
    static float filtered = 0.0f;
    filtered = (filtered - val) * 0.8f + val;  //fft filter: 0.8 analog filter: 0.8 -> ~10.0 sec az állandósulás
    uint32_t out = (filtered * mul);
    out = out > outMax ? outMax : out;
    analogWrite(D2, out); //analogWrite(D1, outMax);//
#endif
}

void setup() {
    disableUnusedPeripherals();
    //LED_RED_INIT();
    LED_GREEN_INIT();
#ifdef GAGE
    LED_BLUE_INIT();
#endif
#ifdef ANALOG_OUT_ON
    analogWrite(D2, 0);
#endif
#ifdef TAIL
    Serial.begin(115200);  // Initialize serial communication at 115200 baud rate
#endif
#ifdef GAGE
    SERIAL_BEGIN(115200);  // Initialize serial communication at 115200 baud rate
#endif
    //Flash::reset();
    //Flash::wake(true);
    //////////////////// Flash::eraseChip(); /////////////////////////////////
    //Flash::eraseChip();
    //Flash::elront();
    ///////////////xsettings.Load();
    ///////////////PRINTLN(xsettings.slew_rate);
    //Flash::begin();
    //Flash::sleep();
    //xsettings.Save();
    //PRINTLN("after sleep.............");
    //Flash::wake();
    //delay(100);
    //Flash::sleep();
    settings.data.filter = 0.8f;
#ifdef GAGE
    settings.data.control = static_cast<Control>(XEN | YEN | ZEN);
#endif
#ifdef TAIL
    settings.data.control = static_cast<Control>(ZEN);
#endif
    settings.data.freq[0] = ((5500. * 22. / 56. * 98. / 66. * 7. / 41.) / 60.); //548;
    settings.data.freq[1] = ((5500. * 22. / 56. * 98. / 66. * 7. / 41. * 2.) / 60.); //1096;
    //settings.data.freq[2] = (900. / 60.); //teszt1:900rpm->0,4;
    //settings.data.freq[2] = (1800. / 60.); //teszt1:1800rpm->0,8;
    settings.data.freq[2] = ((5500. * 22. / 56. * 98. / 66. * 21. / 20.) / 60.); //3368;
    settings.data.freq[3] = ((5500. * 22. / 56. * 98. / 66.) / 60.); //3208;
    settings.data.freq[4] = ((5500. * 22. / 56.) / 60.); //2161;
    settings.data.freq[5] = (5500. / 60.);
#ifdef BLE_ON
    ble::begin();
#endif
    //#ifdef TAIL
    //    imu::begin();
    //#endif
}

void loop() {
    uint32_t now = millis();
    static uint32_t last_update = now;
    static uint32_t last_connection = now;

    switch (imu::state) {
    case UPDATE:
        imu::state = READY;
        last_update = now;
        LED_GREEN_OFF();
        if (!(imu::rawXYZ[511][0] == 0 && imu::rawXYZ[511][1] == 0 && imu::rawXYZ[511][2] == 0)) {
#ifdef GAGE
            LED_BLUE_ON();
#endif
#ifdef TAIL
            LED_GREEN_ON();
#endif
            processAccelerationToVelocityRms();
            ble::sendData();
            update_output();
        }
#ifdef GAGE
        LED_BLUE_OFF();
#endif
#ifdef TAIL
        LED_GREEN_OFF();
#endif
        break;

    case READY:
#ifdef GAGE
        if (now - last_connection > 20000) imu::end();
#endif
        if (now - last_update > 4000) imu::state = ERROR;
        break;

    case OFF:
        last_update = now;
#ifdef GAGE
        if (ble::state == CONNECTED) imu::begin();
        else enterSleepMode();
#endif
#ifdef TAIL
        delay(100);
        imu::begin();
#endif
        break;

    case ERROR:
    default:
        imu::end();
        //NVIC_SystemReset();  // Soft reset the microcontroller
        PRINTLN("IMU ERROR");
    }

#ifdef GAGE
    if (ble::state == CONNECTED) last_connection = now;
    if (ble::state != CONNECTED) blink(now);
#endif
}
