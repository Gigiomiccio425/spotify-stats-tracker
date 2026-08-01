plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Il numero di versione è il conteggio dei commit.
 *
 * Android installa un APK sopra un altro solo se il `versionCode` non
 * diminuisce mai. Scriverlo a mano significa dimenticarselo e ritrovarsi con
 * due build diverse che si dichiarano la stessa versione; il conteggio dei
 * commit cresce da solo e non richiede di ricordarsi nulla.
 *
 * Attenzione al clone superficiale: `fetch-depth: 0` nel workflow non è un
 * dettaglio, senza il conteggio sarebbe più basso e l'aggiornamento verrebbe
 * rifiutato come se fosse un ritorno indietro.
 */
fun gitCommitCount(): Int = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    output.toInt()
}.getOrDefault(1)

val appVersionCode = (System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: gitCommitCount())
val appVersionName: String by project

android {
    namespace = "it.spotifystats.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.spotifystats.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // Da dove l'app cerca gli aggiornamenti. In un fork basta cambiare
        // questa riga perché l'app guardi le release giuste.
        buildConfigField("String", "UPDATE_REPO", "\"Gigiomiccio425/spotify-stats-tracker\"")

        // L'indirizzo del backend NON è più una costante: lo imposta l'utente
        // dentro l'app, perché lo stesso APK viene installato da persone che
        // puntano a server diversi. Questo valore è solo un suggerimento
        // precompilato nelle build di debug: 10.0.2.2 è il localhost del PC
        // visto dall'emulatore.
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"http://10.0.2.2:8787/\"")
    }

    /**
     * La firma di release arriva dalle variabili d'ambiente, riempite in CI dai
     * secret del repository. Il file della chiave non sta nel repository: chi
     * ce l'ha può pubblicare aggiornamenti che il telefono installa sopra
     * quelli esistenti senza chiedere nulla.
     *
     * Se le variabili mancano — build in locale, fork, pull request — la
     * configurazione resta nulla e Gradle ripiega sulla firma di debug: si
     * compila comunque, ma quell'APK non aggiorna quello firmato.
     */
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 spento di proposito. È l'APK che finisce sul telefono e non
            // esiste modo di provarlo prima: un errore nelle regole di
            // conservazione si manifesta solo a runtime, solo in release, e
            // tipicamente su una schermata che in debug funzionava. Qualche MB
            // in più vale molto meno di una build che si apre e crasha.
            // Le regole in proguard-rules.pro restano pronte per quando ci sarà
            // modo di collaudarla.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
