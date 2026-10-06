# Cross-compile the game sources into lib/<abi>/libdoom.so.
#
#   mips        the Amazfit Pace (MIPS32, Android 5.1) — needs NDK r16b, the
#               last NDK with a MIPS toolchain: sdkmanager "ndk;16.1.4479499"
#   arm64-v8a   the repo's `pace` AVD (and any modern device), for on-emulator
#               validation; the emulator has no MIPS backend
#
# Both ABIs are built by default — the root Makefile packages whatever lands in
# lib/. For a watch-only APK, drop $(ARM64_OUT) from `all:` below (that is the
# only place the arm64 build is wired in).
#
#   make -C doom/jni host-smoke WAD=../assets/freedoom1.wad
# builds the engine for the *host* instead and renders 1000 frames.
NDK16 ?= $(HOME)/Library/Android/sdk/ndk/16.1.4479499
NDK28 ?= $(HOME)/Library/Android/sdk/ndk/28.2.13676358

# Doom renders 320x200; the view scales it 0.8 into the round panel.
COMMON := -Os -fPIC -Wall -DNORMALUNIX -DLINUX -DSNDSERV -D_DEFAULT_SOURCE \
          -DDOOMGENERIC_RESX=320 -DDOOMGENERIC_RESY=200
# The NDK's own mips/arm flags: section flags + --gc-sections trim the binary,
# -funwind-tables keeps crash backtraces usable, -fstack-protector-strong is
# applied to every NDK build. -Wall stays on: the vendor tree's warnings are
# upstream's and show up in upstream's Makefile too.
HARDEN := -ffunction-sections -fdata-sections -funwind-tables -fstack-protector-strong

# --- mips (the watch) ------------------------------------------------------
MIPS_TC  := $(NDK16)/toolchains/mipsel-linux-android-4.9/prebuilt/darwin-x86_64/bin
MIPS_CC  := $(MIPS_TC)/mipsel-linux-android-gcc
MIPS_CFLAGS := $(COMMON) $(HARDEN) -mips32 -D__ANDROID_API__=21 \
          --sysroot=$(NDK16)/platforms/android-21/arch-mips \
          -isystem $(NDK16)/sysroot/usr/include \
          -isystem $(NDK16)/sysroot/usr/include/mipsel-linux-android
MIPS_LDFLAGS := -shared -mips32 -Wl,--gc-sections -Wl,-soname,libdoom.so
MIPS_OUT := ../lib/mips/libdoom.so

# --- arm64-v8a (the AVD) --------------------------------------------------
ARM64_TC := $(NDK28)/toolchains/llvm/prebuilt/darwin-x86_64/bin
ARM64_CC := $(ARM64_TC)/aarch64-linux-android22-clang
ARM64_CFLAGS := $(COMMON) $(HARDEN) \
          --sysroot=$(NDK28)/toolchains/llvm/prebuilt/darwin-x86_64/sysroot
ARM64_LDFLAGS := -shared -Wl,--gc-sections -Wl,-soname,libdoom.so
ARM64_OUT := ../lib/arm64-v8a/libdoom.so

LIBS := -llog -ljnigraphics -lm

# doomgeneric's own list, minus the desktop platform files, plus our layer.
SRC := dummy am_map doomdef doomstat dstrings d_event d_items d_iwad d_loop d_main \
 d_mode d_net f_finale f_wipe g_game hu_lib hu_stuff info i_cdmus i_endoom i_joystick \
 i_scale i_sound i_system i_timer memio m_argv m_bbox m_cheat m_config m_controls \
 m_fixed m_menu m_misc m_random p_ceilng p_doors p_enemy p_floor p_inter p_lights \
 p_map p_maputl p_mobj p_plats p_pspr p_saveg p_setup p_sight p_spec p_switch \
 p_telept p_tick p_user r_bsp r_data r_draw r_main r_plane r_segs r_sky r_things \
 sha1 sounds statdump st_lib st_stuff s_sound tables v_video wi_stuff w_checksum \
 w_file w_main w_wad z_zone w_file_stdc i_input i_video doomgeneric doomgeneric_android

all: $(MIPS_OUT) $(ARM64_OUT)

$(MIPS_OUT): $(addprefix obj/mips/,$(addsuffix .o,$(SRC)))
	@mkdir -p $(dir $@)
	$(MIPS_CC) $(MIPS_CFLAGS) $(MIPS_LDFLAGS) $^ -o $@ $(LIBS)
	$(MIPS_TC)/mipsel-linux-android-strip $@
	@echo "built: $@"

obj/mips/%.o: %.c
	@mkdir -p obj/mips
	$(MIPS_CC) $(MIPS_CFLAGS) -c $< -o $@

$(ARM64_OUT): $(addprefix obj/arm64/,$(addsuffix .o,$(SRC)))
	@mkdir -p $(dir $@)
	$(ARM64_CC) $(ARM64_CFLAGS) $(ARM64_LDFLAGS) $^ -o $@ $(LIBS)
	$(ARM64_TC)/llvm-strip $@
	@echo "built: $@"

obj/arm64/%.o: %.c
	@mkdir -p obj/arm64
	$(ARM64_CC) $(ARM64_CFLAGS) -c $< -o $@

# Host smoke test: same engine sources *and the same argv* as the watch build
# (doom_args.h), real WAD, dumps /tmp/doomhost-*.ppm. -w because it compiles the
# frozen vendor tree, whose default-on warnings are not what this check reports.
#   make -C doom/jni host-smoke WAD=../assets/freedoom1.wad
HOST_CC ?= cc
HOST_SRC := $(filter-out doomgeneric_android,$(SRC))
host-smoke: $(HOST_SRC:%=%.c) ../test/host-smoke.c
	@mkdir -p obj
	$(HOST_CC) -O2 -w -I. \
	  -DNORMALUNIX -DLINUX -DSNDSERV -D_DEFAULT_SOURCE \
	  -DDOOMGENERIC_RESX=320 -DDOOMGENERIC_RESY=200 \
	  $(HOST_SRC:%=%.c) ../test/host-smoke.c -o obj/host-smoke -lm
	@d=$$(mktemp -d) && cd $$d && $(abspath obj/host-smoke) $(abspath $(WAD)); r=$$?; rm -rf $$d; exit $$r

clean:
	rm -rf obj ../lib

.PHONY: all clean host-smoke
