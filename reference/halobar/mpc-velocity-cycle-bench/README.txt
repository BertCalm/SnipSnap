MPC keygroup velocity and round-robin bench

This diagnostic tests the remaining HALOBAR export gate. It is separate from
the musical study kit. The combined candidate has no hardware verdict and is
not enabled in the production keygroup exporter.

Three programs are included, each with an MPC 3 XTY, XPM, PCM24 WAVs and phone
sidecar. Extract the whole archive. Load a program's XTY beside its TrackData
folder, or its XPM inside TrackData. Keep every supplied WAV with the program.
Play MIDI note 60. The soft window is 0-79; hard is 80-127.

1. 01-Velocity-Control: velocity 40 plays one low beep every time; velocity 110
   plays one high beep every time. This establishes sample loading and gating.
2. 02-Cycle-Control: either velocity plays high one/two/three-beep takes in a
   repeating cycle. This establishes ordinary layer cycling.
3. 03-Velocity-Cycle-Candidate: velocity 40 should cycle low one/two/three-beep
   takes; velocity 110 should cycle high one/two/three-beep takes. Three samples
   share each velocity window in layer Cycle mode. This is the behavior the
   hardware must prove before this mode becomes a production export option.

Import soft.mid, hard.mid and alternating.mid onto each loaded keygroup track.
Each file plays twelve hits at MIDI 60, spaced 1.2 seconds, tempo 72 BPM.
soft uses velocity 40; hard uses 110; alternating switches between the two.
Count beeps in each hit. A cycle may begin at a different phase; it must stay
in the requested velocity group and visit all three takes without silence or
simultaneous takes. Record the order on the alternating sequence as well.

Each folder has Reference-soft.wav, Reference-hard.wav and
Reference-alternating.wav. These reload the saved PCM24 samples in the JVM
reference player, using a common 4x audition gain. They describe expected take
selection; actual phone/device and MPC playback have not been measured.

In manifest.json record the MPC model, firmware, which format was loaded,
control results and actual candidate sequences. Success in one format or
firmware does not establish the other. If the candidate fails, the production
exporter's first-take-per-velocity behavior remains the supported fallback.
The all-capture phone sidecar continues to cycle each velocity window.

The tone generator and packaging script are included under source/. The tones
have bounded peaks (.18 low / .24 high), 5 ms edges, finite .9 second files,
mono 44.1 kHz PCM24 and MIDI root metadata 60.
