# serialize.java — plain Makefile over the pinned JDK. No Maven, no Gradle,
# no system toolchain: javac and java come from dist/ by absolute path.

JDK_HOME := $(CURDIR)/dist/jdk-21.0.12.1/Contents/Home
JAVAC    := $(JDK_HOME)/bin/javac
JAVA     := $(JDK_HOME)/bin/java

SRC       := $(wildcard src/serialize/*.java)
TEST_SRC  := $(wildcard test/serialize/tests/*.java)

CLASSES      := build/classes
TEST_CLASSES := build/test-classes

.PHONY: all test clean

all: test

# library: Java 17 language level, built and run on the pinned JDK 21
$(CLASSES)/.stamp: $(SRC)
	$(JAVAC) --release 17 -Xlint:all -Werror -d $(CLASSES) $(SRC)
	@touch $@

# tests: compiled against the library classes only — zero dependencies
$(TEST_CLASSES)/.stamp: $(TEST_SRC) $(CLASSES)/.stamp
	$(JAVAC) --release 17 -Xlint:all -Werror -cp $(CLASSES) -d $(TEST_CLASSES) $(TEST_SRC)
	@touch $@

# the suite runs with assertions enabled: write-side contracts are asserts,
# mirroring the family's debug/release split
test: $(TEST_CLASSES)/.stamp
	$(JAVA) -ea -cp $(CLASSES):$(TEST_CLASSES) serialize.tests.AllTests

clean:
	rm -rf build
