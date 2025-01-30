import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
    id("com.android.application")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.google.audio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.google.audio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").apply {
            java.srcDirs("src/main/java")
            proto {
                srcDir("src/main/proto")
            }
        }
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        freeCompilerArgs = listOf("-Xjvm-default=all-compatibility", "-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.api.grpc:grpc-google-cloud-speech-v1p1beta1:2.51.0"){
        exclude(module = "protobuf-java")
    }
    implementation("io.grpc:grpc-okhttp:1.70.0")
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.29.3")
    implementation("com.google.protobuf:protobuf-java-util:4.29.3"){
        exclude(module = "protobuf-java")
    }
//    protobuf("com.google.protobuf:protobuf-java:4.29.3")

    implementation("com.google.flogger:flogger:0.7.4")
    implementation("com.google.flogger:flogger-system-backend:0.7.4")
    implementation("joda-time:joda-time:2.12.2")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

protobuf {
    protoc {
        // use this compiler, from the maven repo (instead of a local file, for instance)
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
    plugins {
//        id("javalite") {
//            artifact = "com.google.protobuf:protoc-gen-javalite:4.29.3"
//        }
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.70.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java"){
                    option("lite")
                }
            }
            task.plugins {
                id("grpc") {
                    // Options added to --grpc_out
                    option("lite")
                }
            }
        }
    }
}
