# ── VPN-сервис (вызывается системой по имени класса) ───────────────────────────
-keep class org.olcbox.app.vpn.service.OlcboxVpnService {
    *;
}

# ── JNI: нативные методы tun2socks (hev-socks5-tunnel / olcbox_tun2socks) ───────
# Имена native-методов и их классов не переименовывать — их зовёт C по имени.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── gomobile-биндинги (olcrtc + sing-box) ──────────────────────────────────────
# Go зовёт эти классы/методы по имени через JNI (RegisterNatives + reflection),
# minify их переименует → движок не стартует. Держим целиком.
-keep class mobile.** { *; }
-keep class singboxmobile.** { *; }
-keep class go.** { *; }
-keep class seq.** { *; }
-dontwarn mobile.**
-dontwarn singboxmobile.**
-dontwarn go.**
-dontwarn seq.**

# Наши Kotlin-колбэки, реализующие gomobile-интерфейсы (LogWriter, SocketProtector
# и т.п.) — Go дёргает их методы по имени.
-keep class * implements mobile.** { *; }
-keep class * implements singboxmobile.** { *; }

# ── kotlinx.serialization ───────────────────────────────────────────────────────
# Без этих правил R8 выкидывает сгенерированные сериализаторы → JSON-конфиги
# (LocationConfig, ReedApi, ProxyKeyImport, обновления) падают в рантайме.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Классы приложения, помеченные @Serializable, + их синтетические Companion/serializer.
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class org.olcbox.app.** { *; }
-keepclassmembers class org.olcbox.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class org.olcbox.app.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Ktor client ─────────────────────────────────────────────────────────────────
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }

# ── Корутины (служебные поля, которые трогает R8-оптимизатор) ───────────────────
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Прочее: не шумим на отсутствующих optional-зависимостях ─────────────────────
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
