# Rokid-Scribe Architecture

## Product intent

`Rokid-Scribe` is built around one core UX rule:

The user starts sync from the phone. The glasses should stay as passive as possible.

That means:

- no external Wi-Fi dependency
- no Rokid `CXR` dependency
- no large audio payload over Bluetooth unless we have no other choice

## Preferred transport

### Primary

`phone hotspot -> glasses connected -> phone-initiated control -> LAN payload transfer`

Why this wins:

- works outside without external Wi-Fi
- keeps the phone online for ElevenLabs
- is much faster than Bluetooth for audio
- keeps the UX to roughly one intentional action on the phone

### Fallback

`Bluetooth SPP`

Keep this only for:

- very short notes
- debugging
- recovery when LAN is unavailable

## Recording format

Use `m4a / AAC mono`, not `wav`.

That keeps transfer time, local storage, and upload time under control while staying perfectly fine for transcription.

## Proposed sync contract

### 1. Queue probe

Phone sends a small control request over SPP:

```json
{ "type": "queue_probe" }
```

Glasses respond with the pending queue:

```json
{
  "type": "queue_state",
  "deviceId": "rokid-xxxx",
  "pendingCount": 3,
  "items": [
    {
      "id": "2026-04-11T20-32-04Z-note-001",
      "fileName": "2026-04-11T20-32-04Z-note-001.m4a",
      "sizeBytes": 248120,
      "durationMs": 42850,
      "createdAt": "2026-04-11T20:32:04Z"
    }
  ]
}
```

### 2. Import start

Phone chooses which recordings to import, opens a local receiver, then sends:

```json
{
  "type": "import_request",
  "transportMode": "wifi_lan",
  "hostIp": "192.168.148.22",
  "port": 43219,
  "itemIds": ["2026-04-11T20-32-04Z-note-001"]
}
```

### 3. Payload move

The glasses connect to the phone receiver and stream the requested recordings.

This keeps the initiation on the phone, even if the byte flow is glasses -> phone.

### 4. Completion

Glasses send a final result:

```json
{
  "type": "result",
  "success": true,
  "importedCount": 1,
  "failedCount": 0,
  "message": "Wi-Fi import complete."
}
```

## Phone-side storage

Current structure:

```text
files/
  imported-recordings/
    <recording-id>.m4a
    <recording-id>.json
    <recording-id>.transcript.json
```

Each metadata JSON currently keeps:

- recording id
- source audio filename
- creation/import timestamps
- md5 checksum
- source device name

Each transcript JSON currently keeps:

- ElevenLabs model id
- detected language
- transcript text
- transcript creation timestamp
- word count
- source audio path

## Implemented now

- Glasses-side local `m4a` recording repository
- Phone-initiated Bluetooth queue probe
- Phone-initiated `SPP` import for pending notes
- Phone-initiated `Wi-Fi LAN` import for pending notes
- Local phone metadata sidecars for imported audio
- Phone-side ElevenLabs transcript requests using the official `POST /v1/speech-to-text` flow
- Local phone transcript sidecars attached to imported recordings
- Phone-side `txt` export
- Phone-side `pdf` export to `Download/Rokid-Scribe` when Android supports public Downloads writes

## Next implementation order

1. Add batch transcript actions.
2. Add transcript retry / overwrite controls.
3. Add better history browsing and filtering on the phone.
