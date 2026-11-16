# Performance and kit-identity reset

Owner feedback: Industrial has the same failed source identities as Street;
all three kits sound extremely similar; grooves sound like sequential pad demos;
roll/riser sounds like alternating stomps rather than tap dancing.

Verified at `d2456013`: the native groove generator schedules sixteen events at
integer beats, one per beat. The roll alternates toe/heel every 115 ms; the riser
accelerates the same alternation. All three kits share one 18-role pad map, and
most voices differ only through a surface filter and damping. Those structures
do not substantiate the three-distinct-kit or tap-articulation claims.

## This audition

Three new four-bar arrangement studies replace the pad-parade demonstration:

- Tap: swung brush/spank pickups, light ball/heel answers, rests and phrase endings.
- Street: syncopated stamp pattern, backbeat clap, quieter offbeat snaps/ghost claps.
- Industrial: a metal 3+3+2 ostinato against a boot pulse and broom strokes.

Two new gesture studies use step–step–heel–heel cells and brush–spank–step–heel
cells. Their timing and accents form phrases; the riser increases phrase density
and touch rather than accelerating a stamp loop. Existing toe samples are proxies
for ball taps. Low support is high-passed/damped to propose a lighter load; this
is processing, not a new physical tap model or verified human choreography.

Terminology references:
- University of Georgia, *Tap Dance*, terminology: https://open.online.uga.edu/tapdance/chapter/terminology/
- PAA, *Top Ten Tap Moves*: https://paacolorado.org/top-ten-tap-moves-to-know/

Audio is assembled from the saved native model-3 velocity/take windows, with the
previous reference-based Python stamp/clap/chest/snap study used in Street.
No recordings are played in these new performances. A single fixed master gain
is used. Every hit selects one of three velocity layers and cycles four takes;
the new roll/riser each has twelve velocity/repeat renders. Browser old/new
comparisons are energy matched separately and clearly labeled.

Why it is built this way:

- Repeating parts, simultaneous hits and rests establish a groove instead of a roster tour.
- Different leading sound families test ensemble contrast before claiming new kit identities.
- Four-contact tap phrases and directional brushes address articulation directly.
- Lightening only the tap gestures preserves the separate full-weight source candidates.
- Existing sounds limit realism; arrangement cannot establish that a bucket or body slap is convincing.

## Required kit redesign, not completed by this audition

Stop treating three surface variants of the same pad map as three distinct kits.
The proposed source/role contracts are:

| Kit | Core | Supporting roles | Excluded as defining core |
| --- | --- | --- | --- |
| Tap Jazz | separately modeled toe/ball/heel plate contacts, forward/back brushes, flaps, shuffle, cramp-roll cells | occasional hand click/clap | bucket/board backbeat and heavy alternating stamps |
| Street Breaks | sneaker sole slap, heel scuff, weight-bearing stomp, close body contacts | independently validated bucket and broom articulations | metallic tap plate on every foot role |
| Industrial Stepping | boot edge/full-sole, steel sheet/lid/rim/pipe contacts, wood strikes | ensemble stomp and scrape phrases | the Street body set under a brighter filter |

These are proposed mechanisms, not finished presets. Separate excitation and
resonance recipes and distinct pad maps must be implemented and heard before
shelf promotion. Industrial's 01/02 and 09–13/16 remain rejected, alongside
Street's reported roles. Thigh/bucket/board reference gaps remain unresolved.

The native kit downloads are retained as historical candidates. No source-model
version or native roll/riser is silently replaced. This study proves only rendered
arrangement integrity, finite/headroom checks and the 24 gesture cells' deterministic
repeats and increasing dynamics. Source identity, native integration, export,
phone/hardware and the existing MPC 3 slice-map gate remain open.
