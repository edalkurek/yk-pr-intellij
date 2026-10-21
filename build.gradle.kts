import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

val pluginGroup: String by project
val pluginVersion: String by project
val pluginSinceBuild: String by project
val pluginUntilBuild: String by project
val platformType: String by project
val platformVersion: String by project

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.fromCode(platformType), platformVersion)
        // Git.Menu grubu için (plugin.xml'de optional depends). Derleme bağımlılığı yok ama verifier bunu bilmeli.
        bundledPlugin("Git4Idea")
        pluginVerifier()
        zipSigner()
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        // Kotlin stdlib IDE tarafından sağlanır; eklentiye gömülürse sınıf çakışması yaratır.
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.23")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    instrumentCode = false

    pluginConfiguration {
        version = pluginVersion
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    // 2023.3 ile pluginUntilBuild arasındaki sürümlerde API/kayıt uyumluluğunu doğrular (gradlew verifyPlugin).
    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    withType<JavaCompile> {
        options.release.set(17)
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    test {
        useJUnitPlatform()
    }
}
