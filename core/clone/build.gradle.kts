plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.xg.toolbox.core.clone"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/versions/**"
        resources.excludes += "META-INF/*.SF"
        resources.excludes += "META-INF/*.RSA"
        resources.excludes += "META-INF/*.DSA"
        resources.excludes += "META-INF/*.EC"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":core:common"))
    api(project(":core:shell"))
    api(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
}

