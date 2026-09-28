import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzalama bilgileri depoya girmeyen keystore.properties dosyasindan okunur.
// >>> FILL IN: COPY keystore.properties.example TO keystore.properties AND ENTER YOUR OWN KEYSTORE.
// >>> DOLDURUN: keystore.properties.example DOSYASINI keystore.properties OLARAK KOPYALAYIP KENDI ANAHTARINIZI GIRIN.
// Dosya yoksa release derlemesi imzasiz uretilir; debug derlemesi her durumda calisir.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.ex30launch"
    compileSdk = 35

    defaultConfig {
        // >>> CHANGE THIS TO YOUR OWN UNIQUE PACKAGE NAME (e.g. io.github.yourname.ex30launch).
        // >>> BUNU KENDI BENZERSIZ PAKET ADINIZLA DEGISTIRIN. Google Play "com.example" ile baslayan adlari kabul etmez.
        applicationId = "com.example.ex30launch"
        minSdk = 29
        targetSdk = 35
        // Play'de basarisiz bir yukleme denemesi bile surum kodunu kalici tuketir;
        // her yeni yukleme icin artir.
        versionCode = 5
        versionName = "1.3.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Debug'a ozel gelistirme kancalarini ayirmak icin (SprintDebugHooks).
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.car.app:app-automotive:1.4.0")
}
