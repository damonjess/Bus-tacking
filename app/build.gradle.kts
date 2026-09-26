import java.util.Properties

plugins {
    id("com.android.application")
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun getSecretProperty(propertyName: String): String {
    return localProperties.getProperty(propertyName)
        ?: providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(propertyName).orNull
        ?: ""
}

val bodsApiKey = getSecretProperty("BODS_API_KEY")
val bodsApiBaseUrl = getSecretProperty("BODS_API_BASE_URL")
    .ifEmpty { "https://data.bus-data.dft.gov.uk/api/v1/datafeed/" }
val bodsBoundingBox = getSecretProperty("BODS_BOUNDING_BOX")
val googleDirectionsApiKey = getSecretProperty("GOOGLE_DIRECTIONS_API_KEY")

android {

    namespace = "org.bustimes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.bustimes.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "BODS_API_KEY", "\"${bodsApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "BODS_API_BASE_URL", "\"${bodsApiBaseUrl.escapeForBuildConfig()}\"")
        buildConfigField("String", "BODS_BOUNDING_BOX", "\"${bodsBoundingBox.escapeForBuildConfig()}\"")
        buildConfigField("String", "GOOGLE_DIRECTIONS_API_KEY", "\"${googleDirectionsApiKey.escapeForBuildConfig()}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ar:core:1.48.0")
}
