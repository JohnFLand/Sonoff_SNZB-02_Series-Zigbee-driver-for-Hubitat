/* groovylint-disable CompileStatic, LineLength, NoDef, VariableTypeRequired */
/**
 *  Sonoff SNZB-02 Series Temperature and Humidity Sensor — unified Hubitat driver
 *
 *  One driver for the Sonoff SNZB-02P, SNZB-02D, SNZB-02DR2 and SNZB-02M Zigbee sensors.
 *  The model is detected from the device's model string (or set with the "Device model"
 *  preference), and model-specific features are switched on accordingly:
 *
 *    All models   Temperature, humidity and battery reporting; Hubitat-side offsets;
 *                 °C/°F temperature settings; temperature decimal places (half-up rounding);
 *                 dew point and VPD; Hubitat-tracked min/max with timestamps and daily
 *                 auto-reset; healthStatus and sensorStatus
 *    SNZB-02DR2   Comfort thresholds and LCD temperature unit written to the device (FC11,
 *                 manufacturer code 0x1286); device-native 24-hour min/max; readings sent
 *                 from Hubitat shown on the display's EXT line (Set Remote Temperature /
 *                 Set Remote Humidity, see "Remote display" below)
 *    SNZB-02M    Barometric pressure (0.1 hPa, selectable unit);
 *                 comfort thresholds and calibration read back from the device (FC11)
 *
 *  The Pressure Measurement capability is declared for every model because Hubitat
 *  capabilities cannot vary by device. Only the SNZB-02M ever reports a pressure value.
 *
 *  NOTE: These are sleepy end-devices. Press the sensor's button immediately before clicking
 *  Save Preferences, Configure, Refresh or Initialize, otherwise the commands will not reach it.
 *  With description text logging on, the log shows whether the sensor accepted each change.
 *
 *  Remote display (SNZB-02DR2): Set Remote Temperature and Set Remote Humidity send a reading from
 *  Hubitat (e.g. from a Rule Machine rule when another sensor changes) to the EXT line of the display.
 *  Temperatures are entered in the hub's temperature scale; the sensor converts to its LCD unit.
 *  Firmware before 1.0.5 has one slot (EXT1); remote humidity needs 1.0.4 or later. Firmware 1.0.5
 *  and later has two slots (EXT1, EXT2). A value is sent at once and, until the sensor confirms it,
 *  again whenever the sensor next wakes up. The display redraws the EXT line when its button is
 *  pressed briefly or at its own update, about every 30 minutes. The EXT line cannot show 100 or
 *  more in the LCD's unit (it shows "HHH"), so higher temperatures are sent as 99.9.
 *
 *  Offsets in the sensor (SNZB-02DR2): with "Apply offsets in the sensor" on, the temperature and
 *  humidity offsets are written to the sensor's calibration attributes, so its screen and Hubitat
 *  agree. Hubitat adds only the part of the offset the sensor has not confirmed applying.
 *
 *  Licensed under the Apache License, Version 2.0
 *
 *  Changelog:
 *    1.2.0  2026-11-17  SNZB-02DR2: new preference "Apply offsets in the sensor" writes the
 *                       temperature and humidity offsets to FC11 0x2003 / 0x2004 (°C × 100,
 *                       % × 100), reads them back on every Save and Refresh, and adds in Hubitat
 *                       only the difference between the offset and the calibration the sensor
 *                       reports. Turning it off writes 0 back to the sensor. Confirmed on
 *                       firmware 1.0.2: the sensor's screen and its Zigbee readings both include
 *                       the calibration.
 *                       temperatureCalibration (SNZB-02DR2 and SNZB-02M) now follows the hub's
 *                       temperature scale (0.1 in °F, 0.01 in °C); it was always °C.
 *                       Remote display: temperatures of 100 or more in the LCD's unit, which the
 *                       screen shows as "HHH" (confirmed with 105 °F), are sent as 99.9.
 *    1.1.0  2026-11-16  Remote display for the SNZB-02DR2: commands Set Remote Temperature,
 *                       Set Remote Humidity and Remote Display Off; attributes remoteDisplay and
 *                       ext1/ext2 Temperature/Humidity; preference "Remote display method".
 *                       Two methods, chosen by firmware (Firmware MT / Software Build data):
 *                       before 1.0.5, FC11 0x600E = 1 plus 0x600D (°C × 100) and 0x6018 (% × 100),
 *                       confirmed working on firmware 1.0.2 (temperature); 1.0.5 and later,
 *                       0x600E = 1 plus the 0x601E remote-source array (as in Zigbee2MQTT).
 *                       On a °F screen the value is rounded so the sensor's own conversion shows the
 *                       tenth entered (it cuts digits off: 70 °F sent as 21.11 °C showed 69.9).
 *                       The Identify message the sensor sends when its button is pressed is now
 *                       recognised instead of being logged as an unhandled cluster.
 *    1.0.0 2026-11-07  First release. Merges the separate drivers SNZB-02x 1.5.0, SNZB-02DR2
 *                       1.13.0 and SNZB-02M 2.4.0. Differences from those drivers:
 *                       - Comfort attributes are numbers on every model: comfortTempMin/Max and
 *                         comfortHumidityMin/Max (the SNZB-02DR2 driver used text values and
 *                         comfortHumiMin/Max).
 *                       - Dew point and VPD are calculated for every model (can be turned off).
 *                       - The Configure command, sanity checks on raw readings and logging of
 *                         write responses apply to every model.
 *                       - temperatureCalibration and humidityCalibration are published whenever
 *                         the sensor sends them.
 *                       - Humidity is shown in whole percent on every model (the SNZB-02M driver
 *                         showed 0.1 %; the sensors are accurate only to a few percent and report
 *                         in steps of at least 1 %).
 *                       - Default values on the preferences page follow the selected temperature
 *                         unit (the separate drivers showed °C defaults even when °F was selected).
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

@Field static final String VERSION    = '1.2.0'
@Field static final String TIME_STAMP = '2026-11-17'

// ── Clusters ─────────────────────────────────────────────────────────────────
@Field static final Integer CLUSTER_BASIC     = 0x0000
@Field static final Integer CLUSTER_POWER_CFG = 0x0001
@Field static final Integer CLUSTER_IDENTIFY  = 0x0003   // the SNZB-02DR2 sends Identify when its button is pressed
@Field static final Integer CLUSTER_TEMP      = 0x0402
@Field static final Integer CLUSTER_PRESSURE  = 0x0403
@Field static final Integer CLUSTER_HUMIDITY  = 0x0405
@Field static final Integer CLUSTER_FC11      = 0xFC11   // Sonoff private cluster

@Field static final Integer PRESSURE_ATTR         = 0x0000  // INT16, whole hPa
@Field static final Integer PRESSURE_ATTR_PRECISE = 0x0004  // INT32, 0.01 hPa (SNZB-02M)

// ── FC11 attributes (int, not Integer, so if/else comparisons work reliably in Groovy) ──
@Field static final int FC11_COMFORT_TMAX = 0x0003  // INT16,  °C × 100
@Field static final int FC11_COMFORT_TMIN = 0x0004  // INT16,  °C × 100
@Field static final int FC11_COMFORT_HMIN = 0x0005  // UINT16, % × 100
@Field static final int FC11_COMFORT_HMAX = 0x0006  // UINT16, % × 100
@Field static final int FC11_TEMP_UNIT    = 0x0007  // UINT16, 0 = °C, 1 = °F (SNZB-02DR2 LCD)
@Field static final int FC11_TEMP_CAL     = 0x2003  // INT16,  °C × 100
@Field static final int FC11_HUMI_CAL     = 0x2004  // INT16,  % × 100
@Field static final int FC11_PRESS_CAL    = 0x2007  // INT16,  hPa × 100 (SNZB-02M)
@Field static final int FC11_MAX_TEMP     = 0x2008  // INT16,  °C × 100 (SNZB-02DR2 24-hour max)
@Field static final int FC11_MIN_TEMP     = 0x2009  // INT16,  °C × 100 (SNZB-02DR2 24-hour min)
@Field static final int FC11_PREV_TEMP    = 0x200A  // INT16,  reference temperature (meaning unknown)
@Field static final int FC11_MAX_HUMI     = 0x200B  // UINT16, % × 100 (SNZB-02DR2 24-hour max)
@Field static final int FC11_MIN_HUMI     = 0x200C  // UINT16, % × 100 (SNZB-02DR2 24-hour min)
@Field static final int FC11_PREV_HUMI    = 0x200D  // UINT16, reference humidity (meaning unknown)

// SNZB-02DR2 remote display (EXT line). Written without a manufacturer code, as Zigbee2MQTT does
// (accepted that way on firmware 1.0.2).
@Field static final int FC11_REMOTE_SELECT = 0x600E  // UINT8. Before 1.0.5: 0 = built-in, 1 = external.
                                                     //        1.0.5+: 0 = unbound, 1 = using, 2 = offline
@Field static final int FC11_EXT_TEMP      = 0x600D  // INT16,  °C × 100 (before 1.0.5)
@Field static final int FC11_EXT_HUMI      = 0x6018  // UINT16, % × 100 (before 1.0.5; needs 1.0.4)
@Field static final int FC11_REMOTE_ITEMS  = 0x601E  // ARRAY of UINT8, remote-source items (1.0.5+)
@Field static final int FW_REMOTE_SOURCES  = 0x1005  // firmware file version 1.0.5
@Field static final Map REMOTE_RANGE = [legacyT: [-50.0, 125.0], legacyH: [0.0, 100.0],
                                        sourcesT: [-20.0, 60.0], sourcesH: [0.0, 99.9]]
@Field static final int REMOTE_MAX_ATTEMPTS = 10     // automatic re-sends before giving up
@Field static final BigDecimal LCD_EXT_MAX = 99.9    // the EXT line shows "HHH" at 100 or more (in the LCD's unit)

// The SNZB-02DR2 needs Sonoff's manufacturer code on FC11; the SNZB-02M is read without it
@Field static final Map FC11_MFG_DR2 = [mfgCode: '0x1286']

// Data types tried, in order, when writing the SNZB-02DR2 LCD unit (0x0007): UINT16, ENUM8,
// BOOLEAN, UINT8. UINT16 is the type the device declares and accepts (firmware 1.0.2); the
// others are fallbacks in case other firmware answers 0x8D (invalid data type).
@Field static final List<Integer> LCD_UNIT_TYPES = [0x21, 0x30, 0x10, 0x20]

// ── Models ───────────────────────────────────────────────────────────────────
@Field static final Map MODEL_NAMES = [BASIC: 'SNZB-02P / SNZB-02D', DR2: 'SNZB-02DR2', M: 'SNZB-02M']

// ── Sanity bounds for RAW readings (reject corrupt or partial frames) ────────
@Field static final double TEMP_MIN_C    =  -40.0
@Field static final double TEMP_MAX_C    =  125.0
@Field static final double HUMI_MIN_PCT  =    0.0
@Field static final double HUMI_MAX_PCT  =  100.0
@Field static final double PRESS_MIN_HPA =  300.0
@Field static final double PRESS_MAX_HPA = 1100.0

// ── Ranges for non-temperature decimal preferences (enforced in code) ────────
@Field static final double OFFSET_MIN       = -20.0   // humidity offset, %
@Field static final double OFFSET_MAX       =  20.0
@Field static final double PRESS_OFFSET_MIN = -50.0   // pressure offset, hPa
@Field static final double PRESS_OFFSET_MAX =  50.0

// Temperature preferences are entered in the unit chosen by tempPrefUnit and used internally in °C.
// ABSOLUTE = true for actual temperatures (°F = °C × 1.8 + 32), false for differences (°F = °C × 1.8).
@Field static final Map TEMP_PREF_ABSOLUTE  = [tempChange: false, temperatureOffset: false,
                                               pref_comfortTempMin: true, pref_comfortTempMax: true]
@Field static final Map TEMP_PREF_DEFAULT_C = [tempChange: 0.2, temperatureOffset: 0.0,
                                               pref_comfortTempMin: 18.0, pref_comfortTempMax: 26.0]
@Field static final Map TEMP_PREF_RANGE_C   = [tempChange: [0.1, 5.0], temperatureOffset: [-20.0, 20.0],
                                               pref_comfortTempMin: [-10.0, 60.0], pref_comfortTempMax: [-10.0, 60.0]]

@Field static final Integer PRESENCE_THRESHOLD = 4   // missed health-check cycles before "offline"

@Field static final String STATUS_WAITING   = "Waiting for first report – press the sensor's button, then click Refresh"
@Field static final String STATUS_REPORTING = 'Reporting'

metadata {
    definition(
        name:      'Sonoff SNZB-02 Series Temperature and Humidity Sensor',
        namespace: 'v. 1.2.0',
        author:    'John Land',
        importUrl: 'https://raw.githubusercontent.com/JohnFLand/Sonoff_SNZB-02_Series-Zigbee-driver-for-Hubitat/main/Sonoff_SNZB-02_Series.groovy'
    ) {
        capability 'Sensor'
        capability 'TemperatureMeasurement'
        capability 'RelativeHumidityMeasurement'
        capability 'PressureMeasurement'          // reported by the SNZB-02M only
        capability 'Battery'
        capability 'Refresh'
        capability 'Configuration'

        // Calculated from temperature + humidity
        attribute 'dewPoint',               'number'
        attribute 'vpd',                    'number'   // vapour pressure deficit, kPa

        // Running min/max tracked by the driver
        attribute 'Humidity Max',           'string'
        attribute 'Humidity Min',           'string'
        attribute 'Temperature Max',        'string'
        attribute 'Temperature Min',        'string'
        attribute 'Min/Max Reset',          'string'
        attribute 'Last Reading',           'string'
        attribute 'Readings Since Reset',   'number'

        // SNZB-02DR2: device-native 24-hour min/max (as shown on its LCD)
        attribute 'Device Humidity Max',    'string'
        attribute 'Device Humidity Min',    'string'
        attribute 'Device Temp Max',        'string'
        attribute 'Device Temp Min',        'string'

        // Settings stored in the sensor (SNZB-02DR2 and SNZB-02M)
        attribute 'comfortTempMin',         'number'
        attribute 'comfortTempMax',         'number'
        attribute 'comfortHumidityMin',     'number'
        attribute 'comfortHumidityMax',     'number'
        attribute 'lcdTempUnit',            'string'   // SNZB-02DR2
        attribute 'temperatureCalibration', 'number'
        attribute 'humidityCalibration',    'number'
        attribute 'pressureCalibration',    'number'   // SNZB-02M

        // SNZB-02DR2 remote display (EXT line): values confirmed by the sensor
        attribute 'remoteDisplay',          'string'   // off, pending, on, offline, unsupported
        attribute 'ext1Temperature',        'number'
        attribute 'ext1Humidity',           'number'
        attribute 'ext2Temperature',        'number'
        attribute 'ext2Humidity',           'number'

        attribute 'healthStatus',           'enum', ['unknown', 'online', 'offline']
        attribute 'sensorStatus',           'string'

        command 'resetMinMax', [[name: 'Reset the Hubitat-tracked min/max temperature and humidity values']]
        command 'initialize',  [[name: 'Re-initialize driver state and re-configure device reporting']]

        // SNZB-02DR2 only
        command 'setRemoteTemperature', [[name: 'Temperature*', type: 'NUMBER', description: 'Shown on the EXT line, in the hub\'s temperature scale'],
                                         [name: 'Slot', type: 'ENUM', constraints: ['EXT1', 'EXT2'], description: 'EXT2 needs firmware 1.0.5 or later']]
        command 'setRemoteHumidity',    [[name: 'Humidity*', type: 'NUMBER', description: 'Shown on the EXT line, in %'],
                                         [name: 'Slot', type: 'ENUM', constraints: ['EXT1', 'EXT2'], description: 'EXT2 needs firmware 1.0.5 or later']]
        command 'remoteDisplayOff',     [[name: 'Show only the built-in sensor again (SNZB-02DR2)']]

        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57',
                    outClusters: '000A,0019', model: 'SNZB-02P', manufacturer: 'SONOFF',
                    deviceJoinName: 'Sonoff SNZB-02P Temperature and Humidity Sensor'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57',
                    outClusters: '000A,0019', model: 'SNZB-02P', manufacturer: 'eWeLink',
                    deviceJoinName: 'Sonoff SNZB-02P Temperature and Humidity Sensor'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57',
                    outClusters: '000A,0019', model: 'SNZB-02D', manufacturer: 'SONOFF',
                    deviceJoinName: 'Sonoff SNZB-02D Temperature and Humidity Sensor'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57',
                    outClusters: '000A,0019', model: 'SNZB-02D', manufacturer: 'eWeLink',
                    deviceJoinName: 'Sonoff SNZB-02D Temperature and Humidity Sensor'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57,FC11',
                    outClusters: '000A,0019', model: 'SNZB-02DR2', manufacturer: 'SONOFF',
                    deviceJoinName: 'Sonoff SNZB-02DR2 AirGuard TH'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0402,0405,0020,FC57,FC11',
                    outClusters: '000A,0019', model: 'SNZB-02DR2', manufacturer: 'eWeLink',
                    deviceJoinName: 'Sonoff SNZB-02DR2 AirGuard TH'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0020,0402,0403,0405,FC57,FC11',
                    outClusters: '0019', model: 'SNZB-02M', manufacturer: 'SONOFF',
                    deviceJoinName: 'Sonoff SNZB-02M'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0001,0003,0020,0402,0403,0405,FC57,FC11',
                    outClusters: '0019', model: 'SNZB-02M', manufacturer: 'eWeLink',
                    deviceJoinName: 'Sonoff SNZB-02M'
    }

    // NOTE: decimal inputs deliberately have no range: parameter. Hubitat's preference page
    // mishandles ranges with fractional bounds (e.g. '0.1..5.0', '-20.0..20.0'). Ranges are
    // enforced in code instead (see validatePreferences()).
    // Model-specific inputs appear only for that model; save once after changing "Device model".
    preferences {
        input name: 'logEnable',
              type: 'bool',
              title: '<b>Debug logging</b>',
              description: 'Enable debug log output. Automatically disabled after 24 hours.',
              defaultValue: true

        input name: 'txtEnable',
              type: 'bool',
              title: '<b>Description text logging</b>',
              description: 'Log sensor readings in the Events log.',
              defaultValue: true

        input name: 'modelOverride',
              type: 'enum',
              title: '<b>Device model</b>',
              description: 'Leave on Automatic unless the model was not recognised (see the log). Save, then reopen this page to see the settings for the chosen model.',
              options: ['auto': 'Automatic (from the device)', 'BASIC': 'SNZB-02P / SNZB-02D', 'DR2': 'SNZB-02DR2', 'M': 'SNZB-02M'],
              defaultValue: 'auto'

        input name: 'tempPrefUnit',
              type: 'enum',
              title: '<b>Units for temperature settings</b>',
              description: 'Unit for the temperature settings below (threshold, offset and, on the SNZB-02DR2, comfort limits). Changing it converts the values already entered: change the unit and Save first, then adjust the values. Readings in Hubitat always follow the hub\'s temperature scale.',
              options: ['C': 'Celsius (°C)', 'F': 'Fahrenheit (°F)'],
              defaultValue: 'C'

        input name: 'tempPrecision',
              type: 'enum',
              title: '<b>Temperature decimal places</b>',
              description: 'Decimal places for temperatures shown in Hubitat, rounded half-up. Use 0 for dashboard tiles that do not handle decimals.',
              options: ['0': '0 (e.g. 72°)', '1': '1 (e.g. 72.4°)', '2': '2 (e.g. 72.43°)'],
              defaultValue: '1'

        input name: 'minReportTemp',
              type: 'number',
              title: '<b>Min time between temperature reports (seconds)</b>',
              description: 'Minimum reporting interval for temperature. Range 1 to 3600. Wake device before saving.',
              defaultValue: 10,
              range: '1..3600'

        input name: 'maxReportTemp',
              type: 'number',
              title: '<b>Max time between temperature reports (seconds)</b>',
              description: 'Maximum reporting interval (heartbeat) for temperature. Range 10 to 43200. Wake device before saving.',
              defaultValue: 3600,
              range: '10..43200'

        input name: 'tempChange',
              type: 'decimal',
              title: '<b>Temperature reporting threshold</b>',
              description: 'Minimum change to trigger a temperature report, in the unit selected above. Range 0.1 to 5.0 °C (0.2 to 9.0 °F). Wake device before saving.',
              defaultValue: tempPrefDefault('tempChange')

        input name: 'minReportHumi',
              type: 'number',
              title: '<b>Min time between humidity reports (seconds)</b>',
              description: 'Minimum reporting interval for humidity. Range 1 to 3600. Wake device before saving.',
              defaultValue: 10,
              range: '1..3600'

        input name: 'maxReportHumi',
              type: 'number',
              title: '<b>Max time between humidity reports (seconds)</b>',
              description: 'Maximum reporting interval (heartbeat) for humidity. Range 10 to 43200. Wake device before saving.',
              defaultValue: 3600,
              range: '10..43200'

        input name: 'humiChange',
              type: 'number',
              title: '<b>Humidity reporting threshold (%)</b>',
              description: 'Minimum change to trigger a humidity report. Range 1 to 10. Wake device before saving.',
              defaultValue: 1,
              range: '1..10'

        if (isModelM()) {
            input name: 'pressureUnit',
                  type: 'enum',
                  title: '<b>Pressure unit</b>',
                  description: 'Unit used to report the pressure attribute.',
                  options: ['hPa': 'hPa (mbar)', 'kPa': 'kPa', 'inHg': 'inHg', 'mmHg': 'mmHg'],
                  defaultValue: 'hPa'

            input name: 'minReportPressure',
                  type: 'number',
                  title: '<b>Min time between pressure reports (seconds)</b>',
                  description: 'Minimum reporting interval for pressure. Range 1 to 3600. Wake device before saving.',
                  defaultValue: 60,
                  range: '1..3600'

            input name: 'maxReportPressure',
                  type: 'number',
                  title: '<b>Max time between pressure reports (seconds)</b>',
                  description: 'Maximum reporting interval (heartbeat) for pressure. Range 10 to 43200. Wake device before saving.',
                  defaultValue: 3600,
                  range: '10..43200'

            input name: 'pressureChange',
                  type: 'number',
                  title: '<b>Pressure reporting threshold (hPa)</b>',
                  description: 'Minimum change (in hPa) to trigger a pressure report. Range 1 to 50. Wake device before saving.',
                  defaultValue: 1,
                  range: '1..50'
        }

        input name: 'temperatureOffset',
              type: 'decimal',
              title: '<b>Temperature offset</b>',
              description: 'Offset added to every temperature reading, in the unit selected above. Range -20 to 20 °C (-36 to 36 °F). Applied in Hubitat, or on the SNZB-02DR2 optionally in the sensor (see below).',
              defaultValue: tempPrefDefault('temperatureOffset')

        input name: 'humidityOffset',
              type: 'decimal',
              title: '<b>Humidity offset (%)</b>',
              description: 'Offset added to every humidity reading. Range -20.0 to 20.0. Applied in Hubitat, or on the SNZB-02DR2 optionally in the sensor (see below).',
              defaultValue: 0.0

        if (isModelM()) {
            input name: 'pressureOffset',
                  type: 'decimal',
                  title: '<b>Pressure offset (hPa)</b>',
                  description: 'Offset added to every pressure reading in Hubitat. Entered in hPa. Range -50.0 to 50.0.',
                  defaultValue: 0.0
        }

        input name: 'derivedEnable',
              type: 'bool',
              title: '<b>Calculate dew point and VPD</b>',
              description: 'Publish dew point and vapour pressure deficit, calculated from temperature and humidity.',
              defaultValue: true

        if (isModelDR2()) {
            input name: 'offsetsInDevice',
                  type: 'bool',
                  title: '<b>Apply offsets in the sensor</b>',
                  description: 'Writes the temperature and humidity offsets to the sensor, so its screen shows the same values as Hubitat. Turning this off writes 0 back to the sensor. Wake device before saving.',
                  defaultValue: false

            input name: 'lcdTempUnit',
                  type: 'enum',
                  title: '<b>LCD temperature unit</b>',
                  description: 'Unit shown on the device screen. Hubitat itself always uses the hub\'s temperature scale (Settings → Location). Wake device before saving.',
                  options: ['nochange': 'Leave unchanged', 'C': 'Celsius (°C)', 'F': 'Fahrenheit (°F)'],
                  defaultValue: 'nochange'

            input name: 'pref_comfortTempMin',
                  type: 'decimal',
                  title: '<b>Comfort temperature minimum</b>',
                  description: 'Device shows ❄️ below this value, in the unit selected above. Range -10 to 60 °C (14 to 140 °F). Wake device before saving.',
                  defaultValue: tempPrefDefault('pref_comfortTempMin')

            input name: 'pref_comfortTempMax',
                  type: 'decimal',
                  title: '<b>Comfort temperature maximum</b>',
                  description: 'Device shows 🔥 above this value, in the unit selected above. Range -10 to 60 °C (14 to 140 °F). Wake device before saving.',
                  defaultValue: tempPrefDefault('pref_comfortTempMax')

            input name: 'pref_comfortHumiMin',
                  type: 'number',
                  title: '<b>Comfort humidity minimum (%)</b>',
                  description: 'Lower end of the comfort humidity range (☀️ icon below it). Range 5 to 95. Wake device before saving.',
                  defaultValue: 40,
                  range: '5..95'

            input name: 'pref_comfortHumiMax',
                  type: 'number',
                  title: '<b>Comfort humidity maximum (%)</b>',
                  description: 'Upper end of the comfort humidity range (💧 icon above it). Range 5 to 95. Wake device before saving.',
                  defaultValue: 60,
                  range: '5..95'

            input name: 'remoteMethod',
                  type: 'enum',
                  title: '<b>Remote display method</b>',
                  description: 'How Set Remote Temperature / Humidity reach the EXT line. Automatic chooses by firmware: before 1.0.5 the older method (EXT1 only; humidity needs 1.0.4), from 1.0.5 the remote-source method (EXT1 and EXT2).',
                  options: ['auto': 'Automatic (by firmware)', 'legacy': 'Older method (firmware before 1.0.5)', 'sources': 'Remote sources (firmware 1.0.5 and later)'],
                  defaultValue: 'auto'
        }

        input name: 'autoResetEnable',
              type: 'bool',
              title: '<b>Auto-reset min/max daily</b>',
              description: 'When enabled, the driver-tracked min/max values are reset automatically each day at the time set below.',
              defaultValue: false

        input name: 'autoResetTime',
              type: 'time',
              title: '<b>Auto-reset time</b>',
              description: 'Time of day at which the driver min/max is automatically reset (only used when auto-reset is enabled).',
              defaultValue: '00:00'
    }
}

// ── Model detection ──────────────────────────────────────────────────────────

// 'DR2', 'M' or 'BASIC' (SNZB-02P / SNZB-02D and anything unrecognised)
private String modelType() {
    String o = (settings?.modelOverride ?: 'auto') as String
    if (o in ['BASIC', 'DR2', 'M']) { return o }
    String m = (device?.getDataValue('model') ?: '') as String
    if (m.startsWith('SNZB-02DR2')) { return 'DR2' }
    if (m.startsWith('SNZB-02M')) { return 'M' }
    return 'BASIC'
}

private boolean isModelDR2() { return modelType() == 'DR2' }
private boolean isModelM()   { return modelType() == 'M' }

private void detectModel() {
    String type = modelType()
    String model = (device.getDataValue('model') ?: 'unknown') as String
    if (state.modelType != type) {
        state.modelType = type
        logInfo "Model ${model}: using ${MODEL_NAMES[type]} features"
    }
    state.model = model
    boolean auto = (settings?.modelOverride ?: 'auto') == 'auto'
    if (auto && type == 'BASIC' && !(model.startsWith('SNZB-02P') || model.startsWith('SNZB-02D'))) {
        logWarn "Model '${model}' is not recognised; using basic temperature/humidity features. " +
                'Set "Device model" in Preferences if it is one of the supported sensors.'
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

def installed() {
    logInfo 'installed()'
    initVars(true)
    configure()
}

def updated() {
    logInfo 'updated()'
    if (logEnable) { runIn(86400, 'logsOff') }
    state.driverVersion = "${VERSION} ${TIME_STAMP}"
    removeLegacyState()
    // Re-learn on the next send what the sensor supports (e.g. after a firmware update)
    ['remoteMethodOverride', 'remoteHumiUnsupported'].each { String k -> state.remove(k) }
    detectModel()
    convertTempPrefsIfUnitChanged()
    validatePreferences()
    rescheduleAutoReset()
    ensureSensorStatus()
    if (derivedEnable == false) {
        ['dewPoint', 'vpd'].each { String a -> try { device.deleteCurrentState(a) } catch (ignored) { } }
    }

    // Show the effect of new offsets, units, precision and pressure unit straight away
    republishCurrent()

    // Push reporting configuration (and SNZB-02DR2 settings) to the device (device must be awake)
    List<String> cmds = []
    cmds += reportingConfigCmds()
    if (isModelDR2()) {
        cmds += writeFC11Settings()
        // Read the settings back so comfort*/lcdTempUnit show what the device actually holds
        cmds += fc11SettingsReadCmds()
    }
    snapshotTempPrefs()
    sendZigbeeCommands(cmds)
}

def configure() {
    logInfo 'configure()'
    if (logEnable) { runIn(86400, 'logsOff') }
    state.driverVersion = "${VERSION} ${TIME_STAMP}"
    detectModel()
    convertTempPrefsIfUnitChanged()
    validatePreferences()
    ensureSensorStatus()

    List<String> cmds = []
    cmds += reportingConfigCmds()
    if (isModelDR2()) { cmds += writeFC11Settings() }

    // Read current values (after the writes, so the reads reflect them)
    cmds += readValueCmds(false)
    cmds += fc11SettingsReadCmds()

    scheduleHealthCheck()   // also (re)schedules the daily auto-reset
    snapshotTempPrefs()
    sendZigbeeCommands(cmds)
}

def initialize() {
    logInfo 'initialize()'
    unschedule()
    initVars(true)
    configure()
}

def refresh() {
    logInfo 'refresh()'
    List<String> cmds = []
    cmds += readValueCmds(true)
    cmds += fc11SettingsReadCmds()
    if (isModelDR2()) {
        // Device-native min/max is pushed unsolicited about hourly; reading it here
        // lets Refresh populate it without waiting for the next automatic report
        [FC11_MAX_TEMP, FC11_MIN_TEMP, FC11_MAX_HUMI, FC11_MIN_HUMI].each { int a ->
            cmds += zigbee.readAttribute(CLUSTER_FC11, a, FC11_MFG_DR2, 200)
        }
    }
    sendZigbeeCommands(cmds)
}

// Re-publish the last readings using the current preferences. Uses the stored raw (pre-offset)
// values; does not touch min/max, Last Reading or the reading counter.
private void republishCurrent() {
    if (state.rawTempC == null && state.rawHumi == null && state.rawPressHPa == null) { return }
    logInfo 'Re-publishing current readings with updated preferences'
    publishTemperature(false)
    publishHumidity(false)
    if (state.rawPressHPa != null && isModelM()) {
        publishPressure(state.rawPressHPa as double, (state.rawPressPrecise ?: false) as boolean, false)
    }
    updateDerived()
}

private List<String> readValueCmds(boolean withVoltage) {
    List<String> cmds = []
    cmds += zigbee.readAttribute(CLUSTER_TEMP,      0x0000, [:], 200)
    cmds += zigbee.readAttribute(CLUSTER_HUMIDITY,  0x0000, [:], 200)
    if (isModelM()) {
        cmds += zigbee.readAttribute(CLUSTER_PRESSURE, PRESSURE_ATTR_PRECISE, [:], 200)
        cmds += zigbee.readAttribute(CLUSTER_PRESSURE, PRESSURE_ATTR, [:], 200)
    }
    cmds += zigbee.readAttribute(CLUSTER_POWER_CFG, 0x0021, [:], 200)
    if (withVoltage && !isModelM()) {
        cmds += zigbee.readAttribute(CLUSTER_POWER_CFG, 0x0020, [:], 200)
    }
    return cmds
}

// ── Commands ─────────────────────────────────────────────────────────────────

def resetMinMax() {
    logInfo 'Resetting min/max tracking'
    state.minTemp      = null
    state.maxTemp      = null
    state.minHumi      = null
    state.maxHumi      = null
    state.readingCount = 0
    String ts = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)
    sendEvent(name: 'Min/Max Reset',        value: ts,   descriptionText: "Min/max reset at ${ts}")
    sendEvent(name: 'Readings Since Reset', value: 0,    descriptionText: 'Reading counter reset to 0')
    sendEvent(name: 'Temperature Min',      value: '--', descriptionText: 'Min temperature reset')
    sendEvent(name: 'Temperature Max',      value: '--', descriptionText: 'Max temperature reset')
    sendEvent(name: 'Humidity Min',         value: '--', descriptionText: 'Min humidity reset')
    sendEvent(name: 'Humidity Max',         value: '--', descriptionText: 'Max humidity reset')
}

// ── Auto-reset scheduling ─────────────────────────────────────────────────────

private void rescheduleAutoReset() {
    unschedule('autoResetMinMax')
    if (!autoResetEnable) {
        logInfo 'Auto-reset disabled'
        return
    }
    try {
        def t = timeToday(autoResetTime, location.timeZone)
        String cronExpr = "0 ${t.minutes} ${t.hours} * * ? *"
        schedule(cronExpr, 'autoResetMinMax')
        logInfo "Auto-reset scheduled daily at ${t.format('HH:mm', location.timeZone)}"
    } catch (e) {
        logWarn "Auto-reset scheduling failed: ${e.message}"
    }
}

def autoResetMinMax() {
    logInfo 'Auto-reset: resetting driver min/max tracking'
    resetMinMax()
}

// ── Parse ─────────────────────────────────────────────────────────────────────

def parse(String description) {
    setPresent()
    remoteResendIfDue()   // the sensor is awake now: re-send remote-display values it has not confirmed

    if (!(description?.startsWith('catchall:') || description?.startsWith('read attr -'))) {
        logDebug "unhandled description: ${description}"
        return
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "parse() descMap = ${descMap}"

    Integer clusterInt = descMap.clusterInt as Integer

    // ZDO responses (bind confirmations etc.) use profile 0x0000 and response cluster IDs
    // 0x8000–0x80FF. Do NOT test clusterInt >= 0x8000 alone — that also matches cluster 0xFC11.
    if (descMap.profileId == '0000' || (clusterInt != null && clusterInt >= 0x8000 && clusterInt <= 0x80FF)) {
        logDebug "ZDO response cluster 0x${Integer.toHexString(clusterInt ?: 0)} (ignored)"
        return
    }

    // Global responses: 0x04 = Write Attributes Response, 0x07 = Configure Reporting Response
    if (description.startsWith('catchall:') && descMap.isClusterSpecific != true &&
            (descMap.command == '04' || descMap.command == '07')) {
        parseGlobalResponse(descMap)
        return
    }

    switch (clusterInt) {
        case CLUSTER_POWER_CFG:
            parsePowerCluster(descMap)
            break
        case CLUSTER_TEMP:
            parseTempCluster(descMap)
            break
        case CLUSTER_PRESSURE:
            parsePressureCluster(descMap)
            break
        case CLUSTER_HUMIDITY:
            parseHumiCluster(descMap)
            break
        case CLUSTER_FC11:
            parseFC11Cluster(descMap)
            break
        case CLUSTER_IDENTIFY:
            logDebug 'Identify from the sensor (button pressed)'
            break
        default:
            logDebug "unhandled cluster: 0x${Integer.toHexString(clusterInt ?: 0)}"
            break
    }
}

// Logs whether the device accepted a Configure Reporting or Write Attributes request.
// A success response carries a single status byte 00; failures list status + attribute ID.
private void parseGlobalResponse(Map descMap) {
    List data = (descMap.data ?: []) as List
    String status = data ? (data[0] as String) : '??'
    String cluster = clusterName(descMap.clusterInt as Integer)
    if (descMap.command == '07') {
        if (status == '00') {
            logInfo "${cluster} reporting configuration accepted by device"
        } else {
            String attr = data.size() >= 4 ? "0x${data[3]}${data[2]}" : '?'
            logWarn "${cluster} reporting configuration for attribute ${attr} rejected (status 0x${status})"
        }
    } else {
        boolean fc11 = (descMap.clusterInt as Integer) == CLUSTER_FC11
        if (status == '00') {
            logInfo "${cluster} attribute write accepted by device"
            if (fc11) { remoteWriteAccepted() }
        } else {
            String attr = data.size() >= 3 ? "0x${data[2]}${data[1]}" : '?'
            logWarn "${cluster} write of attribute ${attr} rejected (status 0x${status})"
            if (fc11 && attr == '0x0007' && status.equalsIgnoreCase('8D')) {
                retryLcdUnitWrite()
            }
            if (fc11) { remoteWriteRejected(attr, status) }
        }
    }
}

private String clusterName(Integer clusterInt) {
    switch (clusterInt) {
        case CLUSTER_POWER_CFG: return 'Battery'
        case CLUSTER_TEMP:      return 'Temperature'
        case CLUSTER_PRESSURE:  return 'Pressure'
        case CLUSTER_HUMIDITY:  return 'Humidity'
        case CLUSTER_FC11:      return 'FC11'
        default:                return "Cluster 0x${Integer.toHexString(clusterInt ?: 0)}"
    }
}

// ── Cluster parsers ──────────────────────────────────────────────────────────

private void parsePowerCluster(Map descMap) {
    if (descMap.attrInt == 0x0021 && descMap.value && descMap.value.length() <= 8) {
        // Battery percentage — ZCL reports 0..200 (half-percent); divide by 2
        Integer raw = (Long.parseLong(descMap.value, 16) & 0xFF) as Integer
        Integer pct = Math.min(100, Math.round(raw / 2.0) as Integer)
        sendEvent(name: 'battery', value: pct, unit: '%',
                  descriptionText: "${device.displayName} battery is ${pct}%", type: 'physical')
        logInfo "battery ${pct}%"
    } else if (descMap.attrInt == 0x0020 && descMap.value && descMap.value.length() <= 8) {
        // Battery voltage (raw in 100 mV units); percentage estimated from 2.1–3.0 V
        Integer raw = (Long.parseLong(descMap.value, 16) & 0xFF) as Integer
        double volts = raw / 10.0
        Integer pct = Math.min(100, Math.max(1, Math.round(((volts - 2.1) / 0.9) * 100) as Integer))
        sendEvent(name: 'battery', value: pct, unit: '%',
                  descriptionText: "${device.displayName} battery ~${pct}% (${volts}V)", type: 'physical')
        logInfo "battery voltage ${volts}V (~${pct}%)"
    }
}

private void parseTempCluster(Map descMap) {
    // Accept attrInt 0x0000 from read-response or null from some catchall frames
    if (descMap.attrInt != null && descMap.attrInt != 0x0000) { return }
    if (!descMap.value || descMap.value.length() > 8) { return }
    Integer raw = (Long.parseLong(descMap.value, 16) & 0xFFFF) as Integer
    if (raw == 0x8000) { logDebug 'temperature: invalid sentinel value'; return }
    if (raw > 32767) { raw = raw - 65536 }   // two's complement
    double tempC = raw / 100.0

    // Sanity-check the sensor's own value, before the Hubitat offset is applied
    if (tempC < TEMP_MIN_C || tempC > TEMP_MAX_C) {
        logWarn "temperature out of range: ${tempC}°C (raw=${raw}) — ignored"
        return
    }
    state.rawTempC = tempC
    publishTemperature(true)
    updateDerived()
}

// Publishes temperature from the stored raw value plus the current offset.
// fresh = true for a new device reading (updates min/max and Last Reading).
private void publishTemperature(boolean fresh) {
    Double tempAdjusted = adjTempC()
    if (tempAdjusted == null) { return }
    BigDecimal display = tempOut(tempAdjusted)
    String unit = tempUnit()

    sendEvent(name: 'temperature', value: display, unit: unit,
              descriptionText: "${device.displayName} temperature is ${display}${unit}",
              type: fresh ? 'physical' : 'digital')
    logInfo "temperature ${display}${unit}${fresh ? '' : ' (re-published)'}"
    if (fresh) {
        markReporting()
        updateMinMax('temperature', tempAdjusted)
        String ts = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)
        sendEvent(name: 'Last Reading', value: ts, descriptionText: "Last reading at ${ts}")
    }
}

private void parseHumiCluster(Map descMap) {
    if (descMap.attrInt != null && descMap.attrInt != 0x0000) { return }
    if (!descMap.value || descMap.value.length() > 8) { return }
    Integer raw = (Long.parseLong(descMap.value, 16) & 0xFFFF) as Integer
    double humiPct = raw / 100.0

    // Sanity-check the sensor's own value, before the Hubitat offset is applied
    if (humiPct < HUMI_MIN_PCT || humiPct > HUMI_MAX_PCT) {
        logWarn "humidity out of range: ${humiPct}% (raw=${raw}) — ignored"
        return
    }
    state.rawHumi = humiPct
    publishHumidity(true)
    updateDerived()
}

// Humidity is published in whole percent on every model (rounded half-up)
private Integer humiOut(double pct) {
    return roundTo(pct, 0).intValue()
}

// Publishes humidity from the stored raw value plus the current offset.
private void publishHumidity(boolean fresh) {
    Double humiAdjusted = adjHumi()
    if (humiAdjusted == null) { return }
    Integer display = humiOut(humiAdjusted)

    sendEvent(name: 'humidity', value: display, unit: '%',
              descriptionText: "${device.displayName} humidity is ${display}%",
              type: fresh ? 'physical' : 'digital')
    logInfo "humidity ${display}%${fresh ? '' : ' (re-published)'}"
    if (fresh) { updateMinMax('humidity', humiAdjusted); markReporting() }
}

// Current adjusted values (raw + offset), or null if no reading has been received yet
private Double adjTempC() {
    return state.rawTempC == null ? null : (state.rawTempC as double) + hubTempOffset()
}

private Double adjHumi() {
    return state.rawHumi == null ? null : clampD((state.rawHumi as double) + hubHumiOffset(), 0.0, 100.0)
}

// Offset Hubitat adds to the sensor's value. When the SNZB-02DR2 manages (or recently managed) the
// offsets itself, its reports already include the calibration it has confirmed, so Hubitat adds only
// the rest: correct whether or not the sensor has received the latest write.
private boolean deviceOffsetsManaged() {
    return isModelDR2() && (settings?.offsetsInDevice || state.devOffsetsManaged)
}

private double hubTempOffset() { return hubOffsetPart(prefTempOffset(), state.devTempCal) }

private double hubHumiOffset() { return hubOffsetPart(prefHumiOffset(), state.devHumiCal) }

// The sensor stores calibration in 0.01 steps (3 °F = 1.6667 °C is stored as 1.67 °C). A
// difference smaller than half a step is that rounding, not a missing offset, so Hubitat adds
// nothing and stays in step with the sensor's screen.
private double hubOffsetPart(double wanted, devCal) {
    if (!deviceOffsetsManaged()) { return wanted }
    double d = wanted - ((devCal ?: 0.0) as double)
    return Math.abs(d) < 0.005 ? 0.0 : d
}

// SNZB-02DR2: the sensor reported its calibration (°C or %). If a known value changed, the last
// raw reading is shifted by the change, so values re-published or derived before the sensor's next
// report stay consistent with the new calibration.
private void deviceCalibrationReported(String key, double v) {
    if (!isModelDR2()) { return }
    String rawKey = key == 'devTempCal' ? 'rawTempC' : 'rawHumi'
    if (state[rawKey] != null && deviceOffsetsManaged()) {
        double delta = v - ((state[key] ?: 0.0) as double)   // first report: assume the factory 0
        if (Math.abs(delta) > 0.0001) { state[rawKey] = (state[rawKey] as double) + delta }
    }
    state[key] = v
    if (!settings?.offsetsInDevice && state.devOffsetsManaged &&
            ((state.devTempCal ?: 0.0) as double) == 0.0 && ((state.devHumiCal ?: 0.0) as double) == 0.0) {
        state.remove('devOffsetsManaged')
        logInfo 'Offsets: the sensor calibration is back to 0; offsets are applied in Hubitat again'
    }
}

private void parsePressureCluster(Map descMap) {
    // The SNZB-02M may send the whole-hPa attribute (0x0000) and the high-res INT32 attribute
    // (0x0004) as separate frames OR packed into one frame with 0x0004 in additionalAttrs.
    // Gather every attribute present in this frame, then prefer the high-res value.
    Map<Integer, String> attrs = [:]
    if (descMap.attrInt != null && descMap.value) {
        attrs[descMap.attrInt as Integer] = descMap.value as String
    }
    descMap.additionalAttrs?.each { Map aa ->
        if (aa.attrInt != null && aa.value) {
            attrs[aa.attrInt as Integer] = aa.value as String
        }
    }

    // Preferred: high-resolution INT32, units of 0.01 hPa
    String preciseHex = attrs[PRESSURE_ATTR_PRECISE]
    if (preciseHex && preciseHex.length() <= 8) {
        long raw = Long.parseLong(preciseHex, 16)
        if (raw > 0x7FFFFFFFL) { raw -= 0x100000000L }
        state.lastPreciseTs = now()
        publishPressure(raw / 100.0d, true, true)
        return
    }

    // Fallback: whole-hPa INT16, suppressed while the high-res feed is active to
    // avoid the value bouncing between e.g. 1006.0 and 1006.6
    String wholeHex = attrs[PRESSURE_ATTR]
    if (wholeHex && wholeHex.length() <= 8) {
        long staleMs = (safeInt(maxReportPressure, 3600) * 2L + 120L) * 1000L
        if ((now() - (state.lastPreciseTs ?: 0L)) < staleMs) {
            logDebug 'pressure(0x0000) suppressed — high-res feed active'
            return
        }
        Integer raw = (Long.parseLong(wholeHex, 16) & 0xFFFF) as Integer
        if (raw == 0x8000) { logDebug 'pressure: invalid sentinel value'; return }
        if (raw > 32767) { raw = raw - 65536 }
        publishPressure(raw as double, false, true)
    }
}

// hPaBase is the raw (pre-offset) reading. fresh = true for a new device reading,
// false when re-publishing the stored reading after a preference change.
private void publishPressure(double hPaBase, boolean precise, boolean fresh) {
    // Sanity-check the sensor's own value, before the Hubitat offset is applied
    if (hPaBase < PRESS_MIN_HPA || hPaBase > PRESS_MAX_HPA) {
        logWarn "pressure out of range: ${hPaBase} hPa — ignored"
        return
    }
    if (fresh) {
        markReporting()
        state.rawPressHPa     = hPaBase
        state.rawPressPrecise = precise
    }
    double hPa = hPaBase + prefPressOffset()
    Map p = convertPressure(hPa)
    sendEvent(name: 'pressure', value: p.value, unit: p.unit,
              descriptionText: "${device.displayName} pressure is ${p.value} ${p.unit}",
              type: fresh ? 'physical' : 'digital')
    logInfo "pressure ${p.value} ${p.unit}${precise ? '' : ' (whole)'}${fresh ? '' : ' (re-published)'}"
}

// Convert hPa into the user-selected display unit. Returns [value: Number, unit: String].
private Map convertPressure(double hPa) {
    switch (pressureUnit ?: 'hPa') {
        case 'kPa':
            return [value: roundTo(hPa / 10.0, 2), unit: 'kPa']
        case 'inHg':
            return [value: roundTo(hPa * 0.0295300, 2), unit: 'inHg']
        case 'mmHg':
            return [value: roundTo(hPa * 0.7500620, 1), unit: 'mmHg']
        case 'hPa':
        default:
            return [value: roundTo(hPa, 1), unit: 'hPa']
    }
}

private void parseFC11Cluster(Map descMap) {
    // Process additional attributes packed into the same frame
    if (descMap.additionalAttrs) {
        descMap.additionalAttrs.each { Map aa ->
            String v = aa.value as String
            if (v && v.length() <= 8) {
                parseFC11Attr(aa.attrInt as int, v, aa.encoding as String)
            }
        }
    }
    String val = descMap.value as String
    if (!val || val.length() > 8) {
        if (val && val.length() > 8) { logDebug "FC11 skipping blob len=${val.length()}" }
        return
    }
    parseFC11Attr(descMap.attrInt as int, val, descMap.encoding as String)
}

// Separate method so attribute comparisons use plain int arithmetic (avoids Groovy
// switch/Integer boxing issues that silently fail for values > 0x00FF)
private void parseFC11Attr(int attrInt, String hexVal, String encoding = null) {
    Integer raw = (Long.parseLong(hexVal, 16) & 0xFFFF) as Integer
    Integer signed = raw > 32767 ? raw - 65536 : raw

    if (attrInt == FC11_TEMP_UNIT) {
        String unitName = raw == 1 ? 'Fahrenheit' : 'Celsius'
        sendEvent(name: 'lcdTempUnit', value: unitName, descriptionText: "LCD temperature unit is ${unitName}")
        logInfo "FC11 LCD temperature unit: ${unitName}${encoding ? " (device reports data type 0x${encoding})" : ''}"

    } else if (attrInt == FC11_COMFORT_TMAX || attrInt == FC11_COMFORT_TMIN) {
        String name = attrInt == FC11_COMFORT_TMAX ? 'comfortTempMax' : 'comfortTempMin'
        double valC = signed / 100.0
        BigDecimal v = tempOut(valC)
        sendEvent(name: name, value: v, unit: tempUnit(),
                  descriptionText: "${name} = ${v}${tempUnit()} (${valC}°C device setting)")
        logInfo "FC11 ${name} = ${v}${tempUnit()}"

    } else if (attrInt == FC11_COMFORT_HMIN || attrInt == FC11_COMFORT_HMAX) {
        String name = attrInt == FC11_COMFORT_HMIN ? 'comfortHumidityMin' : 'comfortHumidityMax'
        Integer pct = roundTo(raw / 100.0, 0).intValue()
        sendEvent(name: name, value: pct, unit: '%', descriptionText: "${name} = ${pct}%")
        logInfo "FC11 ${name} = ${pct}%"

    } else if (attrInt == FC11_TEMP_CAL) {
        // A temperature difference: converted to the hub's scale without the +32. Stored in
        // 0.01 °C steps (0.018 °F), so shown to 0.01 in °C but only to 0.1 in °F (3 °F, stored
        // as 1.67 °C = 3.006 °F, shows as 3.0).
        double cal = signed / 100.0
        boolean f = location.temperatureScale == 'F'
        BigDecimal v = roundTo(f ? cal * 1.8 : cal, f ? 1 : 2)
        String sign = v > 0 ? '+' : ''
        sendEvent(name: 'temperatureCalibration', value: v, unit: tempUnit(),
                  descriptionText: "Device temperature calibration = ${sign}${v}${tempUnit()}")
        logInfo "FC11 device temperature calibration = ${sign}${v}${tempUnit()}"
        deviceCalibrationReported('devTempCal', cal)

    } else if (attrInt == FC11_HUMI_CAL) {
        BigDecimal v = roundTo(signed / 100.0, 2)
        sendEvent(name: 'humidityCalibration', value: v, unit: '%',
                  descriptionText: "Device humidity calibration = ${v}%")
        logInfo "FC11 device humidity calibration = ${v}%"
        deviceCalibrationReported('devHumiCal', signed / 100.0)

    } else if (attrInt == FC11_PRESS_CAL) {
        BigDecimal v = roundTo(signed / 100.0, 1)
        sendEvent(name: 'pressureCalibration', value: v, unit: 'hPa',
                  descriptionText: "Device pressure calibration = ${v} hPa")
        logInfo "FC11 device pressure calibration = ${v} hPa"

    } else if (attrInt == FC11_MAX_TEMP || attrInt == FC11_MIN_TEMP) {
        String name = attrInt == FC11_MAX_TEMP ? 'Device Temp Max' : 'Device Temp Min'
        String display = formatTemp(signed / 100.0)
        sendEvent(name: name, value: display, descriptionText: "${name} = ${display}")
        logInfo "FC11 ${name} = ${display}"

    } else if (attrInt == FC11_PREV_TEMP) {
        logInfo "FC11 0x200A ref temp = ${formatTemp(signed / 100.0)}"

    } else if (attrInt == FC11_MAX_HUMI || attrInt == FC11_MIN_HUMI) {
        String name = attrInt == FC11_MAX_HUMI ? 'Device Humidity Max' : 'Device Humidity Min'
        String display = "${roundTo(raw / 100.0, 0).intValue()}%"
        sendEvent(name: name, value: display, descriptionText: "${name} = ${display}")
        logInfo "FC11 ${name} = ${display}"

    } else if (attrInt == FC11_PREV_HUMI) {
        logInfo "FC11 0x200D ref humidity = ${roundTo(raw / 100.0, 0).intValue()}%"

    } else if (attrInt == FC11_REMOTE_SELECT) {
        remoteSelectReported(raw)

    } else if (attrInt == FC11_EXT_TEMP) {
        remoteValueReported('T1', signed)

    } else if (attrInt == FC11_EXT_HUMI) {
        remoteValueReported('H1', raw)

    } else {
        logDebug "FC11 unhandled attr 0x${Integer.toHexString(attrInt)} = ${raw}"
    }
}

private String formatTemp(double tempC) {
    return "${tempOut(tempC)}${tempUnit()}"
}

// ── Derived readings: dew point and VPD ──────────────────────────────────────
// Calculated from the latest offset-corrected temperature and humidity.

private void updateDerived() {
    if (derivedEnable == false) { return }
    Double tRef = adjTempC()
    Double hRef = adjHumi()
    if (tRef == null || hRef == null) { return }

    double t = tRef
    double h = Math.max(1.0, Math.min(100.0, hRef))

    // Dew point (Magnus)
    double a = 17.62
    double b = 243.12
    double gamma = Math.log(h / 100.0) + (a * t) / (b + t)
    double dewC = (b * gamma) / (a - gamma)

    // Vapour pressure deficit (kPa), saturation pressure by Tetens
    double es = 0.6108 * Math.exp((17.27 * t) / (t + 237.3))
    double vpdKpa = Math.max(0.0, es * (1.0 - h / 100.0))

    String tUnit = tempUnit()
    BigDecimal dewDisplay = tempOut(dewC)
    BigDecimal vpdDisplay = roundTo(vpdKpa, 2)

    sendEvent(name: 'dewPoint', value: dewDisplay, unit: tUnit,
              descriptionText: "${device.displayName} dew point is ${dewDisplay}${tUnit}")
    sendEvent(name: 'vpd', value: vpdDisplay, unit: 'kPa',
              descriptionText: "${device.displayName} VPD is ${vpdDisplay} kPa")
    logDebug "derived dewPoint=${dewDisplay}${tUnit} vpd=${vpdDisplay} kPa"
}

// ── Min/Max tracking ─────────────────────────────────────────────────────────

private void updateMinMax(String type, double value) {
    String ts = new Date().format('MM-dd HH:mm', location.timeZone)
    if (type == 'temperature') {
        state.readingCount = (state.readingCount ?: 0) + 1
        sendEvent(name: 'Readings Since Reset', value: state.readingCount,
                  descriptionText: "${state.readingCount} readings since last reset")
        String unit = tempUnit()
        BigDecimal displayVal = tempOut(value)

        if (state.minTemp == null || value < (state.minTemp as double)) {
            state.minTemp = value
            sendEvent(name: 'Temperature Min', value: "${displayVal}${unit} @ ${ts}",
                      descriptionText: "Min temperature is ${displayVal}${unit} (at ${ts}, since last reset)")
            logInfo "New min temperature: ${displayVal}${unit} @ ${ts}"
        }
        if (state.maxTemp == null || value > (state.maxTemp as double)) {
            state.maxTemp = value
            sendEvent(name: 'Temperature Max', value: "${displayVal}${unit} @ ${ts}",
                      descriptionText: "Max temperature is ${displayVal}${unit} (at ${ts}, since last reset)")
            logInfo "New max temperature: ${displayVal}${unit} @ ${ts}"
        }
    } else if (type == 'humidity') {
        Integer pct = humiOut(value)
        if (state.minHumi == null || value < (state.minHumi as double)) {
            state.minHumi = value
            sendEvent(name: 'Humidity Min', value: "${pct}% @ ${ts}",
                      descriptionText: "Min humidity is ${pct}% (at ${ts}, since last reset)")
            logInfo "New min humidity: ${pct}% @ ${ts}"
        }
        if (state.maxHumi == null || value > (state.maxHumi as double)) {
            state.maxHumi = value
            sendEvent(name: 'Humidity Max', value: "${pct}% @ ${ts}",
                      descriptionText: "Max humidity is ${pct}% (at ${ts}, since last reset)")
            logInfo "New max humidity: ${pct}% @ ${ts}"
        }
    }
}

// ── Reporting configuration ──────────────────────────────────────────────────

private List<String> reportingConfigCmds() {
    List<String> cmds = []

    int tMin = Math.max(1, Math.min(3600, safeInt(minReportTemp, 10)))
    int tMax = Math.max(tMin, Math.min(43200, safeInt(maxReportTemp, 3600)))
    Integer tempDelta = Math.round(prefTempChange() * 100) as Integer
    cmds += zigbee.configureReporting(CLUSTER_TEMP, 0x0000, DataType.INT16, tMin, tMax, tempDelta, [:], 200)

    int hMin = Math.max(1, Math.min(3600, safeInt(minReportHumi, 10)))
    int hMax = Math.max(hMin, Math.min(43200, safeInt(maxReportHumi, 3600)))
    int humiStep = Math.max(1, Math.min(10, safeInt(humiChange, 1)))
    cmds += zigbee.configureReporting(CLUSTER_HUMIDITY, 0x0000, DataType.UINT16, hMin, hMax, humiStep * 100, [:], 200)

    String pressLog = ''
    if (isModelM()) {
        // Configure BOTH the whole-hPa attribute (fallback) and the high-res INT32 attribute
        // (preferred). The reportable change is in hPa for 0x0000 and in 0.01 hPa for 0x0004.
        int pMin = Math.max(1, Math.min(3600, safeInt(minReportPressure, 60)))
        int pMax = Math.max(pMin, Math.min(43200, safeInt(maxReportPressure, 3600)))
        int pressDelta = Math.max(1, Math.min(50, safeInt(pressureChange, 1)))
        cmds += zigbee.configureReporting(CLUSTER_PRESSURE, PRESSURE_ATTR, DataType.INT16, pMin, pMax, pressDelta, [:], 200)
        cmds += zigbee.configureReporting(CLUSTER_PRESSURE, PRESSURE_ATTR_PRECISE, DataType.INT32, pMin, pMax, pressDelta * 100, [:], 200)
        pressLog = ", pressure ${pMin}-${pMax}s / ${pressDelta} hPa"
    }

    cmds += zigbee.configureReporting(CLUSTER_POWER_CFG, 0x0021, DataType.UINT8, 60, 14400, 1, [:], 200)

    logDebug "Reporting config: temp ${tMin}-${tMax}s / ${tempDelta / 100.0}°C, humidity ${hMin}-${hMax}s / ${humiStep}%${pressLog}"
    return cmds
}

// ── FC11 helpers ─────────────────────────────────────────────────────────────

// Settings read back from the sensor: comfort thresholds (+ LCD unit on the SNZB-02DR2,
// + calibration on the SNZB-02M). Nothing for the SNZB-02P / SNZB-02D.
private List<String> fc11SettingsReadCmds() {
    List<String> cmds = []
    if (isModelDR2()) {
        [FC11_COMFORT_TMIN, FC11_COMFORT_TMAX, FC11_COMFORT_HMIN, FC11_COMFORT_HMAX, FC11_TEMP_UNIT,
         FC11_TEMP_CAL, FC11_HUMI_CAL].each { int a ->
            cmds += zigbee.readAttribute(CLUSTER_FC11, a, FC11_MFG_DR2, 200)
        }
    } else if (isModelM()) {
        [FC11_COMFORT_TMAX, FC11_COMFORT_TMIN, FC11_COMFORT_HMIN, FC11_COMFORT_HMAX,
         FC11_TEMP_CAL, FC11_HUMI_CAL, FC11_PRESS_CAL].each { int a ->
            cmds += zigbee.readAttribute(CLUSTER_FC11, a, [:], 200)
        }
    }
    return cmds
}

// SNZB-02DR2 only: comfort thresholds and LCD unit
private List<String> writeFC11Settings() {
    List<String> cmds = []

    Integer tempMinRaw = Math.round(prefComfortTempMin() * 100) as Integer
    cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_COMFORT_TMIN, DataType.INT16, tempMinRaw, FC11_MFG_DR2, 200)

    Integer tempMaxRaw = Math.round(prefComfortTempMax() * 100) as Integer
    cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_COMFORT_TMAX, DataType.INT16, tempMaxRaw, FC11_MFG_DR2, 200)

    Integer humiMinRaw = Math.max(5, Math.min(95, safeInt(pref_comfortHumiMin, 40))) * 100
    cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_COMFORT_HMIN, DataType.UINT16, humiMinRaw, FC11_MFG_DR2, 200)

    Integer humiMaxRaw = Math.max(5, Math.min(95, safeInt(pref_comfortHumiMax, 60))) * 100
    cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_COMFORT_HMAX, DataType.UINT16, humiMaxRaw, FC11_MFG_DR2, 200)

    // LCD temperature unit — UINT16, 0 = Celsius, 1 = Fahrenheit (confirmed on firmware 1.0.2).
    // The type comes from state.lcdUnitType (default UINT16); if a device answers 0x8D (invalid
    // data type), retryLcdUnitWrite() tries the other types in LCD_UNIT_TYPES.
    String unitPref = (lcdTempUnit ?: 'nochange') as String
    if (unitPref == 'C' || unitPref == 'F') {
        int type = (state.lcdUnitType ?: LCD_UNIT_TYPES[0]) as int
        state.lcdUnitTried = [type]
        cmds += lcdUnitWriteCmd(unitPref, type)
    }

    // Calibration (offsets in the sensor) — INT16, °C × 100 and % × 100. Written while the setting
    // is on, and as 0 after it has been turned off until the sensor confirms 0.
    if (settings?.offsetsInDevice || state.devOffsetsManaged) {
        boolean on = settings?.offsetsInDevice as boolean
        int tCal = on ? roundTo(prefTempOffset(), 2).movePointRight(2).intValue() : 0
        int hCal = on ? roundTo(prefHumiOffset(), 2).movePointRight(2).intValue() : 0
        if (on) { state.devOffsetsManaged = true }
        cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_TEMP_CAL, DataType.INT16, tCal & 0xFFFF, FC11_MFG_DR2, 200)
        cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_HUMI_CAL, DataType.INT16, hCal & 0xFFFF, FC11_MFG_DR2, 200)
        logInfo "Offsets: writing calibration ${String.format('%.2f', tCal / 100.0)}°C / ${String.format('%.2f', hCal / 100.0)}% to the sensor"
    }

    logDebug "Writing FC11 settings: tempMin=${tempMinRaw / 100.0} tempMax=${tempMaxRaw / 100.0} humiMin=${humiMinRaw / 100.0} humiMax=${humiMaxRaw / 100.0} lcdUnit=${unitPref}"
    return cmds
}

private List<String> lcdUnitWriteCmd(String unitPref, int type) {
    state.lcdUnitType = type
    logDebug "Writing LCD temperature unit ${unitPref} as data type 0x${Integer.toHexString(type)}"
    return zigbee.writeAttribute(CLUSTER_FC11, FC11_TEMP_UNIT, type, unitPref == 'F' ? 1 : 0, FC11_MFG_DR2, 200)
}

// Called when the device rejects the LCD-unit write as the wrong data type (status 0x8D).
// The rejection arrives within a second or two of the write, while the device is still
// awake, so the next candidate type is tried immediately and the attribute read back.
// The type that is not rejected stays in state.lcdUnitType and is used for later Saves.
private void retryLcdUnitWrite() {
    String unitPref = (lcdTempUnit ?: 'nochange') as String
    if (unitPref != 'C' && unitPref != 'F') { return }
    List<Integer> tried = ((state.lcdUnitTried ?: []) as List).collect { it as int }
    Integer next = LCD_UNIT_TYPES.find { !(it in tried) }
    if (next == null) {
        logWarn 'LCD temperature unit: the device rejected every data type tried; ' +
                'change the unit on the device or in the eWeLink app instead'
        state.remove('lcdUnitType')
        return
    }
    tried << next
    state.lcdUnitTried = tried
    logInfo "LCD temperature unit: retrying with data type 0x${Integer.toHexString(next)}"
    List<String> cmds = lcdUnitWriteCmd(unitPref, next)
    cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_TEMP_UNIT, FC11_MFG_DR2, 200)
    sendZigbeeCommands(cmds)
}

// ── Remote display (EXT1 / EXT2), SNZB-02DR2 ─────────────────────────────────
// Values wait in state.remotePending (keys T1, H1, T2, H2 = raw °C × 100 or % × 100; off = true)
// until the sensor confirms them. Older method: confirmed by reading 0x600D / 0x6018 back.
// Remote-source method: confirmed when both writes (0x600E and 0x601E) are accepted.
// Values confirmed so far are kept in state.remoteValues (the 1.0.5 method re-sends all of them,
// because the sensor may drop stored items when its status is set to "using" again).

def setRemoteTemperature(temperature, slot = 'EXT1') { queueRemote('T', slot, temperature) }

def setRemoteHumidity(humidity, slot = 'EXT1') { queueRemote('H', slot, humidity) }

def remoteDisplayOff() {
    if (!remoteSupported('remoteDisplayOff')) { return }
    state.remotePending = [off: true]
    state.remoteValues  = [:]
    resetRemoteAttempts()
    sendEvent(name: 'remoteDisplay', value: 'pending', descriptionText: 'Switching the display back to the built-in sensor')
    logInfo 'Remote display: switching back to the built-in sensor'
    sendRemote()
}

def sendRemotePending() { sendRemote() }

private boolean remoteSupported(String cmdName) {
    if (isModelDR2()) { return true }
    logWarn "${cmdName}: only the SNZB-02DR2 has a remote display"
    return false
}

private void queueRemote(String type, slot, value) {
    String cmdName = type == 'T' ? 'setRemoteTemperature' : 'setRemoteHumidity'
    if (!remoteSupported(cmdName)) { return }
    double v = safeDouble(value, Double.NaN)
    if (Double.isNaN(v)) { logWarn "${cmdName}: '${value}' is not a number"; return }

    int n = (slot ?: 'EXT1').toString().trim().endsWith('2') ? 2 : 1
    String method = remoteMethod()
    if (n == 2 && method == 'legacy') {
        logWarn "${cmdName}: EXT2 needs firmware 1.0.5 or later; this sensor ${fwText()} has EXT1 only"
        return
    }
    if (type == 'H' && method == 'legacy' && state.remoteHumiUnsupported) {
        logWarn "${cmdName}: this sensor ${fwText()} cannot show remote humidity (needs firmware 1.0.4 or later)"
        return
    }

    double c = v
    BigDecimal target = null   // temperature to show, in the LCD's unit
    boolean lcdF = false
    if (type == 'T') {
        lcdF = lcdShowsF()
        target = remoteLcdTarget(v, lcdF)
        if (target > LCD_EXT_MAX) {
            logInfo "${cmdName}: ${value} would show as HHH on the sensor's screen; " +
                    "sending ${LCD_EXT_MAX}°${lcdF ? 'F' : 'C'}, the highest it can show"
            target = LCD_EXT_MAX
        }
        c = lcdF ? ((target as double) - 32.0) / 1.8 : (target as double)
    }
    List lim = REMOTE_RANGE[method + type] as List
    if (c < (lim[0] as double) - 0.01 || c > (lim[1] as double) + 0.01) {
        String range = type == 'T' ? "${formatTemp(lim[0] as double)} to ${formatTemp(lim[1] as double)}" : "${lim[0]} to ${lim[1]}%"
        logWarn "${cmdName}: ${value} is outside the range the sensor accepts (${range})"
        return
    }

    int raw = type == 'T' ? remoteTempRaw(target, lcdF) : roundTo(c, 2).movePointRight(2).intValue()
    String key = type + n
    Map p = (state.remotePending ?: [:]) as Map
    p.remove('off')
    p[key] = raw
    state.remotePending = p
    resetRemoteAttempts()
    sendEvent(name: 'remoteDisplay', value: 'pending', descriptionText: 'Waiting for the sensor to confirm the remote values')
    String shown = type == 'T' ? formatTemp(raw / 100.0) : "${roundTo(raw / 100.0, 1)}%"
    logInfo "Remote display: EXT${n} ${type == 'T' ? 'temperature' : 'humidity'} ${shown} queued; " +
            'sent now and, if the sensor is asleep, again when it next wakes'
    sendRemote()
}

// Raw value (°C × 100) for a temperature entered in the hub's scale. The sensor converts the value
// to °F by cutting off, not rounding, the digits after the first decimal (70 °F sent as 2111 showed
// 69.9). On a °F screen the raw value is therefore rounded away from zero, so the sensor's result lands
// at or just past the wanted tenth (2112 → 70.016 → 70.0); one raw step is only 0.018 °F.
// Confirmed on firmware 1.0.2 for 70, 73, 75, 77 and 20 °F (the sensor cuts toward zero below 32 °F too).
private int remoteTempRaw(BigDecimal target, boolean lcdF) {
    if (!lcdF) { return target.movePointRight(2).intValue() }   // °C screen: exact
    return (target - 32).multiply(1000).divide(new BigDecimal(18), 0, BigDecimal.ROUND_UP).intValue()
}

// True if the sensor's screen shows °F (from lcdTempUnit; if unknown, assume the hub's scale)
private boolean lcdShowsF() {
    String lcd = (device.currentValue('lcdTempUnit') ?: '') as String
    return lcd == 'Fahrenheit' || (lcd != 'Celsius' && location.temperatureScale == 'F')
}

// A temperature entered in the hub's scale, converted to the screen's unit and rounded to 0.1
private BigDecimal remoteLcdTarget(double entered, boolean lcdF) {
    double c = location.temperatureScale == 'F' ? (entered - 32.0) / 1.8 : entered
    return roundTo(lcdF ? c * 1.8 + 32.0 : c, 1)
}

// 'legacy' (before 1.0.5) or 'sources' (1.0.5 and later). Unknown firmware uses 'legacy', as Zigbee2MQTT does.
private String remoteMethod() {
    String pref = (settings?.remoteMethod ?: 'auto') as String
    if (pref in ['legacy', 'sources']) { return pref }
    if (state.remoteMethodOverride) { return state.remoteMethodOverride as String }
    Integer fw = firmwareVersion()
    return (fw != null && fw >= FW_REMOTE_SOURCES) ? 'sources' : 'legacy'
}

// Firmware file version, e.g. 0x1002 for 1.0.2, from the "Firmware MT" data value (1286-0810-00001002)
// or "Software Build" ("1.0.2" or "00001002"); null if neither is present
private Integer firmwareVersion() {
    String mt = device.getDataValue('firmwareMT')
    if (mt) {
        String last = mt.tokenize('-').last()
        if (last ==~ /[0-9A-Fa-f]{1,8}/) { return Long.parseLong(last, 16).intValue() }
    }
    String sb = device.getDataValue('softwareBuild')
    if (!sb) { return null }
    if (sb ==~ /\d+\.\d+\.\d+/) {
        List<Integer> p = sb.tokenize('.').collect { it as int }
        return (p[0] << 12) | (p[1] << 8) | p[2]
    }
    if (sb ==~ /[0-9A-Fa-f]{8}/) { return Long.parseLong(sb, 16).intValue() }
    return null
}

private String fwText() {
    Integer v = firmwareVersion()
    return v == null ? '(firmware unknown)' : "(firmware ${(v >> 12) & 0xF}.${(v >> 8) & 0xF}.${v & 0xFF})"
}

private void resetRemoteAttempts() {
    state.remoteAttempts = 0
    state.remove('remoteGaveUp')
}

private void sendRemote() {
    Map p = (state.remotePending ?: [:]) as Map
    if (!p) { return }
    String method = remoteMethod()
    state.remoteLastSend = now()
    state.remoteAttempts = ((state.remoteAttempts ?: 0) as int) + 1
    state.remoteAcceptsDue = 0
    List<String> cmds = []

    if (p.off) {
        cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, DataType.UINT8, 0, [:], 200)
        cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, [:], 200)
    } else if (method == 'legacy') {
        cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, DataType.UINT8, 1, [:], 200)
        if (p.T1 != null) {
            cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_EXT_TEMP, DataType.INT16, (p.T1 as int) & 0xFFFF, [:], 200)
        }
        if (p.H1 != null) {
            cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_EXT_HUMI, DataType.UINT16, (p.H1 as int) & 0xFFFF, [:], 200)
        }
        cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, [:], 200)
        if (p.T1 != null) { cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_EXT_TEMP, [:], 200) }
        if (p.H1 != null) { cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_EXT_HUMI, [:], 200) }
    } else {
        Map items = ((state.remoteValues ?: [:]) as Map) + p   // every known slot; pending values win
        state.remoteAcceptsDue = 2
        cmds += zigbee.writeAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, DataType.UINT8, 1, [:], 200)
        cmds += remoteItemsWriteCmd(items)
        cmds += zigbee.readAttribute(CLUSTER_FC11, FC11_REMOTE_SELECT, [:], 200)
    }
    logDebug "Remote display: sending ${p} by the ${method} method (attempt ${state.remoteAttempts})"
    sendZigbeeCommands(cmds)
}

// Write Attributes frame for 0x601E, built by hand because zigbee.writeAttribute() cannot encode a
// ZCL array. Array of UINT8: header 01 01 00 03 <length> <count>, then per item: type (0 temperature,
// 1 humidity), slot (0 EXT1, 1 EXT2), state (1 online), value length (2), value × 100 little-endian.
private List<String> remoteItemsWriteCmd(Map items) {
    List<Integer> body = []
    int count = 0
    ['T1', 'T2', 'H1', 'H2'].each { String k ->
        if (items[k] == null) { return }
        int raw = (items[k] as int) & 0xFFFF
        body += [k.startsWith('T') ? 0x00 : 0x01, k.endsWith('2') ? 0x01 : 0x00, 0x01, 0x02, raw & 0xFF, (raw >> 8) & 0xFF]
        count++
    }
    List<Integer> el = [0x01, 0x01, 0x00, 0x03, 1 + body.size(), count] + body
    String frame = "10 00 02 ${hex2(FC11_REMOTE_ITEMS)} ${hex2(FC11_REMOTE_ITEMS >> 8)} 48 20 ${hex2(el.size())} 00 " +
                   el.collect { int b -> hex2(b) }.join(' ')
    String ep = (device.endpointId ?: '01') as String
    return ["he raw 0x${device.deviceNetworkId} 1 0x${ep} 0xFC11 {${frame}}", 'delay 200']
}

private static String hex2(int v) { return String.format('%02X', v & 0xFF) }

// Called from parse(): the sensor has just sent something, so it is awake for a moment
private void remoteResendIfDue() {
    if (!state.remotePending || !isModelDR2()) { return }
    // A send reaching a sleeping sensor is held by its parent for about 7.5 s, so re-sending sooner
    // than 8 s would only duplicate it (and would repeat on the sensor's own replies to the send)
    if (now() - ((state.remoteLastSend ?: 0L) as long) < 8000L) { return }
    if (((state.remoteAttempts ?: 0) as int) >= REMOTE_MAX_ATTEMPTS) {
        if (!state.remoteGaveUp) {
            state.remoteGaveUp = true
            logWarn "Remote display: the sensor has not confirmed the values after ${REMOTE_MAX_ATTEMPTS} tries; " +
                    'press its button and send them again'
        }
        return
    }
    sendRemote()   // at once: the sensor listens only briefly after it has sent something
}

private void remoteSelectReported(int v) {
    Map p = (state.remotePending ?: [:]) as Map
    if (p.off && v == 0) {
        p.remove('off')
        state.remotePending = p
        resetRemoteAttempts()
        ['ext1Temperature', 'ext1Humidity', 'ext2Temperature', 'ext2Humidity'].each { String a ->
            try { device.deleteCurrentState(a) } catch (ignored) { }
        }
        logInfo 'Remote display off: the sensor shows only its own readings'
    }
    String disp = v == 1 ? 'on' : (v == 2 ? 'offline' : 'off')
    if (state.remotePending) { disp = 'pending' }
    if (device.currentValue('remoteDisplay') != disp) {
        sendEvent(name: 'remoteDisplay', value: disp, descriptionText: "Remote display is ${disp}")
    }
    logDebug "FC11 0x600E remote display select = ${v}"
}

private void remoteValueReported(String key, int val) {
    Map p = (state.remotePending ?: [:]) as Map
    if (p[key] != null && (p[key] as int) == val) {
        p.remove(key)
        state.remotePending = p
        confirmRemote(key, val)
    } else {
        logDebug "Remote display: sensor holds ${key} = ${val}"
    }
}

// 0x601E method: both writes accepted = the values are stored
private void remoteWriteAccepted() {
    int due = (state.remoteAcceptsDue ?: 0) as int
    if (due <= 0 || now() - ((state.remoteLastSend ?: 0L) as long) > 30000L) { return }
    state.remoteAcceptsDue = --due
    if (due > 0) { return }
    Map p = (state.remotePending ?: [:]) as Map
    state.remotePending = [:]
    p.each { String k, v -> if (k != 'off') { confirmRemote(k, v as int) } }
}

private void confirmRemote(String key, int raw) {
    Map vals = (state.remoteValues ?: [:]) as Map
    vals[key] = raw
    state.remoteValues = vals
    String slot = key.endsWith('2') ? 'ext2' : 'ext1'
    if (key.startsWith('T')) {
        BigDecimal t = tempOut(raw / 100.0)
        sendEvent(name: slot + 'Temperature', value: t, unit: tempUnit(),
                  descriptionText: "${slot.toUpperCase()} temperature ${t}${tempUnit()} confirmed by the sensor")
        logInfo "Remote display: ${slot.toUpperCase()} temperature ${t}${tempUnit()} confirmed by the sensor"
    } else {
        BigDecimal h = roundTo(raw / 100.0, 1)
        sendEvent(name: slot + 'Humidity', value: h, unit: '%',
                  descriptionText: "${slot.toUpperCase()} humidity ${h}% confirmed by the sensor")
        logInfo "Remote display: ${slot.toUpperCase()} humidity ${h}% confirmed by the sensor"
    }
    if (!state.remotePending) {
        resetRemoteAttempts()
        sendEvent(name: 'remoteDisplay', value: 'on', descriptionText: 'Remote display is on')
    }
}

private void remoteWriteRejected(String attr, String status) {
    String a = attr.toUpperCase()
    String s = status.toUpperCase()
    if (!(a in ['0X600E', '0X600D', '0X6018', '0X601E'])) { return }
    Map p = (state.remotePending ?: [:]) as Map

    if (a == '0X6018') {
        p.remove('H1')
        state.remotePending = p
        if (s == '86') {
            state.remoteHumiUnsupported = true
            logWarn "Remote display: this sensor ${fwText()} cannot show remote humidity (needs firmware 1.0.4 or later); only temperature will be sent"
        }
        if (!p && (state.remoteValues as Map)) {   // the temperature may already have been confirmed
            resetRemoteAttempts()
            sendEvent(name: 'remoteDisplay', value: 'on', descriptionText: 'Remote display is on')
        }
    } else if (a == '0X601E' && s == '86' && (settings?.remoteMethod ?: 'auto') == 'auto') {
        state.remoteMethodOverride = 'legacy'
        state.remoteAcceptsDue = 0
        ['T2', 'H2'].each { String k -> p.remove(k) }
        state.remotePending = p
        logWarn "Remote display: this sensor ${fwText()} does not support the 1.0.5 method; using the older method (EXT1 only)"
        sendRemote()   // the sensor is awake now
    } else if (a == '0X601E' && s == '87') {
        logWarn 'Remote display: the sensor refused the values (status 0x87); they will be sent again when it next wakes'
    } else if (s == '86') {
        state.remotePending = [:]
        resetRemoteAttempts()
        sendEvent(name: 'remoteDisplay', value: 'unsupported', descriptionText: 'The sensor does not support this remote display method')
        logWarn "Remote display: this sensor ${fwText()} does not support attribute ${attr} " +
                "(${remoteMethod()} method); try the other setting of \"Remote display method\""
    }
}

// ── Preference validation ────────────────────────────────────────────────────

// Effective (range-checked) values for decimal preferences; temperatures in °C
private double prefTempChange()     { return tempPrefC('tempChange') }
private double prefTempOffset()     { return tempPrefC('temperatureOffset') }
private double prefComfortTempMin() { return tempPrefC('pref_comfortTempMin') }
private double prefComfortTempMax() { return tempPrefC('pref_comfortTempMax') }
private double prefHumiOffset()     { return clampD(safeDouble(humidityOffset, 0.0), OFFSET_MIN, OFFSET_MAX) }
private double prefPressOffset()    { return clampD(safeDouble(pressureOffset, 0.0), PRESS_OFFSET_MIN, PRESS_OFFSET_MAX) }

// Rewrites any out-of-range decimal setting (e.g. a reporting threshold saved as 0.0)
// so the preference page shows the value actually being used.
private void validatePreferences() {
    validateTempPrefs()
    fixDecimalSetting('humidityOffset', humidityOffset, prefHumiOffset())
    fixDecimalSetting('pressureOffset', pressureOffset, prefPressOffset())
}

private void fixDecimalSetting(String name, Object current, double valid) {
    if (current == null) { return }
    if (safeDouble(current, valid) != valid) {
        logWarn "Preference '${name}' value ${current} is out of range; using ${valid}"
        device.updateSetting(name, [value: valid, type: 'decimal'])
    }
}

private static double clampD(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v))
}

// ── Sensor status ────────────────────────────────────────────────────────────

// sensorStatus reads STATUS_WAITING until the first reading arrives, then STATUS_REPORTING
private void markReporting() {
    if (device.currentValue('sensorStatus') != STATUS_REPORTING) {
        sendEvent(name: 'sensorStatus', value: STATUS_REPORTING, descriptionText: "${device.displayName} is reporting")
        logInfo 'sensor is reporting'
    }
}

// Sets sensorStatus after an upgrade or a driver change, when it has no value yet
private void ensureSensorStatus() {
    if (device.currentValue('sensorStatus') == null) {
        boolean hasData = state.rawTempC != null || state.rawHumi != null || state.rawPressHPa != null
        sendEvent(name: 'sensorStatus', value: hasData ? STATUS_REPORTING : STATUS_WAITING)
    }
}

// Clean-up after switching from the separate SNZB-02x / SNZB-02DR2 / SNZB-02M drivers
private void removeLegacyState() {
    unschedule('commandTimeout')
    ['pingTime', 'lastTempC', 'lastHumi'].each { String k -> state.remove(k) }
    // rtt (Ping) and the SNZB-02DR2 driver's comfortHumiMin/Max were replaced
    ['rtt', 'comfortHumiMin', 'comfortHumiMax'].each { String a ->
        try { device.deleteCurrentState(a) } catch (ignored) { }
    }
    // The SNZB-02DR2 driver stored comfortTempMin/Max as text such as '64.4°F'; they are
    // numbers now and are re-read from the device on the next Save, Configure or Refresh
    ['comfortTempMin', 'comfortTempMax'].each { String a ->
        try {
            def v = device.currentValue(a)
            if (v != null && !(v instanceof Number)) { device.deleteCurrentState(a) }
        } catch (ignored) { }
    }
}

// ── Temperature settings: units, conversion and rounding ─────────────────────

private String prefUnit() { return (tempPrefUnit ?: 'C') as String }

private static double cToUnit(double c, String unit, boolean absolute) {
    if (unit != 'F') { return c }
    return absolute ? c * 1.8 + 32.0 : c * 1.8
}

private static double unitToC(double v, String unit, boolean absolute) {
    if (unit != 'F') { return v }
    return absolute ? (v - 32.0) / 1.8 : v / 1.8
}

// Entered value of a temperature preference. A conversion or correction made in the last minute
// takes precedence, because the settings map may not reflect device.updateSetting() until later.
private Object tempSettingRaw(String name) {
    Map p = state.tempPrefPending as Map
    if (p != null && (now() - ((p.ts ?: 0L) as long)) < 60000L && ((p.values ?: [:]) as Map).containsKey(name)) {
        return p.values[name]
    }
    return settings[name]
}

private void setTempSetting(String name, BigDecimal value) {
    Map p = (state.tempPrefPending ?: [:]) as Map
    Map values = (p.values ?: [:]) as Map
    values[name] = value
    state.tempPrefPending = [ts: now(), values: values]
    device.updateSetting(name, [value: value, type: 'decimal'])
}

// Default value of a temperature preference, shown in the unit currently selected. The page shows
// this only while the setting has never been saved; it must follow the unit, because Hubitat stores
// whatever is displayed when Save is clicked (a fixed 18.0 would be saved as 18 °F).
private BigDecimal tempPrefDefault(String name) {
    BigDecimal d = roundTo(cToUnit(TEMP_PREF_DEFAULT_C[name] as double, prefUnit(), TEMP_PREF_ABSOLUTE[name] as boolean), 2)
            .stripTrailingZeros()
    return d.scale() < 0 ? d.setScale(0) : d
}

// Effective value of a temperature preference in °C, clamped to its valid range
private double tempPrefC(String name) {
    List range = TEMP_PREF_RANGE_C[name] as List
    def v = tempSettingRaw(name)
    double c = (v == null) ? (TEMP_PREF_DEFAULT_C[name] as double)
                           : unitToC(safeDouble(v, 0.0), prefUnit(), TEMP_PREF_ABSOLUTE[name] as boolean)
    return clampD(c, range[0] as double, range[1] as double)
}

// When the unit preference changes, convert the temperature values already entered so they keep
// the same meaning. A value the user also edited in the same Save is taken as already in the new unit.
private void convertTempPrefsIfUnitChanged() {
    String unit = prefUnit()
    String prev = (state.tempPrefUnit ?: 'C') as String
    if (unit != prev) {
        Map last = state.lastTempPrefs as Map
        TEMP_PREF_ABSOLUTE.each { String name, Boolean abs ->
            def v = settings[name]
            if (v == null) { return }
            if (last != null && last[name] != null &&
                    Math.abs(safeDouble(v, 0.0) - safeDouble(last[name], 0.0)) > 1e-6) {
                logInfo "Preference '${name}' was changed together with the unit; using ${v}°${unit} as entered"
                return
            }
            BigDecimal conv = roundTo(cToUnit(unitToC(safeDouble(v, 0.0), prev, abs), unit, abs), 2)
            setTempSetting(name, conv)
            logInfo "Preference '${name}' converted from ${v}°${prev} to ${conv}°${unit}"
        }
    }
    state.tempPrefUnit = unit
}

// Corrects any out-of-range temperature preference (e.g. a reporting threshold saved as 0.0)
private void validateTempPrefs() {
    String unit = prefUnit()
    TEMP_PREF_ABSOLUTE.each { String name, Boolean abs ->
        def v = tempSettingRaw(name)
        if (v == null) { return }
        double enteredC = unitToC(safeDouble(v, 0.0), unit, abs)
        double validC = tempPrefC(name)
        if (Math.abs(validC - enteredC) > 1e-6) {
            BigDecimal fixed = roundTo(cToUnit(validC, unit, abs), 2)
            logWarn "Preference '${name}' value ${v}°${unit} is out of range; using ${fixed}°${unit}"
            setTempSetting(name, fixed)
        }
    }
}

// Remembers the values as saved, so the next unit change can tell which ones the user edited
private void snapshotTempPrefs() {
    Map snap = [:]
    TEMP_PREF_ABSOLUTE.each { String name, Boolean abs -> snap[name] = tempSettingRaw(name) }
    state.lastTempPrefs = snap
    state.remove('tempPrefPending')
}

// Rounds half-up (away from zero at .5) to the given number of decimal places. The value is first
// rounded to 6 places to remove binary floating-point noise (e.g. 72.95 held as 72.94999…).
private static BigDecimal roundTo(double v, int places) {
    return BigDecimal.valueOf(v).setScale(6, BigDecimal.ROUND_HALF_UP).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private int tempPlaces() {
    try { return Math.max(0, Math.min(2, Integer.parseInt((tempPrecision ?: '1').toString()))) } catch (e) { return 1 }
}

// °C → hub temperature scale, rounded to the selected number of decimal places
private BigDecimal tempOut(double c) {
    return roundTo(location.temperatureScale == 'F' ? c * 1.8 + 32.0 : c, tempPlaces())
}

private String tempUnit() { return location.temperatureScale == 'F' ? '°F' : '°C' }

// ── Health check ──────────────────────────────────────────────────────────────

def setPresent() {
    if ((device.currentValue('healthStatus') ?: 'unknown') != 'online') {
        sendEvent(name: 'healthStatus', value: 'online', type: 'digital')
        logInfo 'device is present (online)'
    }
    state.missedChecks = 0
}

def deviceHealthCheck() {
    state.missedChecks = (state.missedChecks ?: 0) + 1
    if (state.missedChecks > PRESENCE_THRESHOLD) {
        if ((device.currentValue('healthStatus', true) ?: 'unknown') != 'offline') {
            sendEvent(name: 'healthStatus', value: 'offline', type: 'digital')
            logWarn 'device has not reported — marking offline'
        }
    } else {
        logDebug "health check — ok (missed=${state.missedChecks})"
    }
}

private void scheduleHealthCheck() {
    Random rnd = new Random()
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(59)} 1/3 * * ? *", 'deviceHealthCheck')
    rescheduleAutoReset()
}

// ── Init helpers ─────────────────────────────────────────────────────────────

private void initVars(boolean fullInit) {
    if (fullInit) {
        state.minTemp         = null
        state.maxTemp         = null
        state.minHumi         = null
        state.maxHumi         = null
        state.rawTempC        = null
        state.rawHumi         = null
        state.rawPressHPa     = null
        state.rawPressPrecise = false
        state.lastPreciseTs   = 0
        state.missedChecks    = 0
        state.readingCount    = 0
        String ts = new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)
        sendEvent(name: 'healthStatus',         value: 'unknown')
        sendEvent(name: 'sensorStatus',         value: STATUS_WAITING, descriptionText: 'Waiting for the first report from the sensor')
        sendEvent(name: 'Last Reading',         value: '--', descriptionText: 'No reading yet')
        sendEvent(name: 'Min/Max Reset',        value: ts,   descriptionText: "Min/max reset at ${ts}")
        sendEvent(name: 'Readings Since Reset', value: 0,    descriptionText: 'Reading counter initialized')
        sendEvent(name: 'Temperature Min',      value: '--')
        sendEvent(name: 'Temperature Max',      value: '--')
        sendEvent(name: 'Humidity Min',         value: '--')
        sendEvent(name: 'Humidity Max',         value: '--')
        if (isModelDR2()) {
            // Device-native min/max: '--' keeps them visible until the device's hourly report
            ['Device Temp Max', 'Device Temp Min', 'Device Humidity Max', 'Device Humidity Min'].each { String a ->
                sendEvent(name: a, value: '--', descriptionText: 'Waiting for device report')
            }
            sendEvent(name: 'lcdTempUnit', value: '--')
        }
    }
    state.driverVersion = "${VERSION} ${TIME_STAMP}"
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) { return }
    logDebug "sendZigbeeCommands: ${cmds}"
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmds.each { allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)) }
    sendHubCommand(allActions)
}

private double safeDouble(val, double defaultVal = 0.0) {
    try { return val != null ? (val as double) : defaultVal } catch (e) { return defaultVal }
}

private int safeInt(val, int defaultVal = 0) {
    try { return val != null ? (val as int) : defaultVal } catch (e) { return defaultVal }
}

def logsOff() {
    log.warn "${device.displayName} debug logging disabled"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

private void logDebug(String msg) { if (logEnable) { log.debug "${device.displayName} ${msg}" } }
private void logInfo(String msg)  { if (txtEnable) { log.info  "${device.displayName} ${msg}" } }
private void logWarn(String msg)  {                  log.warn  "${device.displayName} ${msg}"   }
