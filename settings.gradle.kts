pluginManagement {
    repositories {
        val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
        if (!isCi) {
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "ReveriePaint"
include(":app")
