local fiitmeteo = Proto("fiitmeteo", "FIITMeteo")

local REGISTER = 0
local ACK = 1
local DATA = 2
local RETRY = 3
local KEEPALIVE = 4
local KEEPALIVE_ACK = 5
local REJECT = 6

local THERMO = 0
local WIND = 1
local RAIN = 2
local AIR = 3

local SEQ_BYTES = 4
local TOKEN_BYTES = 8
local REFERENCE_BYTES = 4
local CRC_BYTES = 4

local messageNames = {
    [REGISTER] = "REGISTER",
    [ACK] = "ACK",
    [DATA] = "DATA",
    [RETRY] = "RETRY",
    [KEEPALIVE] = "KEEPALIVE",
    [KEEPALIVE_ACK] = "KEEPALIVE_ACK",
    [REJECT] = "REJECT"
}

local kindField = ProtoField.uint8("fiitmeteo.kind", "Typ správy", base.DEC,
    messageNames, 0xE0)
local sensorField = ProtoField.uint8("fiitmeteo.sensor", "Typ senzora", base.DEC,
    { [THERMO] = "ThermoNode", [WIND] = "WindSense", [RAIN] = "RainDetect", [AIR] = "AirQuality" },
    0x18)
local idLengthField = ProtoField.uint8("fiitmeteo.id_length", "Dĺžka ID", base.DEC)
local idField = ProtoField.string("fiitmeteo.sensor_id", "ID senzora")
local seqField = ProtoField.uint32("fiitmeteo.seq", "Poradové číslo", base.DEC)
local crcField = ProtoField.uint32("fiitmeteo.crc", "CRC32", base.HEX)

local tokenField = ProtoField.uint64("fiitmeteo.token", "Token", base.DEC)
local referenceField = ProtoField.uint32("fiitmeteo.ack_for", "Číslo pôvodnej správy", base.DEC)
local reasonField = ProtoField.uint8("fiitmeteo.reason", "Dôvod odmietnutia", base.DEC, nil, 0x07)

local temperatureField = ProtoField.double("fiitmeteo.temperature", "Teplota (°C)")
local humidityField = ProtoField.double("fiitmeteo.humidity", "Vlhkosť (%)")
local dewPointField = ProtoField.double("fiitmeteo.dew_point", "Rosný bod (°C)")
local pressureField = ProtoField.double("fiitmeteo.pressure", "Tlak (hPa)")

local windSpeedField = ProtoField.double("fiitmeteo.wind_speed", "Rýchlosť vetra")
local windGustField = ProtoField.double("fiitmeteo.wind_gust", "Náraz vetra")
local windDirectionField = ProtoField.uint16("fiitmeteo.wind_direction", "Smer vetra (°)", base.DEC)
local turbulenceField = ProtoField.double("fiitmeteo.turbulence", "Turbulencia")

local rainfallField = ProtoField.double("fiitmeteo.rainfall", "Zrážky")
local soilMoistureField = ProtoField.double("fiitmeteo.soil_moisture", "Vlhkosť pôdy (%)")
local floodRiskField = ProtoField.uint8("fiitmeteo.flood_risk", "Riziko povodne", base.DEC)
local rainDurationField = ProtoField.uint8("fiitmeteo.rain_duration", "Trvanie dažďa", base.DEC)

local co2Field = ProtoField.uint16("fiitmeteo.co2", "CO2", base.DEC)
local ozoneField = ProtoField.double("fiitmeteo.ozone", "Ozón")
local airQualityIndexField = ProtoField.uint16("fiitmeteo.air_quality_index", "Index kvality vzduchu", base.DEC)

local measurementBytes = {
    [THERMO] = 6,
    [WIND] = 4,
    [RAIN] = 4,
    [AIR] = 5
}

fiitmeteo.fields = {
    kindField, sensorField, idLengthField, idField, seqField, crcField,
    tokenField, referenceField, reasonField,
    temperatureField, humidityField, dewPointField, pressureField,
    windSpeedField, windGustField, windDirectionField, turbulenceField,
    rainfallField, soilMoistureField, floodRiskField, rainDurationField,
    co2Field, ozoneField, airQualityIndexField
}

local function addMeasurements(packetTree, sensorType, measurements)
    -- bit positions start at the left of the measurement block
    if sensorType == THERMO then
        local temperature = (measurements:bitfield(0, 11) - 500) / 10
        local humidity = measurements:bitfield(11, 10) / 10
        local dewPoint = (measurements:bitfield(21, 11) - 500) / 10
        local pressure = (measurements:bitfield(32, 15) + 80000) / 100

        packetTree:add(temperatureField, measurements, temperature)
        packetTree:add(humidityField, measurements, humidity)
        packetTree:add(dewPointField, measurements, dewPoint)
        packetTree:add(pressureField, measurements, pressure)
    elseif sensorType == WIND then
        local windSpeed = measurements:bitfield(0, 9) / 10
        local windGust = measurements:bitfield(9, 10) / 10
        local windDirection = measurements:bitfield(19, 9)
        local turbulence = measurements:bitfield(28, 4) / 10

        packetTree:add(windSpeedField, measurements, windSpeed)
        packetTree:add(windGustField, measurements, windGust)
        packetTree:add(windDirectionField, measurements, windDirection)
        packetTree:add(turbulenceField, measurements, turbulence)
    elseif sensorType == RAIN then
        local rainfall = measurements:bitfield(0, 13) / 10
        local soilMoisture = measurements:bitfield(13, 10) / 10
        local floodRisk = measurements:bitfield(23, 2)
        local rainDuration = measurements:bitfield(25, 6)

        packetTree:add(rainfallField, measurements, rainfall)
        packetTree:add(soilMoistureField, measurements, soilMoisture)
        packetTree:add(floodRiskField, measurements, floodRisk)
        packetTree:add(rainDurationField, measurements, rainDuration)
    elseif sensorType == AIR then
        local co2 = measurements:bitfield(0, 13) + 300
        local ozone = measurements:bitfield(13, 13) / 10
        local airQualityIndex = measurements:bitfield(26, 9)

        packetTree:add(co2Field, measurements, co2)
        packetTree:add(ozoneField, measurements, ozone)
        packetTree:add(airQualityIndexField, measurements, airQualityIndex)
    end
end

function fiitmeteo.dissector(buffer, pinfo, tree)
    local length = buffer:len()
    if length < 11 then
        return 0
    end

    -- header: 3 kind bits, 2 sensor bits, 3 reason bits
    local header = buffer(0, 1):uint()
    local kind = header >> 5
    local messageName = messageNames[kind]
    if messageName == nil then
        return 0
    end

    local sensorType = (header >> 3) & 0x03
    local reason = header & 0x07
    if kind == REJECT then
        if reason < 1 or reason > 5 then
            return 0
        end
    elseif reason ~= 0 then
        return 0
    end

    -- the length byte stores the id length minus one
    local idLength = buffer(1, 1):uint() + 1
    local seqOffset = 2 + idLength
    local tokenOffset = seqOffset + SEQ_BYTES
    local extraFieldsOffset = tokenOffset + TOKEN_BYTES
    local measurementsLength = measurementBytes[sensorType]
    local hasReference = kind == ACK or kind == RETRY or kind == KEEPALIVE_ACK or kind == REJECT

    -- crc starts right after the last field
    local crcOffset
    if kind == REGISTER then
        crcOffset = tokenOffset
    elseif kind == DATA then
        crcOffset = extraFieldsOffset + measurementsLength
    elseif hasReference then
        crcOffset = extraFieldsOffset + REFERENCE_BYTES
    else
        crcOffset = extraFieldsOffset
    end

    if idLength > 16 or length ~= crcOffset + CRC_BYTES then
        return 0
    end

    local sensorId = buffer(2, idLength)
    pinfo.cols.protocol = "FIITMeteo"
    pinfo.cols.info = messageName .. " " .. sensorId:string()
    local packetTree = tree:add(fiitmeteo, buffer(), "FIITMeteo " .. messageName)
    packetTree:add(kindField, buffer(0, 1))
    packetTree:add(sensorField, buffer(0, 1))
    if kind == REJECT then
        packetTree:add(reasonField, buffer(0, 1))
    end
    packetTree:add(idLengthField, buffer(1, 1), idLength)
    packetTree:add(idField, sensorId)
    packetTree:add(seqField, buffer(seqOffset, SEQ_BYTES))

    if kind ~= REGISTER then
        packetTree:add(tokenField, buffer(tokenOffset, TOKEN_BYTES))
    end

    if hasReference then
        packetTree:add(referenceField, buffer(extraFieldsOffset, REFERENCE_BYTES))
    elseif kind == DATA then
        addMeasurements(packetTree, sensorType, buffer(extraFieldsOffset, measurementsLength))
    end

    packetTree:add(crcField, buffer(crcOffset, CRC_BYTES))
    return length
end

local udpPorts = DissectorTable.get("udp.port")
udpPorts:add(9000, fiitmeteo)
udpPorts:add_for_decode_as(fiitmeteo)
