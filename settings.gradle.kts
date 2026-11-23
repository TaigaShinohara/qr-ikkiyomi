// Repository resolution has three modes:
// - Normal machines: the public google() / mavenCentral() / gradlePluginPortal().
// - The author's build VM: its egress proxy resets Java TLS connections, so
//   when QR_LOCAL_MIRROR=1 is set in the environment, dependencies are
//   resolved through a local curl-backed mirror instead (see build.sh).
// - QR_FILE_MIRROR=1 (also the author's VM): resolve --offline against
//   the mirror's disk cache as file repositories. Used by run-tests.sh
//   for the JVM UI verification, whose extra dependencies are pre-fetched
//   into that cache with curl (docs/VERIFICATION.md). The HTTP mirror
//   serialises badly when several builds share it; the file route does
//   not touch it at all.
val fileMirror = System.getenv("QR_FILE_MIRROR") == "1"
val mirrorCache = "/home/hatch/workspace/tools/mirror-cache"
pluginManagement {
    // pluginManagement is hoisted and cannot see the script-level vals
    // above, so it reads the environment itself.
    val pmFileMirror = System.getenv("QR_FILE_MIRROR") == "1"
    val pmMirrorCache = "/home/hatch/workspace/tools/mirror-cache"
    repositories {
        if (pmFileMirror) {
            maven { url = uri("file://$pmMirrorCache/google") }
            maven { url = uri("file://$pmMirrorCache/central") }
            maven { url = uri("file://$pmMirrorCache/plugins") }
        } else if (System.getenv("QR_LOCAL_MIRROR") == "1") {
            maven { url = uri("http://127.0.0.1:18080/google"); isAllowInsecureProtocol = true }
            maven { url = uri("http://127.0.0.1:18080/central"); isAllowInsecureProtocol = true }
            maven { url = uri("http://127.0.0.1:18080/plugins"); isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (fileMirror) {
            maven { url = uri("file://$mirrorCache/google") }
            maven { url = uri("file://$mirrorCache/central") }
        } else if (System.getenv("QR_LOCAL_MIRROR") == "1") {
            maven { url = uri("http://127.0.0.1:18080/google"); isAllowInsecureProtocol = true }
            maven { url = uri("http://127.0.0.1:18080/central"); isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
        }
    }
}
rootProject.name = "QrIkkiyomi"
include(":app")
