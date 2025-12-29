.PHONY: android clean

android:
	cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 -o ./jniLibs build --release

clean:
	rm -rf jniLibs target
