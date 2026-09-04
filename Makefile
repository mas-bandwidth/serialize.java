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

.PHONY: all test test-release conformance interop clean

all: test test-release

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

clean:
	rm -rf build
