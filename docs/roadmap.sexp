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
  (group "v2.1-open" :title "Left open at serialize.java 2.1.2"
   :text "2.1.1 applied reference v2.1.0's uniform rules U1 to U16 and fixed the 2026-10-27 audit findings for this port; 2.1.2 applied reference v2.1.1's rulings (U3, U6, U9, U15): SerializeUtil.copyString, the whole-operation room assertion on every write, and the U15 export list (test/serialize/tests/UniformRulesTests.java). One item stays open: the read slack contract, which waits on a measurement.")
 )

 :items
 (
  (item "ledger-read-buffer-doc" :group "v2.1-open" :area "serialize.java"
   :title "Audit L: measure the read slack contract against the tail window reader"
   :text "The BitReader accepts a tight array by copying the final bytes into a zero-padded tail window, and pays one branch per read to choose the array or the tail, slack or not; IMPLEMENTING.md's buffer contract calls that the slower correct option (speed rule). USAGE.md now states the contract the reader actually has (2.1.1). Same as serialize.js issue 22. Timing runs only on amd-3. Done when: the required-slack reader is measured against the tail window reader on amd-3, the faster one ships, and USAGE.md states its contract."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; the doc half closed in 2.1.1")
 ))
