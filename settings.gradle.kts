pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "GrimmReader"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:designsystem")
include(":core:network")
include(":core:datastore")
include(":api:grimmory")
include(":core:files")
include(":core:data")
include(":reader:ebook")
include(":reader:paged")
include(":reader:comic")
include(":reader:pdf")
include(":feature:setup")
include(":feature:library")
include(":feature:bookdetail")
include(":feature:reader")
include(":feature:settings")
include(":feature:notebook")
