S22i Console protocol implementation references:

STRIDE, https://github.com/keranm/strideApp
Commit d9e1b5363e9a146493ea43b86512bd54738cb23f
Apache License 2.0; original license and NOTICE included.
The Java codec and USB session are adaptations for this S22i project.
STRIDE's bundled third-party application dependencies are not included in this APK.

Hyperborea, https://github.com/ball2jh/Hyperborea
Commit 8351333db64ea503f1121c7fc8ec55384a2cd891
MIT License; original license included.
Used for S22i discovery, field metadata, response-address semantics, and resistance conversion.

Changes in S22i Console: native Java UI, strict response validation, single-field
read queries and explicit policy-bounded resistance/incline adjustments,
no automatic workout-state writes, and bounded USB exchanges with an
explicit connection lifecycle.
