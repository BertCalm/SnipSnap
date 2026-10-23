---
type: llm
focus: trace
weight: 1
---

The spec uses MidiManager.openBluetoothDevice and says the returned MidiDevice must be held for the session (in a foreground service) because closing it removes the device for every app.
