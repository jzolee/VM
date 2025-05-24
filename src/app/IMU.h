#pragma once

#include "Arduino.h"

#include "globals.h"

#include "Wire.h"

/****************** Device ID **************/
#define LSM6DS3_WHO_AM_I  			    0X69
#define LSM6DS3_C_WHO_AM_I              0x6A

/************** Device Register  ***********/
#define LSM6DS3_FIFO_CTRL1  			0X06
#define LSM6DS3_FIFO_CTRL2  			0X07
#define LSM6DS3_FIFO_CTRL3  			0X08
#define LSM6DS3_FIFO_CTRL4  			0X09
#define LSM6DS3_FIFO_CTRL5  			0X0A
#define LSM6DS3_INT1_CTRL               0X0D
#define LSM6DS3_WHO_AM_I_REG  			0X0F
#define LSM6DS3_CTRL1_XL  			    0X10
#define LSM6DS3_CTRL3_C  			    0X12
#define LSM6DS3_STATUS_REG  			0X1E
#define LSM6DS3_OUTX_L_XL  			    0X28
#define LSM6DS3_FIFO_STATUS1  			0X3A
#define LSM6DS3_FIFO_DATA_OUT_L  		0X3E

/************** Register  bits *************/
#define LSM6DS3_XLDA_DATA_AVAIL 		0x01
#define LSM6DS3_FIFO_IS_EMPTY         0x1000
#define LSM6DS3_FIFO_WATERMARK        0x8000
#define LSM6DS3_FIFO_LEVEL_MASK 0b11111111111
#define LSM6DS3_INT1_FTH          0b00001000

#define Wire Wire1

class LSM6DS3 {
public:
    LSM6DS3(uint8_t address) : I2CAddress(address) {}
    ~LSM6DS3() = default;
    bool begin(void);
    void end();
    bool readRegister(uint8_t* outputPtr, const uint8_t offset, const size_t length);
    bool readRegister(uint8_t* outputPtr, const uint8_t offset);
    bool writeRegister(const uint8_t offset, const uint8_t data);
private:
    uint8_t I2CAddress;
};

bool LSM6DS3::begin(void) {
    pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
    NRF_P1->PIN_CNF[8] =
        ((uint32_t)NRF_GPIO_PIN_DIR_OUTPUT << GPIO_PIN_CNF_DIR_Pos) |
        ((uint32_t)NRF_GPIO_PIN_INPUT_DISCONNECT << GPIO_PIN_CNF_INPUT_Pos) |
        ((uint32_t)NRF_GPIO_PIN_NOPULL << GPIO_PIN_CNF_PULL_Pos) |
        ((uint32_t)NRF_GPIO_PIN_H0H1 << GPIO_PIN_CNF_DRIVE_Pos) |
        ((uint32_t)NRF_GPIO_PIN_NOSENSE << GPIO_PIN_CNF_SENSE_Pos);
    digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);
    vTaskDelay(ms2tick(50));//delay(50);
    Wire.begin();
    Wire.setClock(400000);
    vTaskDelay(ms2tick(50)); //volatile uint8_t temp = 0; for (uint16_t i = 0; i < 10000; i++) temp++; //Spin for a few ms
    bool success = true;
    uint8_t readCheck;
    readRegister(&readCheck, LSM6DS3_WHO_AM_I_REG);
    if (!(readCheck == LSM6DS3_WHO_AM_I || readCheck == LSM6DS3_C_WHO_AM_I))
        success = false;
    return success;
}

void LSM6DS3::end() {
    Wire.end();
    digitalWrite(PIN_LSM6DS3TR_C_POWER, LOW);
    NRF_P1->PIN_CNF[8] =
        ((uint32_t)NRF_GPIO_PIN_DIR_INPUT << GPIO_PIN_CNF_DIR_Pos) |
        ((uint32_t)NRF_GPIO_PIN_INPUT_DISCONNECT << GPIO_PIN_CNF_INPUT_Pos) |
        ((uint32_t)NRF_GPIO_PIN_NOPULL << GPIO_PIN_CNF_PULL_Pos) |
        ((uint32_t)NRF_GPIO_PIN_S0S1 << GPIO_PIN_CNF_DRIVE_Pos) |
        ((uint32_t)NRF_GPIO_PIN_NOSENSE << GPIO_PIN_CNF_SENSE_Pos);
}

bool LSM6DS3::readRegister(uint8_t* outputPtr, const uint8_t offset, const size_t length) {
    bool success = true;
    Wire.beginTransmission(I2CAddress);
    Wire.write(offset);
    if (Wire.endTransmission(true) != 0) //if (Wire.endTransmission() != 0)
        success = false;
    else {
        Wire.requestFrom(I2CAddress, length, true); //Wire.requestFrom(I2CAddress, length);
        uint8_t i = 0, c = 0;
        while (Wire.available()) {
            c = Wire.read();
            if (i < length) {
                *outputPtr++ = c;
                ++i;
            } else
                success = false;
        }
    }
    return success;
}

bool LSM6DS3::readRegister(uint8_t* outputPtr, const uint8_t offset) {
    bool success = true;
    uint8_t result = 0;
    Wire.beginTransmission(I2CAddress);
    Wire.write(offset);
    if (Wire.endTransmission(true) != 0)   //if (Wire.endTransmission() != 0)
        success = false;
    Wire.requestFrom(I2CAddress, 1, true);     //Wire.requestFrom(I2CAddress, 1);
    if (Wire.available() != 1)
        success = false;
    while (Wire.available())
        result = Wire.read();
    *outputPtr = result;
    return success;
}

bool LSM6DS3::writeRegister(const uint8_t offset, const uint8_t data) {
    Wire.beginTransmission(I2CAddress);
    Wire.write(offset);
    Wire.write(data);
    return Wire.endTransmission(true) == 0;  //return Wire.endTransmission() == 0;
}

////////////////////////////////////////////////////////////////////////////////////////////////////////

namespace imu {

    LSM6DS3 imu(0x6A);    //I2C device address 0x6A

    //using CallbackPtr = void(*)(state_t);

    //CallbackPtr eventCallback = NULL;

    volatile state_t state = OFF;
    //volatile uint32_t lastUpdate = 0;

    int16_t rawXYZ[samples][3] = { 0 }; // raw acceleration data from accelerometer
    uint16_t rawIdx = 0;

    uint8_t getStatus() {
        uint8_t status;
        imu.readRegister(&status, LSM6DS3_STATUS_REG);
        return status;
    }

    uint32_t getFifoStatus() {
        union {
            uint8_t buf[4];
            uint32_t status;
        };
        imu.readRegister(buf, LSM6DS3_FIFO_STATUS1, 4);
        return status;
    }

    uint16_t getFifoLevel() { return (getFifoStatus() & LSM6DS3_FIFO_LEVEL_MASK); }

    bool isFifoWatermark() { return (getFifoStatus() & LSM6DS3_FIFO_WATERMARK); }

    bool clearFifo(void) {
        bool success = true;
        if (!imu.writeRegister(LSM6DS3_FIFO_CTRL5, 0b00101000)) success = false; // FIFO ODR = 208, FIFO mode = Bypass
        if (!imu.writeRegister(LSM6DS3_FIFO_CTRL5, 0b00101110)) success = false; // FIFO ODR = 208, FIFO mode = continous
        //if (!imu.writeRegister(LSM6DS3_FIFO_CTRL5, 0b00101001)) success = false; // FIFO ODR = 208, FIFO mode = FIFO
        return success;
    }

    void readAccel() {
        union {
            uint8_t buf[6];
            int16_t accel[3];
        };
        for (size_t i = 0; i < samples; ++i) {
            while ((getStatus() & LSM6DS3_XLDA_DATA_AVAIL) == 0) {}
            imu.readRegister(buf, LSM6DS3_OUTX_L_XL, 6);
            accelXYZ[X][i] = accel[0];
            accelXYZ[Y][i] = accel[1];
            accelXYZ[Z][i] = accel[2];
        }
    }

    bool readFifo() {
        bool success = true;
        typedef int16_t axis_t[3];
        union {
            uint8_t buf[192]; // 32  *6
            axis_t axis[32];
        };
        if (imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000000) == false) // Accelerometer sensor not in FIFO
            success = false;
        else
            for (size_t sample = 0; sample < samples; sample += 32)
                if (imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, 192) == false)
                    success = false;
                else
                    for (size_t i = 0; i < 32; ++i)
                        for (size_t j = 0; j < 3; ++j)
                            accelXYZ[j][sample + i] = axis[i][j];
        if (imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000001) == false) // Accelerometer sensor in FIFO
            success = false;
        if (clearFifo() == false)
            success = false;
        return success;
    }

    bool readFifo2() {
        bool success = true;
        const uint16_t size = 512;
        typedef int16_t axis_t[3];
        union {
            uint8_t buf[size * 6];
            axis_t axis[size];
        };
        if (imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000000) == false) // Accelerometer sensor not in FIFO
            success = false;
        else
            if (imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, sizeof(buf)) == false)
                success = false;
            else
                for (size_t i = 0; i < 512; ++i)
                    for (size_t j = 0; j < 3; ++j)
                        accelXYZ[j][i] = axis[i][j];
        if (imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000001) == false) // Accelerometer sensor in FIFO
            success = false;
        if (clearFifo() == false)
            success = false;
        return success;
    }

    bool readFifo3(const uint16_t size = 512) {
        bool success = true;
        const uint16_t bufSize = size * 6;
        uint8_t* buf = new uint8_t[bufSize];
        typedef int16_t accel_data_t[3];
        accel_data_t* accel = reinterpret_cast<accel_data_t*>(buf);
        if (!imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000000))  success = false; // Accelerometer sensor not in FIFO
        if (!imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, bufSize)) success = false;
        if (!imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000001)) success = false; // Accelerometer sensor in FIFO
        if (!clearFifo()) success = false;
        if (success) for (size_t i = 0; i < size; ++i) for (size_t j = 0; j < 3; ++j) accelXYZ[j][i] = accel[i][j];
        delete[] buf;
        return success;
    }

    bool readFifo4(const uint16_t size = 512) {
        bool success = true;
        const uint16_t bufSize = size * 6;
        uint8_t* buf = new uint8_t[bufSize];
        typedef int16_t accel_data_t[3];
        accel_data_t* accel = reinterpret_cast<accel_data_t*>(buf);
        if (!imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, bufSize)) success = false;
        if (success) {
            size_t legacy = samples - size;
            for (size_t i = 0; i < legacy; ++i) { //shift << size
                rawXYZ[i][X] = rawXYZ[i + size][X];
                rawXYZ[i][Y] = rawXYZ[i + size][Y];
                rawXYZ[i][Z] = rawXYZ[i + size][Z];
            }
            for (size_t i = 0; i < size; ++i) {
                rawXYZ[i + legacy][X] = accel[i][X];
                rawXYZ[i + legacy][Y] = accel[i][Y];
                rawXYZ[i + legacy][Z] = accel[i][Z];
            }
        }
        delete[] buf;
        return success;
    }

    bool readFifo5(const uint16_t size = 512) {
        bool success = true;
        const size_t bufSize = size * 6;
        uint8_t* buf = new uint8_t[bufSize];
        if (imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, bufSize)) {
            size_t remain = (samples - size) * 6; // maradó adatok száma
            uint8_t* ptr = (uint8_t*)rawXYZ + bufSize; //pointer a maradó adatok elejére a bufferben
            if (remain) memmove(rawXYZ, ptr, remain); // maradó adatok eltolása a buffer elejére
            ptr = (uint8_t*)rawXYZ + remain; // pointer a maradó adatok végére
            memcpy(ptr, buf, bufSize); // új adatok másolása a buffer végére
        } else success = false;
        delete[] buf;
        state = success ? READY : ERROR;
        //if (eventCallback) eventCallback(state);
        return success;
    }

    bool readFifo55() {
        bool success = true;
        uint16_t fifoSamples = 104 * 3;
        do {
            uint16_t len = fifoSamples * 2;
            uint16_t buf_ptr = rawIdx * 3 * 2;
            uint16_t len1 = sizeof(rawXYZ) - buf_ptr;
            if (len1 < len) {
                if (!imu.readRegister((uint8_t*)rawXYZ + buf_ptr, LSM6DS3_FIFO_DATA_OUT_L, len1)) success = false;
                uint16_t len2 = len - len1;
                if (!imu.readRegister((uint8_t*)rawXYZ, LSM6DS3_FIFO_DATA_OUT_L, len2)) success = false;
                rawIdx = len2 / 6;
            } else {
                if (!imu.readRegister((uint8_t*)rawXYZ + buf_ptr, LSM6DS3_FIFO_DATA_OUT_L, len)) success = false;
                rawIdx += len / 6;
            }
            if (rawIdx >= samples)rawIdx -= samples;

            fifoSamples = getFifoLevel();

        } while (fifoSamples >= 3);

        if (fifoSamples) {
            uint8_t buf[6];
            if (!imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, fifoSamples * 2)) success = false;
            PRINTLN(fifoSamples);
        }

        clearFifo();

        //uint16_t remainder = fifoSamples % 3;
        //if (remainder) { success = false; }

        state = success ? READY : ERROR;
        //if (eventCallback) eventCallback(state);

        //if (fifoSamples) {
        //PRINT(rawPtr);

        //PRINT(fifoSamples);        PRINT("\t");
        //PRINT("   ");
        //PRINTLN(remainder);
    //}

        return success;
    }

    bool readFifo6() {
        bool success = true;
        //if (!imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000000)) success = false; // Accelerometer sensor not in FIFO
        if (!imu.readRegister((uint8_t*)rawXYZ, LSM6DS3_FIFO_DATA_OUT_L, sizeof(rawXYZ))) success = false;
        //if (!imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000001)) success = false; // Accelerometer sensor in FIFO
        //if (!clearFifo()) success = false;
        state = success ? READY : ERROR;
        //if (eventCallback) eventCallback(state);
        return success;
    }

    bool readFifo7() {
        bool success = true;

        const uint16_t buf_ptr = rawIdx * 3 * 2; // buffer bájtpozíció
        const uint16_t len = 104 * 3 * 2; // bufferbe írandó bájtok
        const uint16_t len1 = sizeof(rawXYZ) - buf_ptr; // a buffer végéig beírható bájtok
        const uint16_t len2 = (len1 < len) ? len - len1 : 0; // a buffer elejére írandó bájtok

        static uint8_t buf[512 * 3 * 2];

        if (!imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, len)) success = false;

        if (len2) {
            memcpy((uint8_t*)rawXYZ + buf_ptr, buf, len1);
            memcpy((uint8_t*)rawXYZ, buf + len1, len2);
            rawIdx = len2 / 6;
        } else {
            memcpy((uint8_t*)rawXYZ + buf_ptr, buf, len);
            rawIdx += len / 6;
        }
        if (rawIdx >= samples)rawIdx -= samples;

        state = success ? READY : ERROR;
        //if (eventCallback) eventCallback(state);
        return success;
    }

    bool readFifo8() { // polling interrupt pin
        bool success = true;
        static uint8_t buf[104 * 3 * 2];
        //while (digitalRead(PIN_LSM6DS3TR_C_INT1) == LOW);
        if (!imu.readRegister(buf, LSM6DS3_FIFO_DATA_OUT_L, sizeof(buf))) success = false;
        const uint16_t rawXYZptr = rawIdx * 3 * 2; // buffer bájtpozíció
        const uint16_t len1 = sizeof(rawXYZ) - rawXYZptr; // a buffer végéig beírható bájtok
        const uint16_t len2 = (len1 < sizeof(buf)) ? sizeof(buf) - len1 : 0; // a buffer elejére írandó bájtok
        if (len2) {
            memcpy((uint8_t*)rawXYZ + rawXYZptr, buf, len1);
            memcpy((uint8_t*)rawXYZ, buf + len1, len2);
            rawIdx = len2 / 6;
        } else {
            memcpy((uint8_t*)rawXYZ + rawXYZptr, buf, sizeof(buf));
            rawIdx += sizeof(buf) / 6;
        }
        if (rawIdx >= samples) rawIdx -= samples;
        state = success ? READY : ERROR;
        //if (eventCallback) eventCallback(state);
        return success;
    }

    void interrupt() {
        readFifo8();
        //lastUpdate = millis();
        state = UPDATE;
        //if (eventCallback) eventCallback(state);
        //state = READY;
    }

    bool begin() {
        bool success = true;
        if (imu.begin()) {

            // interrupt
            attachInterrupt(digitalPinToInterrupt(PIN_LSM6DS3TR_C_INT1), interrupt, RISING);
            pinMode(PIN_LSM6DS3TR_C_INT1, INPUT);
            NVIC_SetPriority(GPIOTE_IRQn, 1);

            // Block Data Update: output registers not updated until MSB and LSB have been read
            if (!imu.writeRegister(LSM6DS3_CTRL3_C, 0b01000100)) success = false; // BDU = 1, I2C disable, SW reset = 0, SPI mode = 0, IF_INC = 1

            /*
                ODR_XL = 208 Hz → 0101
                FS_XL = ±16g → 01
                Bandwidth = ODR/2

                ODR_XL [3:0] | FS_XL [1:0] | LPF1_BW_SEL | BW0_XL
                     0101    |     01      |      0      |   0
            */
            if (!imu.writeRegister(LSM6DS3_CTRL1_XL, 0b01010100)) success = false; // ODR_XL = 208 Hz, FS_XL = ±16g, BW0_XL = 0, LPF1_BW_SEL = 0

            // FIFO section
            if (!imu.writeRegister(LSM6DS3_FIFO_CTRL1, 0b00000000)) success = false; // FIFO watermark 512*3
            if (!imu.writeRegister(LSM6DS3_FIFO_CTRL2, 0b00000110)) success = false; // FIFO watermark
            if (!imu.writeRegister(LSM6DS3_FIFO_CTRL3, 0b00000001)) success = false; // FIFO no decimation
            if (!imu.writeRegister(LSM6DS3_FIFO_CTRL4, 0b10000000)) success = false; // STOP_ON_FTH FIFO depth is limited to threshold level
            if (!clearFifo()) success = false; // FIFO ODR = 208, FIFO mode = continous
            if (!imu.writeRegister(LSM6DS3_INT1_CTRL, LSM6DS3_INT1_FTH)) success = false; // enable FIFO threshold interrupt on INT1 pad

        } else success = false;

        bzero(fft_filtered, sizeof(fft_filtered));
        bzero(rawXYZ, sizeof(rawXYZ));
        rawIdx = 0;

        state = success ? READY : ERROR;
        return success;
    }

    void end() {
        detachInterrupt(digitalPinToInterrupt(PIN_LSM6DS3TR_C_INT1));
        imu.end();
        vTaskDelay(ms2tick(50));//delay(50);
        state = OFF;
        //if (eventCallback) eventCallback(state);
    }
}