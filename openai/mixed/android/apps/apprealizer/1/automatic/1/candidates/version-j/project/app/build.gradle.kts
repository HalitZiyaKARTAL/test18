plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val includeKotlinReflect = providers.gradleProperty("includeKotlinReflect")
    .orElse("true")
    .map(String::toBoolean)

android {
    namespace = "a.htmlapprealizer"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "a.htmlapprealizer"
        minSdk = 30
        targetSdk = 36
        versionCode = 10
        versionName = "J-candidate-20260807"
        testInstrumentationRunner = "android.app.Instrumentation"
        buildConfigField("boolean", "HAS_KOTLIN_REFLECT", includeKotlinReflect.get().toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xannotation-default-target=param-property"
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    if (includeKotlinReflect.get()) {
        implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.21")
    }
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
}
