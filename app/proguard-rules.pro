# =================================================================================
# SNAPNOTE İÇİN ÜRETİM SEVİYESİ PROGUARD YAPILANDIRMASI (v3 - Tamamen Yeniden Yazıldı)
# Bu kurallar, Google Play'de sıfır çökme hedefiyle hazırlanmıştır.
# =================================================================================


# —————————— TEMEL ANDROID VE KÜTÜPHANE AYARLARI ——————————
# Hata ayıklama, yansıma (reflection) ve serileştirme için kritik olan
# Java metadata'larını koru.
-keepattributes Signature,InnerClasses,EnclosingMethod

# AppCompat ve Material Design kütüphanelerinden gelen ve bilinen
# zararsız uyarıları gizle.
-dontwarn androidx.appcompat.**
-dontwarn com.google.android.material.**


# —————————— HILT (DEPENDENCY INJECTION) KURALLARI ——————————
# Hilt, arka planda kod üreterek çalışır. Bu kodun ProGuard tarafından
# silinmesi veya yeniden adlandırılması, uygulamanın anında çökmesine neden olur.
# Bu kurallar, Hilt'in tüm sihrini koruma altına alır.
# ÖNCEKİ HATALI KURALLAR TAMAMEN DÜZELTİLDİ.

-keep class dagger.hilt.internal.aggregatedroot.codegen.*
-keep class hilt_aggregated_deps.**
-keep class dagger.hilt.internal.processedrootsentinel.codegen.*
-keep class dagger.hilt.internal.processedroots.*

-keep class * implements dagger.hilt.internal.GeneratedComponent { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedEntryPoint { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManager { <methods>; }
-keep class * implements dagger.hilt.internal.GeneratedComponentManagerHolder { <methods>; }

-keepclassmembers class ** {
    @dagger.hilt.android.AndroidEntryPoint <fields>;
    @dagger.hilt.android.HiltAndroidApp <fields>;
    @dagger.hilt.android.lifecycle.HiltViewModel <fields>;
    @javax.inject.Inject <init>(...);
}


# —————————— GSON (JSON SERİLEŞTİRME) KURALLARI ——————————
# Yedekleme ve geri yükleme özelliğinin kalbidir. Gson, JSON'daki alan adlarını
# kodundaki sınıf değişken adlarıyla eşleştirir. ProGuard bu isimleri değiştirirse,
# tüm yedeklerin bozuk yüklenir. Bu kurallar veri modellerini kilitler.

-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken

# Yedekleme veri modellerinin tüm alanlarını ve metotlarını koru.
-keep class com.codenzi.snapnote.BackupData { *; }
-keep class com.codenzi.snapnote.AppSettings { *; }
-keep class com.codenzi.snapnote.Note { *; }
-keep class com.codenzi.snapnote.NoteContent { *; }
-keep class com.codenzi.snapnote.ChecklistItem { *; }

# Gson'un @SerializedName ek açıklamasını kullandığı alanları koru.
-keepclassmembers,allowshrinking class * {
    @com.google.gson.annotations.SerializedName <fields>;
}


# —————————— GOOGLE API CLIENT & DRIVE & SIGN-IN KURALLARI ——————————
# Google kütüphaneleri, yansıma (reflection) tekniğini yoğun olarak kullanır.
# Bu kurallar olmadan, Google'a yapılan herhangi bir API çağrısı çöker.

-keep class com.google.android.gms.auth.api.identity.** { *; }
-dontwarn com.google.android.gms.auth.api.identity.**

-keep class com.google.api.client.googleapis.extensions.android.gms.auth.**
-keep class com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.client.util.Data { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}


# —————————— KOTLINX COROUTINES KURALLARI ——————————
# Arka plan işlemlerinin (yedekleme, geri yükleme) sorunsuz çalışmasını sağlar.

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.flow.** { *; }
-keepclassmembers class ** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# —————————— ROOM (VERİTABANI) KURALLARI ——————————
# Room kütüphanesi genellikle kendi kurallarını içerir, ancak bu ek kurallar
# her türlü kenar durumuna karşı ek bir güvence katmanı sağlar.

-keep class androidx.room.** { *; }
-keepclassmembers class * { @androidx.room.Entity *; }
-keepclassmembers class * { @androidx.room.Dao *; }
-keepclassmembers class * { @androidx.room.Database *; }
-keepclassmembers class * { @androidx.room.TypeConverter *; }


# —————————— DİĞER KÜTÜPHANELER VE GENEL KURALLAR ——————————

# Parcelable (Android bileşenleri arasında veri taşıma)
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Enum (Sabit değerler)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coil & OkHttp (Görsel yükleme) için uyarıları gizle.
-dontwarn okio.**
-dontwarn okhttp3.**

# PhotoView
-keep class com.github.chrisbanes.photoview.** { *; }

# =================================================================================
# GÜVENLİ YAPILANDIRMANIN SONU
# =================================================================================
