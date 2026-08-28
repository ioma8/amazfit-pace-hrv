# <app>.apk in apks/builds/ (zipaligned + signed with the local debug key).
# Build intermediates (obj/ dexout/ unsigned.apk) stay in each app dir.
APPS := $(patsubst %/,%,$(dir $(wildcard */AndroidManifest.xml)))
APKS := $(addprefix apks/builds/,$(addsuffix .apk,$(APPS)))
#   make                 build all apps
#   make <app>           build one app, e.g. make tuner
#   make clean           remove build outputs
#
# Toolchain: Android SDK from ANDROID_HOME, else ~/Library/Android/sdk.
# The Huami watch API (com.huami.*) is vendored in hrv/src; the shared
# probe core (com.hrv.common: ProbeActivity, RoundView, WavWriter, Net, Fft,
# SkyMath, Engine3d, ...) lives in common/src. Both are on every app's
# classpath so probes can use them.

SDK       ?= $(or $(ANDROID_HOME),$(HOME)/Library/Android/sdk)
BT_VER    ?= 37.0.0
PLATFORM  ?= android-36
AAPT      := $(SDK)/build-tools/$(BT_VER)/aapt
D8        := $(SDK)/build-tools/$(BT_VER)/d8
ZIPALIGN  := $(SDK)/build-tools/$(BT_VER)/zipalign
APKSIGNER := $(SDK)/build-tools/$(BT_VER)/apksigner
AJ        := $(SDK)/platforms/$(PLATFORM)/android.jar
KEYSTORE  := $(HOME)/.android/debug.keystore

.PHONY: all clean $(APPS)

all: $(APKS)

$(APPS): %: apks/builds/%.apk
	@echo "built: apks/builds/$@.apk"

apks/builds/%.apk: FORCE
	@echo "== $* =="
	@mkdir -p apks/builds
	cd $* && rm -rf obj dexout unsigned.apk && mkdir -p obj dexout
	cd $* && "$(AAPT)" package -f -M AndroidManifest.xml -S res -I "$(AJ)" -F unsigned.apk -J obj
	cd $* && CP="$(AJ):../hrv/src:../common/src"; for j in libs/*.jar; do [ -f "$$j" ] && CP="$$CP:$$j"; done; \
		javac --release 8 -classpath "$$CP" -d obj $$(find src -name '*.java') obj/R.java
	cd $* && "$(D8)" --lib "$(AJ)" --output dexout $$(find obj -name '*.class') $$(find libs -name '*.jar' 2>/dev/null)
	cd $* && (cd dexout && zip -q -0 ../unsigned.apk classes.dex)
	cd $* && "$(ZIPALIGN)" -f 4 unsigned.apk ../apks/builds/$*.apk
	cd $* && "$(APKSIGNER)" sign --ks "$(KEYSTORE)" --ks-pass pass:android --ks-key-alias androiddebugkey ../apks/builds/$*.apk

FORCE:

clean:
	rm -rf $(foreach a,$(APPS),$(a)/obj $(a)/dexout $(a)/unsigned.apk $(a)/aligned.apk)
	rm -f $(foreach a,$(APPS),apks/builds/$(a).apk) apks/*.apk
