.PHONY: android clean

export RUSTFLAGS ?= -C link-arg=-Wl,-z,max-page-size=16384

android:
	cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 -o ../app/src/main/jniLibs build --manifest-path ../ninebot-ffi/Cargo.toml --release

clean:
	rm -rf jniLibs target
