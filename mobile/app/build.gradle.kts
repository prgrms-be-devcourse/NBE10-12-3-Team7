import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * 릴리스 서명 정보. **값은 저장소에 절대 넣지 않는다.**
 *
 * 안드로이드는 서명으로 "같은 앱인지" 를 판단한다. 키가 유출되면 누구나 정품으로 위장한 앱을
 * 만들 수 있고, 키를 잃어버리면 **같은 앱을 다시는 업데이트할 수 없다**(사용자가 지우고 다시 깔아야 한다).
 * 그래서 파일도 비밀번호도 git 밖에 둔다.
 *
 * 읽는 곳 두 군데 — 개발자 로컬과 CI 를 같은 코드로 지원한다.
 *  1. `mobile/local.properties` (이미 gitignore 대상) — 개발자 각자의 기계
 *  2. 환경변수 — CI 는 파일을 두지 않고 시크릿을 주입한다
 *
 * ```properties
 * # mobile/local.properties  (git 에 올라가지 않는다)
 * RELEASE_STORE_FILE=/절대/경로/marketon-release.jks
 * RELEASE_STORE_PASSWORD=...
 * RELEASE_KEY_ALIAS=marketon
 * RELEASE_KEY_PASSWORD=...
 * ```
 *
 * **키가 없어도 빌드는 깨지지 않는다.** 서명 정보가 없으면 `release` 를 서명하지 않고 넘어간다 —
 * 키를 갖지 않은 팀원이나 CI 가 `assembleDebug`·테스트를 돌리는 데 지장이 없어야 하기 때문이다.
 * 대신 그렇게 나온 APK 는 **설치되지 않으므로**, 배포용 빌드는 아래 경고를 확인하고 만든다.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** local.properties 를 먼저 보고, 없으면 환경변수(CI)로 넘어간다. */
fun signingValue(key: String): String? =
    signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

/**
 * `lan` 빌드가 바라볼 서버 주소. **저장소에 IP 를 박지 않는다.**
 *
 * 집·카페마다 사설 IP 가 달라지므로 코드에 넣으면 매번 커밋을 고쳐야 하고,
 * 남의 네트워크 주소가 히스토리에 남는다. 빌드할 때 넘긴다.
 *
 * ```bash
 * gradle -p mobile :app:assembleLan -PlanHost=192.168.0.5        # 온프레미스 nginx(80)
 * gradle -p mobile :app:assembleLan -PlanHost=192.168.0.5:8080   # Spring 직접
 * ```
 *
 * 온프레미스 구성(`infra/onprem/docker-compose.yml`)은 app 을 `expose: 8080` 으로만 두고
 * nginx 가 `80:80` 을 열어 `/api/` 를 넘긴다 → **포트 없이 IP 만** 주면 된다.
 */
val lanHost: String? = (findProperty("lanHost") as String?)?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.dongnemarket.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dongnemarket.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 서명 정보가 있을 때만 만든다 — 없는데 선언하면 구성 단계에서 빌드가 깨진다.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 PC의 localhost = 10.0.2.2 (로컬 백엔드 개발용)
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        /**
         * 실제 폰에서 확인하는 용도. **에뮬레이터의 `10.0.2.2` 는 폰에서 절대 동작하지 않는다** —
         * 그건 에뮬레이터가 호스트 PC 를 보는 가상 주소이지 네트워크 상의 주소가 아니다.
         *
         * `initWith(debug)` 라 **debug 키로 서명**된다 → 릴리스 키 없이도 폰에 바로 설치된다.
         * 로그도 그대로 볼 수 있어(`adb logcat`) 문제가 생기면 원인을 찾을 수 있다.
         *
         * `release` 를 건드리지 않고 따로 두는 이유: 아래 평문 HTTP 허용이
         * **이 빌드에만** 적용되게 하기 위해서다(`src/lan/AndroidManifest.xml`).
         */
        create("lan") {
            initWith(getByName("debug"))
            // 폰에 debug 빌드와 나란히 깔릴 수 있게 패키지를 분리한다.
            applicationIdSuffix = ".lan"
            versionNameSuffix = "-lan"
            buildConfigField("String", "BASE_URL", "\"http://${lanHost ?: "LAN_HOST_NOT_SET"}/\"")
        }

        release {
            // ⚠️ 온프레미스 전환으로 marketon.inyeon.io 는 사라졌다(DNS 없음).
            // HTTPS 도메인이 정해지면 이 값을 바꾼다 — 이슈 #114.
            // 그때까지 release 로 만든 APK 는 서버에 닿지 못한다.
            buildConfigField("String", "BASE_URL", "\"https://marketon.inyeon.io/\"")

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true // BuildConfig.BASE_URL 생성용
    }
}

/**
 * `-PlanHost` 없이 lan 빌드를 돌리면 **서버에 닿지 못하는 APK** 가 조용히 만들어진다.
 * 폰에 깔아 로그인 화면에서 "네트워크 연결을 확인해 주세요" 를 보고서야 알게 되므로
 * 빌드 자체를 멈춘다.
 */
tasks.matching { it.name.contains("Lan") && it.name.startsWith("assemble") }.configureEach {
    doFirst {
        require(lanHost != null) {
            """
            lan 빌드에는 서버 주소가 필요합니다.
              gradle -p mobile :app:assembleLan -PlanHost=192.168.0.5
            맥북(온프레미스) Wi-Fi IP 확인:  ipconfig getifaddr en0
            """.trimIndent()
        }
    }
}

/**
 * 서명 없이 릴리스를 만들면 **설치되지 않는 APK** 가 나온다. 그런데 빌드는 성공으로 끝나서
 * 파일을 팀원에게 보내고 나서야 "패키지를 파싱할 수 없습니다" 로 알게 된다.
 * 그 전에 알아채도록 릴리스 빌드 시작 시점에 경고를 띄운다.
 */
tasks.matching { it.name.contains("Release") && it.name.startsWith("assemble") }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            logger.warn(
                """
                ⚠️  릴리스 서명 키가 없어 **서명되지 않은 APK** 가 만들어집니다 — 기기에 설치되지 않습니다.
                    mobile/local.properties 에 아래 4줄을 넣으세요(파일은 git 에 올라가지 않습니다):
                      RELEASE_STORE_FILE=/절대/경로/marketon-release.jks
                      RELEASE_STORE_PASSWORD=...
                      RELEASE_KEY_ALIAS=marketon
                      RELEASE_KEY_PASSWORD=...
                    키스토어 만들기:
                      keytool -genkeypair -v -keystore marketon-release.jks \
                        -alias marketon -keyalg RSA -keysize 2048 -validity 10000
                """.trimIndent(),
            )
        }
    }
}

dependencies {
    // AndroidX / Compose
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
    implementation(libs.androidx.navigation.compose)

    // DI (Hilt)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 네트워크
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // 로컬 저장(JWT)
    implementation(libs.androidx.datastore.preferences)

    // 이미지
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // 단위 테스트
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.kotlinx.serialization)

    // 계기 테스트(Compose UI)
    androidTestImplementation(libs.androidx.junit)
    // Compose BOM 이 전이로 끌고 오는 espresso 3.5.0 / runner 1.5.0 은 Android 15+ 에서
    // Espresso.onIdle 이 NoSuchMethodException(InputManager.getInstance) 으로 죽는다.
    // 테스트 기기가 API 37 이므로 최신 계기 러너를 명시적으로 고정한다.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
