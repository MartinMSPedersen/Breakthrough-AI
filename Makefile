JAVAC  ?= javac
JAVA   ?= java
JFLAGS ?= -d build -sourcepath src

SOURCES := $(wildcard src/*.java)

.PHONY: all jar run play analyse annotate tune bench clean

all: build/.compiled

build/.compiled: $(SOURCES)
	@mkdir -p build
	$(JAVAC) $(JFLAGS) $(SOURCES)
	@touch $@

jar: all
	cd build && jar cfe ../breakthrough.jar Main *.class

run: all
	$(JAVA) -cp build Main $(ARGS)

# convenience: make play, make analyse, make annotate
play: all
	$(JAVA) -cp build Main play $(ARGS)

analyse: all
	$(JAVA) -cp build Main analyse $(ARGS)

annotate: all
	$(JAVA) -cp build Main annotate $(ARGS)

tune: all
	$(JAVA) -cp build Tuner $(ARGS)

bench: all
	$(JAVA) -cp build Main benchmark $(ARGS)

clean:
	rm -rf build breakthrough.jar
