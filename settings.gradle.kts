pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "LearnLanguageInputMethod"
include(":shared")
// An Xcode framework build must not configure or install Android native tooling.
if (System.getenv("PLATFORM_NAME") == null) include(":androidApp")
