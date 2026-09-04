# serialize.java — plain Makefile over the pinned JDK. No Maven, no Gradle,
# no system toolchain: javac and java come from dist/ by absolute path.

JDK_HOME := $(CURDIR)/dist/jdk-21.0.12.1/Contents/Home
JAVAC    := $(JDK_HOME)/bin/javac
JAVA     := $(JDK_HOME)/bin/java

SRC       := $(wildcard src/serialize/*.java)
TEST_SRC  := $(wildcard test/serialize/tests/*.java)

CLASSES      := build/classes
TEST_CLASSES := build/test-classes

.PHONY: all test test-release clean

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

clean:
	rm -rf build
