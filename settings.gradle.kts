pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Add these:
        maven { url  = uri("https://raw.github.com/saki4510t/libcommon/master/repository/") }
        maven { url = uri("https://jitpack.io") } // for Serenegiant and WebRTC
        maven { url = uri("https://maven.brott.dev/") } // backup source for org.webrtc
    }
}


rootProject.name = "Zhiyun Crane 2S Suite"
include(":app")



// ADD THESE TWO LINES:
include(":libuvccamera", ":usbCameraCommon")

// Point them to where you copied the folders:
project(":libuvccamera").projectDir = file("third_party/uvccamera/libuvccamera")
project(":usbCameraCommon").projectDir = file("third_party/uvccamera/usbCameraCommon")