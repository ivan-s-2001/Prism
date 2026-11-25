# Stream recommendation architecture

All automatic music selection uses MyWave.start as the final selector. MyWave.mix is the strict-quota adapter; offlineMix uses the same selector with an explicit scarcity policy. YourMusic retrieves and filters candidates; LocalRadio and remote radio pages are recall sources, not a second playback queue. MixMaker recipes define thematic candidate pools and a finite quota. PlaybackService places results into the one player queue; Smart Shuffle is placement, not ranking. Explicit selections and manual queue entries are never silently replaced by recommendations.

## Invariants
- Personal strict liked/discovery quotas use cumulative offsets; contextual and finite selections may fill missing slots from eligible candidates. Offline fallback may relax unavailable preferences, never exclusions.
- Track and artist entry points retain the seed. Continuation uses the original queue context, not unrelated global cached radios.
- One saved Stream style; legacy radio tune is migrated only in the absence of a saved style. Native playback toggles control placement/continuation, not a competing taste profile.
- Only accepted queued recommendations count as served; explicit seed playback is not a recommendation. Switching off continuation preserves manual queue entries.
- Mobile, TV and external controls share the playback service. Device transport does not select music.

## Verification and follow-up
Local Android build and tests are required. GitHub runner previously failed before Gradle because SDK android-37 was unavailable. Legacy RadioTopUp remains for compatibility/tests; specialized release radar, seasonal/Blend/DJ recipes and remote-provider entry points require separate call-site audit before claiming complete removal of every legacy selector. Real-device checks: seed relevance, sparse taste, offline/online transition, manual additions, rapid source switch, seek/pause during retune, playback on Station.
