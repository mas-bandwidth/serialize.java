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
  (group "v2.1-open" :title "Left open at serialize.java 2.1.0"
   :text "Reference v2.1.0's uniform rules (IMPLEMENTING.md U1 to U16) and the 2026-10-27 audit findings for this port, deferred when the 2.1 wrap-up order (Glenn, 2026-10-27) limited 2.1.0 to the re-vendor and the rejected reason (U2, landed). Each rule is unaudited here until its item closes: none is claimed kept or broken.")
 )

 :items
 (
  (item "u1" :group "v2.1-open" :area "serialize.java"
   :title "U1: write buffers are a multiple of 8 bytes"
   :text "Check that WriteStream and BitWriter require and check (under -ea) a buffer length that is a multiple of 8, and that every doc says 8, never 4. Done when: a unit test cites U1 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U1 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u3" :group "v2.1-open" :area "serialize.java"
   :title "U3: the writer never sends bytes that differ from the caller's value"
   :text "Check that string writes and measures use a strict UTF-8 encoder: a lone surrogate is write misuse and is never replaced with '?' or U+FFFD. Done when: a unit test cites U3 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U3 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u4" :group "v2.1-open" :area "serialize.java"
   :title "U4: check UTF-8 and NUL on every string write"
   :text "Check that the checked (-ea) write path refuses unencodable text and U+0000 on string write and measure. Done when: a unit test cites U4 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U4 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u5" :group "v2.1-open" :area "serialize.java"
   :title "U5: int bounds are int32"
   :text "Check that a min or max outside int32 is caught on write and measure, and refused as value_out_of_range on read; never a silent 0-bit field. Done when: a unit test cites U5 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U5 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u6" :group "v2.1-open" :area "serialize.java"
   :title "U6: a measure stream refuses nothing"
   :text "Check that measure misuse follows the write-misuse mechanism (assert) and a measure never latches a read refusal. Done when: a unit test cites U6 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U6 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u7" :group "v2.1-open" :area "serialize.java"
   :title "U7: a reader never traps"
   :text "Check that read-side caller misuse (negative byte count, string buffer size below 1, bad declarations) returns false with value_out_of_range rather than throwing, with the failure state checked first. See ledger items ledger-string-buffer-size and ledger-read-misuse. Done when: a unit test cites U7 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U7 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u8" :group "v2.1-open" :area "serialize.java"
   :title "U8: over-wide values on write are caught in the checked build"
   :text "Check that a value that does not fit its bit width is caught under -ea rather than masked. Done when: a unit test cites U8 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U8 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u9" :group "v2.1-open" :area "serialize.java"
   :title "U9: one write-misuse mechanism per implementation"
   :text "Check that every operation, compressed_float included, uses the same write-misuse mechanism, and that fail or reject on a write or measure returns false and never throws with -da. reject() follows this since 2.1.0. Done when: a unit test cites U9 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U9 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u10" :group "v2.1-open" :area "serialize.java"
   :title "U10: a write checks room for the whole operation before writing any of it"
   :text "Check that bytes and string check the room for field, align and payload before the first bit, so no length or align bits are written ahead of a payload that does not fit. Done when: a unit test cites U10 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U10 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u11" :group "v2.1-open" :area "serialize.java"
   :title "U11: a writer's buffer is unspecified until flush"
   :text "Check that every doc that describes WriteStream says flush is required. Done when: a unit test cites U11 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U11 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u12" :group "v2.1-open" :area "serialize.java"
   :title "U12: end on a write or measure stream is a no-op returning true"
   :text "Check serializeEnd on WriteStream and MeasureStream. Done when: a unit test cites U12 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U12 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u13" :group "v2.1-open" :area "serialize.java"
   :title "U13: re-initialising a stream is optional"
   :text "Check that reset() is documented on every stream that has one, and that a stream without it says so. Done when: a unit test cites U13 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U13 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u14" :group "v2.1-open" :area "serialize.java"
   :title "U14: the refusal-name function and a public fail(reason)"
   :text "Check that the refusal-name function returns the family spellings with none for no refusal, and that ReadStream exposes a public fail(reason). Done when: a unit test cites U14 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U14 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u15" :group "v2.1-open" :area "serialize.java"
   :title "U15: the allowed extras, and nothing else"
   :text "Audit the public surface against U15's list, and add serialize_copy_string and compressed_float_precomputed if they are missing. Done when: a unit test cites U15 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U15 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "u16" :group "v2.1-open" :area "serialize.java"
   :title "U16: caller declarations validated on every stream before any bits move"
   :text "Check bits counts, fixed point bounds, ranged min <= max, string bufferSize >= 1 and the precomputed compressed_float constants on read (value_out_of_range, never a throw) and on write and measure (the write-misuse mechanism). Done when: a unit test cites U16 and is green, with a red-first test and fix if the port broke the rule, or a one-line note that the port already keeps it."
   :date "2026-10-27"
   :origin "IMPLEMENTING.md U16 at mas-bandwidth/serialize v2.1.0; deferred by the 2.1 wrap-up order, 2026-10-27")
  (item "ledger-string-buffer-size" :group "v2.1-open" :area "serialize.java"
   :title "Audit M: string read with bufferSize below 1 lets the wire pick a throw"
   :text "A string read with bufferSize < 1 and wire length 0x80000000 throws NegativeArraySizeException, so the wire picks throw versus accept (U7). Reproducer: /Volumes/nova/ai/rowan/working/serialize-love/audit2/serialize.java/audit/Repro.java R1. Run it first and confirm it fails before fixing. Done when: the reproducer is a unit test that is red on the old code and green on the fix, refusing value_out_of_range."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; deferred by the 2.1 wrap-up order")
  (item "ledger-read-misuse" :group "v2.1-open" :area "serialize.java"
   :title "Audit L: other read misuse throws ArrayIndexOutOfBoundsException or accepts"
   :text "Other read-side caller misuse throws ArrayIndexOutOfBoundsException or accepts (U7). Reproducer: /Volumes/nova/ai/rowan/working/serialize-love/audit2/serialize.java/audit/Repro.java R2 to R6. Run it first and confirm each fails before fixing. Done when: each reproducer is a unit test that is red on the old code and green on the fix, refusing value_out_of_range."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; deferred by the 2.1 wrap-up order")
  (item "ledger-read-buffer-doc" :group "v2.1-open" :area "serialize.java"
   :title "Audit L: USAGE.md says 8-byte slack; the reader pads a tail window and pays a per-read branch"
   :text "USAGE.md documents an 8-byte read slack, but the BitReader pads a tail window and pays a branch per read, which IMPLEMENTING.md's buffer contract calls the slower correct option (speed rule). Same as serialize.js issue 22. Timing runs only on amd-3. Done when: the slack contract is measured against the zero-slack reader on amd-3, the faster one ships, and USAGE.md states the contract the reader actually has."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; deferred by the 2.1 wrap-up order")
  (item "ledger-fuzz-controls" :group "v2.1-open" :area "serialize.java"
   :title "Audit L: the hostile-read fuzzer has no negative control, no -da leg and a fixed bufferSize"
   :text "The hostile-read fuzzer has no planted-defect negative control, runs only with assertions on, and uses one fixed string bufferSize. Done when: a planted read defect is caught by the hostile-read fuzzer in CI, a -da leg runs, and bufferSize is fuzzed."
   :date "2026-10-27"
   :origin "serialize-love AUDIT-LEDGER.md, heading java, 2026-10-27; deferred by the 2.1 wrap-up order")
 ))
