plugins {
    id("com.android.application")
}

android {
    namespace = "com.zhouqishun.catchreport"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhouqishun.catchreport"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "APP_CFLAGS+=-DPKGNAME=com/zhouqishun/catchreport -DCLSNAME=TProxyService",
                    "APP_LDFLAGS+=-Wl,--build-id=none"
                )
            }
        }
    }

    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libmihomo.so")
        }
    }
}
