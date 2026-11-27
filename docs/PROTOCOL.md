# FitPro implementation constraints

This document describes the implemented protocol boundaries. It contains no device identity, ride recording or experiment log. Protocol naming comes from the upstream implementations credited in `app/assets/licenses/README.txt`.

## Transport and framing

The supported controller uses USB vendor/product `213c:0002`, interface 0, interrupt IN `0x81`, OUT `0x02`, and 64-byte reports. One `BikeSession` owns the interface. A normal claim is tried before a forced claim for the matching controller interface.

Logical frames are embedded in zero-padded reports:

```text
Request: device, total length, command, payload, checksum
Reply:   device, total length, command, status, payload, checksum
Checksum: sum of preceding logical-frame bytes modulo 256
```

Every OUT transfer must write a complete 64-byte report. Length, checksum, command, status, equipment binding and field width are checked before exposing a value. The parser accepts exact logical frames; USB padding is removed by the transport. MAIN is address 2; the supported bike identifies as equipment 8. Read responses may use the bound equipment address.

## Discovery and authentication

DeviceInfo (129), SystemInfo (130), VersionInfo (132) and SupportedCommands (136) establish identity and capabilities. The supported-field mask byte count is at DeviceInfo payload offset 8, followed by the mask at offset 9. Each bit identifies a field. SystemInfo requests carry three zero bytes; VersionInfo requests carry two.

Firmware above 75 and advertised VerifySecurity support require the capability-gated V1 authentication handshake. Identity values are parsed from validated metadata at runtime, never hardcoded. The 36-byte payload contains a 32-byte derived value and a little-endian library value. Preserve Hyperborea's signed shifts and byte truncation; synthetic vectors exercise high-bit values. Metadata/authentication payloads must never enter logs.

## Principal fields

| Field | Meaning | Representation |
| --- | --- | --- |
| 1 | Target incline | signed 16-bit, hundredths of a percent |
| 2 | Raw resistance setting | unsigned 16-bit; not a universal level mapping |
| 3 | Power | unsigned 16-bit watts |
| 5 | Cadence | unsigned 16-bit RPM |
| 7 | Key object | 14 bytes, retained without invented scalar conversion |
| 12 | Workout mode | byte |
| 16 | Speed | unsigned 16-bit, hundredths of km/h |
| 17 | Actual incline | signed 16-bit, hundredths of a percent |
| 18 | Actual resistance | unknown encoding; retain as raw data |
| 26 | Resistance profile (`Gear`) | 8-byte composite |
| 27 / 28 | Maximum / minimum incline | signed 16-bit, hundredths of a percent |
| 42 | Maximum resistance level | byte |
| 61 | Power target | unsigned 16-bit watts |
| 95 | Idle lockout | boolean byte |
| 108 | Require-start request | boolean byte |
| 119 | Constant-watts mode | boolean byte |

Unknown fields, sentinel values, malformed replies and rejected commands never become guessed measurements. A successful status does not alone prove physical actuation.

## Control boundaries

`BikeControlPolicy` validates equipment, freshness, direction, finite values and bounds before creating a target. `FitPro` creates writes only from those targets. `ControllerDiagnostics` reports observations and never authorizes writes.

Version 0.9.0 supports on-screen resistance levels 1–24 without a temporary range selector. Reconnecting does not reset a test ceiling or write a resistance value. Full-range app-button increases and decreases have been exercised on the tested controller with a rider-confirmed physical resistance change. Compatibility with other controller revisions and calibrated power accuracy remain unestablished.

The full eight-byte field-26 profile must match `00 00 27 01 LL 07 (22-LL) 19`, with bytes expressed in hexadecimal and LL representing a level from 1 through 24. Levels outside 1–24 and malformed profiles block further resistance writes until reconnect. The displayed level alone never authorizes a write. Matching a profile alone establishes readback compatibility, not physical braking force.

Each explicit adjustment rereads mode, ERG, maximum and the complete profile, requires fresh Running mode with ERG off and maximum 24, and changes just one level within 1–24. The eight-byte write payload retains the existing encoding: only the level byte at offset 4 is populated; all other bytes are zero. Readback metadata is not copied into the write. The original or expected profile may appear in the write response, but a fresh complete target profile must subsequently confirm the adjustment. Unexpected or unconfirmed responses block further resistance writes. There are no automatic write retries. Do not transplant a treadmill's resistance mapping.

Incline steps are 0.5 percentage points within freshly confirmed limits, constrained to the implementation's -10% to 20% envelope. Matching target readback and actual incline confirmation are separate checks. Keep actuator commands bounded and require a new user action after an unconfirmed adjustment; never retry automatically.

## Ride lifecycle and recovery

Connection authenticates and reads state without automatically starting a workout. Explicit Start stores original initialization flags before writing fields 108 and 95, then enters WarmUp (10) and Running (2). Explicit End transitions through Results (4) to Idle (1) before restoring original flags. Preserve persisted ownership and recovery checkpoints across failures and process recreation. Never fabricate missing original flags for an older recovery record.

Unconfirmed control changes must not remove End ride or convert stale information into permission to write. USB cleanup must consume and validate pending acknowledgements and keep the primary failure visible. Never add calibration, reset, firmware, bootloader or speed-control commands as part of an ordinary UI change.

## Verification

`python3 scripts/test_protocol.py` runs dependency-free Java tests for framing, synthetic metadata, immutable request binding, authentication vectors, state transitions, control limits, failure classification, ride statistics, history, timers and private-file handling. These tests do not establish physical behavior on another controller revision.

Hardware changes require a separate supervised check with a person at the bike. Keep raw captures local and publish only generalized implementation facts. Never turn a reconstructed packet into a claim of a captured or verified actuator command.

For a new controller revision, validate bounded adjacent changes with a person at the bike before claiming support. Check both directions and boundary behavior, keep incline unchanged, and distinguish displayed confirmation from physical response. Never infer a universal watts-per-level mapping from this validation.
