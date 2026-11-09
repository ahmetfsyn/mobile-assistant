# Hardware and Physical Safety Plan

This is a staged design, not a wiring guide or a claim that these parts are installed. Do not buy or power actuators until the enclosure, load, driver, supply, and stop mechanism have been specified and tested.

## Existing hardware and limits

| Part | Current role | Evidence and limit |
| --- | --- | --- |
| General Mobile GM 5 Plus | Landscape face, touch, microphone, speaker, Wi-Fi, Android app | Project `minSdk=24`; the device used in the preceding connection check reported API 25, so test both API 24 compatibility and this physical device. About 3 GB RAM; actual free memory, camera capability, and thermal behavior must be measured. The app holds the screen on while active; prolonged stand use needs charging/heat checks. |
| Mac with Docker Desktop | Local Node AI gateway and future durable memory/hardware gateway | Must be powered and on the private LAN for Gemini/backend requests. Current Compose binds port 3000 to the selected LAN IP and reuses a portable Node 22 image. |
| Phone microphone/speaker | Push-to-talk capture and Android TTS | Android platform STT is now the default and owns the microphone during recognition; its Turkish/offline behavior still needs testing on the physical phone. Audio output diagnostic screen exists for routing checks. |
| Phone camera | Potential future vision input | No camera permission or capture code currently exists. Desk stand angle may not show the room or user; inspect physical field of view before designing person-aware behavior. |

## Optional staged additions

| Stage | Parts and purpose | Cost and dependency |
| --- | --- | --- |
| Sensor prototype | ESP32 development board, one debounced physical button/touch sensor, one distance sensor, LED/status output, suitable USB supply. | Nonzero up-front cost unless already owned. Choose exact board/sensor after availability and power review. No motor in the first firmware integration. |
| Stationary expression | One or two small servos, suitable external regulated supply, driver or PWM interface, mechanical stops, fuse/current protection, strain relief, and physical emergency stop. | Nonzero parts/enclosure cost. Requires measured stall current, total torque, travel clearance, and a safe neutral pose. Never draw servo power from an ESP32 GPIO or the phone. |
| Mobile body | Chassis, wheels/drive motors, motor driver, battery/BMS/charger, obstacle sensors, bumper, emergency stop. | Substantially higher cost and hazard; outside the zero-up-front-cost baseline. Requires a separate motion safety review before any autonomous movement. |
| Additional camera | External camera module or host camera only if phone placement fails. | Extra hardware/bandwidth/privacy burden; not needed for initial vision prototype. |

The first physical milestone should be an LED and touch/distance sensor. A moving robot is a later option, not a hidden requirement for the Android companion.

## Proposed hardware abstraction

Backend `HardwareGateway` accepts validated high-level commands and emits typed telemetry; it does not expose raw PWM, GPIO, shell, or arbitrary URLs to AI. Example interfaces:

```text
readSensor(sensorId) -> { value, unit, quality, observedAt, deviceSeq }
setActuatorTarget(actuatorId, boundedTarget, maxSpeed, deadline, commandId) -> { accepted, reason }
stopAll(reason) -> { stopped, deviceSeq }
getHealth() -> { firmwareVersion, lastHeartbeat, stopLatched, powerState }
```

The ESP32 firmware maintains its own mapping from named actuator to GPIO/driver and hard mechanical limits. A future sensor model can change without altering Android or AI code. Telemetry carries quality/staleness; stale readings are not grounds for motion.

## ESP32 command path

1. Android Core receives a user request or a permitted local schedule. Tool policy checks the actor, capability, risk class, and consent.
2. Backend validates schema, command ID, expiry, allowed target/speed, and device identity. It sends only the bounded target to the paired ESP32.
3. ESP32 checks authentication/replay counter, local stop latch, travel limit, watchdog, power/driver health, and command deadline. It may refuse even an authorized backend command.
4. ESP32 acknowledges acceptance, then reports completion or fault. Android announces an action as completed only after observed completion, never upon model text or HTTP 202 alone.

For one ESP32 on a private LAN, start with coarse authenticated request/response plus low-rate health/sensor reads. Evaluate WebSocket only if event latency requires it; MQTT and a broker are justified only by multiple devices or measured pub/sub needs. Wi-Fi loss triggers local safe behavior. BLE can be prototyped later if Wi-Fi pairing is unsuitable. Plain HTTP plus an IP check is not sufficient authorization; implement pairing and authenticated/encrypted transport before actuators. See [architecture](ROBOT_ARCHITECTURE.md) and [technology evaluation](ROBOT_TECH_STACK.md).

## Non-negotiable motor safety

- **Physical stop:** a reachable, latched switch removes or disables actuator power independently of Android, Mac, Wi-Fi, and model software. Re-arm requires local human action.
- **Independent supply:** select a regulator for measured simultaneous stall load; fuse/protect it; use a shared signal reference only as required by the chosen driver. Check brownout on motor start. Never rely on phone USB power for servos.
- **Firmware limits:** fixed allowable travel, speed/acceleration ramps, maximum command duration, command expiry, replay/sequence checks, and watchdog/heartbeat timeout. On fault, choose a mechanically safe hold or de-energized state based on the actual linkage; “neutral angle” is not automatically safe.
- **Electrical/mechanical checks:** enclosure prevents pinching, cable snagging, and tip-over. Validate servo torque with load, mechanical hard stops, heat, and duty cycle. Keep fingers clear during tests.
- **No uncontrolled autonomy:** model-generated targets never bypass typed policy. Early phases require explicit user authorization for every movement; later scheduled movement needs a separately reviewed permission class and remains physically interruptible.

## Privacy and perception

- Microphone: current push-to-talk only; never record constantly as a hardware convenience. A future physical mute must disconnect or reliably disable capture and display its state.
- Camera: explicit permission/session, visible indicator, short frame lifetime, no continuous storage. Use downscaled frame difference first; person/object recognition requires accuracy and heat tests. A visual observation must be labeled uncertain unless a validated classifier produced it.
- Sensors: proximity is not identity or intent. Include calibration, range, invalid/stale status, and a way to disable sensor-triggered behavior.
- Battery: the current phone stand mode should monitor charge/temperature through platform APIs in a later phase and dim/sleep when appropriate. Never cover ventilation or bypass built-in charging protection.

## Hardware verification gates

| Gate | Test before advancing |
| --- | --- |
| Sensor-only prototype | ESP disconnect/reconnect, malformed/replayed packet, clock skew, stale sensor reading, button debounce, no unexpected network exposure. |
| First servo | Mechanical limit and speed tests with the actual load; stop switch during motion; Wi-Fi/Mac/phone power loss; ESP reboot; command timeout; brownout and stall current. |
| Multi-actuator body | Combined stall load, collision/pinch review, tip stability, enclosure strain relief, watchdog recovery, firmware downgrade/rollback. |
| Any autonomous movement | Explicit consent, quiet/mute mode, human override, safe obstacle behavior, audit trail, and no cloud/model dependence for emergency stop. |
