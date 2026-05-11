plugins {
    id("com.android.application")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

// BODS keys are free after registration, but they are still account-specific credentials.
// Pass BODS_API_KEY as a Gradle property, environment variable, or GitHub Actions secret.
val bodsApiKey = providers.gradleProperty("BODS_API_KEY")
    .orElse(providers.environmentVariable("BODS_API_KEY"))
    .orElse("")
    .get()
val bodsApiBaseUrl = providers.gradleProperty("BODS_API_BASE_URL")
    .orElse(providers.environmentVariable("BODS_API_BASE_URL"))
    .orElse("https://data.bus-data.dft.gov.uk/api/v1/datafeed/")
    .get()
val bodsBoundingBox = providers.gradleProperty("BODS_BOUNDING_BOX")
    .orElse(providers.environmentVariable("BODS_BOUNDING_BOX"))
    .orElse("")
    .get()

android {
    namespace = "org.bustimes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.bustimes.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "BODS_API_KEY", "\"${bodsApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "BODS_API_BASE_URL", "\"${bodsApiBaseUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "BODS_BOUNDING_BOX", "\"${bodsBoundingBox.escapeForBuildConfig()}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    implementation("com.google.ar:core:1.54.0")
}
