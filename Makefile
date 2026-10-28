# serialize.java — plain Makefile over the pinned JDK. No Maven, no Gradle,
# no system toolchain: javac and java come from dist/ by absolute path.

JDK_HOME := $(CURDIR)/dist/jdk-21.0.12.1/Contents/Home
JAVAC    := $(JDK_HOME)/bin/javac
JAVA     := $(JDK_HOME)/bin/java

SRC       := $(wildcard src/serialize/*.java)
TEST_SRC    := $(wildcard test/serialize/tests/*.java)
INTEROP_SRC := $(wildcard interop/serialize/interop/*.java)

CLASSES         := build/classes
TEST_CLASSES    := build/test-classes
INTEROP_CLASSES := build/interop-classes

.PHONY: all test test-release conformance controls interop clean fuzz fuzz-read fuzz-roundtrip fuzz-control fuzz-read-control

all: test test-release controls

# library: Java 17 language level, built and run on the pinned JDK 21
$(CLASSES)/.stamp: $(SRC)
	$(JAVAC) --release 17 -Xlint:all -Werror -d $(CLASSES) $(SRC)
	@touch $@

# tests: compiled against the library classes only — zero dependencies
$(TEST_CLASSES)/.stamp: $(TEST_SRC) $(CLASSES)/.stamp
	$(JAVAC) --release 17 -Xlint:all -Werror -cp $(CLASSES) -d $(TEST_CLASSES) $(TEST_SRC)
	@touch $@

# the checked shape: assertions enabled, so the write-side contracts — which
# are asserts, mirroring the family's debug/release split — are exercised
test: $(TEST_CLASSES)/.stamp
	$(JAVA) -ea -cp $(CLASSES):$(TEST_CLASSES) serialize.tests.AllTests

# the release shape: assertions disabled, the same suite. Every refusal
# STANDARD.md places on a reader is a check rather than an assert, and this
# run is what proves it — a refusal that only held under -ea would pass here
# as an accepted stream.
test-release: $(TEST_CLASSES)/.stamp
	$(JAVA) -da -cp $(CLASSES):$(TEST_CLASSES) serialize.tests.AllTests --release

# the shared conformance corpus alone, so the interop job can hold this reader
# and the pinned C++ reader to the same vendored files in one place. make test
# runs it too, as one suite among many.
conformance: $(TEST_CLASSES)/.stamp
	$(JAVA) -ea -cp $(CLASSES):$(TEST_CLASSES) serialize.tests.ConformanceTests

# the negative controls: a corpus file whose operation has no runner must go
# red, and a reader with one refusal rule deleted, built from a scratch copy of
# the library, must fail EXACTLY the vectors conformance-controls/<rule>.expected
# names. Controls.java holds the sabotages; the lists are the reference's.
controls: $(TEST_CLASSES)/.stamp
	$(JAVA) -cp $(CLASSES):$(TEST_CLASSES) serialize.tests.Controls $(CLASSES) $(TEST_CLASSES) build/controls

# the interop harness: compiled against the library classes only, like the tests.
# One exchange with the C++ reference per invocation:
#   make interop MODE=write FILE=/tmp/java.bin
$(INTEROP_CLASSES)/.stamp: $(INTEROP_SRC) $(CLASSES)/.stamp
	$(JAVAC) --release 17 -Xlint:all -Werror -cp $(CLASSES) -d $(INTEROP_CLASSES) $(INTEROP_SRC)
	@touch $@

# asserts on: the write-side contracts are asserts here as everywhere, and the
# degenerate ranges the message carries must pass with them enabled
interop: $(INTEROP_CLASSES)/.stamp
	$(JAVA) -ea -cp $(CLASSES):$(INTEROP_CLASSES) serialize.interop.Interop $(MODE) $(FILE)

# the fuzz harness: coverage guided, on Jazzer. The functional tier, never part
# of `all`: it runs for real time and drives a native fuzzing engine. Two
# targets mirror the reference's fuzz.cpp: a hostile read of arbitrary bytes
# through every ReadStream primitive, and a differential write -> read round
# trip. Jazzer is the standalone release, pinned and sha256 checked like the
# JDK (tending/PINS.md), fetched into dist/ on first use. The harness has no
# Jazzer dependency at compile time: a target is a class with a static
# fuzzerTestOneInput( byte[] ), compiled against the library classes only.
#   make fuzz                 # both targets, FUZZ_TIME seconds each (default 60)
#   make fuzz-read FUZZ_TIME=3600
#   make fuzz-roundtrip SABOTAGE=1   # against the sabotaged library (see fuzz-control)
#   make fuzz-read FUZZ_DA=1         # the release shape: assertions off
JAZZER_VERSION := 0.30.0
ifeq ($(shell uname -s),Darwin)
JAZZER_ASSET  := jazzer-macos.tar.gz
JAZZER_SHA256 := 6239e2b2b8d626c287fd8a50376315f00ef4310e3f29e96315cd6e60911563c8
else ifeq ($(shell uname -m),x86_64)
JAZZER_ASSET  := jazzer-linux-x86-64.tar.gz
JAZZER_SHA256 := 6eeaf0026d75599b07527d93b576593ce847cfca8b337a579971a5a9cdf792d0
else
JAZZER_ASSET  := jazzer-linux-arm64.tar.gz
JAZZER_SHA256 := 16636a4d3e98f1d3a7fcf59d4ab37f5be1d1ad6df9d94d5c3ca8de644838e369
endif
JAZZER_URL := https://github.com/CodeIntelligenceTesting/jazzer/releases/download/v$(JAZZER_VERSION)/$(JAZZER_ASSET)
JAZZER_DIR := $(CURDIR)/dist/jazzer-$(JAZZER_VERSION)
JAZZER_JAR := $(JAZZER_DIR)/jazzer_standalone.jar

FUZZ_SRC         := $(wildcard fuzz/serialize/fuzz/*.java)
FUZZ_CLASSES     := build/fuzz-classes
SABOTAGE_SRC     := build/fuzz-sabotage-src
SABOTAGE_CLASSES := build/fuzz-sabotage-classes
READ_SABOTAGE_SRC     := build/fuzz-read-sabotage-src
READ_SABOTAGE_CLASSES := build/fuzz-read-sabotage-classes

FUZZ_TIME      ?= 60
FUZZ_CORPUS    ?= corpus
FUZZ_ARTIFACTS ?= fuzz-artifacts
FUZZ_LIBRARY   := $(if $(SABOTAGE),$(SABOTAGE_CLASSES),$(CLASSES))

# assertions on, as the reference builds its fuzzer in Debug: the write side
# contracts are asserts, and the round trip's generated values must keep them.
# FUZZ_DA=1 runs with assertions off, the release shape: the hostile read must
# hold there too, because every read refusal is a check, never an assert.
# Only this repository's classes are instrumented for coverage.
FUZZ_ASSERTS = $(if $(FUZZ_DA),-da,-ea:serialize...)
JAZZER = $(JAVA) $(FUZZ_ASSERTS) -cp $(JAZZER_JAR):$(1) com.code_intelligence.jazzer.Jazzer --cp=$(1) --instrumentation_includes='serialize.**'
FUZZ_FLAGS = -max_total_time=$(FUZZ_TIME) -timeout=10 -print_final_stats=1

# the download is verified before anything is unpacked from it
$(JAZZER_JAR):
	@mkdir -p $(JAZZER_DIR)
	curl -fsSL -o $(JAZZER_DIR)/$(JAZZER_ASSET) $(JAZZER_URL)
	echo "$(JAZZER_SHA256)  $(JAZZER_DIR)/$(JAZZER_ASSET)" | shasum -a 256 -c -
	tar -xzf $(JAZZER_DIR)/$(JAZZER_ASSET) -C $(JAZZER_DIR) jazzer_standalone.jar
	@touch $@

$(FUZZ_CLASSES)/.stamp: $(FUZZ_SRC) $(CLASSES)/.stamp
	$(JAVAC) --release 17 -Xlint:all -Werror -cp $(CLASSES) -d $(FUZZ_CLASSES) $(FUZZ_SRC)
	@touch $@

# the sabotaged library: a scratch copy of src/ with one write/read asymmetry
# planted by serialize.fuzz.Sabotage, built with the same flags as the real one
$(SABOTAGE_CLASSES)/.stamp: $(SRC) $(FUZZ_CLASSES)/.stamp
	rm -rf $(SABOTAGE_SRC) $(SABOTAGE_CLASSES)
	$(JAVA) -cp $(FUZZ_CLASSES) serialize.fuzz.Sabotage src/serialize $(SABOTAGE_SRC)
	$(JAVAC) --release 17 -Xlint:all -Werror -d $(SABOTAGE_CLASSES) $(SABOTAGE_SRC)/*.java
	@touch $@

# the read-sabotaged library: one planted READ defect (Sabotage read mode),
# for the hostile read target's negative control
$(READ_SABOTAGE_CLASSES)/.stamp: $(SRC) $(FUZZ_CLASSES)/.stamp
	rm -rf $(READ_SABOTAGE_SRC) $(READ_SABOTAGE_CLASSES)
	$(JAVA) -cp $(FUZZ_CLASSES) serialize.fuzz.Sabotage src/serialize $(READ_SABOTAGE_SRC) read
	$(JAVAC) --release 17 -Xlint:all -Werror -d $(READ_SABOTAGE_CLASSES) $(READ_SABOTAGE_SRC)/*.java
	@touch $@

FUZZ_DEPS := $(JAZZER_JAR) $(FUZZ_CLASSES)/.stamp $(if $(SABOTAGE),$(SABOTAGE_CLASSES)/.stamp)

fuzz: fuzz-read fuzz-roundtrip

fuzz-read: $(FUZZ_DEPS)
	@mkdir -p $(FUZZ_CORPUS)/hostile-read $(FUZZ_ARTIFACTS)
	$(call JAZZER,$(FUZZ_LIBRARY):$(FUZZ_CLASSES)) --target_class=serialize.fuzz.HostileReadFuzzer \
		--reproducer_path=$(FUZZ_ARTIFACTS) -artifact_prefix=$(FUZZ_ARTIFACTS)/hostile-read- \
		$(FUZZ_FLAGS) $(FUZZ_CORPUS)/hostile-read

fuzz-roundtrip: $(FUZZ_DEPS)
	@mkdir -p $(FUZZ_CORPUS)/round-trip $(FUZZ_ARTIFACTS)
	$(call JAZZER,$(FUZZ_LIBRARY):$(FUZZ_CLASSES)) --target_class=serialize.fuzz.RoundTripFuzzer \
		--reproducer_path=$(FUZZ_ARTIFACTS) -artifact_prefix=$(FUZZ_ARTIFACTS)/round-trip- \
		$(FUZZ_FLAGS) $(FUZZ_CORPUS)/round-trip

# the fuzzer's NEGATIVE CONTROL: the round trip target, from an EMPTY corpus,
# against the sabotaged library, within the smoke budget. Green only when the
# fuzzer traps, and traps on the planted asymmetry (an int64 that does not come
# back) rather than on anything else; a fuzzer that runs out the clock against
# a broken writer is a fuzzer that measures nothing.
fuzz-control: $(JAZZER_JAR) $(FUZZ_CLASSES)/.stamp $(SABOTAGE_CLASSES)/.stamp
	rm -rf build/fuzz-control
	mkdir -p build/fuzz-control/corpus build/fuzz-control/artifacts
	@start=$$(date +%s); \
	if $(call JAZZER,$(SABOTAGE_CLASSES):$(FUZZ_CLASSES)) --target_class=serialize.fuzz.RoundTripFuzzer \
		--reproducer_path=build/fuzz-control/artifacts -artifact_prefix=build/fuzz-control/artifacts/ \
		$(FUZZ_FLAGS) build/fuzz-control/corpus > build/fuzz-control/log 2>&1; then \
		tail -n 20 build/fuzz-control/log; \
		echo "fuzz-control: RED: the planted asymmetry survived $(FUZZ_TIME) s of fuzzing"; exit 1; \
	fi; \
	elapsed=$$(( $$(date +%s) - start )); \
	if ! grep -E 'serialize\.fuzz\.FuzzCheck: int64 [a-z ]+: asymmetric round trip' build/fuzz-control/log; then \
		tail -n 40 build/fuzz-control/log; \
		echo "fuzz-control: RED: the fuzzer stopped, but not on the planted asymmetry"; exit 1; \
	fi; \
	grep -E '^#[0-9]+' build/fuzz-control/log | tail -n 1; \
	echo "fuzz-control: green: the planted asymmetry was caught in $${elapsed} s (budget $(FUZZ_TIME) s)"

# the hostile read's NEGATIVE CONTROL: the hostile read target, from an EMPTY
# corpus, against a library whose ranged int read accepts the offset one past
# its span, within the smoke budget, assertions off (the release shape the
# planted defect would ship in). Green only when the fuzzer traps on the
# planted defect rather than on anything else: an int decoded outside its
# range, or a string length (read as a ranged int) one past its buffer, the
# two places the off-by-one surfaces. Anything else fails the control; a
# hostile read that runs out the clock against a broken reader measures
# nothing.
fuzz-read-control: $(JAZZER_JAR) $(FUZZ_CLASSES)/.stamp $(READ_SABOTAGE_CLASSES)/.stamp
	rm -rf build/fuzz-read-control
	mkdir -p build/fuzz-read-control/corpus build/fuzz-read-control/artifacts
	@start=$$(date +%s); \
	if $(JAVA) -da -cp $(JAZZER_JAR):$(READ_SABOTAGE_CLASSES):$(FUZZ_CLASSES) com.code_intelligence.jazzer.Jazzer \
		--cp=$(READ_SABOTAGE_CLASSES):$(FUZZ_CLASSES) --instrumentation_includes='serialize.**' \
		--target_class=serialize.fuzz.HostileReadFuzzer \
		--reproducer_path=build/fuzz-read-control/artifacts -artifact_prefix=build/fuzz-read-control/artifacts/ \
		$(FUZZ_FLAGS) build/fuzz-read-control/corpus > build/fuzz-read-control/log 2>&1; then \
		tail -n 20 build/fuzz-read-control/log; \
		echo "fuzz-read-control: RED: the planted read defect survived $(FUZZ_TIME) s of fuzzing"; exit 1; \
	fi; \
	elapsed=$$(( $$(date +%s) - start )); \
	if ! grep -E 'serialize\.fuzz\.FuzzCheck: (int: value outside|string: longer than its buffer)' build/fuzz-read-control/log; then \
		tail -n 40 build/fuzz-read-control/log; \
		echo "fuzz-read-control: RED: the fuzzer stopped, but not on the planted read defect"; exit 1; \
	fi; \
	grep -E '^#[0-9]+' build/fuzz-read-control/log | tail -n 1; \
	echo "fuzz-read-control: green: the planted read defect was caught in $${elapsed} s (budget $(FUZZ_TIME) s)"

clean:
	rm -rf build
