; The serialize.java roadmap: the data ROADMAP.md is generated from.
; Data, never evaluated. Edit this file, then regenerate ROADMAP.md with nova-tools' generator,
; from a nova-tools checkout:
;   go build -o /tmp/roadmap ./tools/roadmap && (cd <this repo> && /tmp/roadmap --file docs/roadmap.sexp --out ROADMAP.md)
; Never edit ROADMAP.md by hand. Created 2026-10-27 for the work left open at 2.1.0.
(roadmap "v1"
 :title "serialize.java roadmap"
 :text "Planned work for serialize.java. Landed work is in git."

 :groups
 (
  (group "v2.1-open" :title "Left open at serialize.java 2.1.1"
   :text "2.1.1 applied reference v2.1.0's uniform rules U1 to U16 and fixed the 2026-10-27 audit findings for this port (test/serialize/tests/UniformRulesTests.java). Two items stay open: serialize_copy_string, which waits on the reference's ruling for languages without bounded buffers, and the read slack contract, which waits on a measurement.")
 )

 :items
 (
  (item "u15" :group "v2.1-open" :area "serialize.java"
   :title "U15: serialize_copy_string"
   :text "U15 lists serialize_copy_string as a reference operation every port carries. Whether a language without bounded string buffers needs it is being ruled in the reference; this port waits on that ruling. compressed_float_precomputed landed in 2.1.1. Done when: the reference rules, and this port carries serialize_copy_string or the ruling exempts it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U15 at mas-bandwidth/serialize v2.1.0; the serialize_copy_string question left to the reference, 2026-10-27")
  (item "ledger-read-buffer-doc" :group "v2.1-open" :area "serialize.java"
   :title "Audit L: measure the read slack contract against the tail window reader"
   :text "The BitReader accepts a tight array by copying the final bytes into a zero-padded tail window, and pays one branch per read to choose the array or the tail, slack or not; IMPLEMENTING.md's buffer contract calls that the slower correct option (speed rule). USAGE.md now states the contract the reader actually has (2.1.1). Same as serialize.js issue 22. Timing runs only on amd-3. Done when: the required-slack reader is measured against the tail window reader on amd-3, the faster one ships, and USAGE.md states its contract."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; the doc half closed in 2.1.1")
 ))
