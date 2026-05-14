APP_ID := com.egron.torchcam
ACTIVITY := .MainActivity
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk

.PHONY: all build install install-apk run clean test logcat serve

all: build

build:
	./gradlew assembleDebug

install:
	./gradlew installDebug

install-apk: build
	adb install -r $(DEBUG_APK)

run: install
	adb shell am start -n $(APP_ID)/$(ACTIVITY)

clean:
	./gradlew clean

test:
	./gradlew test

logcat:
	adb logcat -s TorchCam

serve: build
	python -m http.server -d app/build/outputs/apk/debug/
